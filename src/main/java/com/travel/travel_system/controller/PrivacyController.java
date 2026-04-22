package com.travel.travel_system.controller;

import com.travel.travel_system.model.User;
import com.travel.travel_system.service.PrivacyService;
import com.travel.travel_system.service.UserService;
import com.travel.travel_system.utils.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController extends BaseController {

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private UserService userService;

    @PostMapping("/update")
    public ApiResponse<?> updatePrivacyMode(@RequestBody Map<String, Object> request,
                                            HttpServletRequest httpRequest) {
        try {
            Long userId = (Long) httpRequest.getAttribute("userId");
            if (userId == null) {
                return error("AUTH_001", "未授权访问");
            }

            String privacyMode = firstNonBlank(
                    asString(request.get("privacyMode")),
                    asString(request.get("defaultPrivacyMode"))
            );
            if (privacyMode == null) {
                return error("PARAM_001", "隐私模式不能为空");
            }

            privacyService.updatePrivacyMode(userId, privacyMode);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("defaultPrivacyMode", privacyMode.trim().toUpperCase());
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "更新隐私模式失败: " + e.getMessage());
        }
    }

    @GetMapping("/current")
    public ApiResponse<?> getCurrentPrivacyMode(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                return error("AUTH_001", "未授权访问");
            }

            User user = userService.getUserInfo(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", user.getId());
            data.put("defaultPrivacyMode", user.getDefaultPrivacyMode() == null
                    ? null
                    : user.getDefaultPrivacyMode().name());
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取隐私模式失败: " + e.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
