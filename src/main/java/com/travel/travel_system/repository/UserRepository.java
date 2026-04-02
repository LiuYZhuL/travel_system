package com.travel.travel_system.repository;

import com.travel.travel_system.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根据 OpenID 查询用户
     */
    Optional<User> findByOpenId(String openId);

    /**
     * 根据 UnionID 查询用户
     */
    Optional<User> findByUnionId(String unionId);

    /**
     * 检查 OpenID 是否存在
     */
    boolean existsByOpenId(String openId);

    /**
     * 检查 UnionID 是否存在
     */
    boolean existsByUnionId(String unionId);

    /**
     * 根据昵称模糊查询用户
     */
    @Query("SELECT u FROM User u WHERE u.nickname LIKE %:keyword%")
    java.util.List<User> findByNicknameContaining(@Param("keyword") String keyword);

    /**
     * 统计用户总数
     */
    long count();
}
