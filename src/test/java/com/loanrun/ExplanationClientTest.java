package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.report.client.ExplanationClient;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExplanationClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ExplanationClient.Facts facts=new ExplanationClient.Facts("STABLE","BALANCED",0,null,new BigDecimal("25"));
    @Test void disabledClientDoesNotCallARealOrMockEndpoint(){
        var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
        assertThat(new ExplanationClient(builder.build(),mapper,false,"test-token","configured-fixture-model").explain(facts).source()).isEqualTo("FALLBACK");server.verify();
    }
    @Test void structuredOutputAcceptsOnlyExplanationStringsAndSendsNoIdentityFields()throws Exception{
        var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.com/v1/responses")).andExpect(jsonPath("$.store").value(false)).andExpect(jsonPath("$.text.format.strict").value(true))
            .andExpect(jsonPath("$.reasoning").doesNotExist())
            .andExpect(jsonPath("$.text.format.type").value("json_schema")).andExpect(header("Authorization","Bearer test-token"))
            .andExpect(request->{String json=((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();assertThat(json).doesNotContain("user_id","email","institution_id","product_name");})
            .andRespond(withSuccess(response("현재 입력한 계획을 확인해 주세요.","실제 기록을 확인해 주세요."),MediaType.APPLICATION_JSON));
        var result=new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(facts);
        assertThat(result.source()).isEqualTo("MODEL");assertThat(result.summary()).isEqualTo("현재 입력한 계획을 확인해 주세요.");server.verify();
    }
    @Test void lunaUsesNoReasoningWithinTheExistingOutputBudgetAndStrictExplanationSchema()throws Exception{
        var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(jsonPath("$.model").value("gpt-6-luna")).andExpect(jsonPath("$.reasoning.effort").value("none"))
            .andExpect(jsonPath("$.max_output_tokens").value(500)).andExpect(jsonPath("$.store").value(false))
            .andExpect(jsonPath("$.text.format.type").value("json_schema")).andExpect(jsonPath("$.text.format.strict").value(true))
            .andRespond(withSuccess(response("현재 입력한 계획을 확인해 주세요.","실제 기록을 확인해 주세요."),MediaType.APPLICATION_JSON));
        assertThat(new ExplanationClient(builder.build(),mapper,true,"test-token","gpt-6-luna").explain(facts).source()).isEqualTo("MODEL");server.verify();
    }
    @Test void newNumbersOrFailedResponsesUseTheDeterministicFallback()throws Exception{
        for(String response: new String[]{response("100만원을 갚으세요.","확인해 주세요."),"{\"status\":\"incomplete\",\"output\":[]}","{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}"}){
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertThat(new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(facts).source()).isEqualTo("FALLBACK");server.verify();
        }
    }
    @Test void missingConfigurationOrUnexpectedAggregateStringsCannotTriggerAnExternalRequest(){
        for(String[] config:new String[][]{{"","configured-fixture-model"},{"test-token",""}}){
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
            assertThat(new ExplanationClient(builder.build(),mapper,true,config[0],config[1]).explain(facts).source()).isEqualTo("FALLBACK");server.verify();
        }
        for(var invalid:java.util.List.of(new ExplanationClient.Facts("fixture@example.invalid","BALANCED",0,null,null),
                new ExplanationClient.Facts("STABLE","unexpected product",0,null,null),new ExplanationClient.Facts("STABLE","NONE",7,null,null))){
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
            assertThat(new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(invalid).source()).isEqualTo("FALLBACK");server.verify();
        }
    }
    @Test void unsafeTextAndUnexpectedJsonShapesUseFallback()throws Exception{
        for(String text:java.util.List.of("٥퍼센트 줄어듭니다.","한 달이면 완납합니다.","백만원을 갚으세요.","FAST로 전환하세요.",
                "절감을 보장합니다.","<b>상환</b>","확인\u202e하세요.","가".repeat(501))){
            assertFallback(response(text,"실제 기록을 확인해 주세요."));
        }
        for(String text:java.util.List.of("not json",mapper.writeValueAsString(Map.of("summary",12,"action_note","확인해 주세요.")),
                mapper.writeValueAsString(Map.of("summary","확인해 주세요.")),
                mapper.writeValueAsString(Map.of("summary","확인해 주세요.","action_note","기록을 확인해 주세요.","amount",100)))){
            assertFallback(wrap(text));
        }
    }
    @Test void transportAndServiceFailuresDoNotLeakExceptionsOrPreventFallback(){
        for(ResponseCreator reply:java.util.List.<ResponseCreator>of(withServerError(),withException(new java.net.SocketTimeoutException("fixture timeout")))){
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(reply);
            assertThat(new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(facts).source()).isEqualTo("FALLBACK");server.verify();
        }
    }
    @Test void naturalKoreanWordsDoNotFalsePositiveWhileSpelledQuantitiesStillFallback()throws Exception{
        for(String text:java.util.List.of(
                "이번 달 상환 흐름과 현재 조건을 확인해 주세요.",
                "이달 입력한 원금과 상환 일정을 다시 조회해 주세요.",
                "만일 지출이 변동되면 적절한 배분을 유지하고 자금 조달 상태를 점검해 주세요.",
                "지출이 원활하게 관리되고 있으며 이 원금은 계획대로 줄어듭니다.")){
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(response(text,"실제 기록을 확인해 주세요."),MediaType.APPLICATION_JSON));
            var result=new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(facts);
            assertThat(result.source()).as(text).isEqualTo("MODEL");
            assertThat(result.summary()).isEqualTo(text);
            server.verify();
        }
        for(String text:java.util.List.of("두 번 상환하세요.","삼 개월 뒤 완납합니다.","십만원씩 적립하세요.","두배로 늘어납니다.","하루 만에 끝납니다.","한 달만에 완납합니다.","오년 이상 유지하세요.")){
            assertFallback(response(text,"실제 기록을 확인해 주세요."));
        }
    }
    void assertFallback(String payload){
        var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        assertThat(new ExplanationClient(builder.build(),mapper,true,"test-token","configured-fixture-model").explain(facts).source()).isEqualTo("FALLBACK");server.verify();
    }
    String response(String summary,String note)throws Exception{
        String text=mapper.writeValueAsString(Map.of("summary",summary,"action_note",note));
        return wrap(text);
    }
    String wrap(String text)throws Exception{
        return mapper.writeValueAsString(Map.of("status","completed","output",java.util.List.of(Map.of("content",java.util.List.of(Map.of("type","output_text","text",text))))));
    }
}
