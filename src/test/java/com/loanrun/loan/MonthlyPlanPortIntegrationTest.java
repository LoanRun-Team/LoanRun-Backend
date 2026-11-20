package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.repository.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(MonthlyPlanPortIntegrationTest.Config.class)
class MonthlyPlanPortIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId z){return Clock.fixed(value,z);}public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock viewClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets;
    @MockitoBean PaymentObservationSource paymentProof;
    @MockitoBean SavingObservationSource savingProof;
    @Autowired PlanPresentationPort views;
    @Autowired PlanQueryPort plans;
    @Autowired LoanQueryPort loans;
    @Autowired LoanRegistrationService registration;
    @Autowired PlanVersionRepository versions;
    @Autowired PlanApplicationRepository applications;
    @Autowired RevisionPort revisions;
    @Autowired PaymentObservationQueryService payments;
    @Autowired SavingObservationQueryService savings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TestClock clock;
    @BeforeEach void setup()throws Exception {
        clock.value=Instant.parse("2026-02-28T15:30:00Z");
        for(String t:List.of("repayment_plan_versions","plan_application_events"))if(jdbc.queryForObject("SELECT to_regclass(?)",String.class,t)==null)jdbc.execute(Files.readString(Path.of("docs/sql/"+t+"_proposal.sql")));
    }
    long user(){var now=Timestamp.from(clock.instant());return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),now,now);}
    long loan(long u,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();body.put("fee_exempt",true);if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(u,body,null).body().path("data").path("loan_id").longValue();
    }
    LocalDate day(int m,int d){return LocalDate.of(2026,m,d);}YearMonth month(int m){return YearMonth.of(2026,m);}
    void extra(long u,long id,int m,long amount) {
        var t=loans.terms(u,id,day(3,1));var draft=new Draft(id,Mode.EXTRA_AMOUNT,null,null,t.maturityDate(),amount,0,day(m,25),revisions.current(u),t,new Inputs(amount,null,null),null);tx.execute(s->versions.append(u,1,draft));
    }
    PlanPresentationPort.PlanView view(long u,int m,List<PlannedAmount> overrides){return views.monthViews(u,LocalDate.now(clock),month(m),overrides).getFirst();}
    void completePayments(){when(paymentProof.find(any())).thenAnswer(inv->{var scope=inv.<com.loanrun.payment.read.PaymentObservation.Scope>getArgument(0);var p=payments.snapshot(scope.userId(),scope.month(),scope.loanId());return Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(scope,p.fingerprint(),p.recorded().paymentCount()==0?com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED:com.loanrun.payment.read.PaymentObservation.Kind.RECORDING_COMPLETE,clock.instant()));});}
    void completeSavings(){when(savingProof.find(any())).thenAnswer(inv->{var scope=inv.<com.loanrun.repaymentsaving.read.SavingObservation.Scope>getArgument(0);var p=savings.snapshot(scope.userId(),scope.loanId(),scope.month());return Optional.of(new com.loanrun.repaymentsaving.read.SavingObservation.Confirmation(scope,p.fingerprint(),p.recorded().count()==0?com.loanrun.repaymentsaving.read.SavingObservation.Kind.ZERO_CONFIRMED:com.loanrun.repaymentsaving.read.SavingObservation.Kind.RECORDING_COMPLETE,clock.instant()));});}
    @Test void closedLoanOverridesDoNotPoisonCurrentOrFutureHouseholdViews()throws Exception {
        for(String status:List.of("DELETED","PAID")) {
            long u=user(),closed=loan(u,false);
            jdbc.update("UPDATE loans SET status=?,updated_at=? WHERE id=?",status,Timestamp.from(clock.instant()),closed);
            var overrides=List.of(new PlannedAmount(closed,AllocationType.EXTRA_PAYMENT,100000,null));
            assertThat(views.monthViews(u,day(3,1),month(3),overrides)).isEmpty();
            assertThat(views.monthViews(u,day(3,1),month(4),overrides)).isEmpty();
            long active=loan(u,false);
            assertThat(views.monthViews(u,day(3,1),month(3),overrides)).singleElement().satisfies(v->assertThat(v.loanId()).isEqualTo(active));
            assertThat(loans.terms(u,active,day(3,1)).status()).isEqualTo(LoanStatus.ACTIVE);
            assertThat(jdbc.queryForObject("SELECT status FROM loans WHERE id=?",String.class,closed)).isEqualTo(status);
        }
    }
    @Test void closedForeignOverrideStillFailsOwnershipValidation()throws Exception {
        long u=user(),other=user(),id=loan(other,false);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);
        assertThatThrownBy(()->views.monthViews(u,day(3,1),month(3),List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,10,null))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(404));
    }
    @Test void currentOverrideChangesMonthlyDisplayWithoutChangingTheRecurringGoalOrVersion()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,3,500000);
        var before=jdbc.queryForList("SELECT * FROM repayment_plan_versions WHERE user_id=?",u);
        var v=view(u,3,List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,0,null)));
        assertThat(v.extraMonthlyPayment()).isEqualTo(500000);assertThat(v.plannedExtraAmount()).isZero();assertThat(v.cumulativePlannedExtraAmount()).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM repayment_plan_versions WHERE user_id=?",u)).isEqualTo(before);
    }
    @Test void nextCyclePlanDoesNotReplaceTheRequestedMonthsOriginalTarget()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,4,100000);jdbc.update("UPDATE due_items SET required_amount=0,principal_amount=0,interest_amount=0 WHERE loan_id=?",id);
        clock.value=Instant.parse("2026-03-25T15:00:00Z");
        assertThat(plans.currentPlans(u,day(3,26)).getFirst().monthlyExtraCash()).isEqualTo(100000);
        assertThat(view(u,3,List.of()).plannedExtraAmount()).isZero();assertThat(view(u,4,List.of()).plannedExtraAmount()).isNull();
    }
    @Test void capturedFuturePlanComputesTargetsButDoesNotInventFutureActualsOrAssets()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,3,500000);var v=view(u,4,List.of());
        assertThat(v.plannedExtraAmount()).isEqualTo(500000);assertThat(v.actualExtraAmount()).isNull();assertThat(v.savingsBalance()).isNull();
        assertThat(v.projectedPayoffDate()).isNull();assertThat(v.track().dataStatus()).isEqualTo("INSUFFICIENT");
    }
    @Test void historicalExtraMetricsUseTheConfirmedVersionWindowAndActualRecords()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,3,500000);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        jdbc.update("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-25','EXTRA',500000,0,500000,0,0,0,?)",u,id,Timestamp.from(clock.instant()));
        completePayments();var v=view(u,3,List.of());assertThat(v.actualExtraAmount()).isEqualTo(500000);assertThat(v.cumulativeActualExtraAmount()).isEqualTo(500000);
        assertThat(v.cumulativePlannedExtraAmount()).isEqualTo(500000);assertThat(v.cumulativeExtraGapAmount()).isZero();assertThat(v.actualProgressPct()).isNull();
        assertThat(v.observedMonthCount()).isEqualTo(1);
    }
    @Test void historicalSavingNeedsItsOwnEvidenceAndInitialAssetsAreNotDeposits()throws Exception {
        long u=user(),id=loan(u,true);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-25',700,?)",u,id,Timestamp.from(clock.instant()));
        completePayments();assertThat(view(u,3,List.of()).actualSavingAmount()).isNull();completeSavings();
        var v=view(u,3,List.of());assertThat(v.actualSavingAmount()).isEqualTo(700);assertThat(v.cumulativeActualSavingAmount()).isEqualTo(700);assertThat(v.savingsBalance()).isEqualTo(2000700);
    }
    @Test void freeHidesPriorityWhileProReadsTheActualOwnedApplication()throws Exception {
        long u=user(),id=loan(u,false);applications.append(u,revisions.current(u),true,"FAST",List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,0,2)),clock.instant());
        assertThat(view(u,3,List.of()).priority()).isNull();jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",u);
        assertThat(view(u,3,List.of()).priority()).isEqualTo(2);
    }
    @Test void invalidOrForeignOverridesCannotAlterAnotherOwnersDisplay()throws Exception {
        long u=user(),id=loan(u,false),v=user(),other=loan(v,false);
        assertThatThrownBy(()->view(u,3,List.of(new PlannedAmount(other,AllocationType.EXTRA_PAYMENT,100,null)))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
        for(var values:List.of(List.of(new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,100,null)),List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,1,null),new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,2,null))))
            assertThatThrownBy(()->view(u,3,values)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void emptyScopeIsRealAndFutureReadDatesAreUnavailable() {
        long u=user();assertThat(views.monthViews(u,day(3,1),month(3),List.of())).isEmpty();
        assertThatThrownBy(()->views.monthViews(u,day(3,2),month(3),List.of())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void historicalAsOfDoesNotExposeCurrentAssetsOrInventLaterCompletion()throws Exception {
        long u=user(),id=loan(u,true);clock.value=Instant.parse("2026-03-31T15:00:00Z");completePayments();completeSavings();
        var v=views.monthViews(u,day(3,1),month(3),List.of()).getFirst();assertThat(v.savingsBalance()).isNull();assertThat(v.actualSavingAmount()).isNull();assertThat(v.regularDueStatus()).isNull();
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0,maturity_date='2026-03-31',updated_at=? WHERE id=?",Timestamp.from(clock.instant()),id);
        assertThat(views.monthViews(u,day(3,1),month(5),List.of())).singleElement().satisfies(old->{assertThat(old.loanId()).isEqualTo(id);assertThat(old.targetPayoffDate()).isEqualTo(day(12,25));});
    }
    @Test void readsPreserveLedgerRevisionAndApplicationEvents()throws Exception {
        long u=user();loan(u,false);var account=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u);var ledger=jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u);
        view(u,3,List.of());view(u,4,List.of());
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u)).isEqualTo(account);assertThat(jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u)).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_application_events WHERE user_id=?",Long.class,u)).isZero();
    }
    @Test void currentSavingOverrideCanBeShownBeforeTheRegistrationPlansFirstDueMonth()throws Exception {
        clock.value=Instant.parse("2026-03-25T15:00:00Z");long u=user(),id=loan(u,true);
        var goal=plans.currentPlans(u,day(3,26)).getFirst();assertThat(goal.effectiveDate()).isEqualTo(day(4,25));
        var v=view(u,3,List.of(new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,700,null)));
        assertThat(v.plannedSavingAmount()).isEqualTo(700);assertThat(v.monthlyAmount()).isZero();assertThat(v.effectiveDate()).isEqualTo(goal.effectiveDate());
        assertThat(plans.currentPlans(u,day(3,26)).getFirst()).isEqualTo(goal);
    }
    @Test void unknownFeesDoNotTurnAPositiveCashGoalIntoFabricatedNetPrincipal()throws Exception {
        long u=user();var body=new LoanPreviewTest().body();body.put("fee_exempt",false);
        long id=registration.create(u,body,null).body().path("data").path("loan_id").longValue();extra(u,id,3,500000);
        var v=view(u,3,List.of());assertThat(v.extraMonthlyPayment()).isEqualTo(500000);assertThat(v.plannedExtraAmount()).isNull();assertThat(v.estimatedTotalFee()).isNull();
    }
    @Test void payingBulletInterestDoesNotRemoveThisMonthsIndependentSavingOpportunity()throws Exception {
        long u=user(),id=loan(u,true);
        long p=jdbc.queryForObject("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-01','REGULAR',60000,0,0,60000,0,0,?) RETURNING id",Long.class,u,id,Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,0,60000,0 FROM due_items WHERE loan_id=?",u,id,p,id);
        jdbc.update("UPDATE loans SET next_due_date='2026-04-25' WHERE id=?",id);
        var v=view(u,3,List.of());assertThat(v.plannedSavingAmount()).isEqualTo(1000000);assertThat(v.requiredMonthlySaving()).isEqualTo(1000000);
        assertThat(plans.currentPlans(u,day(3,1)).getFirst().requiredMonthlySaving()).isEqualTo(1000000);
    }
}
