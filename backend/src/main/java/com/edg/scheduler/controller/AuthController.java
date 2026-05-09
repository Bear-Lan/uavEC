package com.edg.scheduler.controller;

import com.edg.scheduler.model.Operator;
import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.repository.OperatorRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.edg.scheduler.service.UserSessionService userSessionService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        double x = ((Number) body.getOrDefault("x", 50)).doubleValue();
        double y = ((Number) body.getOrDefault("y", 50)).doubleValue();

        if (operatorRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "用户名已存在"));
        }

        Operator operator = new Operator(username, passwordEncoder.encode(password), x, y);
        operatorRepository.save(operator);
        return ResponseEntity.ok(UserDTO.fromEntity(operator));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");

        Operator operator = operatorRepository.findByUsername(username).orElse(null);
        if (operator == null || !passwordEncoder.matches(password, operator.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }

        if (!operator.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("message", "该账户已被禁用"));
        }

        String token = java.util.UUID.randomUUID().toString();
        operator.setToken(token);
        // Token expires in 24 hours
        operator.setTokenExpiryTime(System.currentTimeMillis() + 24 * 60 * 60 * 1000);
        operator.setLastLoginTime(System.currentTimeMillis());
        operatorRepository.save(operator);

        // 记录用户上线并广播在线用户列表
        userSessionService.userLogin(username);

        UserDTO dto = UserDTO.fromEntity(operator);
        return ResponseEntity.ok(Map.of("user", dto, "token", token));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDTO> getMe(@RequestParam String username) {
        return operatorRepository.findByUsername(username)
                .map(u -> ResponseEntity.ok(UserDTO.fromEntity(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            userSessionService.userLogout(username);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/online-users")
    public ResponseEntity<List<com.edg.scheduler.service.UserSessionService.OnlineUserInfo>> getOnlineUsers() {
        return ResponseEntity.ok(userSessionService.getOnlineUsers());
    }

    @PostMapping("/online")
    public ResponseEntity<?> announceOnline(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            userSessionService.userLogin(username);
        }
        return ResponseEntity.ok(Map.of("online", true));
    }

    @Data
    public static class UpdateProfileRequest {
        private String currentUsername;
        private String newUsername;
        private double x;
        private double y;
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody UpdateProfileRequest req) {
        Operator existing = operatorRepository.findByUsername(req.getCurrentUsername()).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Current user not found"));
        }

        if (!req.getCurrentUsername().equals(req.getNewUsername())) {
            if (operatorRepository.findByUsername(req.getNewUsername()).isPresent()) {
                return ResponseEntity.status(400).body(Map.of("error", "Username already exists"));
            }
        }

        existing.setUsername(req.getNewUsername());
        existing.setX(req.getX());
        existing.setY(req.getY());
        operatorRepository.save(existing);

        return ResponseEntity.ok(UserDTO.fromEntity(existing));
    }
}
