package com.travel.travel_system.service;

import com.travel.travel_system.model.User;

import java.util.Optional;


public interface UserService {

    /**
     * 根据openId查询用户
     */
     User findByOpenId(String openId);

    /**
     * 注册新用户
     */
     User register(String openId, String unionId);

    /**
     * 用户基本信息完善
     * @param openId 微信 OpenID
     * @param nickname 昵称
     * @param avatarUrl 头像 URL
     * @return 用户信息
     */
    User perfectInfo(String openId, String nickname, String avatarUrl);

    /**
     * 根据 openId 查询或创建用户
     * @param openId 微信 OpenID
     * @param unionId 微信 UnionID（可选）
     * @param nickname 用户昵称（可选）
     * @param avatarUrl 用户头像（可选）
     * @return 用户信息
     */
    User findOrCreateUser(String openId, String unionId, String nickname, String avatarUrl);

    /**
     * 根据 openId 查询或创建用户（兼容旧接口）
     * @param openId 微信 OpenID
     * @param unionId 微信 UnionID（可选）
     * @return 用户信息
     */
    default User findOrCreateUser(String openId, String unionId) {
        return findOrCreateUser(openId, unionId, null, null);
    }

    /**
     * 用户登录，生成并返回 JWT
     * @param username 用户名
     * @param password 密码
     * @return JWT Token
     */
    String login(String username, String password);

    /**
     * 获取用户信息
     * @param userId 用户 ID
     * @return 用户信息
     */
    Optional<User> getUserInfo(Long userId);

    /**
     * 更新用户隐私设置
     * @param userId 用户 ID
     * @param privacyMode 隐私模式
     */
    void updatePrivacySettings(Long userId, String privacyMode);

    /**
     * 更新用户资料（昵称、头像）
     * @param openId 微信 OpenID
     * @param nickname 昵称
     * @param avatarUrl 头像 URL
     * @return 更新后的用户信息
     */
    User updateProfile(String openId, String nickname, String avatarUrl);

    /**
     * 更新用户昵称
     * @param openId 微信 OpenID
     * @param nickname 昵称
     * @return 更新后的用户信息
     */
    User updateNickname(String openId, String nickname);

    /**
     * 更新用户头像 URL
     * @param openId 微信 OpenID
     * @param avatarUrl 头像 URL
     * @return 更新后的用户信息
     */
    User updateAvatarUrl(String openId, String avatarUrl);

    /**
     * 注销用户
     * @param userId 用户 ID
     */
    void logout(Long userId);
}