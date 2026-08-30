package com.yukina.codingagent.controller;

import com.yukina.codingagent.workspace.model.WorkspaceImportResult;
import com.yukina.codingagent.workspace.service.WorkspaceImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 接收浏览器上传文件和粘贴源码的工作空间内容接口。
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}")
public class WorkspaceContentController {

    private final WorkspaceImportService importService;

    /** 创建工作空间内容控制器。 */
    public WorkspaceContentController(WorkspaceImportService importService) {
        this.importService = importService;
    }

    /** 上传文件或文件夹，并按 paths 参数保留相对目录结构。 */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceImportResult uploadFiles(
            @PathVariable String workspaceId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths
    ) {
        return importService.importFiles(workspaceId, files, paths);
    }

    /** 将粘贴的源码写入工作空间中的指定相对路径。 */
    @PostMapping("/code")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceImportResult uploadCode(
            @PathVariable String workspaceId,
            @RequestBody PasteCodeRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return importService.importCode(workspaceId, request.path(), request.content());
    }

    /** 粘贴源码请求。 */
    public record PasteCodeRequest(String path, String content) {
    }
}
