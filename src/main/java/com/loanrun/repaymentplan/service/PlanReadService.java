package com.loanrun.repaymentplan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.PlanView;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanReadService {
    private final LoanSourceRepository loans;private final PlanViewQueryService views;
    private final ObjectProvider<BudgetQueryPort> budgets;private final ObjectProvider<PlanQueryPort> plans;
    private final RevisionPort revisions;private final ObjectMapper mapper;private final Clock clock;
    public PlanReadService(LoanSourceRepository loans,PlanViewQueryService views,ObjectProvider<BudgetQueryPort> budgets,ObjectProvider<PlanQueryPort> plans,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.loans=loans;this.views=views;this.budgets=budgets;this.plans=plans;this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    public JsonNode list(long user,PlanType access,String loanId,String asOf) {
        try{return read(user,access,loanId,asOf);}
        catch(ArithmeticException|IllegalArgumentException|org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private JsonNode read(long user,PlanType access,String loanId,String asOf) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if(asOf!=null) {
            LocalDate date;
            try {if(!asOf.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||asOf.startsWith("0000"))throw new IllegalArgumentException();date=LocalDate.parse(asOf);}
            catch(java.time.DateTimeException|IllegalArgumentException e){throw ApiException.validation("as_of","날짜를 확인해 주세요.");}
            if(!date.equals(today))throw ApiException.unavailable(); // No current terms passed off as historical/future actuals.
        }
        var active=loans.activeLoanIds(user);List<Long> ids=active;
        if(loanId!=null) {
            long id;
            try {if(!loanId.matches("[0-9]+"))throw new IllegalArgumentException();id=Long.parseLong(loanId);if(id<=0)throw new IllegalArgumentException();}
            catch(IllegalArgumentException e){throw ApiException.validation("loan_id","대출 ID를 확인해 주세요.");}
            loans.visibleLoan(user,id);ids=List.of(id);
        }
        var month=YearMonth.from(today);List<PlannedAmount> overrides=List.of();Long recurring=null;
        if(!ids.isEmpty()) {
            var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
            var capacity=budget.capacity(user,month);overrides=budget.monthlyOverrides(user,month);
            if(capacity==null||overrides==null||!month.equals(capacity.month()))throw ApiException.unavailable();recurring=capacity.recurringCap();
            if(recurring!=null&&recurring<0)throw ApiException.unavailable();
            overrides=activeOwnedAmounts(user,active,overrides);
        }
        String overall="LOCKED",strategy=null;boolean exists=false;Map<Long,Integer> priorities=new HashMap<>();
        if(access==PlanType.PRO) {
            overall=active.size()<2?"SINGLE_LOAN":"EMPTY";strategy="NONE";
            if(active.size()>1) {
                var source=plans.getIfAvailable();if(source==null)throw ApiException.unavailable();
                var state=source.overallState(user);if(state==null)throw ApiException.unavailable();exists=state.exists();
                if(exists) {
                    overall="AVAILABLE";strategy=state.strategyCode();
                    if(strategy==null||!Set.of("RATE_RISK","INTEREST_SAVING","PARALLEL","MANUAL","NONE","FAST","BALANCED","STABLE","SAVINGS").contains(strategy))throw ApiException.unavailable();
                    var allocations=source.plannedAllocations(user,month);
                    if(allocations==null)throw ApiException.unavailable();
                    for(var a:activeOwnedAmounts(user,active,allocations))if(a.priority()!=null)priorities.put(a.loanId(),a.priority());
                }
            }
        }
        var records=new ArrayList<PlanView>();for(long id:ids)records.add(views.current(user,id,overrides,active.size()>1?null:recurring,priorities.get(id)));
        ObjectNode data=mapper.createObjectNode();data.set("plans",mapper.valueToTree(records));
        ObjectNode aggregate=data.putObject("aggregate");long remaining=0,original=0;
        for(long id:ids){var l=loans.loan(user,id);remaining=Math.addExact(remaining,l.storedRemainingPrincipal());original=Math.addExact(original,l.originalPrincipal());}
        aggregate.put("remaining_principal",remaining);
        Long basic=0L,extra=0L,interest=0L,fee=0L;LocalDate target=null,payoff=null;boolean targetKnown=!records.isEmpty(),payoffKnown=!records.isEmpty();
        for(var v:records) {
            basic=add(basic,v.monthlyAmount()==null?null:Math.subtractExact(v.monthlyAmount(),v.extraMonthlyPayment()));extra=add(extra,v.extraMonthlyPayment());
            interest=add(interest,v.estimatedTotalInterest());fee=add(fee,v.estimatedTotalFee());
            targetKnown&=v.targetPayoffDate()!=null;payoffKnown&=v.projectedPayoffDate()!=null;
            if(v.targetPayoffDate()!=null&&(target==null||v.targetPayoffDate().isAfter(target)))target=v.targetPayoffDate();
            if(v.projectedPayoffDate()!=null&&(payoff==null||v.projectedPayoffDate().isAfter(payoff)))payoff=v.projectedPayoffDate();
        }
        aggregate.set("monthly_basic_amount",mapper.valueToTree(basic));aggregate.set("monthly_extra_amount",mapper.valueToTree(extra));aggregate.set("monthly_total_amount",mapper.valueToTree(add(basic,extra)));
        aggregate.set("estimated_total_interest",mapper.valueToTree(records.isEmpty()?null:interest));aggregate.set("estimated_total_fee",mapper.valueToTree(records.isEmpty()?null:fee));
        aggregate.set("target_payoff_date",mapper.valueToTree(targetKnown?target:null));aggregate.set("projected_payoff_date",mapper.valueToTree(payoffKnown?payoff:null));aggregate.set("estimated_payoff_date",mapper.valueToTree(payoffKnown?payoff:null));
        ObjectNode progress=data.putObject("progress");progress.put("paid_principal",original-remaining);
        progress.set("progress_percent",mapper.valueToTree(original==0?null:BigDecimal.valueOf(original-remaining).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(original),2,RoundingMode.HALF_UP)));
        for(var key:List.of("target_progress_percent","planned_paid_amount","actual_paid_amount","difference"))progress.putNull(key);
        progress.put("data_status","INSUFFICIENT");data.putArray("scenarios");data.put("overall_access",overall);data.put("strategy_code",strategy);data.put("overall_plan_exists",exists);
        data.put("data_revision","rev-"+revisions.current(user)+"-"+today);return data;
    }
    private List<PlannedAmount> activeOwnedAmounts(long user,List<Long> active,List<PlannedAmount> amounts) {
        var seen=new HashSet<Long>();var result=new ArrayList<PlannedAmount>();
        for(var a:amounts) {
            if(a==null||!seen.add(a.loanId())||a.cashAmount()<0||a.allocationType()==null
                    ||a.priority()!=null&&a.priority()<1)throw ApiException.unavailable();
            if(!active.contains(a.loanId())) {
                // Owner-scoped lookup distinguishes a retained closed loan from a foreign/missing reference.
                if(loans.loan(user,a.loanId()).storedStatus()==LoanStatus.ACTIVE)throw ApiException.unavailable();
                continue;
            }
            result.add(a);
        }
        return List.copyOf(result);
    }
    private static Long add(Long a,Long b){return a==null||b==null?null:Math.addExact(a,b);}
}
