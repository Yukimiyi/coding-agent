package com.yukina.codingagent.agent.perception;

import java.util.List;
import java.util.Map;

/**
 * 规划前由程序采集的受限项目快照，不包含模型隐藏推理内容。
 *
 * @param empty 工作目录中是否没有可见项目文件
 * @param files 有上限的工作区相对路径列表
 * @param descriptors 构建文件和 README 等关键文本摘要
 * @param environmentSummary 当前宿主可用执行环境摘要
 * @param truncated 文件列表或描述文件是否因上限被截断
 */
public record ProjectSnapshot(
        boolean empty,
        List<String> files,
        Map<String, String> descriptors,
        String environmentSummary,
        boolean truncated
) {

    /** 复制集合，保证快照不会被调用方修改。 */
    public ProjectSnapshot {
        files = files == null ? List.of() : List.copyOf(files);
        descriptors = descriptors == null ? Map.of() : Map.copyOf(descriptors);
        environmentSummary = environmentSummary == null ? "" : environmentSummary;
    }

    /**
     * 生成不包含文件内容和宿主机路径的感知摘要。
     *
     * @return 可通过 SSE 展示的简短感知结果
     */
    public String publicSummary() {
        if (empty) {
            return "已感知空项目和当前执行环境";
        }
        return "已感知项目结构，共发现 " + files.size() + (truncated ? "+" : "") + " 个条目";
    }
}
