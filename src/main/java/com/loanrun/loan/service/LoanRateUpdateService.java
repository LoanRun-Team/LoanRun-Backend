package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.finance.engine.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.repository.LoanContractWriteRepository;
import com.loanrun.repaymentplan.service.PlanReadService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRateUpdateService {
    private final UserMutationLock locks;private final LoanContractWriteRepository writes;private final LoanSourceRepository sources;
    private final CurrentLoanTermsQueryService current;private final RepaymentEngine engine;private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final RevisionPort revisions;private final LoanListService loans;private final PlanReadService plans;private final ObjectMapper mapper;private final Clock clock;
    public LoanRateUpdateService(UserMutationLock locks,LoanContractWriteRepository writes,LoanSourceRepository sources,CurrentLoanTermsQueryService current,
        RepaymentEngine engine,ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,LoanListService loans,PlanReadService plans,ObjectMapper mapper,Clock clock) {
        this.locks=locks;this.writes=writes;this.sources=sources;this.current=current;this.engine=engine;this.budgets=budgets;this.revisions=revisions;this.loans=loans;this.plans=plans;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public JsonNode update(long user,long id,PlanType access,JsonNode body) {
        locks.lock(user);var loan=writes.lock(user,id);
        JsonPatch.fields(body,"effective_date","annual_rate");
        LocalDate date;try {String raw=JsonPatch.text(body,"effective_date",false,10);if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeException("date");date=LocalDate.parse(raw);if(date.getYear()<1)throw new DateTimeException("date");}
        catch(DateTimeException e){throw ApiException.validation("effective_date","적용일을 확인해 주세요.");}
        var node=body.get("annual_rate");if(node==null||!node.isNumber())throw ApiException.validation("annual_rate","금리를 확인해 주세요.");
        BigDecimal rate=node.decimalValue();if(rate.signum()<0||rate.compareTo(BigDecimal.valueOf(30))>=0||rate.stripTrailingZeros().scale()>2)throw ApiException.validation("annual_rate","금리를 확인해 주세요.");
        if(loan.rateType()!=RateType.VARIABLE)throw new ApiException(HttpStatus.BAD_REQUEST,"RATE_CHANGE_NOT_ALLOWED","고정금리 대출은 금리 변경 기록을 등록할 수 없습니다.");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));if(date.isAfter(today))throw ApiException.validation("effective_date","미래 금리를 예약할 수 없습니다.");
        var latest=writes.latestRateDate(user,id);
        boolean initialSameDay=latest!=null&&latest.equals(date)&&date.equals(loan.latestSnapshotDate())
            &&loan.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(date)
            &&writes.onlyRegistrationRateOn(user,id,date);
        if(date.isBefore(loan.latestSnapshotDate())||latest!=null&&!date.isAfter(latest)&&!initialSameDay)throw conflict();
        var basis=current.current(user,id);if(basis.terms()==null)throw conflict();var old=basis.terms();
        var source=sources.visibleLoanSnapshot(user,id,today);
        for(var due:source.dues())if(!due.dueDate().isBefore(old.nextDueDate())&&(due.confirmedUnpaid()||due.fee()!=0||source.allocations().stream().anyMatch(a->a.dueItemId()==due.id())))throw conflict();
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        Long anchor=old.baseAnchorAmount();var method=old.method()==RepaymentMethod.GRACE_INSTALLMENT?old.postGraceMethod():old.method();
        if(method==RepaymentMethod.EQUAL_PAYMENT) {
            int periods=(int)RepaymentDates.remaining(old,today).stream().filter(d->old.graceEnd()==null||!d.isBefore(old.graceEnd())).count();
            if(periods==0)throw conflict();anchor=RepaymentMath.annuity(old.outstanding(),rate,periods);
        }
        LocalDate reset=loan.rateResetMonths()==null?old.rateResetDate():date.plusMonths(loan.rateResetMonths());
        var changed=new LoanTerms(old.loanId(),user,old.productName(),old.loanType(),old.principalOriginal(),old.outstanding(),old.snapshotDate(),rate,old.rateType(),old.method(),old.graceEnd(),old.postGraceMethod(),old.nextDueDate(),old.dueDay(),old.maturityDate(),old.contractualNextAmount(),anchor,old.fixedPrincipal(),old.prepaymentPolicy(),old.feeMethod(),old.feeRate(),old.feeStartDate(),old.feeEndDate(),old.initialSavingBalance(),old.currentSavingBalance(),old.status(),reset);
        var schedule=engine.simulate(new SimulationInput(List.of(changed),today,1200,Map.of(),Map.of(),null,"NONE"));
        if(schedule.warnings().contains("CONTRACTUAL_AMOUNT_BELOW_INTEREST")||schedule.warnings().contains("BASIC_PAYMENT_BELOW_INTEREST"))throw conflict();
        long rateId=writes.insertRate(user,id,date,rate,clock.instant());writes.updateRate(user,id,changed,clock.instant());
        long revision=revisions.bump(user,"LOAN_RATE_UPDATED");
        for(var due:source.dues())if(!due.dueDate().isBefore(old.nextDueDate())) {
            var row=schedule.rows().stream().filter(r->r.date().equals(due.dueDate())).findFirst().orElseThrow(LoanRateUpdateService::conflict);
            writes.repriceDue(user,id,due.id(),row,due.contractualAmount(),revision);
        }
        budget.recalculateFrom(user,YearMonth.from(old.nextDueDate()));
        var view=loans.one(user,id);ObjectNode result=mapper.createObjectNode();var record=result.putObject("rate_record");
        record.put("rate_id",rateId);record.put("loan_id",id);record.put("effective_date",date.toString());record.put("previous_rate",loan.annualRate());record.put("new_rate",rate);record.put("source","MANUAL");
        result.set("loan",mapper.valueToTree(view));result.put("estimated_monthly_payment",view.estimatedMonthlyPayment());
        var p=plans.list(user,access,Long.toString(id),null).path("plans");if(p.size()!=1)throw ApiException.unavailable();result.set("recalculated_plan",p.get(0));return result;
    }
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"RATE_HISTORY_CONFLICT","같은 적용일의 금리 변경 기록이 있습니다.");}
}
