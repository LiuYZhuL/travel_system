package com.travel.travel_system.controller;

import com.travel.travel_system.model.User;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.service.HeatmapService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.service.UserService;
import com.travel.travel_system.service.pub.OssService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.vo.UserHeatmapVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController extends BaseController {

    @Autowired
    private UserService userService;

    @Autowired
    private TripService tripService;

    @Autowired
    private HeatmapService heatmapService;

    @Autowired
    private OssService ossService;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * 获取用户主页
     */
    @GetMapping("/home")
    public ApiResponse<?> getUserHome(HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }

            User user = userService.findByOpenId(openId);
            if (user == null) {
                return error("AUTH_002", "用户不存在");
            }

            Map<String, Object> homeData = new LinkedHashMap<>();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", user.getId());
            profile.put("nickname", user.getNickname());
            profile.put("avatarUrl", user.getAvatarUrl());
            profile.put("defaultPrivacyMode", user.getDefaultPrivacyMode() != null
                    ? user.getDefaultPrivacyMode().toString()
                    : PrivacyMode.PRIVATE.toString());
            profile.put("createdAt", formatDateTime(user.getCreatedAt()));
            homeData.put("profile", profile);
            homeData.put("stats", tripService.getUserTripStats(user.getId()));
            return success(homeData);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取用户主页数据失败：" + e.getMessage());
        }
    }

    /**
     * 获取用户基础资料
     */
    @GetMapping("/profile")
    public ApiResponse<?> getUserProfile(HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }

            User user = userService.findByOpenId(openId);
            if (user == null) {
                return error("AUTH_002", "用户不存在");
            }

            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", user.getId());
            userData.put("nickname", user.getNickname());
            userData.put("avatarUrl", user.getAvatarUrl());
            userData.put("defaultPrivacyMode", user.getDefaultPrivacyMode() != null
                    ? user.getDefaultPrivacyMode().toString()
                    : PrivacyMode.PRIVATE.toString());
            userData.put("createdAt", formatDateTime(user.getCreatedAt()));
            return success(userData);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取用户信息失败：" + e.getMessage());
        }
    }

    /**
     * 更新用户资料（昵称、头像）
     */
    @PatchMapping("/profile")
    public ApiResponse<?> updateUserProfile(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }

            String nickname = asString(requestBody.get("nickname"));
            String avatarUrl = asString(requestBody.get("avatarUrl"));
            if ((nickname == null || nickname.trim().isEmpty()) && (avatarUrl == null || avatarUrl.trim().isEmpty())) {
                return error("PARAM_001", "请提供要更新的昵称或头像");
            }
            if (nickname != null && nickname.length() > 20) {
                return error("PARAM_002", "昵称长度不能超过20个字符");
            }

            User updatedUser = userService.updateProfile(openId, nickname, avatarUrl);
            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("nickname", updatedUser.getNickname());
            userData.put("avatarUrl", updatedUser.getAvatarUrl());
            userData.put("defaultPrivacyMode", updatedUser.getDefaultPrivacyMode() != null
                    ? updatedUser.getDefaultPrivacyMode().toString()
                    : PrivacyMode.PRIVATE.toString());
            userData.put("updatedAt", formatDateTime(updatedUser.getUpdatedAt()));
            return success(userData);
        } catch (Exception e) {
            return error("SYSTEM_500", "更新用户资料失败：" + e.getMessage());
        }
    }

    /**
     * 更新用户昵称
     */
    @PatchMapping("/nickname")
    public ApiResponse<?> updateNickname(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }

            String nickname = asString(requestBody.get("nickname"));
            if (nickname == null || nickname.trim().isEmpty()) {
                return error("PARAM_001", "昵称不能为空");
            }
            if (nickname.length() > 20) {
                return error("PARAM_002", "昵称长度不能超过20个字符");
            }

            User updatedUser = userService.updateNickname(openId, nickname);
            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("nickname", updatedUser.getNickname());
            userData.put("updatedAt", formatDateTime(updatedUser.getUpdatedAt()));
            return success(userData);
        } catch (Exception e) {
            return error("SYSTEM_500", "更新昵称失败：" + e.getMessage());
        }
    }

    /**
     * 更新用户头像 URL
     */
    @PatchMapping("/avatar-url")
    public ApiResponse<?> updateAvatarUrl(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }

            String avatarUrl = asString(requestBody.get("avatarUrl"));
            if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
                return error("PARAM_001", "头像 URL 不能为空");
            }

            User updatedUser = userService.updateAvatarUrl(openId, avatarUrl);
            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", updatedUser.getId());
            userData.put("avatarUrl", updatedUser.getAvatarUrl());
            userData.put("updatedAt", formatDateTime(updatedUser.getUpdatedAt()));
            return success(userData);
        } catch (Exception e) {
            return error("SYSTEM_500", "更新头像失败：" + e.getMessage());
        }
    }

    /**
     * 上传头像
     */
    @PostMapping("/avatar")
    public ApiResponse<?> uploadAvatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        try {
            String openId = requireOpenId(request);
            if (openId == null) {
                return error("AUTH_001", "未授权访问");
            }
            if (file == null || file.isEmpty()) {
                return error("PARAM_001", "请选择要上传的头像");
            }
            long maxSize = 5 * 1024 * 1024L;
            if (file.getSize() > maxSize) {
                return error("PARAM_002", "头像大小不能超过5MB");
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return error("PARAM_003", "只支持上传图片文件");
            }

            String avatarUrl = ossService.uploadFile(file, "avatars");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", avatarUrl);
            data.put("fileName", file.getOriginalFilename());
            data.put("fileSize", file.getSize());
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "上传头像失败：" + e.getMessage());
        }
    }

    /**
     * 更新默认隐私模式
     */
    @PatchMapping("/settings/privacy-mode")
    public ApiResponse<?> updateDefaultPrivacyMode(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        return error("SYSTEM_501", "更新默认隐私模式接口暂未实现");
    }

    /**
     * 获取当前用户热力图
     */
    @GetMapping("/heatmap")
    public ApiResponse<?> getUserHeatmap(HttpServletRequest request,
                                         @RequestParam(required = false) String scope,
                                         @RequestParam(required = false) Integer gridMeters) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                return error("AUTH_001", "未授权访问");
            }
            UserHeatmapVO heatmap = heatmapService.buildUserHeatmap(userId, scope, gridMeters);
            return success(heatmap);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取热力图失败：" + e.getMessage());
        }
    }

    private String requireOpenId(HttpServletRequest request) {
        Object openId = request.getAttribute("openId");
        if (openId == null) {
            return null;
        }
        String value = String.valueOf(openId);
        return value.trim().isEmpty() ? null : value;
    }

    private String formatDateTime(java.util.Date date) {
        return date == null ? null : DATE_FORMAT.format(date);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
