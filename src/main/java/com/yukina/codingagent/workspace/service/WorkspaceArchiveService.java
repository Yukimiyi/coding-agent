package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将受管项目安全地打包为可下载的 ZIP 文件。
 */
@Service
public class WorkspaceArchiveService {

    private final WorkspaceService workspaceService;

    /** 创建项目归档服务。 */
    public WorkspaceArchiveService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 校验项目并创建稳定的归档文件清单。
     * 符号链接不会进入清单，也不会被递归遍历。
     */
    public WorkspaceArchive prepare(String workspaceId) {
        Workspace workspace = workspaceService.get(workspaceId);
        if (workspace.type() != WorkspaceType.MANAGED) {
            throw new WorkspaceConflictException("Only managed projects can be downloaded");
        }
        Path root = workspaceService.rootPath(workspace);
        try (var paths = Files.walk(root)) {
            List<Path> files = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted((left, right) -> archivePath(root, left).compareTo(archivePath(root, right)))
                    .toList();
            return new WorkspaceArchive(workspace, root, files);
        } catch (IOException exception) {
            throw new WorkspaceConflictException("Unable to read project files for download");
        }
    }

    /** 将已准备的项目文件清单流式写入 ZIP 输出流。 */
    public void write(WorkspaceArchive archive, OutputStream outputStream) {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            for (Path file : archive.files()) {
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(archive.root()) || Files.isSymbolicLink(file)) {
                    throw new WorkspaceConflictException("Project file is outside the workspace");
                }
                ZipEntry entry = new ZipEntry(archivePath(archive.root(), file));
                entry.setLastModifiedTime(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
                zip.putNextEntry(entry);
                Files.copy(realFile, zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException exception) {
            throw new WorkspaceConflictException("Unable to create project archive");
        }
    }

    /** 生成不包含目录分隔符或控制字符的下载文件名。 */
    public String fileName(WorkspaceArchive archive) {
        String safeName = archive.workspace().name()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .strip();
        return (safeName.isBlank() ? "project" : safeName) + ".zip";
    }

    /** 将文件转换为使用正斜杠的工作空间相对 ZIP 路径。 */
    private static String archivePath(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new WorkspaceConflictException("Project file is outside the workspace");
        }
        return relative.toString().replace('\\', '/');
    }

    /** 保存经过校验的项目、根目录和不可变文件清单。 */
    public record WorkspaceArchive(Workspace workspace, Path root, List<Path> files) {
        /** 防止调用方修改归档文件清单。 */
        public WorkspaceArchive {
            files = List.copyOf(files);
        }
    }
}
