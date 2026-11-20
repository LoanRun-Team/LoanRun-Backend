package com.loanrun.loan.repository;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.Loan;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

/** All callers hold the user mutation lock and an enclosing write transaction. */
@Repository
public class LoanContractWriteRepository {
    private final NamedParameterJdbcTemplate jdbc;private final LoanSourceRepository sources;
    public LoanContractWriteRepository(NamedParameterJdbcTemplate jdbc,LoanSourceRepository sources){this.jdbc=jdbc;this.sources=sources;}
    public Loan lock(long user,long id) {
        var ids=jdbc.queryForList("SELECT id FROM loans WHERE user_id=:user AND id=:id AND status<>'DELETED' FOR UPDATE",Map.of("user",user,"id",id),Long.class);
        if(ids.isEmpty())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        return sources.visibleLoan(user,id);
    }
    public LocalDate latestRateDate(long user,long id) {
        return jdbc.queryForObject("SELECT max(effective_date) FROM loan_rate_histories WHERE user_id=:user AND loan_id=:id",Map.of("user",user,"id",id),LocalDate.class);
    }
    public boolean onlyRegistrationRateOn(long user,long id,LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT count(*)=1 AND min(effective_date)=:date
            FROM loan_rate_histories WHERE user_id=:user AND loan_id=:id
            """,new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("date",date),Boolean.class));
    }
    public long insertRate(long user,long id,LocalDate date,BigDecimal rate,Instant now) {
        return jdbc.queryForObject("INSERT INTO loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at) VALUES (:user,:id,:date,:rate,:now) RETURNING id",
            new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("date",date).addValue("rate",rate).addValue("now",Timestamp.from(now)),Long.class);
    }
    public void updateRate(long user,long id,LoanTerms terms,Instant now) {
        jdbc.update("UPDATE loans SET annual_rate=:rate,next_rate_reset_date=:reset,base_anchor_amount=:anchor,updated_at=:now WHERE user_id=:user AND id=:id",
            new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("rate",terms.annualRate()).addValue("reset",terms.rateResetDate()).addValue("anchor",terms.baseAnchorAmount()).addValue("now",Timestamp.from(now)));
    }
    public void repriceDue(long user,long id,long dueId,SimulationRow row,Long contractual,long revision) {
        int n=jdbc.update("""
            UPDATE due_items d SET principal_amount=:principal,interest_amount=:interest,
                required_amount=COALESCE(:contractual,:principal+:interest),terms_revision=:revision
            WHERE d.user_id=:user AND d.loan_id=:id AND d.id=:due AND d.due_date=:date
              AND d.fee_amount=0 AND NOT d.confirmed_unpaid
              AND NOT EXISTS (SELECT 1 FROM payment_allocations a WHERE a.user_id=d.user_id AND a.due_item_id=d.id)
            """,new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("due",dueId).addValue("date",row.date())
            .addValue("principal",row.scheduledPrincipal()).addValue("interest",row.interest()).addValue("contractual",contractual,java.sql.Types.BIGINT).addValue("revision",revision));
        if(n!=1)throw new IllegalStateException("Locked due changed");
    }
    public void updateContract(long user,long id,Map<String,Object> changes,Instant now) {
        var allowed=Set.of("original_principal","remaining_principal","maturity_date","payment_day","contractual_monthly_payment","institution_name","product_name","start_date","next_rate_reset_date","prepayment_policy","prepayment_fee_method","prepayment_fee_rate","fee_start_date","fee_end_date","fee_exempt","rate_type","post_grace_method","rate_reset_months","next_due_date","base_anchor_amount","fixed_principal","grace_end_date","status","snapshot_date","snapshot_principal");
        if(!allowed.containsAll(changes.keySet()))throw new IllegalArgumentException("Invalid contract field");
        var p=new MapSqlParameterSource();changes.forEach(p::addValue);p.addValue("user",user).addValue("id",id).addValue("now",Timestamp.from(now));
        jdbc.update("UPDATE loans SET "+changes.keySet().stream().map(k->k+"=:"+k).collect(java.util.stream.Collectors.joining(","))+",updated_at=:now WHERE user_id=:user AND id=:id",p);
    }
    public void correction(long user,long id,LocalDate date,long amount,Instant now) {
        jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (:user,:id,:date,:amount,'CORRECTION',:now)",
            new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("date",date).addValue("amount",amount).addValue("now",Timestamp.from(now)));
    }
    public void reviseDue(long user,long id,long dueId,SimulationRow row,Long contractual,long revision) {
        int n=jdbc.update("""
            UPDATE due_items d SET due_date=:date,principal_amount=:principal,interest_amount=:interest,
                contractual_amount=:contractual,required_amount=COALESCE(:contractual,:principal+:interest),terms_revision=:revision
            WHERE d.user_id=:user AND d.loan_id=:id AND d.id=:due AND d.fee_amount=0 AND NOT d.confirmed_unpaid
              AND NOT EXISTS (SELECT 1 FROM payment_allocations a WHERE a.user_id=d.user_id AND a.due_item_id=d.id)
            """,new MapSqlParameterSource().addValue("user",user).addValue("id",id).addValue("due",dueId).addValue("date",row.date())
            .addValue("principal",row.scheduledPrincipal()).addValue("interest",row.interest()).addValue("contractual",contractual,java.sql.Types.BIGINT).addValue("revision",revision));
        if(n!=1)throw new IllegalStateException("Locked due changed");
    }
    public void removeUnconfirmedDue(long user,long id,long dueId) {
        int n=jdbc.update("DELETE FROM due_items d WHERE d.user_id=:user AND d.loan_id=:id AND d.id=:due AND NOT d.confirmed_unpaid AND NOT EXISTS(SELECT 1 FROM payment_allocations a WHERE a.user_id=d.user_id AND a.due_item_id=d.id)",Map.of("user",user,"id",id,"due",dueId));
        if(n!=1)throw new IllegalStateException("Locked due changed");
    }
}
