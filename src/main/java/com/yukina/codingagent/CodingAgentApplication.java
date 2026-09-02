package com.yukina.codingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 编程智能体服务的 Spring Boot 启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CodingAgentApplication {

    /** 创建 Spring Boot 配置入口实例。 */
    public CodingAgentApplication() {
    }

    /**
     * 启动应用并初始化 HTTP 接口、Agent、工具和会话组件。
     *
     * @param args JVM 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }

}
