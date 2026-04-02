package com.travel.travel_system.service;

public interface PrivacyService {

    /**
     * 更新用户的隐私模式
     * @param userId 用户 ID
     * @param privacyMode 隐私模式
     */
    void updatePrivacyMode(Long userId, String privacyMode);

}
