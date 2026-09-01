package com.yukina.codingagent.tool.command;

/**
 * 一个开发工具在当前主机和项目中的可用状态。
 *
 * @param id 稳定工具标识
 * @param name 前端展示名称
 * @param available 是否可执行
 * @param command Agent 应使用的精确命令名
 * @param version 探测到的简短版本信息
 * @param source 工具来源
 * @param message 补充状态说明
 * @param installHint 缺失时的安装建议
 */
public record EnvironmentToolStatus(
        String id,
        String name,
        boolean available,
        String command,
        String version,
        Source source,
        String message,
        String installHint
) {

    /** 命令来源，不暴露宿主机绝对路径。 */
    public enum Source {
        CONFIGURED_PATH,
        SYSTEM_PATH,
        PROJECT_WRAPPER,
        UNAVAILABLE
    }
}
