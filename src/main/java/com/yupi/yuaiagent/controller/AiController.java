package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.app.LoveApp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    // ==================== 恋爱大师接口 ====================

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppSseEmitter(String message, String chatId) {
        SseEmitter sseEmitter = new SseEmitter(180000L);
        loveApp.doChatByStream(message, chatId)
                .subscribe(
                        chunk -> {
                            try {
                                sseEmitter.send(chunk);
                            } catch (IOException e) {
                                sseEmitter.completeWithError(e);
                            }
                        },
                        sseEmitter::completeWithError,
                        sseEmitter::complete);
        return sseEmitter;
    }

    /**
     * 恋爱大师多模态对话（POST，支持图片上传）
     */
    @PostMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppSseEmitterWithImage(
            @RequestParam("message") String message,
            @RequestParam(value = "chatId", defaultValue = "") String chatId,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        SseEmitter sseEmitter = new SseEmitter(180000L);
        Flux<String> flux;
        if (image != null && !image.isEmpty()) {
            try {
                flux = loveApp.doChatByStreamWithImage(message, chatId, image.getBytes(), image.getContentType());
            } catch (IOException e) {
                log.error("读取图片失败，降级为纯文本模式", e);
                flux = loveApp.doChatByStream(message, chatId);
            }
        } else {
            flux = loveApp.doChatByStream(message, chatId);
        }
        flux.subscribe(
                chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                },
                sseEmitter::completeWithError,
                sseEmitter::complete);
        return sseEmitter;
    }

    // ==================== 超级智能体接口 ====================

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel);
        return yuManus.runStream(message);
    }

    /**
     * 超级智能体多模态对话（POST，支持图片上传）
     */
    @PostMapping("/manus/chat")
    public SseEmitter doChatWithManusWithImage(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel);
        return yuManus.runStreamWithImage(message, image);
    }
}
