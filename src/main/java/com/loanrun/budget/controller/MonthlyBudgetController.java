package com.loanrun.budget.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.dto.BudgetDtos.Monthly;
import com.loanrun.budget.service.MonthlyBudgetService;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonthlyBudgetController {
    private final CurrentUserProvider currentUser;
    private final MonthlyBudgetService service;
    public MonthlyBudgetController(CurrentUserProvider currentUser,MonthlyBudgetService service){this.currentUser=currentUser;this.service=service;}
    @GetMapping("/api/budgets/{year_month}/")
    public ApiResponse<Monthly> get(@PathVariable("year_month")String month){
        return ApiResponse.success("MONTHLY_BUDGET_DETAIL_SUCCESS","월별 예산·재정정보 정보를 조회했습니다.",service.get(currentUser.currentUserId(),month));
    }
    @PutMapping("/api/budgets/{year_month}/")
    public ApiResponse<Monthly> save(@PathVariable("year_month")String month,@RequestBody JsonNode body){
        return ApiResponse.success("MONTHLY_BUDGET_SAVE_SUCCESS","월별 재정정보를 저장했습니다.",service.save(currentUser.currentUserId(),month,body));
    }
    @PostMapping("/api/income-entries/")
    public ResponseEntity<JsonNode> income(@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        var result=service.addIncome(currentUser.currentUserId(),key,body);return ResponseEntity.status(result.status()).body(result.body());
    }
    @PutMapping("/api/budgets/{year_month}/allocation/")
    public ResponseEntity<JsonNode> allocate(@PathVariable("year_month")String month,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        long id=currentUser.currentUserId();
        var result=service.allocate(id,month,key,body);return ResponseEntity.status(result.status()).body(result.body());
    }
}
