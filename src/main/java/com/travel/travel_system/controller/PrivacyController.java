package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    /**
     * 更新隐私模式
     */
    @PostMapping("/update")
    public Map<String, Object> updatePrivacyMode(@RequestBody Map<String, Object> request) {
        return null;
    }
}
