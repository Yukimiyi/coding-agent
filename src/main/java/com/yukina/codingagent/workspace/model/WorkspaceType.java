package com.yukina.codingagent.workspace.model;

/**
 * 工作空间的文件来源与生命周期类型。
 */
public enum WorkspaceType {

    /** 文件由应用托管在项目根目录的 .tmp 存储中。 */
    MANAGED,

    /** 文件位于用户选择的真实本地目录中。 */
    LOCAL
}
