package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.contract.PlanQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.idempotency.IdempotencyService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"loanrun.demo-mode=true", "server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
class BootstrapIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired IdempotencyService idempotency;
    @Autowired RevisionPort revisions;
    @Autowired TransactionTemplate transactions;
    @MockitoBean LoanQueryPort loans;
    @MockitoBean PlanQueryPort plans;

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
        @Bean ProbeController probe(CurrentUserProvider currentUser) { return new ProbeController(currentUser); }
    }
    @RestController
    static class ProbeController {
        private final CurrentUserProvider currentUser;
        ProbeController(CurrentUserProvider currentUser) { this.currentUser = currentUser; }
        @GetMapping("/api/test/pro/") String pro() { currentUser.requirePro(); return "ok"; }
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (origin != null) builder.header("Origin", origin);
        builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private long userId(HttpClient browser) throws Exception {
        return json(request(browser, "GET", "/api/users/me/", null, null)).path("data").path("user_id").longValue();
    }

    @Test
    void persistentAnonymousCookiesIsolateProfilesAndExpireToNewUsers() throws Exception {
        HttpClient first = browser(), second = browser();
        var initial = request(first, "GET", "/api/users/me/", null, null);
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax", "Max-Age=604800");
        var profile = json(initial).path("data");
        long id = profile.path("user_id").longValue();
        assertThat(profile.path("email").asText()).isEqualTo("demo-" + id + "@example.invalid");
        assertThat(profile.path("plan_type").asText()).isEqualTo("FREE");
        assertThat(profile.path("onboarding_completed").booleanValue()).isFalse();
        assertThat(profile.path("income_type").isNull()).isTrue();
        assertThat(userId(first)).isEqualTo(id);
        assertThat(userId(second)).isNotEqualTo(id);
        var patch = request(first, "PATCH", "/api/users/me/", "{\"name\":\"테스트\",\"occupation\":\"개발자\",\"income_type\":\"REGULAR\"}", "http://localhost:5173");
        assertThat(json(patch).path("code").asText()).isEqualTo("USER_UPDATE_SUCCESS");
        request(first, "PATCH", "/api/users/me/", "{\"occupation\":null}", "http://localhost:5173");
        var updated = json(request(first, "GET", "/api/users/me/", null, null)).path("data");
        assertThat(updated.path("occupation").isNull()).isTrue();
        assertThat(updated.path("name").asText()).isEqualTo("테스트");
        assertThat(json(request(second, "GET", "/api/users/me/", null, null)).path("data").path("name").asText()).isEqualTo("사용자");
        jdbc.update("UPDATE spring_session SET last_access_time=0, expiry_time=0");
        assertThat(userId(first)).isNotEqualTo(id);
    }

    @Test
    void profileRejectsNullsUnknownFieldsAndOversizedNamesWithoutChangingState() throws Exception {
        var client = browser();
        userId(client);
        for (String body : new String[]{"{\"name\":null}", "{\"name\":\"\"}", "{\"name\":123}", "{\"income_type\":null}",
                "{\"user_id\":1}", "{\"name\":\"" + "가".repeat(51) + "\"}", "{"}) {
            var response = request(client, "PATCH", "/api/users/me/", body, "http://localhost:5173");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(response).has("data")).isFalse();
        }
        assertThat(json(request(client, "GET", "/api/users/me/", null, null)).path("data").path("name").asText()).isEqualTo("사용자");
    }

    @Test
    void subscriptionTransitionsAreIdempotentAndProIsReadFromServer() throws Exception {
        var client = browser();
        long id = userId(client);
        long initialRevision = revisions.current(id);
        assertThat(request(client, "GET", "/api/test/pro/", null, null).statusCode()).isEqualTo(403);
        var pro = request(client, "PATCH", "/api/settings/subscription/", "{\"plan_type\":\"PRO\"}", "http://localhost:5173");
        assertThat(pro.statusCode()).isEqualTo(200);
        assertThat(json(pro).path("data").path("billing_enabled").booleanValue()).isFalse();
        assertThat(revisions.current(id)).isEqualTo(initialRevision + 1);
        request(client, "PATCH", "/api/settings/subscription/", "{\"plan_type\":\"PRO\"}", "http://localhost:5173");
        assertThat(revisions.current(id)).isEqualTo(initialRevision + 1);
        assertThat(request(client, "GET", "/api/test/pro/", null, null).statusCode()).isEqualTo(200);
        assertThat(json(request(browser(), "GET", "/api/settings/subscription/", null, null)).path("data").path("plan_type").asText()).isEqualTo("FREE");
        request(client, "PATCH", "/api/settings/subscription/", "{\"plan_type\":\"FREE\"}", "http://localhost:5173");
        assertThat(request(client, "GET", "/api/test/pro/", null, null).statusCode()).isEqualTo(403);
        assertThat(request(client, "PATCH", "/api/settings/subscription/", "{\"plan_type\":\"ADMIN\"}", "http://localhost:5173").statusCode()).isEqualTo(400);
    }

    @Test
    void notificationPatchRetainsOmittedValuesAndRejectsBooleanCoercion() throws Exception {
        var client = browser();
        var defaults = json(request(client, "GET", "/api/settings/notifications/", null, null)).path("data");
        assertThat(defaults.path("payment_due").booleanValue()).isTrue();
        var response = request(client, "PATCH", "/api/settings/notifications/", "{\"grace_end\":false}", "http://localhost:5173");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").path("grace_end").booleanValue()).isFalse();
        assertThat(json(response).path("data").path("report_update").booleanValue()).isTrue();
        for (String body : new String[]{"{\"grace_end\":null}", "{\"grace_end\":\"false\"}", "{\"grace_end\":0}"})
            assertThat(request(client, "PATCH", "/api/settings/notifications/", body, "http://localhost:5173").statusCode()).isEqualTo(400);
        assertThat(json(request(browser(), "GET", "/api/settings/notifications/", null, null)).path("data").path("grace_end").booleanValue()).isTrue();
    }

    @Test
    void originChecksAndExactMappingsReturnErrorsWithoutLoginRedirect() throws Exception {
        var client = browser();
        for (String origin : new String[]{null, "https://attacker.invalid", "http://localhost:5173.evil.invalid"}) {
            var response = request(client, "PATCH", "/api/users/me/", "{\"name\":\"x\"}", origin);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json(response).path("code").asText()).isEqualTo("ORIGIN_NOT_ALLOWED");
        }
        assertThat(request(client, "GET", "/api/users/me", null, null).statusCode()).isEqualTo(404);
        assertThat(json(request(client, "GET", "/api/users/me/", null, "https://attacker.invalid")).path("code").asText()).isEqualTo("ORIGIN_NOT_ALLOWED");
        assertThat(json(request(client, "POST", "/api/users/me/", "{}", "http://localhost:5173")).path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(request(client, "POST", "/api/auth/login/", "{}", "http://localhost:5173").statusCode()).isEqualTo(404);
        assertThat(request(client, "DELETE", "/api/users/me/", null, "http://localhost:5173").statusCode()).isEqualTo(405);
        assertThat(json(request(client, "GET", "/actuator/health", null, null)).path("status").asText()).isEqualTo("UP");
        assertThat(request(client, "PATCH", "/api/users/me/", "{\"name\":\"" + "x".repeat(1024 * 1024) + "\"}", "http://localhost:5173").statusCode()).isEqualTo(400);
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void idempotencySerializesConcurrentMutationsAndRollsBackFailedMutations() throws Exception {
        long id = userId(browser());
        AtomicInteger calls = new AtomicInteger();
        var body = mapper.readTree("{\"amount\":100,\"type\":\"EXTRA\"}");
        Callable<IdempotencyService.StoredResponse> operation = () -> transactions.execute(status -> idempotency.execute(
            id, "POST", "/test-only", "key", body, "IDEMPOTENCY_CONFLICT", () -> {
                calls.incrementAndGet(); revisions.bump(id, "TEST");
                return new IdempotencyService.StoredResponse(201, mapper.valueToTree(java.util.Map.of("amount", 100)));
            }));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(operation); var second = executor.submit(operation);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(calls).hasValue(1);
        assertThat(revisions.current(id)).isEqualTo(1);
        var changed = mapper.readTree("{\"amount\":101}");
        assertThatThrownBy(() -> transactions.execute(status -> idempotency.execute(id, "POST", "/test-only", "key", changed,
            "IDEMPOTENCY_CONFLICT", () -> { throw new AssertionError(); }))).hasMessageContaining("같은 요청 키");
        assertThatThrownBy(() -> transactions.execute(status -> idempotency.execute(id, "POST", "/test-only", "rollback", body,
            "IDEMPOTENCY_CONFLICT", () -> { revisions.bump(id, "TEST"); throw new IllegalStateException("rollback"); })));
        assertThat(revisions.current(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?", Long.class, id)).isEqualTo(1);
    }

    private String regularFinance() {
        return """
            {"monthly_income":3000000,"income_type":"REGULAR","payday":25,"fixed_essential_expenses":800000,
            "variable_essential_expenses":200000,"discretionary_expenses":500000,"savings":200000,"emergency_fund":null}
            """;
    }

    @Test
    void financeProfilePersistsZeroAndNullAndKeepsEachUsersBudgetSeparate() throws Exception {
        var client = browser();
        long id = userId(client);
        assertThat(request(client, "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        var saved = request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        assertThat(saved.statusCode()).isEqualTo(200);
        var profile = json(saved).path("data");
        assertThat(profile.path("essential_expenses").longValue()).isEqualTo(1000000);
        assertThat(profile.path("emergency_fund").isNull()).isTrue();
        assertThat(json(request(client, "GET", "/api/users/me/", null, null)).path("data").path("income_type").asText()).isEqualTo("REGULAR");
        assertThat(request(browser(), "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        var changed = regularFinance().replace("500000", "700000").replace("\"emergency_fund\":null", "\"emergency_fund\":0");
        assertThat(request(client, "PUT", "/api/budgets/profile/", changed, "http://localhost:5173").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT discretionary_limit FROM monthly_budgets WHERE user_id=?", Long.class, id)).isEqualTo(500000);
        assertThat(jdbc.queryForObject("SELECT discretionary_expenses FROM monthly_budgets WHERE user_id=?", Long.class, id)).isEqualTo(700000);
        assertThat(json(request(client, "GET", "/api/budgets/profile/", null, null)).path("data").path("emergency_fund").longValue()).isZero();
        assertThat(request(client, "PATCH", "/api/users/me/", "{\"income_type\":\"IRREGULAR\"}", "http://localhost:5173").statusCode()).isEqualTo(400);
    }

    @Test
    void financeValidationRejectsTypeCoercionNegativeAmountsAndInvalidPaydaysAtomically() throws Exception {
        var client = browser();
        for (String body : new String[]{regularFinance().replace("3000000", "\"3000000\""), regularFinance().replace("3000000", "3.1"),
                regularFinance().replace("3000000", "-1"), regularFinance().replace("\"payday\":25", "\"payday\":null"),
                regularFinance().replace("\"payday\":25", "\"payday\":32"), regularFinance().replace("REGULAR", "IRREGULAR")}) {
            var response = request(client, "PUT", "/api/budgets/profile/", body, "http://localhost:5173");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("BUDGET_VALIDATION_ERROR");
        }
        assertThat(request(client, "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        String irregular = regularFinance().replace("REGULAR", "IRREGULAR").replace("\"payday\":25", "\"payday\":null").replace("3000000", "0");
        assertThat(request(client, "PUT", "/api/budgets/profile/", irregular, "http://localhost:5173").statusCode()).isEqualTo(200);
        assertThat(json(request(client, "GET", "/api/budgets/profile/", null, null)).path("data").path("payday").isNull()).isTrue();
    }

    @Test
    void onboardingRequiresOwnedActiveLoansFinanceAndEverySavedGoalAndCompletesOnlyOnce() throws Exception {
        var client = browser();
        long id = userId(client);
        when(loans.activeLoans(eq(id), any())).thenReturn(List.of());
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("LOAN");
        var incomplete = request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(incomplete.statusCode()).isEqualTo(400);
        assertThat(json(incomplete).path("code").asText()).isEqualTo("ONBOARDING_INCOMPLETE");
        var loan = new LoanTerms(101, id, "가상 대출", LoanType.CREDIT, 12000000, 12000000, LocalDate.of(2026,11,17),
            new BigDecimal("6.0"), RateType.FIXED, RepaymentMethod.EQUAL_PAYMENT, null, null, LocalDate.of(2026,11,25), 25,
            LocalDate.of(2027,11,25), null, null, null, PrepaymentPolicy.KEEP_PAYMENT, FeeMethod.NONE, null, null, null, 0, 0, LoanStatus.ACTIVE, null);
        when(loans.activeLoans(eq(id), any())).thenReturn(List.of(loan));
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("FINANCE");
        request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("REPAYMENT_GOAL");
        when(plans.currentPlans(eq(id), any())).thenReturn(List.of(new PlanSnapshot(101, true, loan.maturityDate(), loan.maturityDate(), 0, 0, 0, "MATURITY", null, LocalDate.of(2026,11,25), 1)));
        long revision = revisions.current(id);
        var completed = request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(json(completed).path("data").path("dashboard_ready").booleanValue()).isTrue();
        request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(revisions.current(id)).isEqualTo(revision + 1);
        assertThat(json(request(browser(), "GET", "/api/onboarding/", null, null)).path("data").path("registered_loan_ids").isEmpty()).isTrue();
    }

    @Test
    void exportUsesOwnedProjectionsAndProtectsCsvCellsWhilePreservingFeesAndSavingsUse() throws Exception {
        var client = browser();
        long id = userId(client);
        when(loans.exportLoans(id)).thenReturn(List.of(new ExportLoan(101, " =SUM(1,2)", 12000000)));
        when(loans.exportPayments(eq(id), any())).thenReturn(List.of(new ExportPayment(101, "가상 \"대출\"", LocalDate.of(2026,11,16), 10000, 7000, 1000, 1000, 1000, 500)));
        var response = request(client, "GET", "/api/settings/export/?start_month=2026-11&end_month=2026-11", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("ronrun-export.csv");
        assertThat(response.body()).startsWith("\uFEFFrecord_type,").contains("\"' =SUM(1,2)\"", "fee_amount,savings_used_amount", "\"가상 \"\"대출\"\"\"");
        verify(loans).exportPayments(id, new DateRange(LocalDate.of(2026,11,1), LocalDate.of(2026,11,30)));
        assertThat(request(client, "GET", "/api/settings/export/?format=xlsx", null, null).statusCode()).isEqualTo(400);
        assertThat(json(request(client, "GET", "/api/settings/export/?start_month=2026-12&end_month=2026-11", null, null)).path("code").asText()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(request(client, "GET", "/api/settings/export/?start_month=2026-1", null, null).statusCode()).isEqualTo(400);
        var other = request(browser(), "GET", "/api/settings/export/", null, null);
        assertThat(other.body()).doesNotContain("SUM", "가상");
    }
}
