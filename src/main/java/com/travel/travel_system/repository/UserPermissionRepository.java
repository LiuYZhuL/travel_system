package com.travel.travel_system.repository;

import com.travel.travel_system.model.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    Optional<UserPermission> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
