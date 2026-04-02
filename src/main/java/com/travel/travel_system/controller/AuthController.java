package com.travel.travel_system.controller;


import com.alibaba.fastjson.JSONObject;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.User;
import com.travel.travel_system.service.pub.RedisService;
import com.travel.travel_system.service.UserService;
import com.travel.travel_system.service.pub.WechatService;
import com.travel.travel_system.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



import java.text.SimpleDateFormat;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private WechatService wechatService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RedisService redisService;


    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * 微信登录
     */
    @PostMapping("/wx-login")
    public Map<String, Object> wxLogin(@RequestBody Map<String, String> request) {
        try {
            // 1. 获取微信授权码
            String code = request.get("code");
            if (code == null || code.trim().isEmpty()) {
                return errorResponse("VALID_001", "微信授权码不能为空", null);
            }

            // 2. 调用微信 API 获取 session
            JSONObject session = wechatService.getWechatSession(code);

            // 检查微信 API 是否调用成功
            if (session.containsKey("errcode")) {
                return errorResponse("VALID_001", "微信授权失败：" + session.getString("errmsg"), null);
            }

            // 3. 获取用户信息
            String openId = session.getString("openid");
            String unionId = session.getString("unionid");

            if (openId == null || openId.trim().isEmpty()) {
                return errorResponse("VALID_001", "微信授权码无效", null);
            }

            // 4. 获取用户昵称和头像（可选）
            String nickname = request.get("nickname");
            String avatarUrl = request.get("avatarUrl");

            // 5. 查询或创建用户（使用封装好的方法）
            User user = userService.findOrCreateUser(openId, unionId, nickname, avatarUrl);

            // 6. 生成 JWT token
            String token = jwtUtils.generateToken(openId);

            // 7. 构建响应数据
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("expiresIn", jwtUtils.getExpiration());
            data.put("userId", user.getId());

            // 返回成功响应（符合接口文档设计）
            return successResponse(data);

        } catch (Exception e) {
            return errorResponse("SYSTEM_500", "登录失败：" + e.getMessage(), null);
        }
    }


    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        try {
            // 从请求属性中获取 token（由拦截器设置）
            String token = (String) request.getAttribute("token");

            if (token != null) {
                // 将 token 加入黑名单，使其失效
                redisService.addTokenToBlacklist(token, jwtUtils.getExpiration());
            }

            return successResponse(null);

        } catch (Exception e) {
            return errorResponse("SYSTEM_500", "退出登录失败：" + e.getMessage(), null);
        }
    }
    /**
     * 构建成功响应（符合接口文档设计）
     */
    private Map<String, Object> successResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", "0");
        response.put("message", "success");
        response.put("data", data);
        response.put("requestId", UUID.randomUUID().toString());
        return response;
    }

    /**
     * 构建错误响应（符合接口文档设计）
     */
    private Map<String, Object> errorResponse(String code, String message, Object errors) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", null);
        response.put("requestId", UUID.randomUUID().toString());
        return response;
    }

}
