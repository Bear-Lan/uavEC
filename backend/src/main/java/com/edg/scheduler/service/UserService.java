package com.edg.scheduler.service;

import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.repository.OperatorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户管理服务
 *
 * 核心职责：
 * - 用户列表：获取所有用户信息（脱敏）
 * - 用户状态：启用/禁用用户账户
 * - 用户删除：删除指定用户（软删除考虑）
 *
 * 事务策略：
 * - listAllUsers: @Transactional(readOnly=true)
 * - updateUserStatus: @Transactional
 * - deleteUser: @Transactional
 */
@Slf4j
@Service
public class UserService {

    @Autowired
    private OperatorRepository operatorRepository;

    /**
     * 获取所有用户列表
     * @return 所有用户的 DTO 列表
     */
    @Transactional(readOnly = true)
    public List<UserDTO> listAllUsers() {
        return operatorRepository.findAll().stream()
                .map(UserDTO::fromEntity)
                .toList();
    }

    /**
     * 更新用户启用/禁用状态
     * @param id 用户 ID
     * @param enabled 是否启用
     * @return 更新后的用户 DTO
     * @throws IllegalArgumentException 用户不存在
     */
    @Transactional
    public UserDTO updateUserStatus(String id, boolean enabled) {
        return operatorRepository.findById(id)
                .map(u -> {
                    u.setEnabled(enabled);
                    operatorRepository.save(u);
                    log.info("User {} status updated: enabled={}", id, enabled);
                    return UserDTO.fromEntity(u);
                })
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    /**
     * 删除用户
     * @param id 用户 ID
     * @return 是否删除成功
     */
    @Transactional
    public boolean deleteUser(String id) {
        if (operatorRepository.existsById(id)) {
            operatorRepository.deleteById(id);
            log.info("User deleted: {}", id);
            return true;
        }
        return false;
    }
}