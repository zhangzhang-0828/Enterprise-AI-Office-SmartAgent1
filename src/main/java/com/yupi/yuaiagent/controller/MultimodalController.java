package com.yupi.yuaiagent.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.chat.MessageFormat;
import com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 多模态对话控制器，支持 AI 解释图片内容。
 * <p>
 * 提供多种调用方式：
 * <ul>
 *   <li>/ai/multimodal/chat/sync            - 同步返回完整回复</li>
 *   <li>/ai/multimodal/chat/sse            - SSE 流式返回</li>
 *   <li>/ai/multimodal/chat/server_sent_event - SSE ServerSentEvent 格式</li>
 *   <li>/ai/multimodal/chat/sse_emitter    - SseEmitter 格式流式返回</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai")
@Slf4j
public class MultimodalController {

    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final List<String> ALLOWED_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 多模态对话（同步，返回完整回复）
     *
     * @param message 文本消息
     * @param image  图片文件（可选，支持 JPG/PNG/GIF/WebP，最大 10MB）
     * @return AI 完整回复
     */
    @PostMapping("/multimodal/chat/sync")
    public String doMultimodalChatSync(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        validateImage(image);
        Prompt prompt = buildMultimodalPrompt(message, image);
        ChatResponse response = ChatClient.builder(dashscopeChatModel)
                .build()
                .prompt(prompt)
                .call()
                .chatResponse();
        return response.getResult().getOutput().getText();
    }

    /**
     * 多模态对话（SSE 流式）
     */
    @GetMapping(value = "/multimodal/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doMultimodalChatSSE(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        validateImage(image);
        Prompt prompt = buildMultimodalPrompt(message, image);
        return ChatClient.builder(dashscopeChatModel)
                .build()
                .prompt(prompt)
                .stream()
                .content();
    }

    /**
     * 多模态对话（ServerSentEvent 格式，流式）
     */
    @GetMapping(value = "/multimodal/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doMultimodalChatSSEWithSSE(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        validateImage(image);
        Prompt prompt = buildMultimodalPrompt(message, image);
        return ChatClient.builder(dashscopeChatModel)
                .build()
                .prompt(prompt)
                .stream()
                .content()
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    /**
     * 多模态对话（SseEmitter 格式，流式）
     */
    @GetMapping("/multimodal/chat/sse_emitter")
    public SseEmitter doMultimodalChatSseEmitter(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        validateImage(image);
        SseEmitter emitter = new SseEmitter(180000L);
        Prompt prompt = buildMultimodalPrompt(message, image);
        ChatClient.builder(dashscopeChatModel)
                .build()
                .prompt(prompt)
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete);
        return emitter;
    }

    /**
     * 多模态对话（SseEmitter 格式，流式，POST 表单上传）
     */
    @PostMapping("/multimodal/chat/sse_emitter")
    public SseEmitter doMultimodalChatSseEmitterPost(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        return doMultimodalChatSseEmitter(message, image);
    }

    private Prompt buildMultimodalPrompt(String message, MultipartFile image) {
        if (image != null && !image.isEmpty()) {
            try {
                byte[] imageBytes = image.getBytes();
                Media media = Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType(image.getContentType()))
                        .data(new ByteArrayResource(imageBytes))
                        .build();
                UserMessage userMessage = UserMessage.builder()
                        .text(message)
                        .media(media)
                        .metadata(Map.of(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE))
                        .build();
                return new Prompt(userMessage,
                        DashScopeChatOptions.builder()
                                .withModel("qwen-vl-plus")
                                .withMultiModel(true)
                                .build());
            } catch (IOException e) {
                log.error("读取图片失败，降级为纯文本模式", e);
            }
        }
        return new Prompt(message);
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return;
        }
        if (image.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB，当前: " + image.getSize() / 1024 + "KB");
        }
        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("只支持 JPG、PNG、GIF、WebP 格式的图片，当前: " + contentType);
        }
    }
}
