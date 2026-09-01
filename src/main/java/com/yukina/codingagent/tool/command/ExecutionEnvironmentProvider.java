package com.yukina.codingagent.tool.command;

/** 为 Agent 系统提示词提供当前运行环境的公开能力摘要。 */
public interface ExecutionEnvironmentProvider {

    /**
     * 返回不包含绝对路径和敏感环境变量的能力摘要。
     *
     * @return 可安全加入 Agent 系统提示词的环境能力文本
     */
    String agentSummary();
}
