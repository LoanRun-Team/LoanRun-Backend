package com.loanrun.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.finance.engine.*;
import com.loanrun.loan.domain.LoanTermMapper;
import com.loanrun.loan.read.*;
import com.loanrun.loan.repository.*;
import com.loanrun.payment.domain.*;
import com.loanrun.payment.repository.PaymentWriteRepository;
import com.loanrun.repaymentplan.service.PlanReadService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The caller owns user/loan locks and the actual-row mutation. All derived writes share that transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PaymentLedgerCoordinator {
    private final LoanContractWriteRepository contracts;private final LoanSourceRepository loans;private final PaymentWriteRepository writes;private final LoanRegistrationRepository registrations;
    private final RepaymentEngine engine;private final RevisionPort revisions;private final ObjectProvider<BudgetRecalculationPort> budgets;private final PaymentReadService payments;private final PaymentScheduleService schedule;private final PlanReadService plans;private final ObjectMapper mapper;private final Clock clock;
    public PaymentLedgerCoordinator(LoanContractWriteRepository contracts,LoanSourceRepository loans,PaymentWriteRepository writes,LoanRegistrationRepository registrations,RepaymentEngine engine,RevisionPort revisions,ObjectProvider<BudgetRecalculationPort> budgets,PaymentReadService payments,PaymentScheduleService schedule,PlanReadService plans,ObjectMapper mapper,Clock clock){this.contracts=contracts;this.loans=loans;this.writes=writes;this.registrations=registrations;this.engine=engine;this.revisions=revisions;this.budgets=budgets;this.payments=payments;this.schedule=schedule;this.plans=plans;this.mapper=mapper;this.clock=clock;}
    public LoanSources.Snapshot lock(long user,long loan) {contracts.lock(user,loan);return loans.loanSnapshot(user,loan,today());}
    public void requireBudget(){if(budgets.getIfAvailable()==null)throw ApiException.unavailable();}
    public void recalculateHistorical(long user,LocalDate date){budgets.getIfAvailable().recalculateFrom(user,YearMonth.from(date));}
    public void apply(long user,LoanSources.Snapshot old,PaymentReplay.Result replay,LocalDate changedDate) {
        var l=old.loans().getFirst();long id=l.loanId();var today=today();var sorted=old.dues().stream().sorted(Comparator.comparing(LoanSources.Due::dueDate).thenComparingLong(LoanSources.Due::id)).toList();
        if(replay.balance()==0&&sorted.stream().anyMatch(d->replay.paid(d.id())<d.requiredAmount()&&(!d.dueDate().isAfter(today)||replay.allocations().stream().anyMatch(a->a.due()==d.id()))))throw new PaymentReplay.Conflict();
        if(replay.balance()>0&&l.storedStatus()==LoanStatus.PAID&&registrations.activeCount(user)>=7)throw new PaymentReplay.Conflict();
        writes.allocations(user,id,replay);LocalDate cursor=l.storedNextDueDate();
        var next=sorted.stream().filter(d->replay.paid(d.id())<d.requiredAmount()||d.requiredAmount()==0&&!d.dueDate().isBefore(today)).findFirst();
        if(next.isPresent())cursor=next.get().dueDate();else if(!sorted.isEmpty())cursor=LoanTermMapper.after(l,sorted.getLast().dueDate());
        boolean paid=replay.balance()==0;Map<String,Object> changes=new LinkedHashMap<>();changes.put("remaining_principal",replay.balance());changes.put("status",paid?"PAID":"ACTIVE");changes.put("next_due_date",cursor);
        if(next.isPresent())changes.put("contractual_monthly_payment",next.get().contractualAmount());else if(!cursor.equals(l.storedNextDueDate()))changes.put("contractual_monthly_payment",null);
        if(!paid&&cursor.isAfter(l.storedNextDueDate())&&l.prepaymentPolicy()==PrepaymentPolicy.KEEP_MATURITY&&!cursor.isAfter(l.maturityDate())) {
            var t=LoanTermMapper.current(l,cursor,null,0);int periods=(int)RepaymentDates.remaining(t,today).stream().filter(d->l.graceEnd()==null||!d.isBefore(l.graceEnd())).count();var method=l.method()==RepaymentMethod.GRACE_INSTALLMENT?l.postGraceMethod():l.method();
            if(periods>0&&method==RepaymentMethod.EQUAL_PAYMENT)changes.put("base_anchor_amount",RepaymentMath.annuity(replay.balance(),l.annualRate(),periods));
            if(periods>0&&method==RepaymentMethod.EQUAL_PRINCIPAL)changes.put("fixed_principal",RepaymentMath.fixedPrincipal(replay.balance(),periods));
        }
        contracts.updateContract(user,id,changes,clock.instant());long revision=revisions.bump(user,"PAYMENT_LEDGER_CHANGED");
        for(var due:sorted)if(due.dueDate().isAfter(today)&&replay.allocations().stream().noneMatch(a->a.due()==due.id())&&(paid||due.dueDate().isAfter(cursor))) {
            if(due.confirmedUnpaid())throw new PaymentReplay.Conflict();writes.archiveDue(user,id,due,clock.instant());contracts.removeUnconfirmedDue(user,id,due.id());
        }
        if(!paid&&!cursor.isAfter(l.maturityDate())) {
            var dueDate=cursor;var fresh=loans.loanSnapshot(user,id,today);var due=fresh.dues().stream().filter(d->d.dueDate().equals(dueDate)).findFirst();
            if(due.isEmpty()) {
                if(writes.restoreDue(user,id,cursor)) {
                    var restored=loans.loanSnapshot(user,id,today).dues().stream().filter(d->d.dueDate().equals(dueDate)).findFirst().orElseThrow(ApiException::unavailable);var fields=new HashMap<String,Object>();fields.put("contractual_monthly_payment",restored.contractualAmount());contracts.updateContract(user,id,fields,clock.instant());
                } else {
                    if(cursor.isBefore(today))throw ApiException.unavailable(); // Only a captured old obligation may be restored into the past.
                    var loan=fresh.loans().getFirst();var terms=LoanTermMapper.current(loan,cursor,loan.contractualNextAmount(),com.loanrun.loan.domain.LoanSavingsCalculator.balance(loan,fresh));
                    var result=engine.simulate(new SimulationInput(List.of(terms),today,1200,Map.of(),Map.of(),null,"NONE"));if(result.rows().isEmpty())throw ApiException.unavailable();registrations.firstDue(user,id,result.rows().getFirst(),loan.contractualNextAmount(),revision,clock.instant());
                }
            }
            // Existing first-cycle interest remains frozen; only the next generated cycle uses the updated balance.
        }
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();budget.recalculateFrom(user,YearMonth.from(changedDate));
    }
    public ObjectNode response(long user,long payment,long loan,Long targetDue,boolean includePlan) {
        var result=mapper.createObjectNode();result.set("payment",mapper.valueToTree(payments.detail(user,payment)));var source=loans.loanSnapshot(user,loan,today());result.put("remaining_principal",source.loans().getFirst().storedRemainingPrincipal());
        Long due=targetDue;if(due==null||source.dues().stream().noneMatch(d->d.id()==targetDue))due=source.dues().stream().filter(d->d.dueDate().equals(source.loans().getFirst().storedNextDueDate())).map(LoanSources.Due::id).findFirst().orElse(null);
        if(includePlan){result.set("updated_schedule",due==null?mapper.nullNode():schedule.item(user,due));var plan=plans.list(user,PlanType.FREE,Long.toString(loan),null).path("plans");if(plan.size()!=1)throw ApiException.unavailable();result.set("updated_plan",plan.get(0));}
        else {result.put("schedule_status",due==null?"PAID":schedule.item(user,due).path("status").asText());result.put("report_stale",true);}return result;
    }
    public ObjectNode deletionResponse(long user,long loan,long payment,Long preferred) {
        var source=loans.loanSnapshot(user,loan,today());var l=source.loans().getFirst();var due=preferred==null?Optional.<LoanSources.Due>empty():source.dues().stream().filter(d->d.id()==preferred).findFirst();
        if(due.isEmpty())due=source.dues().stream().filter(d->d.dueDate().equals(l.storedNextDueDate())).findFirst();
        if(due.isEmpty())due=source.dues().stream().max(Comparator.comparing(LoanSources.Due::dueDate).thenComparingLong(LoanSources.Due::id));
        String status;if(due.isPresent())status=schedule.item(user,due.get().id()).path("status").asText();else if(l.storedStatus()==LoanStatus.PAID)status="PAID";else throw ApiException.unavailable();
        var data=mapper.createObjectNode();data.put("payment_id",payment);data.put("deleted",true);data.put("remaining_principal",l.storedRemainingPrincipal());data.put("schedule_status",status);data.put("report_stale",true);return data;
    }
    public LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
}
