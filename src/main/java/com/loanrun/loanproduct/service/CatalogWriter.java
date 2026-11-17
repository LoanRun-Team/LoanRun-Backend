package com.loanrun.loanproduct.service;

import com.loanrun.loanproduct.client.FssCatalogClient.Snapshot;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogWriter {
    private final JdbcTemplate jdbc;private final Clock clock;
    public CatalogWriter(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Transactional public void replace(Snapshot snapshot){
        // Calls finish before this transaction. One failure retains the complete last successful catalog.
        jdbc.execute("SELECT pg_advisory_xact_lock(74011001)");
        jdbc.update("UPDATE loan_products SET eligible=FALSE WHERE loan_type IN ('CREDIT','MORTGAGE','JEONSE')");
        for(var p:snapshot.products()){
            if(p.name().contains("마이너스")||p.name().contains("한도대출")||p.name().contains("종합통장"))throw new IllegalArgumentException("Unsupported credit-line product");
            jdbc.update("INSERT INTO financial_institutions(external_code,name) VALUES (?,?) ON CONFLICT(external_code) DO UPDATE SET name=excluded.name",p.institutionCode(),p.institutionName());
            Long institution=jdbc.queryForObject("SELECT id FROM financial_institutions WHERE external_code=?",Long.class,p.institutionCode());
            jdbc.update("""
                INSERT INTO loan_products(institution_id,external_code,name,loan_type,repayment_methods,source,source_url,updated_at)
                VALUES (?,?,?,?,?,'LIVE',?,?) ON CONFLICT(institution_id,external_code,loan_type) DO UPDATE SET name=excluded.name,
                repayment_methods=excluded.repayment_methods,source='LIVE',source_url=excluded.source_url,updated_at=excluded.updated_at,eligible=TRUE
                """,institution,p.code(),p.name(),p.type().name(),p.methods().stream().map(Enum::name).collect(Collectors.joining(",")),p.sourceUrl(),Timestamp.from(clock.instant()));
        }
        jdbc.update("INSERT INTO catalog_sync_state(id,last_success_at) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET last_success_at=excluded.last_success_at",Timestamp.from(clock.instant()));
    }
}
