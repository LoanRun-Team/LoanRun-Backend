package com.loanrun.budget.service;

import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.contract.UserMutationLock;
import java.sql.Date;
import java.time.Clock;
import java.time.YearMonth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetRecalculationService implements BudgetRecalculationPort {
    private final BudgetReadService reads;
    private final JdbcTemplate jdbc;
    private final UserMutationLock locks;
    private final Clock clock;
    public BudgetRecalculationService(BudgetReadService reads, JdbcTemplate jdbc, UserMutationLock locks, Clock clock) {
        this.reads = reads; this.jdbc = jdbc; this.locks = locks; this.clock = clock;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void recalculateFrom(long userId, YearMonth earliestMonth) {
        locks.lock(userId);
        for (var e : reads.through(userId, YearMonth.now(clock))) {
            if (e.budget() == null || e.budget().id() == null || e.budget().month().isBefore(earliestMonth)) continue;
            var m = e.metrics();
            jdbc.update("""
                UPDATE monthly_budgets SET carry_in=?,carry_out=?,reserve_settled=?,settlement_status=? WHERE user_id=? AND year_month=?
                """, m.carryIn(), m.carryOut(), m.reserveSettled(), m.settlementStatus(), userId, Date.valueOf(e.budget().month().atDay(1)));
        }
    }
}
