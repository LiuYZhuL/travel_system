package com.travel.travel_system.config;

import com.travel.travel_system.model.User;
import com.travel.travel_system.repository.UserRepository;
import com.travel.travel_system.service.pub.RedisService;
import com.travel.travel_system.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.UUID;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RedisService redisService;
    
    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头获取令牌
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(jwtUtils.getTokenPrefix() + " ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\": \"AUTH_001\", \"message\": \"未授权访问\", \"data\": null, \"requestId\": \"" + UUID.randomUUID() + "\"}");
            return false;
        }

        // 提取令牌
        String token = authorization.substring((jwtUtils.getTokenPrefix() + " ").length());
        
        // 检查令牌是否在黑名单中
        if (redisService.isTokenInBlacklist(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\": \"AUTH_001\", \"message\": \"令牌已被注销\", \"data\": null, \"requestId\": \"" + UUID.randomUUID() + "\"}");
            return false;
        }
        
        if (!jwtUtils.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\": \"AUTH_001\", \"message\": \"令牌无效或已过期\", \"data\": null, \"requestId\": \"" + UUID.randomUUID() + "\"}");
            return false;
        }

        // 将openId存储到请求中，供后续处理使用
        String openId = jwtUtils.getOpenIdFromToken(token);
        request.setAttribute("openId", openId);
        
        // 通过openId查询用户并设置userId
        User user = userRepository.findByOpenId(openId).orElse(null);
        if (user != null) {
            request.setAttribute("userId", user.getId());
        }
        
        // 将令牌存储到请求中，供退出登录时使用
        request.setAttribute("token", token);
        return true;
    }
}