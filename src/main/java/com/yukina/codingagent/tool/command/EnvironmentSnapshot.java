package com.yukina.codingagent.tool.command;

import java.time.Instant;
import java.util.List;

/**
 * 当前应用进程可见的开发环境快照。
 *
 * @param checkedAt 主机工具最后检测时间
 * @param tools 工具状态列表
 */
public record EnvironmentSnapshot(Instant checkedAt, List<EnvironmentToolStatus> tools) {

    /** 固化工具列表，保证缓存快照不可变。 */
    public EnvironmentSnapshot {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
