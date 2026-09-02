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
        /** 来自应用配置的优先搜索目录。 */
        CONFIGURED_PATH,
        /** 来自当前进程继承的系统 PATH。 */
        SYSTEM_PATH,
        /** 来自当前会话项目根目录的构建 Wrapper。 */
        PROJECT_WRAPPER,
        /** 未找到、被白名单禁用或版本检测失败。 */
        UNAVAILABLE
    }
}
