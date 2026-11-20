package com.loanrun.repaymentsaving.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.dto.*;
import com.loanrun.repaymentsaving.repository.SavingWriteRepository;
import java.time.*;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

@Service
public class SavingCreateService {
    private final IdempotencyService idempotency;
    private final SavingWriteRepository writes;
    private final LoanSourceRepository sources;
    private final SavingListService summaries;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final RevisionPort revisions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SavingCreateService(IdempotencyService idempotency,SavingWriteRepository writes,LoanSourceRepository sources,SavingListService summaries,
        ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.idempotency=idempotency;this.writes=writes;this.sources=sources;this.summaries=summaries;this.budgets=budgets;
        this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public StoredResponse create(long userId,JsonNode body,String key) {
        try {
            // A committed replay wins over later status/date/dependency changes.
            return idempotency.execute(userId,"POST","/api/repayment-savings/",key,body,"SAVING_CONFLICT",()->save(userId,body));
        } catch(ApiException ex) {
            if(ex.code().equals("SAVING_CONFLICT"))throw conflict();
            if(ex.code().equals("VALIDATION_ERROR")&&ex.errors().containsKey("Idempotency-Key"))throw invalid();
            throw ex;
        }
    }
    private StoredResponse save(long userId,JsonNode body) {
        if(body==null||!body.isObject()||body.size()!=3||!positive(body.path("loan_id"))||!positive(body.path("amount"))
            ||!body.path("saved_at").isTextual())throw invalid();
        long loanId=body.path("loan_id").longValue(),amount=body.path("amount").longValue();
        LocalDate date;
        try {
            String value=body.path("saved_at").textValue();if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
            date=LocalDate.parse(value);if(date.getYear()<1)throw invalid();
        } catch(DateTimeParseException ex) {throw invalid();}
        var now=clock.instant();var today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var loan=writes.lock(userId,loanId);
        if(!loan.status().equals("ACTIVE")||!loan.method().equals("BULLET")||date.isAfter(today))throw invalid();
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        var snapshot=sources.loanSnapshot(userId,loanId,today);if(snapshot.loans().size()!=1)throw ApiException.unavailable();
        var registered=snapshot.loans().getFirst().createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        if(!date.isBefore(registered)&&date.isBefore(loan.snapshotDate()))throw invalid();
        try {
            LoanSavingsCalculator.balance(snapshot.loans().getFirst(),snapshot);
            LoanSavingsCalculator.balanceAfterAddition(snapshot.loans().getFirst(),snapshot,date,amount);
        } catch(ArithmeticException ex) {throw conflict();}
        // A positive addition cannot reduce any existing event's asset balance. No withdrawals are reordered.
        long id=writes.insert(userId,loanId,date,amount,now);
        budget.recalculateFrom(userId,YearMonth.from(date));
        revisions.bump(userId,"REPAYMENT_SAVING_CREATED");
        var query=new LinkedMultiValueMap<String,String>();query.add("loan_id",Long.toString(loanId));
        var summary=summaries.list(userId,query);
        var item=summary.results().stream().filter(s->s.savingId()==id).findFirst().orElseThrow(ApiException::unavailable);
        var response=new SavingCreated(item,loanId,summary.savingsBalance(),summary.requiredMonthlySaving(),summary.plannedSavingAmount(),
            summary.actualSavingAmount(),summary.savingProgressPct(),summary.remainingPrincipal(),true);
        return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("SAVING_CREATE_SUCCESS","상환자금 적립 기록 성공",response)));
    }
    private static boolean positive(JsonNode node){return node.isIntegralNumber()&&node.canConvertToLong()&&node.longValue()>0;}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"SAVING_VALIDATION_ERROR","만기일시상환 적립 입력값을 확인해 주세요.");}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"SAVING_CONFLICT","적립 잔액 또는 이미 사용된 금액과 충돌합니다.");}
}
