package com.edg.scheduler.repository;

import com.edg.scheduler.model.Operator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 操作员数据访问层
 *
 * 提供操作员（Operator）实体的数据库操作方法
 */
@Repository
public interface OperatorRepository extends JpaRepository<Operator, String> {
    /**
     * 根据用户名查找操作员
     * @param username 用户名
     * @return 匹配的操作员（如果存在）
     */
    Optional<Operator> findByUsername(String username);

    /**
     * 根据认证 Token 查找操作员
     * @param token 认证 Token
     * @return 匹配的操作员（如果存在且有效）
     */
    Optional<Operator> findByToken(String token);
}
