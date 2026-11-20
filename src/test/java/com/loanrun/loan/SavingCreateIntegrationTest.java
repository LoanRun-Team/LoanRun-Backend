package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.service.SavingListService;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(SavingCreateIntegrationTest.Config.class)
class SavingCreateIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock savingClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets; // Registration fixture only; the GET needs no recalculation.
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanSourceRepository sources;
    @Autowired TestClock clock;
    @LocalServerPort int port;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");org.mockito.Mockito.reset(budgets);}
    void day(int day){clock.value=LocalDate.of(2026,3,day).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    String path(long id){return "/api/repayment-savings/?loan_id="+id;}
    ObjectNode input()throws Exception {var body=new LoanPreviewTest().body();body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);return body;}
    long loan(HttpClient b,ObjectNode input)throws Exception {
        var r=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return json(r).path("data").path("loan_id").longValue();
    }
    JsonNode list(HttpClient b,long id,String filters)throws Exception {var r=get(b,path(id)+filters);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json(r).path("data");}
    long saving(long user,long loan,String date,long amount){return jdbc.queryForObject("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,?) RETURNING id",Long.class,user,loan,LocalDate.parse(date),amount,Timestamp.from(clock.instant()));}
    long payment(long user,long loan,String date,long used,long interest){return jdbc.queryForObject("""
        INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
        VALUES (?,?,?,'REGULAR',?,?,0,?,0,?,?) RETURNING id
        """,Long.class,user,loan,LocalDate.parse(date),used+interest,used,interest,used,Timestamp.from(clock.instant()));}

    ObjectNode request(long id,long amount){var body=mapper.createObjectNode();body.put("loan_id",id);body.put("saved_at","2026-03-01");body.put("amount",amount);return body;}
    HttpResponse<String> post(HttpClient browser,String body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"))
            .header("Content-Type","application/json").header("Origin","http://localhost:5173");
        if(key!=null)r.header("Idempotency-Key",key);
        return browser.send(r.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    long count(String table,long user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user);}
    long revision(long user){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user);}
    String key(){return UUID.randomUUID().toString();}

    @Test void historicalSavingRecordsActualMonthWithoutIncreasingCurrentAssets()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());
        var old=request(id,100000);old.put("saved_at","2026-02-25");
        var r=post(b,old.toString(),key());assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).path("data").path("savings_balance").longValue()).isEqualTo(2000000);
        assertThat(json(r).path("data").path("saving").path("editable").booleanValue()).isFalse();
        var current=request(id,50000);var newer=post(b,current.toString(),key());assertThat(newer.statusCode()).as(newer.body()).isEqualTo(201);
        assertThat(json(newer).path("data").path("savings_balance").longValue()).isEqualTo(2050000);
        assertThat(list(b,id,"&from=2026-02-01&to=2026-02-28").path("results").size()).isEqualTo(1);
    }

    @Test void createsRealSavingAndReusesListProjectionWithoutChangingPrincipalOrDues()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long revision=revision(user);
        var r=post(b,request(id,100000).toString(),key());assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_CREATE_SUCCESS");assertThat(json(r).path("message").asText()).isEqualTo("상환자금 적립 기록 성공");
        var d=json(r).path("data");assertThat(d.path("savings_balance").longValue()).isEqualTo(2100000);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(100000);assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(990000);
        assertThat(d.path("planned_saving_amount").longValue()).isEqualTo(1000000);assertThat(d.path("remaining_principal").longValue()).isEqualTo(12000000);
        assertThat(d.path("saving_progress_pct").decimalValue()).isEqualByComparingTo("17.50");assertThat(d.path("report_stale").booleanValue()).isTrue();
        assertThat(d.path("saving").path("editable").booleanValue()).isTrue();assertThat(d.path("saving").path("created_at").asText()).isEqualTo("2026-03-01T00:30:00+09:00");
        var after=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(after.loans()).isEqualTo(before.loans());assertThat(after.dues()).isEqualTo(before.dues());
        assertThat(after.payments()).isEqualTo(before.payments());assertThat(after.balances()).isEqualTo(before.balances());assertThat(after.rates()).isEqualTo(before.rates());
        assertThat(after.savings()).hasSize(1);assertThat(revision(user)).isEqualTo(revision+1);assertThat(count("idempotency_records",user)).isEqualTo(1);
        verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
        var read=list(b,id,"");assertThat(read.path("results").get(0)).isEqualTo(d.path("saving"));
        assertThat(read.path("savings_balance")).isEqualTo(d.path("savings_balance"));
    }
    @Test void canonicalReplayWinsAfterLoanClosureSnapshotAndDateChanges()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);String key=key();var body=request(id,100);
        var first=post(b,body.toString(),key);assertThat(first.statusCode()).isEqualTo(201);long rev=revision(user);
        jdbc.update("UPDATE loans SET status='DELETED',snapshot_date='2026-03-10' WHERE id=?",id);day(20);
        var replay=post(b,"{\"amount\":100,\"saved_at\":\"2026-03-01\",\"loan_id\":"+id+"}",key);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(json(replay)).isEqualTo(json(first));assertThat(revision(user)).isEqualTo(rev);
        assertThat(count("repayment_savings",user)).isEqualTo(1);verify(budgets,times(1)).recalculateFrom(eq(user),any());
    }
    @Test void changedBodyWithSameKeyConflictsButSeparateKeysAllowIdenticalAmounts()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());String key=key();assertThat(post(b,request(id,100).toString(),key).statusCode()).isEqualTo(201);
        var r=post(b,request(id,101).toString(),key);assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_CONFLICT");
        assertThat(json(r).path("message").asText()).isEqualTo("적립 잔액 또는 이미 사용된 금액과 충돌합니다.");
        assertThat(post(b,request(id,100).toString(),key()).statusCode()).isEqualTo(201);assertThat(count("repayment_savings",user)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":0}","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":-1}","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":1.0}","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":\"1\"}","{\"loan_id\":1,\"saved_at\":\"2026-02-30\",\"amount\":1}","{\"loan_id\":1,\"saved_at\":\"2026-3-01\",\"amount\":1}","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":9223372036854775808}","{\"loan_id\":1,\"saved_at\":\"2026-03-01\",\"amount\":1,\"user_id\":2}"})
    void invalidBodyUsesSavingErrorAndStoresNothing(String body)throws Exception {
        var b=browser();long user=owner(b);var r=post(b,body,key());assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");assertThat(count("idempotency_records",user)).isZero();
        assertThat(count("repayment_savings",user)).isZero();assertThat(revision(user)).isZero();
    }
    @Test void keyIsRequiredAndBounded()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());
        for(String key:Arrays.asList(null,"","x".repeat(129))) {
            var r=post(b,request(id,1).toString(),key);assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");
        }
        assertThat(post(b,request(id,1).toString(),"x".repeat(128)).statusCode()).isEqualTo(201);
    }
    @Test void ownershipFailureIsIndistinguishableFromMissingLoan()throws Exception {
        var a=browser();var b=browser();long id=loan(a,input());var foreign=post(b,request(id,1).toString(),key());
        var missing=post(b,request(Long.MAX_VALUE,1).toString(),key());assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(json(foreign)).isEqualTo(json(missing));assertThat(json(foreign).path("code").asText()).isEqualTo("SAVING_NOT_FOUND");
    }
    @Test void onlyActiveBulletAndMutableDatesAreAllowed()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),installment=loan(b,new LoanPreviewTest().body());
        assertThat(post(b,request(installment,1).toString(),key()).statusCode()).isEqualTo(400);
        var future=request(id,1);future.put("saved_at","2026-03-02");assertThat(post(b,future.toString(),key()).statusCode()).isEqualTo(400);
        day(20);jdbc.update("UPDATE loans SET snapshot_date='2026-03-05' WHERE id=?",id);
        var before=request(id,1);before.put("saved_at","2026-03-04");assertThat(post(b,before.toString(),key()).statusCode()).isEqualTo(400);
        before.put("saved_at","2026-03-05");assertThat(post(b,before.toString(),key()).statusCode()).isEqualTo(201);
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE id=?",status,id);before.put("saved_at","2026-03-20");
            assertThat(post(b,before.toString(),key()).statusCode()).isEqualTo(400);
        }
    }
    @Test void backdatedSavingRecalculatesItsMonthButSummaryStaysCurrentMonth()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        var r=post(b,request(id,100).toString(),key());assertThat(r.statusCode()).isEqualTo(201);
        var d=json(r).path("data");assertThat(d.path("actual_saving_amount").longValue()).isZero();assertThat(d.path("savings_balance").longValue()).isEqualTo(2000100);
        verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
    }
    @Test void recalculationFailureRollsBackSavingRevisionAndKeyAndRetryCanSucceed()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());long rev=revision(user);String key=key();
        doAnswer(invocation->{assertThat(count("repayment_savings",user)).isEqualTo(1);throw new IllegalStateException("test rollback");})
            .when(budgets).recalculateFrom(eq(user),any());
        assertThat(post(b,request(id,100).toString(),key).statusCode()).isEqualTo(500);
        assertThat(count("repayment_savings",user)).isZero();assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
        doNothing().when(budgets).recalculateFrom(eq(user),any());assertThat(post(b,request(id,100).toString(),key).statusCode()).isEqualTo(201);
    }
    @Test void responseProjectionFailureAfterRevisionBumpStillRollsBackEverything()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());long rev=revision(user);
        doAnswer(invocation->{jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",id);return null;}).when(budgets).recalculateFrom(eq(user),any());
        assertThat(post(b,request(id,100).toString(),key()).statusCode()).isEqualTo(503);
        assertThat(count("repayment_savings",user)).isZero();assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
        assertThat(sources.loan(user,id).initialPlan().version()).isEqualTo(1);
    }
    @Test void concurrentIdenticalKeyCommitsOneSavingAndOneRevision()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);long rev=revision(user);String key=key();String body=request(id,100).toString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> task=()->{start.await();return post(b,body,key);};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=c.get(15,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(201);assertThat(json(second)).isEqualTo(json(first));
        }
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(revision(user)).isEqualTo(rev+1);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void concurrentDistinctKeysRetainBothGenuineRecords()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());long rev=revision(user);String body=request(id,100).toString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> task=()->{start.await();return post(b,body,key()).statusCode();};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(201);assertThat(c.get(15,TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(count("repayment_savings",user)).isEqualTo(2);assertThat(revision(user)).isEqualTo(rev+2);
        assertThat(list(b,id,"").path("savings_balance").longValue()).isEqualTo(2000200);
    }
    @Test void overflowIncludingAnEarlierPeakReturnsConflictAndRollsBack()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());day(20);long rev=revision(user);
        saving(user,id,"2026-03-02",Long.MAX_VALUE-2000000);payment(user,id,"2026-03-03",100,0);
        var r=post(b,request(id,1).toString(),key());assertThat(r.statusCode()).isEqualTo(409);
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
    }
}
