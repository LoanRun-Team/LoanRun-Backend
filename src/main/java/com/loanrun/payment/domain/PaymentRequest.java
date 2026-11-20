package com.loanrun.payment.domain;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.loan.read.LoanSources.PaymentKind;
import java.time.*;
import org.springframework.http.HttpStatus;

public record PaymentRequest(long id,LocalDate date,PaymentKind kind,long amount,Long dueId,Long principal,Long extra,Long interest,Long fee,long used,Instant createdAt,boolean edited) {
    public boolean automatic(){return principal==null;}
    public static PaymentRequest read(JsonNode n,long id,Instant created) {return read(n,id,created,false);}
    public static PaymentRequest read(JsonNode n,long id,Instant created,boolean allowHistoricalRegular) {
        JsonPatch.fields(n,"loan_id","paid_at","payment_type","amount","due_item_id","principal_amount","extra_amount","interest_amount","fee_amount","savings_used_amount");
        LocalDate date;try{String text=JsonPatch.text(n,"paid_at",false,10);if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||text.startsWith("0000"))throw new DateTimeException("date");date=LocalDate.parse(text);}catch(DateTimeException e){throw invalid();}
        var kind=JsonPatch.enumeration(n,"payment_type",PaymentKind.class);long amount=money(n,"amount",true);if(amount==0)throw invalid();
        Long due=n.hasNonNull("due_item_id")?money(n,"due_item_id",true):null;if(kind==PaymentKind.REGULAR&&(due==null&&!allowHistoricalRegular||due!=null&&due<=0)||kind==PaymentKind.EXTRA&&due!=null)throw invalid();
        String[] keys={"principal_amount","extra_amount","interest_amount","fee_amount"};int provided=0;for(var k:keys)if(n.hasNonNull(k))provided++;if(provided!=0&&provided!=4)throw invalid();
        Long principal=null,extra=null,interest=null,fee=null;if(provided==4){principal=money(n,keys[0],true);extra=money(n,keys[1],true);interest=money(n,keys[2],true);fee=money(n,keys[3],true);}
        return new PaymentRequest(id,date,kind,amount,due,principal,extra,interest,fee,money(n,"savings_used_amount",false),created,true);
    }
    public static long money(JsonNode n,String key,boolean required){var v=n.get(key);if(v==null&&!required)return 0;if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw invalid();return v.longValue();}
    public static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_AMOUNT","납부액을 확인해 주세요.");}
}
