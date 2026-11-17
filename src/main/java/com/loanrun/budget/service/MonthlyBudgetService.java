package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.budget.repository.MonthlyBudgetRepository;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.common.validation.JsonPatch;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyBudgetService {
    private final BudgetReadService reads;
    private final MonthlyBudgetRepository budgets;
    private final FinanceProfileService profiles;
    private final BudgetRecalculationPort recalculation;
    private final UserMutationLock locks;
    private final RevisionPort revisions;
    private final IdempotencyService idempotency;
    private final ObjectProvider<PlanQueryPort> plans;
    private final ObjectProvider<PlanPresentationPort> presentation;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final UserAccessPort access;
    public MonthlyBudgetService(BudgetReadService reads, MonthlyBudgetRepository budgets, FinanceProfileService profiles,
        BudgetRecalculationPort recalculation, UserMutationLock locks, RevisionPort revisions, IdempotencyService idempotency,
        ObjectProvider<PlanQueryPort> plans, ObjectProvider<PlanPresentationPort> presentation, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, UserAccessPort access) {
        this.reads=reads; this.budgets=budgets; this.profiles=profiles; this.recalculation=recalculation; this.locks=locks;
        this.revisions=revisions; this.idempotency=idempotency; this.plans=plans; this.presentation=presentation; this.jdbc=jdbc; this.mapper=mapper; this.clock=clock;this.access=access;
    }
    private PlanQueryPort plans() { var port=plans.getIfAvailable(); if(port==null)throw ApiException.unavailable(); return port; }

    @Transactional
    public Monthly get(long userId, String monthText) {
        YearMonth month=BudgetInput.month(monthText);
        locks.lock(userId);
        profiles.ensureCurrentSnapshots(userId);
        var snapshots=budgets.through(userId,YearMonth.now(clock));
        recalculation.recalculateFrom(userId,snapshots.isEmpty()?month:snapshots.getFirst().month());
        return view(userId, month);
    }

    public Monthly view(long userId, YearMonth month) {
        var e=reads.evaluate(userId,month); var b=e.budget();
        var allocation=allocation(userId,e,month);
        Long total=b==null?null:Math.addExact(b.fixedEssentialExpenses(),b.variableEssentialExpenses());
        long paid=Math.addExact(e.payments().cashPaid(), e.payments().savingsUsed());
        return new Monthly(month.toString(), e.monthlyIncome(), b==null?null:b.incomeType(), b==null?null:b.payday(), total,
            b==null?null:b.discretionaryExpenses(), b==null?null:b.savings(), b==null?null:b.emergencyFund(),e.due().scheduledCash(), paid,
            allocation, b==null?"INSUFFICIENT":b.expensesConfirmed()?"ENTERED":"DEFAULT", b==null||b.updatedAt()==null?null:b.updatedAt().atZone(clock.getZone()).toOffsetDateTime(),
            b==null?null:b.fixedEssentialExpenses(), b==null?null:b.variableEssentialExpenses(),b==null?"DEFAULT":b.source(),
            b==null?null:b.incomeType()==IncomeType.REGULAR?b.expectedIncome():0L, e.entries(), needsReview(e,allocation),e.metrics());
    }
    private boolean needsReview(BudgetReadService.Evaluation e, AllocationSummary allocation) {
        return e.metrics().allocationCapacity()==null || allocation.totalAllocated()>e.metrics().allocationCapacity();
    }
    private AllocationSummary allocation(long userId, BudgetReadService.Evaluation e, YearMonth month) {
        List<PlannedAmount> amounts=e.budget()!=null&&e.budget().allocationOverridden()?budgets.overrides(e.budget()):plans().plannedAllocations(userId,month);
        List<Allocation> result=amounts.stream().map(p -> new Allocation(p.loanId(),p.cashAmount(),p.allocationType(),
            p.allocationType()==AllocationType.REPAYMENT_SAVING?e.savings().additionsByLoan().getOrDefault(p.loanId(),0L):e.payments().extraCashByLoan().getOrDefault(p.loanId(),0L))).toList();
        long reserve=e.budget()==null?0:e.budget().reserveAmount();
        Map<Long,Long> target=new HashMap<>(); result.forEach(a->target.put(a.loanId(),a.amount()));
        long allocated=reserve;
        for(var a:result)allocated=Math.addExact(allocated,Math.max(a.amount(),a.actualAmount()));
        for(var a:e.payments().extraCashByLoan().entrySet()) if(!target.containsKey(a.getKey()))allocated=Math.addExact(allocated,a.getValue());
        for(var a:e.savings().additionsByLoan().entrySet()) if(!target.containsKey(a.getKey()))allocated=Math.addExact(allocated,a.getValue());
        Long capacity=e.metrics().allocationCapacity();
        return new AllocationSummary(result,reserve,allocated,capacity==null?null:Math.max(capacity-allocated,0));
    }
    private YearMonth currentMonth(String monthText) {
        YearMonth month=BudgetInput.month(monthText);
        if(!month.equals(YearMonth.now(clock)))throw BudgetInput.invalid("year_month","현재 월만 수정할 수 있습니다.");
        return month;
    }
    private MonthlyBudget requireBudget(long userId,YearMonth month) {
        profiles.requireProfile(userId); profiles.ensureCurrentSnapshots(userId);
        return budgets.find(userId,month).orElseThrow(ApiException::unavailable);
    }

    @Transactional
    public Monthly save(long userId,String monthText,JsonNode body) {
        YearMonth month=currentMonth(monthText); locks.lock(userId); var b=requireBudget(userId,month);
        try {
            JsonPatch.fields(body,"fixed_essential_expenses","variable_essential_expenses","discretionary_expenses","savings","income_entries");
            long fixed=BudgetInput.amount(body,"fixed_essential_expenses"), variable=BudgetInput.amount(body,"variable_essential_expenses"),
                discretionary=BudgetInput.amount(body,"discretionary_expenses"), savings=BudgetInput.amount(body,"savings");
            Math.addExact(Math.addExact(fixed,variable),Math.addExact(discretionary,savings));
            if(body.has("income_entries")) replaceEntries(userId,b,body.get("income_entries"));
            jdbc.update("""
                UPDATE monthly_budgets SET fixed_essential_expenses=?,variable_essential_expenses=?,discretionary_expenses=?,savings=?,
                expenses_confirmed=TRUE,source='INPUT',updated_at=? WHERE id=? AND user_id=?
                """,fixed,variable,discretionary,savings,Timestamp.from(clock.instant()),b.id(),userId);
            recalculation.recalculateFrom(userId,month); revisions.bump(userId,"MONTHLY_BUDGET_CHANGED");
            return view(userId,month);
        } catch(ApiException ex) { throw budgetError(ex); }
        catch(ArithmeticException ex) { throw BudgetInput.invalid("non_field_errors","금액 합계가 지원 범위를 초과합니다."); }
    }
    private ApiException budgetError(ApiException ex) {
        return ex.code().equals("VALIDATION_ERROR")?new ApiException(HttpStatus.BAD_REQUEST,"BUDGET_VALIDATION_ERROR","월별 재정정보를 확인해 주세요.",ex.errors()):ex;
    }
    private record EntryInput(Long id,String type,LocalDate date,long amount) {}
    private EntryInput entry(JsonNode body,MonthlyBudget b,boolean allowId) {
        if(allowId)JsonPatch.fields(body,"income_entry_id","entry_type","received_date","amount");
        else JsonPatch.fields(body,"entry_type","received_date","amount");
        String type=JsonPatch.text(body,"entry_type",false,16);
        if(!type.equals(b.incomeType()==IncomeType.REGULAR?"EXTRA":"IRREGULAR"))throw BudgetInput.invalid("entry_type","현재 소득 유형과 일치해야 합니다.");
        LocalDate date;
        try { date=LocalDate.parse(JsonPatch.text(body,"received_date",false,10)); }
        catch(DateTimeParseException ex) { throw BudgetInput.invalid("received_date","날짜를 확인해 주세요."); }
        if(!YearMonth.from(date).equals(b.month())||date.isAfter(LocalDate.now(clock)))throw BudgetInput.invalid("received_date","해당 월의 오늘 이하 날짜여야 합니다.");
        long amount=BudgetInput.amount(body,"amount"); if(amount==0)throw BudgetInput.invalid("amount","1원 이상이어야 합니다.");
        Long id=body.hasNonNull("income_entry_id")?BudgetInput.amount(body,"income_entry_id"):null;
        if(id!=null&&id==0)throw BudgetInput.invalid("income_entry_id","양의 ID가 필요합니다.");
        return new EntryInput(id,type,date,amount);
    }
    private IncomeEntry insert(long userId,MonthlyBudget b,EntryInput e) {
        long id=jdbc.queryForObject("INSERT INTO income_entries(user_id,budget_id,entry_type,received_date,amount,created_at) VALUES (?,?,?,?,?,?) RETURNING id",
            Long.class,userId,b.id(),e.type(),Date.valueOf(e.date()),e.amount(),Timestamp.from(clock.instant()));
        return new IncomeEntry(id,e.type(),e.date(),e.amount());
    }
    private void replaceEntries(long userId,MonthlyBudget b,JsonNode array) {
        if(!array.isArray())throw BudgetInput.invalid("income_entries","배열이어야 합니다.");
        var owned=budgets.entries(userId,b.id()).stream().map(IncomeEntry::incomeEntryId).collect(java.util.stream.Collectors.toSet());
        List<EntryInput> inputs=new ArrayList<>(); Set<Long> kept=new HashSet<>();
        for(JsonNode value:array) {
            var e=entry(value,b,true);
            if(e.id()!=null&&!owned.contains(e.id()))throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","조회한 경로 또는 리소스를 찾을 수 없습니다.");
            if(e.id()!=null&&!kept.add(e.id()))throw BudgetInput.invalid("income_entries","입금 ID를 중복 없이 입력해 주세요.");
            inputs.add(e);
        }
        for(Long id:owned)if(!kept.contains(id))jdbc.update("DELETE FROM income_entries WHERE id=? AND user_id=? AND budget_id=?",id,userId,b.id());
        for(var e:inputs)if(e.id()==null)insert(userId,b,e); else jdbc.update("UPDATE income_entries SET entry_type=?,received_date=?,amount=? WHERE id=? AND user_id=? AND budget_id=?",
            e.type(),Date.valueOf(e.date()),e.amount(),e.id(),userId,b.id());
        jdbc.update("UPDATE monthly_budgets SET income_confirmed=TRUE WHERE id=? AND user_id=?",b.id(),userId);
    }

    @Transactional
    public StoredResponse addIncome(long userId,String key,JsonNode body) {
        return idempotency.execute(userId,"POST","/api/income-entries/",key,body,"INCOME_ENTRY_CONFLICT",()->{
            try {
                var month=YearMonth.now(clock); var b=requireBudget(userId,month); var e=entry(body,b,false); var created=insert(userId,b,e);
                jdbc.update("UPDATE monthly_budgets SET income_confirmed=TRUE,updated_at=? WHERE id=?",Timestamp.from(clock.instant()),b.id());
                recalculation.recalculateFrom(userId,month); revisions.bump(userId,"INCOME_CHANGED");
                var v=view(userId,month);
                return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("INCOME_ENTRY_CREATE_SUCCESS","소득 입금 기록을 저장했습니다.",
                    new IncomeCreated(created,month.toString(),v.monthlyIncome(),v.allocationNeedsReview(),v.metrics()))));
            }catch(ApiException ex){throw budgetError(ex);}
        });
    }

    @Transactional
    public StoredResponse allocate(long userId,String monthText,String key,JsonNode body) {
        YearMonth month=currentMonth(monthText);
        StoredResponse stored=idempotency.execute(userId,"PUT","/api/budgets/"+month+"/allocation/",key,body,"IDEMPOTENCY_CONFLICT",()->{
            try {
                var b=requireBudget(userId,month); JsonPatch.fields(body,"allocations","reserve_amount");
                long reserve=BudgetInput.amount(body,"reserve_amount");
                if(!body.has("allocations")||!body.get("allocations").isArray())throw BudgetInput.invalid("allocations","배열이어야 합니다.");
                var evaluation=reads.evaluate(userId,month); List<PlannedAmount> requested=new ArrayList<>(); Set<Long> ids=new HashSet<>();
                for(JsonNode value:body.get("allocations")) {
                    JsonPatch.fields(value,"loan_id","allocation_type","amount"); long loanId=BudgetInput.amount(value,"loan_id");
                    if(loanId==0||!ids.add(loanId))throw BudgetInput.invalid("allocations","대출 ID를 중복 없이 입력해 주세요.");
                    var loan=reads.loans().terms(userId,loanId,LocalDate.now(clock));
                    if(loan.status()!=LoanStatus.ACTIVE)throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
                    var type=JsonPatch.enumeration(value,"allocation_type",AllocationType.class);
                    if((loan.method()==RepaymentMethod.BULLET)!=(type==AllocationType.REPAYMENT_SAVING)
                        ||(loan.method()==RepaymentMethod.GRACE_INSTALLMENT&&loan.graceEnd()!=null&&LocalDate.now(clock).isBefore(loan.graceEnd())))
                        throw BudgetInput.invalid("allocations","대출 상환 방식과 거치 기간을 확인해 주세요.");
                    long amount=BudgetInput.amount(value,"amount");
                    long actual=type==AllocationType.REPAYMENT_SAVING?evaluation.savings().additionsByLoan().getOrDefault(loanId,0L):evaluation.payments().extraCashByLoan().getOrDefault(loanId,0L);
                    if(amount<actual)throw BudgetInput.invalid("allocations","이미 실제 사용한 금액 미만으로 줄일 수 없습니다.");
                    requested.add(new PlannedAmount(loanId,type,amount,null));
                }
                long sum=reserve;
                for(var p:requested)sum=Math.addExact(sum,p.cashAmount());
                for(var e:evaluation.payments().extraCashByLoan().entrySet())if(!ids.contains(e.getKey()))sum=Math.addExact(sum,e.getValue());
                for(var e:evaluation.savings().additionsByLoan().entrySet())if(!ids.contains(e.getKey()))sum=Math.addExact(sum,e.getValue());
                Long cap=evaluation.metrics().allocationCapacity();
                if(cap==null||sum>cap)throw BudgetInput.invalid("allocations","배분 합계가 현재 배분 가능액을 초과합니다.");
                var viewPort=presentation.getIfAvailable(); if(viewPort==null)throw ApiException.unavailable();
                jdbc.update("DELETE FROM monthly_allocations WHERE budget_id=?",b.id());
                for(var p:requested)jdbc.update("INSERT INTO monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (?,?,?,?)",b.id(),p.loanId(),p.allocationType().name(),p.cashAmount());
                jdbc.update("UPDATE monthly_budgets SET reserve_amount=?,allocation_overridden=TRUE,updated_at=? WHERE id=?",reserve,Timestamp.from(clock.instant()),b.id());
                recalculation.recalculateFrom(userId,month); revisions.bump(userId,"MONTHLY_ALLOCATION_CHANGED");
                var updated=reads.evaluate(userId,month); var overall=plans().overallState(userId);
                PlanType planType=access.planType(userId);
                var result=new AllocationSaved(month.toString(),allocation(userId,updated,month),viewPort.monthViews(userId,LocalDate.now(clock),month,List.copyOf(requested)),
                    planType==PlanType.PRO?overall.strategyCode():null,planType==PlanType.PRO&&overall.exists(),true,AllocationMetrics.from(updated.metrics()));
                return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("BUDGET_ALLOCATION_SUCCESS","여유자금 배분을 저장했습니다.",result)));
            }catch(ApiException ex) {
                if(!ex.code().equals("VALIDATION_ERROR")&&!ex.code().equals("BUDGET_VALIDATION_ERROR"))throw ex;
                throw new ApiException(HttpStatus.BAD_REQUEST,"ALLOCATION_VALIDATION_ERROR","배분 입력값을 확인해 주세요.",ex.errors());
            }catch(ArithmeticException ex){throw new ApiException(HttpStatus.BAD_REQUEST,"ALLOCATION_VALIDATION_ERROR","배분 금액 합계를 확인해 주세요.");}
        });
        if(access.planType(userId)==PlanType.FREE) {
            var bodyCopy=stored.body().deepCopy();
            var data=(com.fasterxml.jackson.databind.node.ObjectNode)bodyCopy.get("data");
            data.putNull("strategy_code");data.put("overall_plan_exists",false);
            for(var plan:data.path("updated_plans"))if(plan.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)plan).putNull("priority");
            return new StoredResponse(stored.status(),bodyCopy);
        }
        return stored;
    }
}
