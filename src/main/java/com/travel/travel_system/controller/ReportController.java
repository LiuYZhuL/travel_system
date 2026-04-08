package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/report")
public class ReportController extends BaseController {

    /**
     * 获取年度足迹报告
     */
    @GetMapping("/yearly/{year}")
    public ApiResponse<?> getYearlyReport(@PathVariable Integer year) {
        return error("SYSTEM_501", "获取年度足迹报告接口暂未实现");
    }

    @GetMapping("/monthly/{year}/{month}")
    public ApiResponse<?> getMonthlyReport(@PathVariable Integer year, @PathVariable Integer month) {
        return error("SYSTEM_501", "获取月度足迹报告接口暂未实现");
    }
}
