package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.PlanView;
import com.loanrun.common.error.ApiException;
import com.loanrun.loanproduct.client.FssCatalogClient;
import com.loanrun.loanproduct.service.CatalogWriter;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
class BudgetIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);registry.add("spring.datasource.password",DB::getPassword);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RevisionPort revisions;
    @Autowired BudgetQueryPort budgetQueries;
    @Autowired BudgetRecalculationPort recalculation;
    @Autowired TransactionTemplate transactions;
    @Autowired CatalogWriter catalogWriter;
    @Autowired ProductQueryPort productQueries;
    @MockitoBean LoanQueryPort loans;
    @MockitoBean PlanQueryPort plans;
    @MockitoBean PlanPresentationPort presentation;
    final AtomicLong owner=new AtomicLong(),usedExtra=new AtomicLong(),pastCash=new AtomicLong();
    static final YearMonth MONTH=YearMonth.of(2026,11);

    @BeforeEach void projections(){
        owner.set(0);usedExtra.set(0);pastCash.set(0);
        when(loans.dueSummary(anyLong(),any())).thenAnswer(inv->{YearMonth m=inv.getArgument(1);return new DueSummary(m,m.equals(MONTH)?800000:0,0,0,m.equals(MONTH)?800000:0,0,m.atDay(25),m.equals(MONTH)?1:0,0,0,0,0);});
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(inv->{YearMonth m=inv.getArgument(1);long extra=m.equals(MONTH)?usedExtra.get():0;
            return new PaymentSummary(m,0,extra,0,0,m.equals(MONTH)?extra:pastCash.get(),0,extra>0?1:0,true,0,0,extra>0?Map.of(101L,extra):Map.of());});
        when(loans.savingSummary(anyLong(),any())).thenAnswer(inv->new SavingSummary(inv.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(loans.terms(anyLong(),anyLong(),any())).thenAnswer(inv->{long u=inv.getArgument(0),id=inv.getArgument(1);
            if(u!=owner.get()||id!=101)throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");return loan(u);});
        when(plans.plannedAllocations(anyLong(),any())).thenReturn(List.of());
        when(plans.overallState(anyLong())).thenReturn(new OverallState(true,"RATE_RISK","MULTI",Instant.parse("2026-11-17T00:00:00Z")));
        PlanView view=mapper.convertValue(Map.of("loan_id",101,"mode","EXTRA_AMOUNT","priority",1,"observed_month_count",0,"warnings",List.of()),PlanView.class);
        when(presentation.monthViews(anyLong(),any(),any(),anyList())).thenReturn(List.of(view));
    }
    LoanTerms loan(long id){return new LoanTerms(101,id,"가상 대출",LoanType.CREDIT,12000000,12000000,LocalDate.of(2026,11,17),
        new BigDecimal("6"),RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null,null,LocalDate.of(2026,11,25),25,LocalDate.of(2027,11,25),
        null,null,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,null,null,null,0,0,LoanStatus.ACTIVE,null);}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();}
    HttpResponse<String> call(HttpClient client,String method,String path,String body,String key)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(20)).header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)b.header("Idempotency-Key",key);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long initialize(HttpClient client)throws Exception{
        long id=json(call(client,"GET","/api/users/me/",null,null)).path("data").path("user_id").longValue();
        String profile="""
            {"monthly_income":3000000,"income_type":"REGULAR","payday":25,"fixed_essential_expenses":800000,
            "variable_essential_expenses":200000,"discretionary_expenses":500000,"savings":200000}
            """;
        assertThat(call(client,"PUT","/api/budgets/profile/",profile,null).statusCode()).isEqualTo(200);return id;
    }
    String expenses(String entries){return "{\"fixed_essential_expenses\":800000,\"variable_essential_expenses\":200000,\"discretionary_expenses\":500000,\"savings\":200000"+(entries==null?"":",\"income_entries\":"+entries)+"}";}
    String income(long amount){return "{\"entry_type\":\"EXTRA\",\"received_date\":\"2026-11-16\",\"amount\":"+amount+"}";}
    String allocation(long amount,long reserve){return "{\"allocations\":[{\"loan_id\":101,\"allocation_type\":\"EXTRA_PAYMENT\",\"amount\":"+amount+"}],\"reserve_amount\":"+reserve+"}";}

    @Test void monthlyForecastKeepsPlannedAndActualMoneyDistinctAndPreviewDoesNotWrite()throws Exception{
        var client=browser();long id=initialize(client);
        var response=call(client,"GET","/api/budgets/2026-11/",null,null);
        assertThat(response.statusCode()).isEqualTo(200);var data=json(response).path("data");
        assertThat(data.path("available_cash").longValue()).isEqualTo(500000);
        assertThat(data.path("planning_monthly_income").longValue()).isEqualTo(3000000);
        assertThat(data.path("actual_income").isNull()).isTrue();assertThat(data.path("actual_available_cash").isNull()).isTrue();
        assertThat(data.path("income_status").asText()).isEqualTo("FORECAST");assertThat(data.has("metrics")).isFalse();
        long revision=revisions.current(id);long count=jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id=?",Long.class,id);
        assertThat(budgetQueries.capacity(id,MONTH).allocationCapacity()).isEqualTo(500000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id=?",Long.class,id)).isEqualTo(count);
        assertThat(revisions.current(id)).isEqualTo(revision);
    }

    @Test void onlyCurrentMonthCanBeEditedAndConfirmedZeroIsKept()throws Exception{
        var client=browser();initialize(client);
        var saved=call(client,"PUT","/api/budgets/2026-11/",expenses(null),null);
        assertThat(saved.statusCode()).isEqualTo(200);assertThat(json(saved).path("data").path("source").asText()).isEqualTo("INPUT");
        assertThat(json(saved).path("data").path("actual_discretionary_expenses").longValue()).isEqualTo(500000);
        assertThat(json(saved).path("data").path("discretionary_savings_amount").longValue()).isZero();
        for(String month:List.of("2026-10","2026-12"))assertThat(json(call(client,"PUT","/api/budgets/"+month+"/",expenses(null),null)).path("code").asText()).isEqualTo("BUDGET_VALIDATION_ERROR");
        assertThat(json(call(client,"GET","/api/budgets/2026-1/",null,null)).path("code").asText()).isEqualTo("INVALID_MONTH");
    }

    @Test void incomeRetriesDoNotDuplicateButDifferentKeysAllowSameRealTransactionAmounts()throws Exception{
        var client=browser();long id=initialize(client);
        assertThat(call(client,"POST","/api/income-entries/",income(200000),null).statusCode()).isEqualTo(400);
        var first=call(client,"POST","/api/income-entries/",income(200000),"income-1");
        assertThat(first.statusCode()).isEqualTo(201);long revision=revisions.current(id);
        var repeat=call(client,"POST","/api/income-entries/",income(200000),"income-1");assertThat(json(repeat)).isEqualTo(json(first));
        assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(json(first).path("data").path("monthly_income").longValue()).isEqualTo(3200000);
        assertThat(json(first).path("data").path("available_cash").longValue()).isEqualTo(700000);
        assertThat(json(first).path("data").path("actual_income").longValue()).isEqualTo(200000);
        assertThat(json(call(client,"POST","/api/income-entries/",income(200001),"income-1")).path("code").asText()).isEqualTo("INCOME_ENTRY_CONFLICT");
        assertThat(call(client,"POST","/api/income-entries/",income(200000),"income-2").statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM income_entries WHERE user_id=?",Long.class,id)).isEqualTo(400000);
    }

    @Test void incomeListOmissionRetainsEntriesAndEmptyArrayDeletesWithoutDoubleCounting()throws Exception{
        var client=browser();long id=initialize(client);
        call(client,"POST","/api/income-entries/",income(200000),"entry");
        assertThat(call(client,"PUT","/api/budgets/2026-11/",expenses(null),null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isEqualTo(1);
        var invalid=call(client,"PUT","/api/budgets/2026-11/",expenses("[{\"income_entry_id\":999999,\"entry_type\":\"EXTRA\",\"received_date\":\"2026-11-16\",\"amount\":1}]"),null);
        assertThat(invalid.statusCode()).isEqualTo(404);assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isEqualTo(1);
        var deleted=call(client,"PUT","/api/budgets/2026-11/",expenses("[]"),null);
        assertThat(deleted.statusCode()).isEqualTo(200);assertThat(json(deleted).path("data").path("monthly_income").longValue()).isEqualTo(3000000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isZero();
    }

    @Test void allocationHonorsCashCapOwnershipSpentAmountsAndDoesNotWriteLongTermPlans()throws Exception{
        var client=browser();long id=initialize(client);owner.set(id);
        var saved=call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(400000,100000),"allocate");
        assertThat(saved.statusCode()).isEqualTo(200);var data=json(saved).path("data");
        assertThat(data.path("allocation").path("unallocated_amount").longValue()).isZero();assertThat(data.path("monthly_override_only").booleanValue()).isTrue();
        assertThat(data.path("strategy_code").isNull()).isTrue();assertThat(data.has("planning_monthly_income")).isFalse();assertThat(data.has("actual_available_cash")).isFalse();
        assertThat(json(call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(400001,100000),"excess")).path("code").asText()).isEqualTo("ALLOCATION_VALIDATION_ERROR");
        usedExtra.set(100000);
        assertThat(call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(99999,0),"spent").statusCode()).isEqualTo(400);
        var other=browser();initialize(other);
        assertThat(json(call(other,"PUT","/api/budgets/2026-11/allocation/",allocation(100000,0),"other")).path("code").asText()).isEqualTo("LOAN_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT amount FROM monthly_allocations a JOIN monthly_budgets b ON a.budget_id=b.id WHERE b.user_id=?",Long.class,id)).isEqualTo(400000);
    }

    @Test void storedProAllocationResponseIsFilteredAfterDowngradeAndDoesNotReapplyMutation()throws Exception{
        var client=browser();long id=initialize(client);owner.set(id);
        call(client,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}",null);
        var pro=call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(100000,0),"permission");
        assertThat(json(pro).path("data").path("strategy_code").asText()).isEqualTo("RATE_RISK");
        call(client,"PATCH","/api/settings/subscription/","{\"plan_type\":\"FREE\"}",null);long revision=revisions.current(id);
        var free=call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(100000,0),"permission");
        assertThat(free.statusCode()).isEqualTo(200);assertThat(json(free).path("data").path("strategy_code").isNull()).isTrue();
        assertThat(json(free).path("data").path("updated_plans").get(0).path("priority").isNull()).isTrue();assertThat(revisions.current(id)).isEqualTo(revision);
    }

    @Test void downstreamPlanReadFailureRollsBackAllocationRevisionAndIdempotencyTogether()throws Exception{
        var client=browser();long id=initialize(client);owner.set(id);long revision=revisions.current(id);
        when(presentation.monthViews(anyLong(),any(),any(),anyList())).thenThrow(new IllegalStateException("fixture failure"));
        assertThat(call(client,"PUT","/api/budgets/2026-11/allocation/",allocation(100000,0),"rollback").statusCode()).isEqualTo(500);
        assertThat(revisions.current(id)).isEqualTo(revision);assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_allocations a JOIN monthly_budgets b ON a.budget_id=b.id WHERE b.user_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,id)).isZero();
    }

    @Test void pastPaymentCorrectionsRecalculateReserveAndOnlyNextMonthCarryWithoutInventingIncome()throws Exception{
        var client=browser();long id=initialize(client);
        jdbc.update("""
            INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,
            discretionary_expenses,discretionary_limit,savings,source,expenses_confirmed,reserve_amount,created_at,updated_at)
            VALUES (?,'2026-10-01','REGULAR',300000,1,0,0,0,0,0,'INPUT',TRUE,100000,now(),now())
            """,id);
        var result=call(client,"GET","/api/budgets/2026-11/",null,null);
        assertThat(json(result).path("data").path("carry_in").longValue()).isEqualTo(200000);
        assertThat(json(result).path("data").path("allocation_capacity").longValue()).isEqualTo(700000);
        pastCash.set(100000);transactions.executeWithoutResult(s->recalculation.recalculateFrom(id,YearMonth.of(2026,10)));
        assertThat(jdbc.queryForObject("SELECT carry_in FROM monthly_budgets WHERE user_id=? AND year_month='2026-11-01'",Long.class,id)).isEqualTo(100000);
        assertThat(budgetQueries.capacity(id,MONTH).allocationCapacity()).isEqualTo(600000);
        jdbc.update("UPDATE monthly_budgets SET expenses_confirmed=FALSE WHERE user_id=? AND year_month='2026-10-01'",id);
        transactions.executeWithoutResult(s->recalculation.recalculateFrom(id,YearMonth.of(2026,10)));
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM monthly_budgets WHERE user_id=? AND year_month='2026-10-01'",String.class,id)).isEqualTo("INSUFFICIENT");
        assertThat(budgetQueries.capacity(id,MONTH).carryIn()).isZero();
    }

    @Test void productCatalogDistinguishesUninitializedFromEmptyAndPreservesLastCacheOnFailure()throws Exception{
        var client=browser();
        assertThat(call(client,"GET","/api/loan-products/options/?loan_type=CREDIT",null,null).statusCode()).isEqualTo(503);
        assertThat(call(client,"GET","/api/loan-products/options/?loan_type=AUTO",null,null).statusCode()).isEqualTo(200);
        assertThat(call(client,"GET","/api/loan-products/options/?loan_form=LIMIT",null,null).statusCode()).isEqualTo(400);
        catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-CREDIT","가상 신용 상품",LoanType.CREDIT,List.of(),"https://example.invalid/fixture"),
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-JEONSE","가상 전세 상품",LoanType.JEONSE,List.of(RepaymentMethod.BULLET),"https://example.invalid/fixture"))));
        var options=json(call(client,"GET","/api/loan-products/options/?loan_type=CREDIT",null,null)).path("data");
        long institution=options.path("institutions").get(0).path("institution_id").longValue();
        assertThat(options.path("loan_types").size()).isEqualTo(6);
        var products=json(call(client,"GET","/api/loan-products/?institution_id="+institution+"&loan_type=CREDIT",null,null)).path("data").path("products");
        assertThat(products.size()).isEqualTo(1);assertThat(products.get(0).path("repayment_methods").isEmpty()).isTrue();
        long productId=products.get(0).path("product_id").longValue();assertThat(productQueries.findEligibleProduct(productId).name()).isEqualTo("가상 신용 상품");
        assertThat(call(client,"GET","/api/loan-products/?institution_id=999999",null,null).statusCode()).isEqualTo(404);
        assertThatThrownBy(()->catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","BAD","마이너스한도대출",LoanType.CREDIT,List.of(),"https://example.invalid/fixture")))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(productQueries.findEligibleProduct(productId).name()).isEqualTo("가상 신용 상품");
    }
}
