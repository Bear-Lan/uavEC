package com.edg.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 跨域资源配置
 *
 * 配置允许所有来源、所有请求头、所有HTTP方法的跨域请求
 * 使用通配符模式以支持前后端分离架构的开发需求
 *
 * 主要用途：
 * - 开发环境：允许前端开发服务器访问后端API
 * - 生产环境：可配合NGINX反向代理实现跨域
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS Documentation</a>
 */
@Configuration
public class CorsConfig {

    /**
     * 配置CORS跨域过滤器
     *
     * 功能说明：
     * - 允许所有来源（AllowedOriginPatterns: *）
     * - 允许所有请求头（AllowedHeaders: *）
     * - 允许所有HTTP方法（AllowedMethods: *）
     * - 允许携带凭证（AllowCredentials: true）
     * - 应用到所有路径（/**）
     *
     * @return CorsFilter实例
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
