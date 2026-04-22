package com.travel.travel_system.service.impl;

import com.travel.travel_system.service.PrivacyService;
import com.travel.travel_system.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PrivacyServiceImpl implements PrivacyService {

    @Autowired
    private UserService userService;

    @Override
    public void updatePrivacyMode(Long userId, String privacyMode) {
        userService.updatePrivacySettings(userId, privacyMode);
    }
}
