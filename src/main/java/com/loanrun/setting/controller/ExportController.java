package com.loanrun.setting.controller;

import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.setting.service.CsvExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class ExportController {
    private final CurrentUserProvider currentUser;
    private final CsvExportService service;
    public ExportController(CurrentUserProvider currentUser, CsvExportService service) { this.currentUser = currentUser; this.service = service; }
    @GetMapping("/export/")
    public ResponseEntity<byte[]> export(@RequestParam(name="format", defaultValue="csv") String format,
            @RequestParam(name="start_month", required=false) String startMonth, @RequestParam(name="end_month", required=false) String endMonth) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ronrun-export.csv\"")
            .body(service.export(currentUser.currentUserId(), format, startMonth, endMonth));
    }
}
