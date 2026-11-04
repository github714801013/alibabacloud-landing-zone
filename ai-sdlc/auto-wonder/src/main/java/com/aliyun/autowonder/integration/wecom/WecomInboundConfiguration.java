package com.aliyun.autowonder.integration.wecom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 群回调解析与连接读线程隔离，避免等待回复确认时阻塞收帧。 */
@Configuration(proxyBeanMethods = false)
public class WecomInboundConfiguration {

    @Bean(name = "wecomInboundExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor wecomInboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("wecom-inbound-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
