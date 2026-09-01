package com.yukina.codingagent.deepseek;

/**
 * 表示调用模型前发现必要的 DeepSeek 配置缺失。
 */
public class DeepSeekConfigurationException extends RuntimeException {

    /**
     * 创建配置异常。
     *
     * @param message 缺失或无效配置说明
     */
    public DeepSeekConfigurationException(String message) {
        super(message);
    }
}
