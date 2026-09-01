package com.yukina.codingagent.controller;

import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationImportResult;
import com.yukina.codingagent.conversation.service.ConversationService;
import com.yukina.codingagent.conversation.workspace.ConversationArchiveService;
import com.yukina.codingagent.conversation.workspace.ConversationFileImportService;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提供 CODE 会话内的上传、粘贴和完整项目下载。
 */
@RestController
@RequestMapping("/conversations/{conversationId}")
public class ConversationContentController {

    private final ConversationService conversationService;
    private final ConversationFileImportService importService;
    private final ConversationArchiveService archiveService;

    /**
     * 创建会话内容控制器。
     *
     * @param conversationService 会话查询服务
     * @param importService 安全上传服务
     * @param archiveService 项目归档服务
     */
    public ConversationContentController(
            ConversationService conversationService,
            ConversationFileImportService importService,
            ConversationArchiveService archiveService
    ) {
        this.conversationService = conversationService;
        this.importService = importService;
        this.archiveService = archiveService;
    }

    /**
     * 将浏览器选择的文件或目录上传至会话项目目录。
     *
     * @param conversationId CODE 会话 ID
     * @param files 上传文件
     * @param paths 浏览器提供的相对路径
     * @return 导入摘要
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationImportResult uploadFiles(
            @PathVariable String conversationId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths
    ) {
        return importService.importFiles(conversationService.get(conversationId), files, paths);
    }

    /**
     * 将一段源码写入会话项目中的新文件。
     *
     * @param conversationId CODE 会话 ID
     * @param request 相对路径和源码
     * @return 导入摘要
     */
    @PostMapping("/code")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationImportResult uploadCode(
            @PathVariable String conversationId,
            @RequestBody PasteCodeRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return importService.importCode(
                conversationService.get(conversationId),
                request.path(),
                request.content()
        );
    }

    /**
     * 下载会话项目的当前完整快照。
     *
     * @param conversationId CODE 会话 ID
     * @return 流式 ZIP 响应
     */
    @GetMapping(value = "/archive", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String conversationId) {
        Conversation conversation = conversationService.get(conversationId);
        ConversationWorkspaceService.requireCode(conversation);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(archiveService.fileName(conversation), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> archiveService.write(conversation, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
    }

    /** @param path 项目相对路径 @param content UTF-8 源码 */
    public record PasteCodeRequest(String path, String content) {
    }
}
