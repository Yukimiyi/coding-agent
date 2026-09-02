package com.yukina.codingagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供最小化的服务存活检查接口。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /** 创建无状态的健康检查控制器。 */
    public HealthController() {
    }

    /**
     * 返回应用进程的存活状态。
     *
     * @return 固定字符串 {@code ok}
     */
    @GetMapping
    public String healthCheck() {
        return "ok";
    }
}
