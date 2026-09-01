package com.yukina.codingagent.deepseek;

/**
 * 接收 DeepSeek 流式响应中的公开回答增量。
 * 模型的原始 reasoning_content 仅用于协议聚合，不通过此接口暴露。
 */
@FunctionalInterface
public interface DeepSeekStreamObserver {

    /** 不处理增量的默认观察器。 */
    DeepSeekStreamObserver NONE = delta -> {
    };

    /**
     * 在收到一段公开回答文本时触发。
     *
     * @param delta 新收到的公开回答文本片段
     */
    void onContentDelta(String delta);
}
