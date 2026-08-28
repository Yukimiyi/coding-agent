package com.yukina.codingagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证 Spring 应用上下文可以完整启动。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1"
})
class CodingAgentApplicationTests {

    /** 验证所有生产 Bean 和配置能够成功装配。 */
    @Test
    void contextLoads() {
    }

}
