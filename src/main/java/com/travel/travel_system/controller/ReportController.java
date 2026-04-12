package com.travel.travel_system.controller;

import com.travel.travel_system.service.ReportService;
import com.travel.travel_system.utils.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ReportController extends BaseController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/trips/{tripId}/report")
    public ApiResponse<?> getTripReport(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            return success(reportService.generateTripReport(userId, tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "生成行程报告失败：" + e.getMessage());
        }
    }

    @GetMapping("/reports/yearly/{year}")
    public ApiResponse<?> getYearlyReport(@PathVariable Integer year, HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            return success(reportService.generateYearlyReport(userId, year));
        } catch (Exception e) {
            return error("SYSTEM_500", "生成年度报告失败：" + e.getMessage());
        }
    }

    @GetMapping("/reports/monthly/{year}/{month}")
    public ApiResponse<?> getMonthlyReport(@PathVariable Integer year, 
                                           @PathVariable Integer month, 
                                           HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            return success(reportService.generateMonthlyReport(userId, year, month));
        } catch (Exception e) {
            return error("SYSTEM_500", "生成月度报告失败：" + e.getMessage());
        }
    }

    @PostMapping("/trips/{tripId}/report/export/pdf")
    public ResponseEntity<byte[]> exportTripReportPdf(@PathVariable Long tripId, 
                                                      HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            byte[] pdfBytes = reportService.exportTripReportPdf(userId, tripId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "trip-report-" + tripId + ".pdf");
            headers.setContentLength(pdfBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/trips/{tripId}/report/export/image")
    public ResponseEntity<byte[]> exportTripReportImage(@PathVariable Long tripId, 
                                                        HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            byte[] imageBytes = reportService.exportTripReportImage(userId, tripId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentDispositionFormData("attachment", "trip-report-" + tripId + ".png");
            headers.setContentLength(imageBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(imageBytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }
}
