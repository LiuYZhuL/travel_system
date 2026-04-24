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
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController extends BaseController {

    /** 微信 code 有效期 5 分钟，state 与之对齐 */
    private static final long STATE_TTL_SECONDS = 300;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private WechatService wechatService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RedisService redisService;

    // ----------------------------------------------------------------
    // 1. 预授权端点：生成 state nonce（对应 RFC 6749 §4.1.1 state 参数）
    // ----------------------------------------------------------------

    /**
     * 前端在调用 wx.login() 之前必须先请求此端点获取 state。
     * state 是一次性随机字符串，5 分钟内有效，防止授权码重放攻击。
     */
    @GetMapping("/state")
    public ApiResponse<?> generateState() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisService.storeStateNonce(state, STATE_TTL_SECONDS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state);
        data.put("expiresIn", STATE_TTL_SECONDS);
        return success(data);
    }

    // ----------------------------------------------------------------
    // 2. Token 端点：Authorization Code Exchange（RFC 6749 §4.1.3）
    // ----------------------------------------------------------------

    /**
     * 前端携带 wx.login() 返回的 code 以及从 /state 取得的 state。
     * 后端校验 state 合法性（防重放），再换取 openid，颁发 access_token + refresh_token。
     */
    @PostMapping("/wx-login")
    public ApiResponse<?> wxLogin(@RequestBody Map<String, Object> request) {
        try {
            // --- state 校验（一次性消耗，防止授权码重放） ---
            String state = asString(request.get("state"));
            if (state == null || state.trim().isEmpty()) {
                return error("AUTH_003", "缺少 state 参数");
            }
            if (!redisService.consumeStateNonce(state)) {
                return error("AUTH_003", "state 无效或已过期，请重新发起登录");
            }

            // --- code 校验 ---
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

            // --- 用户初始化与隐私协议 ---
            String nickname = asString(request.get("nickname"));
            String avatarUrl = asString(request.get("avatarUrl"));
            User user = userService.findOrCreateUser(openId, unionId, nickname, avatarUrl);

            boolean acceptedInRequest = Boolean.parseBoolean(
                    String.valueOf(request.getOrDefault("privacyAgreementAccepted", "false")));
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

            // --- 颁发 Token ---
            String accessToken  = jwtUtils.generateAccessToken(openId);
            String refreshToken = jwtUtils.generateRefreshToken(openId);
            redisService.storeRefreshToken(openId, refreshToken, jwtUtils.getRefreshExpiration());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", accessToken);
            data.put("refreshToken", refreshToken);
            data.put("tokenType", "Bearer");
            data.put("expiresIn", jwtUtils.getExpiration());
            data.put("refreshExpiresIn", jwtUtils.getRefreshExpiration());
            data.put("userId", user.getId());
            data.put("privacyAgreementAccepted", userService.hasAcceptedCurrentPrivacyAgreement(user));
            data.put("privacyAgreementVersion", currentAgreementVersion);
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "登录失败：" + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // 3. Token 刷新端点（RFC 6749 §6 + Refresh Token Rotation）
    // ----------------------------------------------------------------

    /**
     * 用 refresh_token 换取新的 access_token，同时轮换 refresh_token。
     * 若检测到 refresh_token 已被轮换后仍有人使用（疑似令牌被盗），
     * 立即删除该用户所有 session，强制重新登录。
     */
    @PostMapping("/refresh")
    public ApiResponse<?> refreshToken(@RequestBody Map<String, Object> request) {
        try {
            String refreshToken = asString(request.get("refreshToken"));
            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                return error("AUTH_002", "刷新令牌不能为空");
            }

            if (!jwtUtils.validateToken(refreshToken)) {
                return error("AUTH_002", "刷新令牌无效或已过期，请重新登录");
            }

            if (!"refresh".equals(jwtUtils.getTokenType(refreshToken))) {
                return error("AUTH_002", "令牌类型错误");
            }

            String openId = jwtUtils.getOpenIdFromToken(refreshToken);

            // 若在黑名单中，说明该 refresh token 已被轮换；
            // 有人仍在使用旧 token ⇒ 疑似被盗，吊销该用户所有 session
            if (redisService.isTokenInBlacklist(refreshToken)) {
                redisService.deleteRefreshToken(openId);
                return error("AUTH_002", "检测到刷新令牌重复使用，为保障账户安全已强制登出，请重新登录");
            }

            String storedRefreshToken = redisService.getStoredRefreshToken(openId);
            if (!refreshToken.equals(storedRefreshToken)) {
                // Redis 中已无对应记录（用户已登出）
                return error("AUTH_002", "刷新令牌已失效，请重新登录");
            }

            // 颁发新 access token
            String newAccessToken = jwtUtils.generateAccessToken(openId);

            // Refresh token 轮换：旧 token 加黑名单，存入新 token
            String newRefreshToken = jwtUtils.generateRefreshToken(openId);
            redisService.addTokenToBlacklist(refreshToken, jwtUtils.getRefreshExpiration());
            redisService.storeRefreshToken(openId, newRefreshToken, jwtUtils.getRefreshExpiration());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", newAccessToken);
            data.put("refreshToken", newRefreshToken);
            data.put("tokenType", "Bearer");
            data.put("expiresIn", jwtUtils.getExpiration());
            data.put("refreshExpiresIn", jwtUtils.getRefreshExpiration());
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "刷新令牌失败：" + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // 4. Token 撤销端点（RFC 7009）
    // ----------------------------------------------------------------

    /**
     * 同时吊销 access token（黑名单）和 refresh token（Redis 删除 + 黑名单）。
     * /api/v1/auth/** 已排除拦截器，此处手动从 Authorization 头提取 access token。
     */
    @PostMapping("/logout")
    public ApiResponse<?> logout(HttpServletRequest request,
                                  @RequestBody(required = false) Map<String, Object> body) {
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith(jwtUtils.getTokenPrefix() + " ")) {
                String accessToken = authorization.substring((jwtUtils.getTokenPrefix() + " ").length());
                if (jwtUtils.validateToken(accessToken)) {
                    redisService.addTokenToBlacklist(accessToken, jwtUtils.getExpiration());
                }
            }

            if (body != null) {
                String refreshToken = asString(body.get("refreshToken"));
                if (refreshToken != null && !refreshToken.trim().isEmpty()) {
                    try {
                        String openId = jwtUtils.getOpenIdFromToken(refreshToken);
                        if (openId != null) {
                            redisService.deleteRefreshToken(openId);
                            redisService.addTokenToBlacklist(refreshToken, jwtUtils.getRefreshExpiration());
                        }
                    } catch (Exception ignored) {
                        // refresh token 解析失败时仍正常退出
                    }
                }
            }

            return success();
        } catch (Exception e) {
            return error("SYSTEM_500", "退出登录失败：" + e.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
