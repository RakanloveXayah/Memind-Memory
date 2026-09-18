package com.trae.memind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MemindApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemindApplication.class, args);
    }

    /** 记忆抽取与 Insight Tree 整合专用线程池：与请求线程隔离，不阻塞对话主链路。 */
    @Bean("memindExecutor")
    public TaskExecutor memindExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("memind-")
                .corePoolSize(4)
                .maxPoolSize(16)
                .queueCapacity(500)
                .build();
    }
}