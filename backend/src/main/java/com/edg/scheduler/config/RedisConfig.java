package com.edg.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 *
 * 配置 RedisTemplate 的序列化方式：
 * - Key 使用 String 序列化（支持跨实例共享）
 * - Value 使用 Jackson JSON 序列化（支持复杂对象存储）
 *
 * 主要用于：
 * - 分布式锁（Redisson）
 * - 任务队列（Redisson RDeque）
 * - 在线用户会话（Redis Set）
 */
@Configuration
public class RedisConfig {

    /**
     * 创建 RedisTemplate 实例，配置键值序列化器
     *
     * @param connectionFactory Redis 连接工厂（由 Spring 自动注入）
     * @return 配置好的 RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 使用 String 序列化
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 使用 Jackson JSON 序列化
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}