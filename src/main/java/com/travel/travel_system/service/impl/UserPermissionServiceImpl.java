package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.UserPermission;
import com.travel.travel_system.repository.UserPermissionRepository;
import com.travel.travel_system.service.UserPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

@Service
public class UserPermissionServiceImpl implements UserPermissionService {

    @Autowired
    private UserPermissionRepository userPermissionRepository;

    @Override
    public Optional<UserPermission> findByUserId(Long userId) {
        return userPermissionRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public UserPermission getOrCreateByUserId(Long userId) {
        return userPermissionRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserPermission permission = new UserPermission();
                    permission.setUserId(userId);
                    permission.setLocationEnabled(false);
                    permission.setAlbumEnabled(false);
                    permission.setCameraEnabled(false);
                    return userPermissionRepository.save(permission);
                });
    }

    @Override
    @Transactional
    public UserPermission updatePermission(Long userId, String permissionType, Boolean enabled) {
        UserPermission permission = getOrCreateByUserId(userId);
        Date now = new Date();

        switch (permissionType.toLowerCase()) {
            case "location":
                permission.setLocationEnabled(enabled);
                if (Boolean.TRUE.equals(enabled)) {
                    permission.setLocationGrantedAt(now);
                }
                break;
            case "album":
                permission.setAlbumEnabled(enabled);
                if (Boolean.TRUE.equals(enabled)) {
                    permission.setAlbumGrantedAt(now);
                }
                break;
            case "camera":
                permission.setCameraEnabled(enabled);
                if (Boolean.TRUE.equals(enabled)) {
                    permission.setCameraGrantedAt(now);
                }
                break;
            default:
                throw new IllegalArgumentException("未知的权限类型: " + permissionType);
        }

        return userPermissionRepository.save(permission);
    }

    @Override
    @Transactional
    public UserPermission updatePermissions(Long userId, Map<String, Boolean> permissions) {
        UserPermission permission = getOrCreateByUserId(userId);
        Date now = new Date();

        if (permissions.containsKey("location")) {
            permission.setLocationEnabled(permissions.get("location"));
            if (Boolean.TRUE.equals(permissions.get("location"))) {
                permission.setLocationGrantedAt(now);
            }
        }

        if (permissions.containsKey("album")) {
            permission.setAlbumEnabled(permissions.get("album"));
            if (Boolean.TRUE.equals(permissions.get("album"))) {
                permission.setAlbumGrantedAt(now);
            }
        }

        if (permissions.containsKey("camera")) {
            permission.setCameraEnabled(permissions.get("camera"));
            if (Boolean.TRUE.equals(permissions.get("camera"))) {
                permission.setCameraGrantedAt(now);
            }
        }

        return userPermissionRepository.save(permission);
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        userPermissionRepository.deleteByUserId(userId);
    }
}
