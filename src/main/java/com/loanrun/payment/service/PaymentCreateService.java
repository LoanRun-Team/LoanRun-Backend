package com.loanrun.payment.service;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.contract.FinanceModels.LoanStatus;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.payment.domain.*;
import com.loanrun.payment.repository.PaymentWriteRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PaymentCreateService {
    private final IdempotencyService idempotency;private final PaymentLedgerCoordinator ledger;private final PaymentWriteRepository writes;private final RevisionPort revisions;private final ObjectMapper mapper;private final Clock clock;
    public PaymentCreateService(IdempotencyService idempotency,PaymentLedgerCoordinator ledger,PaymentWriteRepository writes,RevisionPort revisions,ObjectMapper mapper,Clock clock){this.idempotency=idempotency;this.ledger=ledger;this.writes=writes;this.revisions=revisions;this.mapper=mapper;this.clock=clock;}
    @Transactional public StoredResponse create(long user,JsonNode body,String key) {
        try{return idempotency.execute(user,"POST","/api/payments/",key,body,"PAYMENT_CONFLICT",()->insert(user,body));}
        catch(PaymentReplay.Conflict e){throw new ApiException(HttpStatus.CONFLICT,"PAYMENT_CONFLICT","이미 반영된 납부 기록입니다.");}
        catch(ApiException e){if(e.code().equals("PAYMENT_CONFLICT"))throw new ApiException(HttpStatus.CONFLICT,"PAYMENT_CONFLICT","이미 반영된 납부 기록입니다.");throw e;}
        catch(ArithmeticException e){throw PaymentRequest.invalid();}catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private StoredResponse insert(long user,JsonNode body) {
        long loan=PaymentRequest.money(body,"loan_id",true);if(loan<=0)throw PaymentRequest.invalid();var source=ledger.lock(user,loan);var l=source.loans().getFirst();if(l.storedStatus()!=LoanStatus.ACTIVE)throw new PaymentReplay.Conflict();
        var input=PaymentRequest.read(body,writes.nextId(),clock.instant(),true);
        if(input.date().isAfter(ledger.today())||input.date().isBefore(l.startDate()))throw PaymentRequest.invalid();
        var registered=l.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        if(input.date().isBefore(registered)) {
            if(input.automatic()||input.dueId()!=null||input.used()!=0
                ||Math.addExact(Math.addExact(input.principal(),input.extra()),Math.addExact(input.interest(),input.fee()))!=input.amount()
                ||input.kind()==com.loanrun.loan.read.LoanSources.PaymentKind.EXTRA&&input.principal()!=0
                ||input.kind()==com.loanrun.loan.read.LoanSources.PaymentKind.REGULAR&&Math.addExact(input.principal(),Math.addExact(input.interest(),input.fee()))==0)
                throw PaymentRequest.invalid();
            ledger.requireBudget();
            writes.insert(user,loan,new PaymentReplay.Entry(input,input.principal(),input.extra(),input.interest(),input.fee()));
            revisions.bump(user,"PAYMENT_HISTORY_RECORDED");
            ledger.recalculateHistorical(user,input.date());
            return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("PAYMENT_CREATE_SUCCESS","납부 기록을 등록했습니다.",ledger.response(user,input.id(),loan,null,true))));
        }
        if(input.kind()==com.loanrun.loan.read.LoanSources.PaymentKind.REGULAR&&input.dueId()==null)throw PaymentRequest.invalid();
        if(input.date().isBefore(l.latestSnapshotDate()))throw PaymentRequest.invalid();
        var base=PaymentReplay.latest(source);if(base.reason().equals("CORRECTION")&&input.date().equals(base.effectiveDate())&&!input.createdAt().isAfter(base.createdAt()))throw new PaymentReplay.Conflict();
        ledger.requireBudget();var requests=new ArrayList<>(PaymentReplay.requests(source));requests.add(input);var replay=PaymentReplay.replay(source,requests);var item=replay.entries().stream().filter(e->e.request().id()==input.id()).findFirst().orElseThrow(ApiException::unavailable);
        writes.insert(user,loan,item);ledger.apply(user,source,replay,input.date());return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("PAYMENT_CREATE_SUCCESS","납부 기록을 등록했습니다.",ledger.response(user,input.id(),loan,input.dueId(),true))));
    }
}
