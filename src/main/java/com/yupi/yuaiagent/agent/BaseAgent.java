package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants;
import com.alibaba.cloud.ai.dashscope.chat.MessageFormat;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    private static final String DONE_MESSAGE = "[DONE]";

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    /**
     * 循环检测阈值：当最近的 AssistantMessage 内容与历史 AssistantMessage 重复次数 >= 此值，
     * 视为智能体陷入循环（参考 OpenManus 默认值 2）。
     */
    private int duplicateThreshold = 2;

    /**
     * 连续重试 stuckPrompt 的最大次数：
     * 超过此次数后即便还在循环也直接 FINISHED，避免无限调用 LLM。
     */
    private int maxStuckAttempts = 3;

    /**
     * 当前累计的连续 stuck 次数（每次成功 step 会清零，stuck 时 ++）。
     */
    private int stuckCount = 0;

    /**
     * 卡死时注入到 nextStepPrompt 前的提示词，用于提示智能体切换策略。
     */
    private static final String STUCK_PROMPT =
            "观察到重复响应。考虑新策略，避免重复已尝试过的无效路径。";

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        this.state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));
        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                String stepResult = step();
                if (StrUtil.isNotBlank(stepResult)) {
                    results.add(stepResult);
                }
            }
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("执行结束：达到最大步骤（" + maxSteps + "）");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误：" + e.getMessage();
        } finally {
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        SseEmitter sseEmitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    sendAndComplete(sseEmitter, "错误：无法从状态运行代理：" + this.state);
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sendAndComplete(sseEmitter, "错误：不能使用空提示词运行代理");
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }

            this.state = AgentState.RUNNING;
            messageList.add(new UserMessage(userPrompt));
            try {
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    String stepResult = step();
                    if (StrUtil.isNotBlank(stepResult)) {
                        sseEmitter.send(stepResult);
                    }
                }
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                sseEmitter.send(DONE_MESSAGE);
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.send(DONE_MESSAGE);
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                this.cleanup();
            }
        });

        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    /**
     * 定义单个步骤
     * 有图片时自动使用视觉模型（qwen-vl-plus）。
     *
     * @param userPrompt 用户消息
     * @param image      图片文件（可为 null）
     */
    public SseEmitter runStreamWithImage(String userPrompt, MultipartFile image) {
        return runStreamWithImage(userPrompt, image, null, null);
    }

    /**
     * 带图片的流式对话入口（完整参数版）
     *
     * @param userPrompt      用户消息
     * @param image          图片文件（可为 null）
     * @param useVisionModel 是否强制用视觉模型（null 时：有图片则用，无图片则不用）
     * @param visionModelName 视觉模型名（null 时默认为 qwen-vl-plus）
     */
    public SseEmitter runStreamWithImage(String userPrompt, MultipartFile image,
                                         Boolean useVisionModel, String visionModelName) {
        SseEmitter sseEmitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    sendAndComplete(sseEmitter, "错误：无法从状态运行代理：" + this.state);
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sendAndComplete(sseEmitter, "错误：不能使用空提示词运行代理");
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }

            this.state = AgentState.RUNNING;
            addUserMessageWithImage(userPrompt, image);
            try {
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED && state != AgentState.STUCK; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    checkAndHandleStuck();
                    if (state == AgentState.STUCK) {
                        sseEmitter.send("执行结束：检测到循环，已强制终止（连续" + stuckCount + "次重复响应）");
                        break;
                    }
                    String stepResult = step();
                    if (StrUtil.isNotBlank(stepResult)) {
                        sseEmitter.send(stepResult);
                    }
                }
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                sseEmitter.send(DONE_MESSAGE);
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.send(DONE_MESSAGE);
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                this.cleanup();
            }
        });

        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    /**
     * 将用户消息（含图片）添加到消息列表。
     * 有图片时用 qwen-vl-plus，无图片时用默认模型。
     */
    protected void addUserMessageWithImage(String text, MultipartFile image) {
        boolean hasImage = image != null && !image.isEmpty();
        log.info("[addUserMessageWithImage] hasImage={}, contentType={}, size={}, text=[{}]",
                hasImage,
                image != null ? image.getContentType() : "null",
                image != null ? image.getSize() : -1,
                text);
        String finalText = text;
        if (hasImage && StrUtil.isBlank(finalText)) {
            finalText = "请分析这张图片并回答用户的问题。";
        }
        if (!hasImage) {
            messageList.add(new UserMessage(finalText));
            log.info("[addUserMessageWithImage] 无图片，纯文本消息已添加");
            return;
        }
        try {
            String mimeType = image.getContentType();
            if (mimeType == null) {
                mimeType = "image/jpeg";
                log.warn("[addUserMessageWithImage] contentType 为 null，强制设为 image/jpeg");
            }
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                    .data(new ByteArrayResource(image.getBytes()))
                    .build();
            messageList.add(UserMessage.builder()
                    .text(finalText)
                    .media(media)
                    .metadata(Map.of(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE))
                    .build());
            log.info("[addUserMessageWithImage] 带图片消息已添加，mimeType={}", mimeType);
        } catch (IOException e) {
            log.error("[addUserMessageWithImage] 读取图片失败，降级为纯文本消息", e);
            messageList.add(new UserMessage(finalText));
        }
    }

    public String getFinalResponse() {
        Message lastMessage = messageList.isEmpty() ? null : messageList.get(messageList.size() - 1);
        if (lastMessage instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return "";
    }

    private void sendAndComplete(SseEmitter sseEmitter, String message) throws IOException {
        sseEmitter.send(message);
        sseEmitter.send(DONE_MESSAGE);
        sseEmitter.complete();
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    // ============================ 循环检测与处理 ============================
    // 参考 OpenManus 的实现思路：检测最近的 AssistantMessage 是否与历史 AssistantMessage 重复，
    // 若重复达到阈值则注入"避免重复无效路径"的提示，让 LLM 切换策略；
    // 若连续多次仍未脱离循环，直接置为 STUCK 终止运行，避免无限调用大模型。

    /**
     * 每轮 step() 前的循环检测入口：
     * 1) isStuck() 为 true 时执行 handleStuckState()；
     * 2) 连续超过 maxStuckAttempts 次仍未脱离，则把 state 置为 STUCK，由外层循环统一退出。
     */
    protected void checkAndHandleStuck() {
        if (!isStuck()) {
            // 没有卡死，正常 step 已经发生（或即将发生），把计数器清零。
            stuckCount = 0;
            return;
        }
        handleStuckState();
        stuckCount++;
        log.warn("[循环检测] step={}, stuckCount={}/{}, 最近 LLM 输出存在循环",
                currentStep, stuckCount, maxStuckAttempts);
        if (stuckCount > maxStuckAttempts) {
            log.error("[循环检测] 连续 {} 次仍卡死，强制终止，state=STUCK", stuckCount);
            state = AgentState.STUCK;
        }
    }

    /**
     * 注入"避免重复"的提示词，让 LLM 在下一次 think 时尝试新策略。
     * 通过把 STUCK_PROMPT 拼接到 nextStepPrompt 前面实现：
     * 因为 ToolCallAgent.think() 会把 nextStepPrompt 作为 UserMessage 加入对话，
     * LLM 在下一轮就能看到这条提醒。
     */
    protected void handleStuckState() {
        String existing = nextStepPrompt != null ? nextStepPrompt : "";
        nextStepPrompt = STUCK_PROMPT + "\n" + existing;
        log.info("[循环检测] 已注入 stuckPrompt，下一轮 think 会作为 UserMessage 出现");
    }

    /**
     * 判断是否卡死：
     * 找到最后一条 AssistantMessage 的文本内容，回看历史 AssistantMessage，统计
     * 内容完全相同的条数；达到 duplicateThreshold 即视为循环。
     */
    protected boolean isStuck() {
        List<Message> messages = messageList;
        if (messages.size() < 2) {
            return false;
        }

        // 找到最后一条 AssistantMessage 的位置和内容
        int lastIdx = messages.size() - 1;
        while (lastIdx >= 0 && !(messages.get(lastIdx) instanceof AssistantMessage)) {
            lastIdx--;
        }
        if (lastIdx < 1) {
            return false;
        }
        String lastContent = ((AssistantMessage) messages.get(lastIdx)).getText();
        if (lastContent == null || lastContent.isEmpty()) {
            return false;
        }

        int duplicateCount = 0;
        for (int i = lastIdx - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage assistantMessage
                    && lastContent.equals(assistantMessage.getText())) {
                duplicateCount++;
            }
        }
        return duplicateCount >= duplicateThreshold;
    }
}
