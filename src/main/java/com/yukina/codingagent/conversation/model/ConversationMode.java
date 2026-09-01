package com.yukina.codingagent.conversation.model;

/**
 * 决定会话是否拥有本地项目目录和 Agent 工具。
 */
public enum ConversationMode {
    /** 仅进行模型对话，不创建目录，也不提供本地工具。 */
    CHAT,

    /** 自动创建会话目录，并允许 Agent 读写、执行和交付项目。 */
    CODE
}
