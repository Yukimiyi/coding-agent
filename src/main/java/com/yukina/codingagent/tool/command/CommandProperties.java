package com.yukina.codingagent.tool.command;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 本地命令工具的执行边界和可执行程序白名单。
 *
 * @param defaultTimeout 未指定超时时使用的默认值
 * @param maxTimeout 单次命令允许的最大超时
 * @param terminationGrace 进程正常终止失败后的宽限时间
 * @param maxOutputChars stdout 和 stderr 各自保留的最大字符数
 * @param maxArguments 单次命令允许的最大参数数量
 * @param maxInputBytes 单次命令标准输入允许的最大字节数
 * @param allowedExecutables 允许执行的程序名称
 * @param searchPaths 除系统 PATH 外由服务端信任的程序查找目录
 */
@ConfigurationProperties(prefix = "agent.command")
public record CommandProperties(
        Duration defaultTimeout,
        Duration maxTimeout,
        Duration terminationGrace,
        int maxOutputChars,
        int maxArguments,
        int maxInputBytes,
        List<String> allowedExecutables,
        List<Path> searchPaths
) {
    /**
     * 校验命令边界并规范化可执行程序白名单和搜索路径。
     *
     * @throws IllegalArgumentException 超时、数量上限或白名单配置不合法时抛出
     */
    public CommandProperties {
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("agent.command.default-timeout must be positive");
        }
        if (maxTimeout == null || maxTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("agent.command.max-timeout must not be less than the default timeout");
        }
        if (terminationGrace == null || terminationGrace.isZero() || terminationGrace.isNegative()) {
            throw new IllegalArgumentException("agent.command.termination-grace must be positive");
        }
        if (maxOutputChars <= 0 || maxArguments <= 0 || maxInputBytes <= 0) {
            throw new IllegalArgumentException("agent command limits must be positive");
        }
        if (allowedExecutables == null || allowedExecutables.isEmpty()) {
            throw new IllegalArgumentException("agent.command.allowed-executables must not be empty");
        }
        allowedExecutables = allowedExecutables.stream()
                .map(CommandProperties::normalizeExecutable)
                .distinct()
                .toList();
        searchPaths = searchPaths == null
                ? List.of()
                : searchPaths.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .distinct()
                        .toList();
    }

    /**
     * 判断规范化后的程序名称是否位于白名单中。
     *
     * @param executable 程序名或路径，可以包含 Windows 可执行扩展名
     * @return 规范化名称存在于白名单时返回 {@code true}
     */
    public boolean isAllowed(String executable) {
        return allowedExecutables.contains(normalizeExecutable(executable));
    }

    /**
     * 将路径和 Windows 可执行扩展名转换为稳定的程序名称。
     *
     * @param executable 原始程序名或路径；可以为 {@code null}
     * @return 小写且不含目录及 exe、cmd、bat、com 扩展名的名称；空值返回空字符串
     */
    public static String normalizeExecutable(String executable) {
        if (executable == null || executable.isBlank()) {
            return "";
        }
        String normalized = executable.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String fileName = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        String lowerCase = fileName.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".exe", ".cmd", ".bat", ".com")) {
            if (lowerCase.endsWith(extension)) {
                return lowerCase.substring(0, lowerCase.length() - extension.length());
            }
        }
        return lowerCase;
    }
}
