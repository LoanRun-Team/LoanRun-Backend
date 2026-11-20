package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.finance.engine.PrepaymentFees;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.Loan;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.payment.service.StoredDueQueryService;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.*;
import com.loanrun.repaymentplan.repository.PlanApplicationRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Month-specific stored goals and verified actuals; overrides never become recurring plan versions. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MonthlyPlanPortService implements PlanPresentationPort {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final LoanSourceRepository loans;
    private final PlanSourceQueryService sources;
    private final PlanViewQueryService current;
    private final PaymentObservationQueryService payments;
    private final SavingObservationQueryService savings;
    private final StoredDueQueryService dues;
    private final PlanApplicationRepository applications;
    private final UserAccessPort access;
    private final PlanScheduleProjection schedules;
    private final Clock clock;
    public MonthlyPlanPortService(LoanSourceRepository loans,PlanSourceQueryService sources,PlanViewQueryService current,
        PaymentObservationQueryService payments,SavingObservationQueryService savings,StoredDueQueryService dues,
        PlanApplicationRepository applications,UserAccessPort access,RepaymentEngine engine,Clock clock) {
        this.loans=loans;this.sources=sources;this.current=current;this.payments=payments;this.savings=savings;
        this.dues=dues;this.applications=applications;this.access=access;this.schedules=new PlanScheduleProjection(engine);this.clock=clock;
    }
    @Override public List<PlanView> monthViews(long user,LocalDate asOf,YearMonth month,List<PlannedAmount> overrides) {
        if(user<=0)throw new IllegalArgumentException("Positive internal owner required");
        Objects.requireNonNull(asOf);Objects.requireNonNull(month);Objects.requireNonNull(overrides);
        try {
            var now=clock.instant();var today=now.atZone(KST).toLocalDate();if(asOf.isAfter(today))throw ApiException.unavailable();
            var cutoff=asOf.equals(today)?now:asOf.plusDays(1).atStartOfDay(KST).toInstant().minusNanos(1);
            var snapshot=loans.snapshot(user,asOf);var selected=!asOf.equals(today)||month.isBefore(YearMonth.from(asOf))?snapshot.loans():snapshot.loans().stream().filter(l->l.storedStatus()==LoanStatus.ACTIVE).toList();
            Map<Long,PlannedAmount> supplied=new HashMap<>();
            for(var o:overrides) {
                if(o==null||o.cashAmount()<0||o.allocationType()==null||supplied.put(o.loanId(),o)!=null)throw ApiException.unavailable();
                var loan=loans.loan(user,o.loanId());
                // Retain the stored historical override, but do not revive a closed loan's current target.
                // Resolve ownership before excluding it; foreign/missing references remain errors.
                if(asOf.equals(today)&&!month.isBefore(YearMonth.from(today))&&loan.storedStatus()!=LoanStatus.ACTIVE)continue;
                if((loan.method()==RepaymentMethod.BULLET)!=(o.allocationType()==AllocationType.REPAYMENT_SAVING))throw ApiException.unavailable();
                if(selected.stream().noneMatch(l->l.loanId()==loan.loanId()))throw ApiException.unavailable();
            }
            Map<Long,Integer> priorities=new HashMap<>();
            if(!selected.isEmpty()&&access.planType(user)==PlanType.PRO) {
                var limit=month.atEndOfMonth().isBefore(asOf)?month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant().minusNanos(1):cutoff;
                var state=applications.latestBefore(user,limit);var seen=new HashSet<Long>();
                if(state.isPresent())for(var a:state.get().allocations()) {
                    if(a==null||!seen.add(a.loanId())||a.cashAmount()<0||a.priority()!=null&&a.priority()<1)throw ApiException.unavailable();
                    if(a.priority()!=null)priorities.put(a.loanId(),a.priority());
                }
            }
            var known=month.atEndOfMonth().isBefore(asOf)?month.atEndOfMonth():asOf;var result=new ArrayList<PlanView>();
            for(var loan:selected) {
                boolean stateKnown=!loan.updatedAt().isAfter(cutoff);
                if(stateKnown&&month.isAfter(YearMonth.from(asOf))&&month.isAfter(YearMonth.from(loan.maturityDate())))continue;
                if(stateKnown&&loan.storedStatus()!=LoanStatus.ACTIVE&&loan.updatedAt().isBefore(month.atDay(1).atStartOfDay(KST).toInstant()))continue;
                var source=sources.forDate(user,loan.loanId(),month.atEndOfMonth(),known);
                // A registered next-cycle plan can coexist with a current-month cash override before its first due.
                if(source.isEmpty()&&loan.initialPlan().effectiveDate().isAfter(month.atEndOfMonth()))
                    source=sources.forDate(user,loan.loanId(),loan.initialPlan().effectiveDate(),known);
                if(source.isEmpty())continue;
                var s=source.get();var priority=priorities.get(loan.loanId());
                if(asOf.equals(today)&&month.equals(YearMonth.from(today))&&!s.effectiveDate().isAfter(month.atEndOfMonth())) {
                    var view=current.currentForVersion(user,loan.loanId(),overrides,priority,s.version());
                    if(view.isPresent()){result.add(view.get());continue;}
                }
                result.add(month(loan,s,asOf,month,cutoff,supplied.get(loan.loanId()),priority));
            }
            return List.copyOf(result);
        }catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
    private PlanView month(Loan loan,SavedPlanSource s,LocalDate asOf,YearMonth month,Instant cutoff,PlannedAmount override,Integer priority) {
        boolean bullet=loan.method()==RepaymentMethod.BULLET,future=month.isAfter(YearMonth.from(asOf));
        boolean effective=!s.effectiveDate().isAfter(month.atEndOfMonth());
        var schedule=schedules.throughMonth(s,month);var warnings=new ArrayList<String>();
        Long extra=!effective||bullet||s.monthlyExtraCash()==0?0L:null,saving=bullet&&effective?s.monthlySaving():0L,basic=effective?null:0L;
        com.loanrun.common.contract.FinanceModels.SimulationRow row=null;
        if(schedule.planned()!=null) {
            row=schedule.planned().rows().stream().filter(r->YearMonth.from(r.date()).equals(month)).findFirst().orElse(null);
            basic=row==null?0L:Math.addExact(row.scheduledPrincipal(),row.interest());
            extra=row==null?0L:row.extraPrincipal();saving=row==null?0L:row.saving();
        }
        if(override!=null) {
            if(bullet)saving=override.cashAmount();
            else {
                extra=override.cashAmount()==0?0L:null;
                if(row!=null&&s.basisTerms()!=null) {
                    var funded=PrepaymentFees.withinCash(s.basisTerms(),row.date(),Math.addExact(row.balance(),row.extraPrincipal()),override.cashAmount());
                    if(funded.fee()!=null)extra=funded.principal();
                }
            }
        }
        Long actualExtra=null,actualSaving=bullet?null:0L,assets=null,required=bullet?null:0L;
        PlanPerformanceCalculator.Performance performance=null;
        if(!future) {
            var through=month.atEndOfMonth().isBefore(asOf)?month.atEndOfMonth():asOf;
            var snapshot=loans.loanSnapshot(s.userId(),loan.loanId(),through);
            var p=payments.observeAt(s.userId(),month,loan.loanId(),cutoff);
            var monthly=snapshot.payments().stream().filter(a->YearMonth.from(a.paidAt()).equals(month)).toList();
            boolean paid=p.observed()&&p.actuals().paymentCount()==monthly.size();
            if(paid)actualExtra=monthly.stream().mapToLong(a->a.extraPrincipal()).reduce(0,Math::addExact);
            if(bullet) {
                var proof=savings.observeAt(s.userId(),loan.loanId(),month,cutoff);
                var saved=snapshot.savings().stream().filter(a->YearMonth.from(a.savedAt()).equals(month)).toList();
                if(proof.observed()&&proof.actuals().count()==saved.size())actualSaving=saved.stream().mapToLong(a->a.amount()).reduce(0,Math::addExact);
            }
            if(asOf.equals(LocalDate.now(clock.withZone(KST))))assets=LoanSavingsCalculator.balance(loan,snapshot);
            boolean principalKnown=loan.updatedAt().isBefore(through.plusDays(1).atStartOfDay(KST).toInstant());
            if(bullet&&assets!=null&&principalKnown&&s.targetPayoffDate()!=null)required=LoanSavingsCalculator.required(loan,assets,through,s.targetPayoffDate());
            if(!s.effectiveDate().isAfter(through)) {
                long regular=0,allExtra=0,allSaving=0,monthPrincipal=0,monthExtra=0,monthSaving=0;
                for(var a:snapshot.payments())if(!a.paidAt().isBefore(s.effectiveDate())) {
                    regular=Math.addExact(regular,a.regularPrincipal());allExtra=Math.addExact(allExtra,a.extraPrincipal());
                    if(YearMonth.from(a.paidAt()).equals(month)){monthPrincipal=Math.addExact(monthPrincipal,Math.addExact(a.regularPrincipal(),a.extraPrincipal()));monthExtra=Math.addExact(monthExtra,a.extraPrincipal());}
                }
                for(var a:snapshot.savings())if(!a.savedAt().isBefore(s.effectiveDate())) {
                    allSaving=Math.addExact(allSaving,a.amount());if(YearMonth.from(a.savedAt()).equals(month))monthSaving=Math.addExact(monthSaving,a.amount());
                }
                boolean completePayments=paid,completeSavings=bullet&&actualSaving!=null;
                if(ChronoUnit.MONTHS.between(YearMonth.from(s.effectiveDate()),month)>1199){completePayments=false;completeSavings=false;}
                else for(var m=YearMonth.from(s.effectiveDate());!m.isAfter(month);m=m.plusMonths(1)) {
                    completePayments&=payments.observeAt(s.userId(),m,loan.loanId(),cutoff).observed();
                    if(bullet)completeSavings&=savings.observeAt(s.userId(),loan.loanId(),m,cutoff).observed();
                    if(!completePayments&&!completeSavings)break;
                }
                var cumulative=new PlanPerformanceCalculator.Actuals(new PlanPerformanceCalculator.Scope(s.userId(),loan.loanId(),s.version(),s.effectiveDate(),through),
                    completePayments?Math.addExact(regular,allExtra):null,!bullet&&completePayments?allExtra:null,bullet&&completeSavings?allSaving:null);
                var first=s.effectiveDate().isAfter(month.atDay(1))?s.effectiveDate():month.atDay(1);
                var currentMonth=new PlanPerformanceCalculator.Actuals(new PlanPerformanceCalculator.Scope(s.userId(),loan.loanId(),s.version(),first,through),
                    paid?monthPrincipal:null,!bullet&&paid?monthExtra:null,bullet&&actualSaving!=null?monthSaving:null);
                performance=PlanPerformanceCalculator.calculate(new PlanPerformanceCalculator.Input(s,loan.method(),through,schedule.planned(),null,cumulative,currentMonth,
                    new PlanPerformanceCalculator.SavingBalance(s.userId(),loan.loanId(),through,principalKnown?loan.storedRemainingPrincipal():null,assets),null));
            }
        }
        String regular=null;
        if(asOf.equals(LocalDate.now(clock.withZone(KST)))) {
            var rows=dues.range(s.userId(),new DateRange(month.atDay(1),month.atEndOfMonth()),loan.loanId());
            if(!rows.isEmpty()) {
                regular=rows.stream().map(r->r.state().status().name()).filter(v->v.equals("UNRECORDED")).findFirst().orElse(null);
                if(regular==null)regular=rows.stream().map(r->r.state().status().name()).filter(v->!v.equals("PAID")).findFirst().orElse("PAID");
            }
        }
        // Unknown original basis, future assets and missing monthly evidence remain nullable.
        if(schedule.planned()==null&&effective)warnings.add("INSUFFICIENT_PLAN_BASIS");
        if(actualExtra==null||bullet&&actualSaving==null)warnings.add("INSUFFICIENT_OBSERVATION");
        int observedMonths=0;var first=YearMonth.from(asOf).minusMonths(6);var registration=YearMonth.from(loan.createdAt().atZone(KST));
        if(first.isBefore(registration))first=registration;
        for(var m=first;m.isBefore(YearMonth.from(asOf));m=m.plusMonths(1)) {
            boolean observed=bullet?savings.observeAt(s.userId(),loan.loanId(),m,cutoff).observed():payments.observeAt(s.userId(),m,loan.loanId(),cutoff).observed();
            if(observed)observedMonths++;
        }
        var target=s.targetPayoffDate();var track=new Track(null,"INSUFFICIENT",regular);
        boolean comparison=performance!=null&&override==null;
        if(comparison)track=new Track(performance.track().status(),performance.track().dataStatus(),regular);
        return new PlanView(loan.loanId(),s.mode(),s.preference(),s.targetDate(),s.monthlyExtraCash(),
            basic==null?null:Math.addExact(basic,effective?s.monthlyExtraCash():0),null,null,s.effectiveDate(),target,null,
            bullet?"LUMP_SUM":"INSTALLMENT",comparison?performance.plannedProgressPct():null,null,extra,actualExtra,
            extra==null||actualExtra==null||s.effectiveDate().isAfter(month.atDay(1))?null:Math.subtractExact(actualExtra,extra),null,track,
            comparison?performance.cumulativeExtra().planned():null,performance==null?null:performance.cumulativeExtra().actual(),comparison?performance.cumulativeExtra().gap():null,
            priority,target==null?null:Math.toIntExact(ChronoUnit.DAYS.between(asOf,target)),null,assets,
            required,saving,actualSaving,comparison?performance.cumulativeSaving().planned():null,performance==null?null:performance.cumulativeSaving().actual(),
            comparison?performance.cumulativeSaving().gap():null,performance==null?null:performance.savingProgressPct(),"INSUFFICIENT",observedMonths,s.startPrincipal(),regular,
            comparison?performance.cumulativeExtra().achievementPct():null,null,warnings);
    }
}
