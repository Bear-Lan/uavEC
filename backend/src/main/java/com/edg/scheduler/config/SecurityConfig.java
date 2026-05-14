package com.edg.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全配置
 *
 * 提供密码加密相关的 Spring Security 组件：
 * - PasswordEncoder: BCrypt 密码加密器
 *
 * BCrypt 特点：
 * - 内置盐值，相同密码每次加密结果不同
 * - 迭代次数可配置，默认10轮
 * - 单向加密，不可解密
 */
@Configuration
public class SecurityConfig {

    /**
     * 创建密码编码器
     *
     * @return BCrypt密码编码器实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
