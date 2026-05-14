package com.edg.scheduler.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 *
 * 注册应用拦截器和视图控制器：
 * - 注册 AuthInterceptor 认证拦截器到 Spring MVC
 * - 配置拦截路径为 /api/**
 * - 排除登录注册等公开路径
 *
 * @see AuthInterceptor
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    /**
     * 注册拦截器
     *
     * 功能说明：
     * - 注册AuthInterceptor到Spring MVC
     * - 拦截所有/api/**路径
     * - 排除登录注册公开路径
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}
