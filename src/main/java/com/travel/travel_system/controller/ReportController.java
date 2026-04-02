package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    /**
     * 获取年度足迹报告
     */
    @GetMapping("/yearly/{year}")
    public Map<String, Object> getYearlyReport(@PathVariable Integer year) {
        return null;
    }

    @GetMapping("/monthly/{year}/{month}")
    public Map<String, Object> getMonthlyReport(@PathVariable Integer year, @PathVariable Integer month) {
        return null;
    }
}
