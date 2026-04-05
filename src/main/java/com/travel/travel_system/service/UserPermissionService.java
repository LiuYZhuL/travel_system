package com.travel.travel_system.service;

import com.travel.travel_system.model.UserPermission;

import java.util.Map;
import java.util.Optional;

public interface UserPermissionService {

    Optional<UserPermission> findByUserId(Long userId);

    UserPermission getOrCreateByUserId(Long userId);

    UserPermission updatePermission(Long userId, String permissionType, Boolean enabled);

    UserPermission updatePermissions(Long userId, Map<String, Boolean> permissions);

    void deleteByUserId(Long userId);
}
