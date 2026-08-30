package com.yukina.codingagent.controller;

import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.service.WorkspaceArchiveService;
import com.yukina.codingagent.workspace.service.WorkspaceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提供持久化项目工作空间的创建、查询、重命名和移除接口。
 */
@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceArchiveService workspaceArchiveService;

    /** 创建项目工作空间控制器。 */
    public WorkspaceController(
            WorkspaceService workspaceService,
            WorkspaceArchiveService workspaceArchiveService
    ) {
        this.workspaceService = workspaceService;
        this.workspaceArchiveService = workspaceArchiveService;
    }

    /** 列出所有项目，不暴露服务器本地路径。 */
    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaceService.list().stream().map(WorkspaceResponse::from).toList();
    }

    /** 查询指定项目。 */
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable String workspaceId) {
        return WorkspaceResponse.from(workspaceService.get(workspaceId));
    }

    /** 创建可通过上传、粘贴或 Agent 对话填充的空白项目。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@RequestBody CreateWorkspaceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return WorkspaceResponse.from(workspaceService.create(request.name()));
    }

    /** 注册 API 调用方提供的真实本地项目目录。 */
    @PostMapping("/local")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse registerLocal(@RequestBody LocalWorkspaceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return WorkspaceResponse.from(workspaceService.registerLocal(request.name(), request.path()));
    }

    /** 将受管项目的当前文件打包为 ZIP 并流式下载。 */
    @GetMapping(value = "/{workspaceId}/archive", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String workspaceId) {
        WorkspaceArchiveService.WorkspaceArchive archive = workspaceArchiveService.prepare(workspaceId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(workspaceArchiveService.fileName(archive), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> workspaceArchiveService.write(archive, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
    }

    /** 修改项目展示名称。 */
    @PatchMapping("/{workspaceId}")
    public WorkspaceResponse rename(
            @PathVariable String workspaceId,
            @RequestBody RenameWorkspaceRequest request
    ) {
        return WorkspaceResponse.from(
                workspaceService.rename(workspaceId, request == null ? null : request.name())
        );
    }

    /** 移除尚未创建对话的项目注册，并在目录为空时回收目录。 */
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId) {
        workspaceService.delete(workspaceId);
    }

    /** 项目创建请求。 */
    public record CreateWorkspaceRequest(String name) {
    }

    /** 本地项目注册请求，主要供 CLI 或未来的桌面壳调用。 */
    public record LocalWorkspaceRequest(String name, String path) {
    }

    /** 项目重命名请求。 */
    public record RenameWorkspaceRequest(String name) {
    }

    /** 隐藏服务器绝对路径的项目响应。 */
    public record WorkspaceResponse(
            String id,
            String name,
            String type,
            Instant createdAt,
            Instant updatedAt
    ) {
        /** 从内部工作空间模型生成公开项目响应。 */
        private static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.id(),
                    workspace.name(),
                    workspace.type().name(),
                    workspace.createdAt(),
                    workspace.updatedAt()
            );
        }
    }
}
