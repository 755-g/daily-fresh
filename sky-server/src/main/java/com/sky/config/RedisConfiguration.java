package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@Slf4j
@EnableAsync // 启用异步支持
public class RedisConfiguration {

    @Value("${sky.redis.host}")
    private String redisHost;
    
    @Value("${sky.redis.port}")
    private int redisPort;

    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建 redis 模板对象...");
        RedisTemplate redisTemplate = new RedisTemplate();
        //设置 redis 的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置 redis key 的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }

    /**
     * 配置 RedissonClient，用于分布式锁
     */
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        log.info("Redisson 配置完成，host:{}, port:{}", redisHost, redisPort);
        return Redisson.create(config);
    }

    /**
     * 配置异步任务线程池（用于缓存二次删除）
     */
    @Bean(name = "cacheAsyncExecutor")
    public Executor cacheAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);      // 核心线程数
        executor.setMaxPoolSize(10);      // 最大线程数
        executor.setQueueCapacity(100);   // 队列容量
        executor.setThreadNamePrefix("cache-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("异步缓存删除线程池配置完成");
        return executor;
    }
}
