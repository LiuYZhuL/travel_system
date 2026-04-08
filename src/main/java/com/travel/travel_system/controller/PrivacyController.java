package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController extends BaseController {

    /**
     * 更新隐私模式
     */
    @PostMapping("/update")
    public ApiResponse<?> updatePrivacyMode(@RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "更新隐私模式接口暂未实现");
    }
}
