package com.yukina.codingagent.agent.plan;

import java.util.Set;

/**
 * 计划步骤完成时需要的工具证据类型。
 */
public enum PlanEvidenceType {
    /** 读取、列举或搜索项目内容。 */
    INSPECTION(Set.of("read_file", "list_files", "search_text")),
    /** 写入、精确修改或删除项目文件。 */
    MUTATION(Set.of("write_file", "edit_file", "delete_file")),
    /** 通过退出码为零且未超时的命令执行证明编译、测试或其他验证成功。 */
    VERIFICATION(Set.of("execute_command")),
    /** Planner 降级时接受任意成功的非计划工具调用。 */
    GENERAL(Set.of());

    /** 可以证明该类步骤完成的工具名称集合。 */
    private final Set<String> toolNames;

    /**
     * 创建证据类型。
     *
     * @param toolNames 可接受的工具名称；空集合表示由类型自行决定
     */
    PlanEvidenceType(Set<String> toolNames) {
        this.toolNames = toolNames;
    }

    /**
     * 判断工具是否能证明当前类型的步骤完成。
     *
     * @param toolName 实际工具名称
     * @return GENERAL 或工具属于对应类型时返回 {@code true}
     */
    public boolean accepts(String toolName) {
        return this == GENERAL || toolNames.contains(toolName);
    }
}
