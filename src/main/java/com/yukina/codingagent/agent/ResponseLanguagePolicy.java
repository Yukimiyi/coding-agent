package com.yukina.codingagent.agent;

/** 根据用户当前任务生成一致的公开输出语言约束。 */
public final class ResponseLanguagePolicy {

    /** 工具类不允许实例化。 */
    private ResponseLanguagePolicy() {
    }

    /**
     * 判断当前任务是否包含中文汉字。
     *
     * @param task 用户当前任务
     * @return 包含任意汉字时返回 {@code true}
     */
    public static boolean prefersChinese(String task) {
        if (task == null || task.isBlank()) {
            return false;
        }
        String normalized = task.toLowerCase();
        if (explicitChineseRequested(normalized)) {
            return true;
        }
        if (explicitEnglishRequested(normalized)) {
            return false;
        }
        return task.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        );
    }

    /**
     * 判断中文任务的公开说明是否完全缺少中文。
     * 围栏代码和行内代码会先被排除，避免把源码标识符误判为回答语言。
     *
     * @param task 用户当前任务
     * @param response 候选公开文本
     * @return 中文任务的非代码文本没有汉字且含有拉丁字母时返回 {@code true}
     */
    public static boolean requiresChineseRewrite(String task, String response) {
        if (!prefersChinese(task) || response == null || response.isBlank()) {
            return false;
        }
        String prose = response
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("`[^`]*`", " ");
        boolean hasHan = prose.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        );
        boolean hasLatin = prose.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
        );
        return !hasHan && hasLatin;
    }

    /**
     * 生成发送给模型的语言约束。代码、命令和原始输出不受翻译要求影响。
     *
     * @param task 用户当前任务
     * @return 中文或英文公开输出约束
     */
    public static String instructionFor(String task) {
        String normalized = task == null ? "" : task.toLowerCase();
        if (!explicitChineseRequested(normalized) && explicitEnglishRequested(normalized)) {
            return "The latest user request explicitly asks for English. Write all user-facing prose in English. "
                    + "Keep source code, identifiers, commands, paths, protocol fields, and original error output "
                    + "unchanged when translation would reduce accuracy.";
        }
        if (prefersChinese(task)) {
            return "The latest user request contains Chinese. Write all user-facing prose in Simplified Chinese, "
                    + "including public progress text, plan goal and steps, review summary, and final answer. "
                    + "Keep source code, identifiers, commands, paths, protocol fields, and original error output "
                    + "unchanged when translation would reduce accuracy.";
        }
        return "Write all user-facing prose in the language of the latest user request. Keep source code, "
                + "identifiers, commands, paths, protocol fields, and original error output unchanged.";
    }

    /**
     * 判断文本是否包含任一候选短语。
     *
     * @param value 已规范化文本
     * @param candidates 待匹配短语
     * @return 命中任一候选短语时返回 {@code true}
     */
    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断用户是否明确要求使用英文回答。
     *
     * @param normalizedTask 已转换为小写的任务文本
     * @return 存在明确英文输出要求时返回 {@code true}
     */
    private static boolean explicitEnglishRequested(String normalizedTask) {
        return containsAny(
                normalizedTask,
                "请用英文",
                "用英文",
                "使用英文",
                "英文回答",
                "英文输出",
                "in english",
                "english only"
        );
    }

    /**
     * 判断用户是否明确要求使用中文或明确排除英文。
     *
     * @param normalizedTask 已转换为小写的任务文本
     * @return 存在明确中文输出要求时返回 {@code true}
     */
    private static boolean explicitChineseRequested(String normalizedTask) {
        return containsAny(
                normalizedTask,
                "不要用英文",
                "请用中文",
                "用中文",
                "使用中文",
                "中文回答",
                "中文输出"
        );
    }
}
