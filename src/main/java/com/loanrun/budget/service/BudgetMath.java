package com.loanrun.budget.service;

import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.Metrics;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigInteger;
import java.time.YearMonth;

/** Budget cash arithmetic only. Interest/schedules/savings requirements remain owned by Team 1. */
public final class BudgetMath {
    private BudgetMath() {}
    public static Metrics calculate(MonthlyBudget budget, Long planningIncome, Long actualIncome, String incomeStatus,
            DueSummary due, PaymentSummary payment, SavingSummary saving, long carryIn, long usedExtraCash, YearMonth currentMonth) {
        long arrears = due.previousOutstandingCash();
        Long available = budget == null || planningIncome == null ? null : difference(planningIncome,
            budget.fixedEssentialExpenses(), budget.variableEssentialExpenses(), budget.discretionaryExpenses(), budget.savings(),
            Math.subtractExact(Math.addExact(due.scheduledCash(), arrears), due.savingsUsedForDue()));
        Long capacity = available == null ? null : Math.max(Math.addExact(available, carryIn), 0);
        Long actual = budget == null || !budget.expensesConfirmed() || actualIncome == null || "FORECAST".equals(incomeStatus)
            || due.unrecordedCount() > 0
            ? null : difference(actualIncome, budget.fixedEssentialExpenses(), budget.variableEssentialExpenses(),
                budget.discretionaryExpenses(), budget.savings(), payment.cashPaid(), saving.additions());
        boolean closed = budget != null && budget.month().isBefore(currentMonth);
        String settlement = !closed ? "OPEN" : actual == null ? "INSUFFICIENT" : "SETTLED";
        Long settledReserve = null, carryOut = null;
        if (closed && actual != null) {
            long surplus = Math.max(Math.addExact(actual, carryIn), 0);
            settledReserve = Math.min(budget.reserveAmount(), surplus);
            carryOut = surplus - settledReserve;
        }
        Long actualDiscretionary = budget != null && budget.expensesConfirmed() ? budget.discretionaryExpenses() : null;
        Long limit = budget == null ? null : budget.discretionaryLimit();
        return new Metrics(available, planningIncome, actual, carryIn, capacity,
            available == null ? null : Math.max(available, 0), available == null ? null : Math.max(Math.negateExact(available), 0),
            capacity == null ? null : Math.max(difference(capacity, usedExtraCash, saving.additions()), 0), settlement, carryOut,
            settledReserve, actualIncome, incomeStatus, arrears, payment.cashPaid(), saving.additions(), limit, actualDiscretionary,
            actualDiscretionary == null || limit == null ? null : Math.max(limit - actualDiscretionary, 0));
    }

    public static long difference(long start, long... amounts) {
        BigInteger value = BigInteger.valueOf(start);
        for (long amount : amounts) value = value.subtract(BigInteger.valueOf(amount));
        return value.longValueExact();
    }
}
