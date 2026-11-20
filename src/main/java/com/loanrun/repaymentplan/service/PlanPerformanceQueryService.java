package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.contract.PlanPresentationPort.Track;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.Snapshot;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.service.*;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.*;
import com.loanrun.repaymentplan.domain.PlanPerformanceCalculator.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current version performance from real records and independent monthly evidence; no completion inference. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanPerformanceQueryService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final LoanSourceRepository loans;
    private final PlanSourceQueryService plans;
    private final PaymentObservationQueryService payments;
    private final SavingObservationQueryService savings;
    private final StoredDueQueryService dues;
    private final ObjectProvider<PaymentObservationSource> paymentEvidence;
    private final ObjectProvider<SavingObservationSource> savingEvidence;
    private final PlanScheduleProjection schedules;
    private final Clock clock;
    public PlanPerformanceQueryService(LoanSourceRepository loans,PlanSourceQueryService plans,PaymentObservationQueryService payments,
            SavingObservationQueryService savings,StoredDueQueryService dues,ObjectProvider<PaymentObservationSource> paymentEvidence,
            ObjectProvider<SavingObservationSource> savingEvidence,RepaymentEngine engine,Clock clock) {
        this.loans=loans;this.plans=plans;this.payments=payments;this.savings=savings;this.dues=dues;
        this.paymentEvidence=paymentEvidence;this.savingEvidence=savingEvidence;this.schedules=new PlanScheduleProjection(engine);this.clock=clock;
    }
    /** Exact recorded facts, not confirmed performance. All amounts/counts belong to the stated version period. */
    public record Recorded(long paymentCount,long savingCount,long regularPrincipal,long extraPrincipal,long savingAdditions) {
        long principal(){return Math.addExact(regularPrincipal,extraPrincipal);}
    }
    public enum Reason { AVAILABLE,INACTIVE,NO_APPLICABLE_PLAN,NOT_CONFIGURED,NOT_EFFECTIVE }
    public record Result(LocalDate asOf,Instant knownAt,SavedPlanSource plan,PlanScheduleProjection.Projection schedule,
        Recorded monthlyRecorded,Recorded cumulativeRecorded,Performance performance,Reason reason) {}
    private record Verified(boolean payment,boolean saving) {}

    public Result current(long userId,long loanId) {
        if(loanId<=0)throw missing();
        try {return read(userId,loanId);}
        catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
    private Result read(long userId,long loanId) {
        var knownAt=clock.instant();var today=knownAt.atZone(KST).toLocalDate();var month=YearMonth.from(today);
        var snapshot=loans.loanSnapshot(userId,loanId,today);
        if(snapshot.loans().isEmpty())throw missing();var loan=snapshot.loans().getFirst();
        if(loan.createdAt().isAfter(knownAt))throw ApiException.unavailable();
        if(loan.storedStatus()!=LoanStatus.ACTIVE)return new Result(today,knownAt,null,null,null,null,null,Reason.INACTIVE);
        var goalDate=loan.storedNextDueDate().isAfter(today)?loan.storedNextDueDate():today;
        var selected=plans.forDate(userId,loanId,goalDate,today);
        if(selected.isEmpty())return new Result(today,knownAt,null,null,null,null,null,Reason.NO_APPLICABLE_PLAN);
        var plan=selected.get();if(plan.recordedAt().isAfter(knownAt))throw ApiException.unavailable();
        if(!plan.configured())return new Result(today,knownAt,plan,null,null,null,null,Reason.NOT_CONFIGURED);
        if(plan.basisTerms()!=null&&plan.basisTerms().method()!=loan.method())throw ApiException.unavailable();
        var schedule=schedules.throughMonth(plan,month);
        if(plan.effectiveDate().isAfter(today)) {
            var registration=loan.createdAt().atZone(KST).toLocalDate();
            boolean earlierPayment=snapshot.payments().stream().anyMatch(p->!p.paidAt().isBefore(registration)&&!p.paidAt().isAfter(today));
            if(earlierPayment&&plan.startPrincipal()!=null&&schedule.planned()!=null) {
                // The next-cycle plan has not started. Recorded earlier payments are excluded from its progress.
                var empty=new Comparison(null,null,null,null);
                var status=regularStatus(userId,loanId,registration,today);
                var track=new Track(null,"INSUFFICIENT",status==null?null:status.name());
                var zero=new Performance(BigDecimal.ZERO,BigDecimal.ZERO,empty,empty,empty,empty,null,track);
                return new Result(today,knownAt,plan,schedule,null,null,zero,Reason.NOT_EFFECTIVE);
            }
            return new Result(today,knownAt,plan,schedule,null,null,null,Reason.NOT_EFFECTIVE);
        }
        var currentFrom=plan.effectiveDate().isAfter(month.atDay(1))?plan.effectiveDate():month.atDay(1);
        var monthly=recorded(snapshot,currentFrom,today);var cumulative=recorded(snapshot,plan.effectiveDate(),today);
        boolean bullet=loan.method()==RepaymentMethod.BULLET;
        boolean hasPayment=paymentEvidence.getIfAvailable()!=null,hasSaving=bullet&&savingEvidence.getIfAvailable()!=null;
        Map<YearMonth,Verified> confirmed=new HashMap<>();
        var current=verify(userId,loanId,month,knownAt,snapshot,hasPayment,hasSaving);confirmed.put(month,current);
        boolean paymentComplete=hasPayment,savingComplete=hasSaving;
        long months=ChronoUnit.MONTHS.between(YearMonth.from(plan.effectiveDate()),month)+1;
        // Beyond the supported plan horizon, do not label a truncated evidence window complete.
        if(months>1200){paymentComplete=false;savingComplete=false;}
        else if(hasPayment||hasSaving)for(var cursor=YearMonth.from(plan.effectiveDate());!cursor.isAfter(month);cursor=cursor.plusMonths(1)) {
            var key=cursor;
            var proof=confirmed.computeIfAbsent(key,m->verify(userId,loanId,m,knownAt,snapshot,hasPayment,hasSaving));
            paymentComplete&=proof.payment();savingComplete&=proof.saving();
        }
        var cumulativeActual=actuals(new Scope(userId,loanId,plan.version(),plan.effectiveDate(),today),cumulative,bullet,paymentComplete,savingComplete);
        var monthlyActual=actuals(new Scope(userId,loanId,plan.version(),currentFrom,today),monthly,bullet,current.payment(),current.saving());
        var regular=regularStatus(userId,loanId,loan.createdAt().atZone(KST).toLocalDate(),today);
        var balance=new SavingBalance(userId,loanId,today,loan.storedRemainingPrincipal(),LoanSavingsCalculator.balance(loan,snapshot));
        // Monthly date scopes cannot prove the intraday point at which a version's start balance was captured.
        // Reduction from this version's captured start balance to the stored current balance; unknown without a captured start.
        Long start=plan.startPrincipal();long remaining=loan.storedRemainingPrincipal();
        var principalActual=start!=null&&remaining>=0&&remaining<=start?new PrincipalActual(userId,loanId,plan.version(),today,start-remaining):null;
        var performance=PlanPerformanceCalculator.calculate(new Input(plan,loan.method(),today,schedule.planned(),principalActual,cumulativeActual,monthlyActual,balance,regular));
        return new Result(today,knownAt,plan,schedule,monthly,cumulative,performance,Reason.AVAILABLE);
    }
    private Verified verify(long userId,long loanId,YearMonth month,Instant knownAt,Snapshot source,boolean payment,boolean saving) {
        var bounded=recorded(source,month.atDay(1),month.atEndOfMonth());
        boolean p=false,s=false;
        if(payment) {
            var observed=payments.observeAt(userId,month,loanId,knownAt);
            p=observed.observed()&&observed.actuals().paymentCount()==bounded.paymentCount()
                &&observed.actuals().regularPrincipal()==bounded.regularPrincipal()&&observed.actuals().extraPrincipal()==bounded.extraPrincipal();
        }
        if(saving) {
            var observed=savings.observeAt(userId,loanId,month,knownAt);
            s=observed.observed()&&observed.actuals().count()==bounded.savingCount()&&observed.actuals().additions()==bounded.savingAdditions();
        }
        return new Verified(p,s);
    }
    private static Actuals actuals(Scope scope,Recorded raw,boolean bullet,boolean payment,boolean saving) {
        return new Actuals(scope,payment?raw.principal():null,!bullet&&payment?raw.extraPrincipal():null,bullet&&saving?raw.savingAdditions():null);
    }
    Status regularStatus(long user,long loan,LocalDate registration,LocalDate today) {
        var rows=dues.range(user,new DateRange(registration,YearMonth.from(today).atEndOfMonth()),loan);
        for(var row:rows)if(!row.evaluatedOn().equals(today))throw ApiException.unavailable();
        for(var row:rows)if(row.state().status()==Status.UNRECORDED)return Status.UNRECORDED;
        for(var row:rows)if(row.state().status()!=Status.PAID)return row.state().status();
        if(rows.isEmpty()||!YearMonth.from(rows.getLast().source().due().dueDate()).equals(YearMonth.from(today)))return null;
        return Status.PAID;
    }
    private static Recorded recorded(Snapshot source,LocalDate from,LocalDate to) {
        long count=0,savingCount=0,regular=0,extra=0,additions=0;
        for(var p:source.payments())if(!p.paidAt().isBefore(from)&&!p.paidAt().isAfter(to)) {
            count=Math.incrementExact(count);regular=Math.addExact(regular,p.regularPrincipal());extra=Math.addExact(extra,p.extraPrincipal());
        }
        for(var s:source.savings())if(!s.savedAt().isBefore(from)&&!s.savedAt().isAfter(to)) {
            savingCount=Math.incrementExact(savingCount);additions=Math.addExact(additions,s.amount());
        }
        return new Recorded(count,savingCount,regular,extra,additions);
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
}
