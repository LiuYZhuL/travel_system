package com.travel.travel_system.controller;

import com.alibaba.fastjson.JSONObject;
import com.travel.travel_system.model.User;
import com.travel.travel_system.service.UserService;
import com.travel.travel_system.service.pub.RedisService;
import com.travel.travel_system.service.pub.WechatService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController extends BaseController {

    @Autowired
    private WechatService wechatService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RedisService redisService;

    /**
     * 微信登录
     */
    @PostMapping("/wx-login")
    public ApiResponse<?> wxLogin(@RequestBody Map<String, Object> request) {
        try {
            String code = asString(request.get("code"));
            if (code == null || code.trim().isEmpty()) {
                return error("VALID_001", "微信授权码不能为空");
            }

            JSONObject session = wechatService.getWechatSession(code);
            if (session.containsKey("errcode")) {
                return error("VALID_001", "微信授权失败：" + session.getString("errmsg"));
            }

            String openId = session.getString("openid");
            String unionId = session.getString("unionid");
            if (openId == null || openId.trim().isEmpty()) {
                return error("VALID_001", "微信授权码无效");
            }

            String nickname = asString(request.get("nickname"));
            String avatarUrl = asString(request.get("avatarUrl"));
            User user = userService.findOrCreateUser(openId, unionId, nickname, avatarUrl);
            boolean acceptedInRequest = Boolean.parseBoolean(String.valueOf(request.getOrDefault("privacyAgreementAccepted", "false")));
            String agreementVersion = asString(request.get("privacyAgreementVersion"));
            String currentAgreementVersion = userService.getCurrentPrivacyAgreementVersion();
            if (!userService.hasAcceptedCurrentPrivacyAgreement(user)) {
                if (!acceptedInRequest || !currentAgreementVersion.equalsIgnoreCase(agreementVersion)) {
                    return error("PRIVACY_001", "请先同意最新的用户协议和隐私政策");
                }
                user = userService.acceptPrivacyAgreement(user.getId(), currentAgreementVersion);
            } else if (acceptedInRequest && currentAgreementVersion.equalsIgnoreCase(agreementVersion)) {
                user = userService.acceptPrivacyAgreement(user.getId(), currentAgreementVersion);
            }
            String token = jwtUtils.generateToken(openId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("expiresIn", jwtUtils.getExpiration());
            data.put("userId", user.getId());
            data.put("privacyAgreementAccepted", userService.hasAcceptedCurrentPrivacyAgreement(user));
            data.put("privacyAgreementVersion", currentAgreementVersion);
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "登录失败：" + e.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ApiResponse<?> logout(HttpServletRequest request) {
        try {
            String token = (String) request.getAttribute("token");
            if (token != null) {
                redisService.addTokenToBlacklist(token, jwtUtils.getExpiration());
            }
            return success();
        } catch (Exception e) {
            return error("SYSTEM_500", "退出登录失败：" + e.getMessage());
        }
    }
}
