package com.yukina.codingagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证公开输出语言跟随当前用户任务。 */
class ResponseLanguagePolicyTest {

    /** 中文和中英混合任务都应优先使用简体中文。 */
    @Test
    void detectsChineseTaskAndBuildsChineseInstruction() {
        assertTrue(ResponseLanguagePolicy.prefersChinese("请修复 Calculator.java"));
        assertTrue(ResponseLanguagePolicy.instructionFor("请修复 Calculator.java").contains("Simplified Chinese"));
        assertTrue(ResponseLanguagePolicy.instructionFor("不要用英文回答").contains("Simplified Chinese"));
    }

    /** 纯英文任务应继续遵循用户的英文表达。 */
    @Test
    void keepsEnglishTaskLanguage() {
        assertFalse(ResponseLanguagePolicy.prefersChinese("Fix Calculator.java"));
        assertFalse(ResponseLanguagePolicy.prefersChinese("请用英文回答这个问题"));
        assertTrue(ResponseLanguagePolicy.instructionFor("Fix Calculator.java").contains("latest user request"));
        assertTrue(ResponseLanguagePolicy.instructionFor("请用英文回答这个问题").contains("explicitly asks for English"));
    }

    /** 仅检查公开说明，代码块中的英文标识符不应触发中文重写。 */
    @Test
    void detectsEnglishProseButIgnoresCodeBlocks() {
        assertTrue(ResponseLanguagePolicy.requiresChineseRewrite("请修复项目", "Updated the project successfully."));
        assertFalse(ResponseLanguagePolicy.requiresChineseRewrite(
                "请输出代码",
                "```java\npublic class Main {}\n```"
        ));
    }
}
