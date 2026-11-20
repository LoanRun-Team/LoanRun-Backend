package com.loanrun.report.client;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;

/** Only non-identifying, server-computed aggregates cross this boundary. Model outputs never set numeric fields. */
public class ExplanationClient {
    private static final Set<String> RISK_LEVELS=Set.of("STABLE","CAUTION","DANGER");
    private static final Set<String> STRATEGIES=Set.of("FAST","BALANCED","STABLE","SAVINGS","RATE_RISK","INTEREST_SAVING","PARALLEL","NONE");
    private static final Pattern QUANTITIES=Pattern.compile(
        "\\p{N}|[%％<>]"
        +"|(?<![가-힣])(?!(?:이번|이달|만일|조건|조회|조달|사회|사건|이건)(?:[^가-힣]|$|[은는이가을를에의도로만과와]|으로|부터|까지|해|하|되|적))"
        +"(?:[영공일이삼사오육칠팔구십백천만억조]+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*"
        +"(?:퍼센트|개월|(?:원|달|년|주|일|회|번|배|건)(?=(?:[^가-힣]|$|(?:[은는이가을를에의도로만과와씩간쯤]|으로|에서|에는|에도|보다|만에|부터|까지|이면|이나|라도|정도|이상|이하|초과|미만|동안|내외|가량|여|째|차)(?:[^가-힣]|$))))"
        +"|(?<![가-힣])(?:하루|이틀|사흘)");
    private static final Pattern UNSUPPORTED_CLAIMS=Pattern.compile("FAST|BALANCED|STABLE|SAVINGS|RATE_RISK|INTEREST_SAVING|PARALLEL|보장|무조건|확실|면제",Pattern.CASE_INSENSITIVE);
    public record Facts(String riskLevel,String strategyCode,int observedMonths,BigDecimal principalRate,BigDecimal debtServiceRatio){}
    public record Explanation(String summary,String actionNote,String source){}
    private final RestClient client;private final ObjectMapper mapper;private final boolean enabled;private final String key,model;
    public ExplanationClient(RestClient client,ObjectMapper mapper,boolean enabled,String key,String model){this.client=client;this.mapper=mapper;this.enabled=enabled;this.key=key;this.model=model;}
    private Explanation fallback(){return new Explanation("입력한 정보와 상환 기록을 기준으로 현재 계획을 확인해 주세요.","실제 납부·적립 내역과 재정정보를 확인해 주세요.","FALLBACK");}
    public Explanation explain(Facts facts){
        if(!enabled||key==null||key.isBlank()||model==null||model.isBlank())return fallback();
        if(facts==null||facts.observedMonths()<0||facts.observedMonths()>6
            ||(facts.riskLevel()!=null&&!RISK_LEVELS.contains(facts.riskLevel()))
            ||facts.strategyCode()==null||!STRATEGIES.contains(facts.strategyCode()))return fallback();
        try{
            Map<String,Object> schema=Map.of("type","object","properties",Map.of("summary",Map.of("type","string"),"action_note",Map.of("type","string")),
                "required",List.of("summary","action_note"),"additionalProperties",false);
            Map<String,Object> request=new LinkedHashMap<>(Map.of("model",model,"store",false,"max_output_tokens",500,
                "input",List.of(Map.of("role","system","content","서버가 확정한 집계 결과를 한국어로 짧게 설명합니다. 숫자·금액·날짜·비율·확률과 수량 단위 표현을 쓰거나 계산하지 마세요. 영문 전략 코드와 보장·무조건·확실·면제 표현을 쓰지 마세요. 추천 전략을 변경하지 마세요. 정보가 부족하면 확인을 안내하세요. summary와 action_note 문자열만 반환하세요."),
                    Map.of("role","user","content",mapper.writeValueAsString(facts))),
                "text",Map.of("format",Map.of("type","json_schema","name","repayment_explanation","strict",true,"schema",schema))));
            if("gpt-6-luna".equals(model))request.put("reasoning",Map.of("effort","none"));
            JsonNode response=client.post().uri("/responses").header("Authorization","Bearer "+key).body(request).retrieve().body(JsonNode.class);
            if(response==null||!"completed".equals(response.path("status").asText()))return fallback();
            String text=null;
            for(var output:response.path("output"))for(var content:output.path("content")){
                if("refusal".equals(content.path("type").asText()))return fallback();
                if("output_text".equals(content.path("type").asText())){if(text!=null)return fallback();text=content.path("text").asText();}
            }
            if(text==null||text.length()>4000)return fallback();JsonNode parsed=mapper.readTree(text);
            if(!parsed.isObject()||parsed.size()!=2||!safe(parsed.get("summary"))||!safe(parsed.get("action_note")))return fallback();
            return new Explanation(parsed.get("summary").textValue(),parsed.get("action_note").textValue(),"MODEL");
        }catch(Exception ex){return fallback();} // Never log bearer tokens, aggregate payloads or model text.
    }
    private boolean safe(JsonNode node){
        if(node==null||!node.isTextual())return false;String text=node.textValue();
        return !text.isBlank()&&text.codePointCount(0,text.length())<=500
            &&!QUANTITIES.matcher(text).find()&&!UNSUPPORTED_CLAIMS.matcher(text).find()
            &&text.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT);
    }
}
