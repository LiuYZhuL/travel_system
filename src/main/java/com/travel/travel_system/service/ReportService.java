package com.travel.travel_system.service;

import java.util.Map;

public interface ReportService {
    Map<String, Object> generateTripReport(Long userId, Long tripId);
    
    Map<String, Object> generateYearlyReport(Long userId, Integer year);
    
    Map<String, Object> generateMonthlyReport(Long userId, Integer year, Integer month);
    
    byte[] exportTripReportPdf(Long userId, Long tripId);
    
    byte[] exportTripReportImage(Long userId, Long tripId);
}
