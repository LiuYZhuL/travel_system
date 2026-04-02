package com.travel.travel_system.controller;

import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.User;
import com.travel.travel_system.service.UserService;
import com.travel.travel_system.service.pub.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private OssService ossService;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * 获取用户主页
     */
    @GetMapping("/home")
    public Map<String, Object> getUserHome(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从请求属性中获取 openId（由拦截器设置）
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            // 根据 openId 查询用户
            User user = userService.findByOpenId(openId);

            if (user == null) {
                response.put("code", "AUTH_002");
                response.put("message", "用户不存在");
                return response;
            }

            // 构建用户主页数据
            Map<String, Object> homeData = new HashMap<>();

            // 1. 用户资料
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", user.getId());
            profile.put("nickname", user.getNickname());
            profile.put("avatarUrl", user.getAvatarUrl());
            if (user.getDefaultPrivacyMode() != null) {
                profile.put("defaultPrivacyMode", user.getDefaultPrivacyMode().toString());
            } else {
                profile.put("defaultPrivacyMode", PrivacyMode.PRIVATE.toString());
            }
            profile.put("createdAt", dateFormat.format(user.getCreatedAt()));
            homeData.put("profile", profile);

            // 2. 用户统计信息
            Map<String, Object> stats = new HashMap<>();
            stats.put("tripCount", 5);
            stats.put("totalDistanceM", 12500);
            stats.put("totalDistanceText", "12.5 km");
            stats.put("totalDurationSec", 7200);
            stats.put("totalDurationText", "2 小时 0 分钟");
            stats.put("totalPhotoCount", 28);
            stats.put("totalVideoCount", 5);
            homeData.put("stats", stats);

            // 3. 进行中的行程
            Map<String, Object> activeTrip = new HashMap<>();
            activeTrip.put("tripId", 1001);
            activeTrip.put("title", "杭州西湖之旅");
            activeTrip.put("coverUrl", "/static/logo.png");
            activeTrip.put("startTime", dateFormat.format(new Date()));
            activeTrip.put("status", "ACTIVE");
            homeData.put("activeTrip", activeTrip);

            // 4. 最近的行程
            Map<String, Object> latestTrip = new HashMap<>();
            latestTrip.put("tripId", 1000);
            latestTrip.put("title", "上海外滩之行");
            latestTrip.put("coverUrl", "/static/logo.png");
            latestTrip.put("startTime", "2026-03-10 09:00:00");
            latestTrip.put("endTime", "2026-03-10 18:00:00");
            latestTrip.put("status", "FINISHED");
            latestTrip.put("distanceText", "8.2 km");
            homeData.put("latestTrip", latestTrip);

            // 5. 权限状态
            Map<String, Object> permissionState = new HashMap<>();
            permissionState.put("locationAuthorized", true);
            permissionState.put("backgroundLocationEnabled", true);
            permissionState.put("albumAuthorized", true);
            permissionState.put("cameraAuthorized", true);
            permissionState.put("lastSyncTime", dateFormat.format(new Date()));
            permissionState.put("collecting", true);
            homeData.put("permissionState", permissionState);

            // 6. 设置信息
            Map<String, Object> settings = new HashMap<>();
            settings.put("defaultPrivacyMode", user.getDefaultPrivacyMode() != null ? user.getDefaultPrivacyMode().toString() : PrivacyMode.PRIVATE.toString());
            settings.put("canDeleteAllData", true);
            settings.put("canLogout", true);
            homeData.put("settings", settings);

            // 7. 热力图数据
            Map<String, Object> heatmap = new HashMap<>();
            heatmap.put("userId", user.getId());
            heatmap.put("scope", "ALL");
            // 模拟热力图点数据
            List<Map<String, Object>> points = new ArrayList<>();
            points.add(java.util.Map.of("lat", 30.2741, "lng", 120.1551, "weight", 10));
            points.add(java.util.Map.of("lat", 31.2304, "lng", 121.4737, "weight", 8));
            points.add(java.util.Map.of("lat", 23.1291, "lng", 113.2644, "weight", 6));
            heatmap.put("points", points);
            homeData.put("heatmap", heatmap);

            response.put("code", "0");
            response.put("message", "success");
            response.put("data", homeData);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "获取用户主页数据失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 获取用户基础资料
     */
    @GetMapping("/profile")
    public Map<String, Object> getUserProfile(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从请求属性中获取 openId（由拦截器设置）
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            // 根据 openId 查询用户
            User user = userService.findByOpenId(openId);

            if (user == null) {
                response.put("code", "AUTH_002");
                response.put("message", "用户不存在");
                return response;
            }

            // 构建用户信息响应
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", user.getId());
            userData.put("nickname", user.getNickname());
            userData.put("avatarUrl", user.getAvatarUrl());

            // 空指针保护
            if (user.getDefaultPrivacyMode() != null) {
                userData.put("defaultPrivacyMode", user.getDefaultPrivacyMode().toString());
            } else {
                userData.put("defaultPrivacyMode", PrivacyMode.PRIVATE.toString());
            }

            userData.put("createdAt", dateFormat.format(user.getCreatedAt()));

            response.put("code", "0");
            response.put("message", "success");
            response.put("data", userData);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "获取用户信息失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 更新用户资料（昵称、头像）
     */
    @PatchMapping("/profile")
    public Map<String, Object> updateUserProfile(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从请求属性中获取 openId（由拦截器设置）
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            // 获取请求参数
            String nickname = (String) requestBody.get("nickname");
            String avatarUrl = (String) requestBody.get("avatarUrl");

            // 至少需要提供一个更新字段
            if ((nickname == null || nickname.trim().isEmpty()) && 
                (avatarUrl == null || avatarUrl.trim().isEmpty())) {
                response.put("code", "PARAM_001");
                response.put("message", "请提供要更新的昵称或头像");
                return response;
            }

            // 昵称长度校验
            if (nickname != null && nickname.length() > 20) {
                response.put("code", "PARAM_002");
                response.put("message", "昵称长度不能超过20个字符");
                return response;
            }

            // 更新用户资料
            User updatedUser = userService.updateProfile(openId, nickname, avatarUrl);

            // 构建响应数据
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("nickname", updatedUser.getNickname());
            userData.put("avatarUrl", updatedUser.getAvatarUrl());
            if (updatedUser.getDefaultPrivacyMode() != null) {
                userData.put("defaultPrivacyMode", updatedUser.getDefaultPrivacyMode().toString());
            } else {
                userData.put("defaultPrivacyMode", PrivacyMode.PRIVATE.toString());
            }
            userData.put("updatedAt", dateFormat.format(updatedUser.getUpdatedAt()));

            response.put("code", "0");
            response.put("message", "更新成功");
            response.put("data", userData);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "更新用户资料失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 更新用户昵称
     */
    @PatchMapping("/nickname")
    public Map<String, Object> updateNickname(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            String nickname = (String) requestBody.get("nickname");

            if (nickname == null || nickname.trim().isEmpty()) {
                response.put("code", "PARAM_001");
                response.put("message", "昵称不能为空");
                return response;
            }

            if (nickname.length() > 20) {
                response.put("code", "PARAM_002");
                response.put("message", "昵称长度不能超过20个字符");
                return response;
            }

            User updatedUser = userService.updateNickname(openId, nickname);

            Map<String, Object> userData = new HashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("nickname", updatedUser.getNickname());
            userData.put("updatedAt", dateFormat.format(updatedUser.getUpdatedAt()));

            response.put("code", "0");
            response.put("message", "更新成功");
            response.put("data", userData);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "更新昵称失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 更新用户头像 URL
     */
    @PatchMapping("/avatar-url")
    public Map<String, Object> updateAvatarUrl(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            String avatarUrl = (String) requestBody.get("avatarUrl");

            if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
                response.put("code", "PARAM_001");
                response.put("message", "头像 URL 不能为空");
                return response;
            }

            User updatedUser = userService.updateAvatarUrl(openId, avatarUrl);

            Map<String, Object> userData = new HashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("avatarUrl", updatedUser.getAvatarUrl());
            userData.put("updatedAt", dateFormat.format(updatedUser.getUpdatedAt()));

            response.put("code", "0");
            response.put("message", "更新成功");
            response.put("data", userData);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "更新头像失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 上传头像
     */
    @PostMapping("/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从请求属性中获取 openId（由拦截器设置）
            String openId = (String) request.getAttribute("openId");

            if (openId == null || openId.isEmpty()) {
                response.put("code", "AUTH_001");
                response.put("message", "未授权访问");
                return response;
            }

            // 文件校验
            if (file == null || file.isEmpty()) {
                response.put("code", "PARAM_001");
                response.put("message", "请选择要上传的头像");
                return response;
            }

            // 文件大小校验（最大5MB）
            long maxSize = 5 * 1024 * 1024;
            if (file.getSize() > maxSize) {
                response.put("code", "PARAM_002");
                response.put("message", "头像大小不能超过5MB");
                return response;
            }

            // 文件类型校验
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                response.put("code", "PARAM_003");
                response.put("message", "只支持上传图片文件");
                return response;
            }

            // 上传到OSS
            String avatarUrl = ossService.uploadFile(file, "avatars");

            // 构建响应
            Map<String, Object> data = new HashMap<>();
            data.put("url", avatarUrl);
            data.put("fileName", file.getOriginalFilename());
            data.put("fileSize", file.getSize());

            response.put("code", "0");
            response.put("message", "上传成功");
            response.put("data", data);

        } catch (Exception e) {
            response.put("code", "SYSTEM_500");
            response.put("message", "上传头像失败：" + e.getMessage());
        }

        return response;
    }

    /**
     * 更新默认隐私模式
     */
    @PatchMapping("/settings/privacy-mode")
    public Map<String, Object> updateDefaultPrivacyMode(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        return null;
    }

    /**
     * 获取权限状态
     */
    @GetMapping("/permission-state")
    public Map<String, Object> getPermissionState(HttpServletRequest request) {
        return null;
    }

    /**
     * 获取当前用户热力图
     */
    @GetMapping("/heatmap")
    public Map<String, Object> getUserHeatmap(HttpServletRequest request) {
        return null;
    }
}
