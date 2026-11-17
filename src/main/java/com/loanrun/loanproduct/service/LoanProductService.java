package com.loanrun.loanproduct.service;

import com.loanrun.common.contract.ProductQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class LoanProductService implements ProductQueryPort {
    public record Option(String code,String label){}
    public record Institution(long institutionId,String name){}
    public record Options(List<Option> loanTypes,List<Institution> institutions){}
    public record Product(long productId,long institutionId,String name,LoanType loanType,List<RepaymentMethod> repaymentMethods,String source){}
    public record Products(List<Product> products){}
    private final JdbcTemplate jdbc;
    public LoanProductService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private LoanType type(String value){try{return value==null?null:LoanType.valueOf(value);}catch(IllegalArgumentException ex){throw ApiException.validation("loan_type","지원하지 않는 유형입니다.");}}
    private void form(String value){if(value!=null&&!"GENERAL".equals(value))throw ApiException.validation("loan_form","GENERAL만 지원합니다.");}
    private void ready(){if(jdbc.queryForObject("SELECT count(*) FROM catalog_sync_state",Long.class)==0)throw ApiException.unavailable();}
    public Options options(String type,String form){
        form(form);LoanType loanType=type(type);
        List<Option> options=List.of(new Option("CREDIT","신용대출"),new Option("MORTGAGE","주택담보대출"),new Option("JEONSE","전세자금대출"),
            new Option("STUDENT","학자금대출"),new Option("AUTO","자동차대출"),new Option("OTHER","기타"));
        if(loanType!=null&&!Set.of(LoanType.CREDIT,LoanType.MORTGAGE,LoanType.JEONSE).contains(loanType))return new Options(options,List.of());
        ready();
        List<Institution> institutions=loanType==null?jdbc.query("SELECT DISTINCT i.id,i.name FROM financial_institutions i JOIN loan_products p ON p.institution_id=i.id WHERE p.eligible ORDER BY i.id",(rs,n)->new Institution(rs.getLong(1),rs.getString(2)))
            :jdbc.query("SELECT DISTINCT i.id,i.name FROM financial_institutions i JOIN loan_products p ON p.institution_id=i.id WHERE p.eligible AND p.loan_type=? ORDER BY i.id",(rs,n)->new Institution(rs.getLong(1),rs.getString(2)),loanType.name());
        return new Options(options,institutions);
    }
    public Products products(String type,String form,long institutionId){
        form(form);LoanType loanType=type(type);if(institutionId<=0)throw ApiException.validation("institution_id","양의 ID가 필요합니다.");ready();
        if(jdbc.queryForObject("SELECT count(*) FROM financial_institutions WHERE id=?",Long.class,institutionId)==0)throw new ApiException(HttpStatus.NOT_FOUND,"INSTITUTION_NOT_FOUND","금융기관을 찾을 수 없습니다.");
        String sql="SELECT * FROM loan_products WHERE institution_id=? AND eligible"+(loanType==null?"":" AND loan_type=?")+" ORDER BY id";
        Object[] parameters=loanType==null?new Object[]{institutionId}:new Object[]{institutionId,loanType.name()};
        return new Products(jdbc.query(sql,(rs,n)->new Product(rs.getLong("id"),institutionId,rs.getString("name"),LoanType.valueOf(rs.getString("loan_type")),methods(rs.getString("repayment_methods")),"CATALOG"),parameters));
    }
    private List<RepaymentMethod> methods(String values){return values.isBlank()?List.of():Arrays.stream(values.split(",")).map(RepaymentMethod::valueOf).toList();}
    @Override public EligibleProduct findEligibleProduct(long productId){
        return jdbc.query("SELECT p.*,i.name AS institution_name FROM loan_products p JOIN financial_institutions i ON i.id=p.institution_id WHERE p.id=? AND p.eligible",
            (rs,n)->new EligibleProduct(rs.getLong("id"),rs.getLong("institution_id"),rs.getString("institution_name"),rs.getString("name"),LoanType.valueOf(rs.getString("loan_type")),methods(rs.getString("repayment_methods"))),productId)
            .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","조회한 경로 또는 리소스를 찾을 수 없습니다."));
    }
}
