package com.edg.scheduler.controller;

import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.repository.OperatorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理控制器
 *
 * 提供系统用户管理相关 API（需 ADMIN 权限）：
 * - 用户列表查询（/api/users）
 * - 用户状态更新（/api/users/{id}/status）
 * - 用户删除（/api/users/{id}）
 *
 * 注意：此控制器受 AuthInterceptor 权限拦截，仅 ADMIN 角色可访问
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private OperatorRepository operatorRepository;

    @GetMapping
    public ResponseEntity<List<UserDTO>> listUsers() {
        List<UserDTO> users = operatorRepository.findAll().stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<UserDTO> updateUserStatus(@PathVariable String id, @RequestParam boolean enabled) {
        return operatorRepository.findById(id).map(u -> {
            u.setEnabled(enabled);
            operatorRepository.save(u);
            return ResponseEntity.ok(UserDTO.fromEntity(u));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        if (operatorRepository.existsById(id)) {
            operatorRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
