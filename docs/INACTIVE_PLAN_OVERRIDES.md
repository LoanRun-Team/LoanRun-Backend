# Retained overrides after loan closure

Deleting or paying off a loan preserves its original monthly allocation and plan history. Current and future plan views must not interpret that retained allocation as a target for an active loan, or fail the entire household read because it is no longer active.

MonthlyPlanPortService resolves ownership before excluding closed-loan overrides from current/future views. Historical queries retain the original behavior. PlanReadService filters owned closed-loan entries from current overrides and overall priorities before building active plan views. Invalid amounts, duplicates, and foreign/missing references remain errors. No stored allocation, transaction, payment, asset, or revision is changed by this filtering.

Regression tests cover DELETED/PAID targets, no active loans, a newly registered active loan, foreign ownership rejection, retained source rows, and HTTP schedule/monthly-summary/dashboard/plan/preview reads with real financial providers. The failure was reproduced before the fix. No data cleanup or migration is required.
