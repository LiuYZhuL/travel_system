package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.User;
import com.travel.travel_system.repository.UserRepository;
import com.travel.travel_system.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private static final String CURRENT_PRIVACY_AGREEMENT_VERSION = "2026-04-23";

    @Autowired
    private UserRepository userRepository;

    //默认头像
    private static final String USER_AVATAR_URL = "/static/avatar.jpg";
    //默认昵称
    private static final String USER_NICKNAME = "未命名";

    /**
     * 根据openId查询用户
     */
    public User findByOpenId(String openId) {
        User user = userRepository.findByOpenId(openId).orElse(null);
        return user;
    }

    /**
     * 注册新用户
     */
    public User register(String openId, String unionId) {
        User user = new User();
        user.setOpenId(openId);
        user.setUnionId(unionId);
        user.setNickname(USER_NICKNAME);
        user.setAvatarUrl(USER_AVATAR_URL);
        user.setDefaultPrivacyMode(PrivacyMode.PRIVATE);
        user.setPrivacyAgreementAccepted(Boolean.FALSE);
        user.setPrivacyAgreementVersion(null);
        user.setPrivacyAgreementAcceptedAt(null);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());

        userRepository.save(user);
        return user;
    }

    @Override
    public User perfectInfo(String openId, String nickname, String avatarUrl) {
        User user = findByOpenId(openId);
        if (user != null){
            user.setNickname(nickname);
            user.setAvatarUrl(avatarUrl);
            user.setUpdatedAt(new Date());
            userRepository.save(user);
            return user;
        }
        return null;
    }

    /**
     * 根据 openId 查询或创建用户
     */
    public User findOrCreateUser(String openId, String unionId, String nickname, String avatarUrl) {
        User user = findByOpenId(openId);
        boolean isNewUser = false;
        
        if (user == null) {
            user = register(openId, unionId);
            isNewUser = true;
        } else if (unionId != null && !unionId.isEmpty() && user.getUnionId() == null) {
            user.setUnionId(unionId);
            user.setUpdatedAt(new Date());
        }
        
        if (isNewUser) {
            if (nickname != null && !nickname.isEmpty()) {
                user.setNickname(nickname);
            }
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                user.setAvatarUrl(avatarUrl);
            }
        } else {
            if (nickname != null && !nickname.isEmpty() && USER_NICKNAME.equals(user.getNickname())) {
                user.setNickname(nickname);
                user.setUpdatedAt(new Date());
            }
            if (avatarUrl != null && !avatarUrl.isEmpty() && USER_AVATAR_URL.equals(user.getAvatarUrl())) {
                user.setAvatarUrl(avatarUrl);
                user.setUpdatedAt(new Date());
            }
        }
        
        userRepository.save(user);
        return user;
    }

    @Override
    public String login(String username, String password) {
        return null;
    }

    @Override
    public Optional<User> getUserInfo(Long userId) {
        return userRepository.findById(userId);
    }

    @Override
    public String getCurrentPrivacyAgreementVersion() {
        return CURRENT_PRIVACY_AGREEMENT_VERSION;
    }

    @Override
    public boolean hasAcceptedCurrentPrivacyAgreement(User user) {
        if (user == null) {
            return false;
        }
        return Boolean.TRUE.equals(user.getPrivacyAgreementAccepted())
                && CURRENT_PRIVACY_AGREEMENT_VERSION.equalsIgnoreCase(user.getPrivacyAgreementVersion());
    }

    @Override
    public User acceptPrivacyAgreement(Long userId, String agreementVersion) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setPrivacyAgreementAccepted(Boolean.TRUE);
        user.setPrivacyAgreementVersion(
                agreementVersion != null && !agreementVersion.trim().isEmpty()
                        ? agreementVersion.trim()
                        : CURRENT_PRIVACY_AGREEMENT_VERSION
        );
        user.setPrivacyAgreementAcceptedAt(new Date());
        user.setUpdatedAt(new Date());
        return userRepository.save(user);
    }


    @Override
    public void updatePrivacySettings(Long userId, String privacyMode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        try {
            // 将 String 转换为 PrivacyMode 枚举
            PrivacyMode mode = PrivacyMode.valueOf(privacyMode.toUpperCase());
            user.setDefaultPrivacyMode(mode);
            user.setUpdatedAt(new Date());
            userRepository.save(user);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("无效的隐私模式：" + privacyMode +
                    "，有效值为：PUBLIC, MASKED, PRIVATE");
        }
    }

    @Override
    public User updateProfile(String openId, String nickname, String avatarUrl) {
        User user = findByOpenId(openId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        // 更新昵称
        if (nickname != null && !nickname.trim().isEmpty()) {
            user.setNickname(nickname.trim());
        }
        
        // 更新头像
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            user.setAvatarUrl(avatarUrl.trim());
        }
        
        user.setUpdatedAt(new Date());
        userRepository.save(user);
        return user;
    }

    @Override
    public User updateNickname(String openId, String nickname) {
        User user = findByOpenId(openId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new RuntimeException("昵称不能为空");
        }
        
        if (nickname.length() > 20) {
            throw new RuntimeException("昵称长度不能超过20个字符");
        }
        
        user.setNickname(nickname.trim());
        user.setUpdatedAt(new Date());
        userRepository.save(user);
        return user;
    }

    @Override
    public User updateAvatarUrl(String openId, String avatarUrl) {
        User user = findByOpenId(openId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            throw new RuntimeException("头像 URL 不能为空");
        }
        
        user.setAvatarUrl(avatarUrl.trim());
        user.setUpdatedAt(new Date());
        userRepository.save(user);
        return user;
    }


    @Override
    public void logout(Long userId) {

    }
}
