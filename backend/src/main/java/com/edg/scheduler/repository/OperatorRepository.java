package com.edg.scheduler.repository;

import com.edg.scheduler.model.Operator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 操作员数据访问层
 *
 * 继承JpaRepository，提供操作员（Operator）实体的数据库操作：
 * - findByUsername: 根据用户名查找操作员（用于登录验证）
 * - findByToken: 根据认证令牌查找操作员（用于Token认证）
 *
 * 实体说明：
 * - 操作员是系统用户，包含ADMIN和OPERATOR两种角色
 * - 密码使用BCrypt加密存储
 */
@Repository
public interface OperatorRepository extends JpaRepository<Operator, String> {
    /**
     * 根据用户名查找操作员
     *
     * @param username 用户名
     * @return 操作员（如果存在）
     */
    Optional<Operator> findByUsername(String username);

    /**
     * 根据认证令牌查找操作员
     *
     * @param token 认证令牌
     * @return 操作员（如果存在且令牌有效）
     */
    Optional<Operator> findByToken(String token);
}