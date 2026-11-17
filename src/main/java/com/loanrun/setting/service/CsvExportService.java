package com.loanrun.setting.service;

import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CsvExportService {
    private final ObjectProvider<LoanQueryPort> loans;
    public CsvExportService(ObjectProvider<LoanQueryPort> loans) { this.loans = loans; }

    @Transactional(readOnly = true)
    public byte[] export(long userId, String format, String startMonth, String endMonth) {
        if (!"csv".equals(format)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_FORMAT", "지원하지 않는 파일 형식입니다.");
        YearMonth start = month(startMonth, YearMonth.of(1, 1)), end = month(endMonth, YearMonth.of(9999, 12));
        if (start.isAfter(end)) throw invalidRange();
        LoanQueryPort port = loans.getIfAvailable();
        if (port == null) throw ApiException.unavailable();
        // The detailed spec also explicitly requires fee and savings-use columns after the original nine columns.
        StringBuilder csv = new StringBuilder("\uFEFFrecord_type,loan_id,product_name,paid_at,amount,principal_amount,interest_amount,extra_amount,remaining_principal,fee_amount,savings_used_amount\r\n");
        for (var loan : port.exportLoans(userId)) {
            row(csv, "loan", loan.loanId(), loan.productName(), null, null, null, null, null, loan.remainingPrincipal(), null, null);
        }
        for (var payment : port.exportPayments(userId, new DateRange(start.atDay(1), end.atEndOfMonth()))) {
            row(csv, "payment", payment.loanId(), payment.productName(), payment.paidAt(), payment.amount(),
                payment.principalAmount(), payment.interestAmount(), payment.extraAmount(), null, payment.feeAmount(), payment.savingsUsedAmount());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private YearMonth month(String value, YearMonth defaultValue) {
        if (value == null) return defaultValue;
        try {
            if (!value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000")) throw invalidRange();
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) { throw invalidRange(); }
    }
    private ApiException invalidRange() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "조회 기간을 확인해 주세요."); }
    private void row(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(cell(values[i]));
        }
        csv.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (value instanceof String) {
            String stripped = text.stripLeading();
            if (text.chars().anyMatch(c -> c == '\t' || c == '\r' || c == '\n') ||
                (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0)) text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
