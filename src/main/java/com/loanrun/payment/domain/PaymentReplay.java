package com.loanrun.payment.domain;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.loan.domain.*;
import com.loanrun.finance.engine.PrepaymentFees;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** Frozen dues and actual components are preserved. Allocations/balance are derived, never re-guessed on replay. */
public final class PaymentReplay {
    private PaymentReplay(){}
    public static final class Conflict extends RuntimeException {}
    public record Allocation(long payment,long due,long principal,long interest,long fee) {long amount(){return Math.addExact(principal,Math.addExact(interest,fee));}}
    public record Entry(PaymentRequest request,long principal,long extra,long interest,long fee) {}
    public record Result(long balance,List<Entry> entries,List<Allocation> allocations,Set<Long> mutableIds) {
        public long paid(long due){return allocations.stream().filter(a->a.due()==due).mapToLong(Allocation::amount).reduce(0,Math::addExact);}
    }
    public static Balance latest(Snapshot s){return s.balances().stream().max(Comparator.comparing(Balance::effectiveDate).thenComparingLong(Balance::id)).orElseThrow(ApiException::unavailable);}
    public static boolean mutable(Payment p,Balance base){return p.paidAt().isAfter(base.effectiveDate())||p.paidAt().equals(base.effectiveDate())&&(base.reason().equals("REGISTRATION")||p.createdAt().isAfter(base.createdAt()));}
    public static List<PaymentRequest> requests(Snapshot s) {
        var base=latest(s);return s.payments().stream().filter(p->mutable(p,base)).map(p->new PaymentRequest(p.id(),p.paidAt(),p.kind(),p.amount(),s.allocations().stream().filter(a->a.paymentId()==p.id()).map(Allocation0->Allocation0.dueItemId()).findFirst().orElse(null),p.regularPrincipal(),p.extraPrincipal(),p.interest(),p.fee(),p.savingsUsed(),p.createdAt(),false)).toList();
    }
    public static Result replay(Snapshot source,List<PaymentRequest> proposed) {
        if(source.loans().size()!=1)throw ApiException.unavailable();var loan=source.loans().getFirst();var base=latest(source);
        if(!base.effectiveDate().equals(loan.latestSnapshotDate())||base.amount()!=loan.latestSnapshotPrincipal())throw ApiException.unavailable();
        long expected=base.amount();var oldMutable=requests(source);for(var p:oldMutable)expected=Math.subtractExact(expected,Math.addExact(p.principal(),p.extra()));if(expected!=loan.storedRemainingPrincipal())throw ApiException.unavailable();
        var oldIds=new HashSet<Long>();oldMutable.forEach(p->oldIds.add(p.id()));var ids=new HashSet<Long>();proposed.forEach(p->{if(!ids.add(p.id()))throw new Conflict();});
        var allocations=new ArrayList<Allocation>();var totals=new HashMap<Long,long[]>();
        for(var a:source.allocations())if(!oldIds.contains(a.paymentId())){allocations.add(new Allocation(a.paymentId(),a.dueItemId(),a.principal(),a.interest(),a.fee()));add(totals,a.dueItemId(),a.principal(),a.interest(),a.fee());}
        var dues=source.dues().stream().sorted(Comparator.comparing(Due::dueDate).thenComparingLong(Due::id)).toList();var entries=new ArrayList<Entry>();long balance=base.amount();
        for(var p:proposed.stream().sorted(Comparator.comparing(PaymentRequest::date).thenComparingLong(PaymentRequest::id)).toList()) {
            if(p.date().isBefore(base.effectiveDate())||p.amount()<=0||p.used()<0)throw new Conflict();
            Due first=dues.stream().filter(d->remaining(d,totals)>0).findFirst().orElse(null);
            if(p.kind()==PaymentKind.REGULAR&&(first==null||p.edited()&&!Objects.equals(p.dueId(),first.id())))fail(p);
            long principal=0,extra=0,interest=0,fee=0;
            if(p.automatic()) {
                if(p.kind()==PaymentKind.EXTRA) {
                    var terms=LoanTermMapper.current(loan,loan.storedNextDueDate(),loan.contractualNextAmount(),LoanSavingsCalculator.balance(loan,source));
                    var split=PrepaymentFees.withinCash(terms,p.date(),balance,p.amount());if(split.fee()==null||split.cash()!=p.amount())throw PaymentRequest.invalid();extra=split.principal();fee=split.fee();
                } else {
                    long cash=p.amount();for(var due:dues) {
                        long room=remaining(due,totals);if(room==0)continue;var paid=totals.getOrDefault(due.id(),new long[3]);long f=Math.min(cash,Math.min(room,Math.max(0,due.fee()-paid[2])));cash-=f;room-=f;
                        long i=Math.min(cash,Math.min(room,Math.max(0,due.estimatedInterest()-paid[1])));cash-=i;room-=i;long pr=Math.min(cash,Math.min(room,Math.max(0,due.estimatedPrincipal()-paid[0])));cash-=pr;
                        fee=Math.addExact(fee,f);interest=Math.addExact(interest,i);principal=Math.addExact(principal,pr);allocations.add(new Allocation(p.id(),due.id(),pr,i,f));add(totals,due.id(),pr,i,f);if(cash==0)break;if(remaining(due,totals)>0)throw PaymentRequest.invalid();
                    }if(cash!=0)throw PaymentRequest.invalid();
                }
            } else {
                principal=p.principal();extra=p.extra();interest=p.interest();fee=p.fee();if(principal<0||extra<0||interest<0||fee<0||Math.addExact(Math.addExact(principal,extra),Math.addExact(interest,fee))!=p.amount())fail(p);
                if(p.kind()==PaymentKind.EXTRA&&principal!=0)fail(p);
                if(p.kind()==PaymentKind.REGULAR) {
                    long pr=principal,in=interest,fe=fee;if(Math.addExact(pr,Math.addExact(in,fe))==0)fail(p);
                    for(var due:dues) {long room=remaining(due,totals);if(room==0)continue;long f=Math.min(fe,room);fe-=f;room-=f;long i=Math.min(in,room);in-=i;room-=i;long r=Math.min(pr,room);pr-=r;
                        if(r+i+f>0){allocations.add(new Allocation(p.id(),due.id(),r,i,f));add(totals,due.id(),r,i,f);}if(pr==0&&in==0&&fe==0)break;
                    }if(pr!=0||in!=0||fe!=0)fail(p);
                }
            }
            if(p.kind()==PaymentKind.REGULAR&&extra>0&&remaining(first,totals)>0)fail(p);
            long decrease=Math.addExact(principal,extra);if(decrease>balance||p.used()>decrease||p.used()>0&&loan.method()!=RepaymentMethod.BULLET)fail(p);balance-=decrease;
            entries.add(new Entry(p,principal,extra,interest,fee));
        }
        verifySavings(loan,source,proposed,oldIds);
        var allIds=new HashSet<>(oldIds);allIds.addAll(ids);return new Result(balance,List.copyOf(entries),List.copyOf(allocations),Set.copyOf(allIds));
    }
    private static long remaining(Due d,Map<Long,long[]> totals){var paid=totals.getOrDefault(d.id(),new long[3]);long amount=Math.addExact(paid[0],Math.addExact(paid[1],paid[2]));if(amount>d.requiredAmount())throw new Conflict();return d.requiredAmount()-amount;}
    private static void add(Map<Long,long[]> totals,long id,long principal,long interest,long fee){var t=totals.computeIfAbsent(id,k->new long[3]);t[0]=Math.addExact(t[0],principal);t[1]=Math.addExact(t[1],interest);t[2]=Math.addExact(t[2],fee);}
    private static void fail(PaymentRequest p){if(p.edited())throw PaymentRequest.invalid();throw new Conflict();}
    private static void verifySavings(Loan loan,Snapshot source,List<PaymentRequest> next,Set<Long> oldIds) {
        LoanSavingsCalculator.balance(loan,source);var days=new TreeMap<LocalDate,BigInteger[]>();
        var registered=loan.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        for(var s:source.savings())if(!s.savedAt().isBefore(registered))day(days,s.savedAt())[0]=day(days,s.savedAt())[0].add(BigInteger.valueOf(s.amount()));
        for(var p:source.payments())if(!p.paidAt().isBefore(registered)){var d=day(days,p.paidAt());d[1]=d[1].add(BigInteger.valueOf(p.savingsUsed()));if(!oldIds.contains(p.id()))d[2]=d[2].add(BigInteger.valueOf(p.savingsUsed()));}
        for(var p:next){var d=day(days,p.date());d[2]=d[2].add(BigInteger.valueOf(p.used()));}
        BigInteger old=BigInteger.valueOf(loan.initialSavings()),balance=old,max=BigInteger.valueOf(Long.MAX_VALUE);
        for(var d:days.values()) {var difference=balance.subtract(old);var after=difference.subtract(d[2].subtract(d[1]));if(difference.min(after).signum()<0&&balance.compareTo(d[2])<0)throw new Conflict();old=old.add(d[0]).subtract(d[1]);balance=balance.add(d[0]).subtract(d[2]);if(balance.signum()<0||balance.compareTo(max)>0)throw new Conflict();}
    }
    private static BigInteger[] day(Map<LocalDate,BigInteger[]> days,LocalDate date){return days.computeIfAbsent(date,d->new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO});}
}
