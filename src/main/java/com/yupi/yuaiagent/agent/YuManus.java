package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * kyrie的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class YuManus extends ToolCallAgent {

    private static final String SYSTEM_PROMPT = """
            你是 YuManus，一名全能 AI 助手，擅长通过清晰、自然、友好的对话帮助用户解决问题。
            默认始终使用简体中文回答，除非用户明确要求使用英文或其他语言。
            默认直接用简洁、有帮助的自然语言回复用户。
            只有在确实能帮助完成用户请求时才调用工具。
            除非用户明确要求导出、保存、下载、打印，或生成 PDF / 文档 / 报告，否则不要生成任何文件。
            对于情感支持、日常问答、头脑风暴、解释说明、学习建议等轻量级请求，直接回答，不要调用工具。
            如果调用了工具，请用简体中文向用户总结有效结果。
            如果需要生成文档或 PDF，文档内容默认也必须使用简体中文，除非用户明确要求其他语言。
            当用户请求已经完成时，调用 terminate 工具结束本轮任务。
            """;

    private static final String NEXT_STEP_PROMPT = """
            请先判断用户是否真的需要工具。
            如果用户没有明确要求生成文件、PDF、报告、导出内容或其他外部动作，就不要调用文件生成类工具。
            对于简单对话请求，请直接用简体中文回答，然后调用 terminate 工具结束。
            只有在工具确实必要时，才把任务拆成多步执行。
            """;

    private final ChatClient chatClient;
    private final ChatClient visionChatClient;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;
    private final ToolCallback[] allTools;

    private ChatResponse toolCallChatResponse;
    private boolean hasImage;

    public YuManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
        super(allTools);
        this.allTools = allTools;
        this.setName("yuManus");
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(8);

        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();

        this.chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();

        DashScopeChatOptions visionOptions = DashScopeChatOptions.builder()
                .withModel("qwen-vl-plus")
                .withMultiModel(true)
                .build();
        this.visionChatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .defaultOptions(visionOptions)
                .build();

        this.setChatClient(chatClient);
    }

    @Override
    public boolean think() {
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            getMessageList().add(new UserMessage(getNextStepPrompt()));
        }
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        try {
            ChatClient client = hasImage ? visionChatClient : chatClient;
            log.info("[think] hasImage={}, using client={}", hasImage, hasImage ? "visionChatClient" : "chatClient");
            ChatResponse chatResponse = client.prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(allTools)
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return getFinalResponse();
        }
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
        }

        String assistantReply = extractLatestAssistantReply(toolExecutionResult.conversationHistory());
        String toolSummary = toolResponseMessage.getResponses().stream()
                .filter(response -> !"doTerminate".equals(response.name()))
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));

        if (StrUtil.isNotBlank(toolSummary)) {
            log.info(toolSummary);
        }

        if (StrUtil.isNotBlank(assistantReply) && StrUtil.isNotBlank(toolSummary)) {
            return assistantReply + "\n" + toolSummary;
        }
        if (StrUtil.isNotBlank(assistantReply)) {
            return assistantReply;
        }
        if (StrUtil.isNotBlank(toolSummary)) {
            return toolSummary;
        }
        return terminateToolCalled ? "" : "工具执行完成";
    }

    private String extractLatestAssistantReply(List<Message> conversationHistory) {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            Message message = conversationHistory.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                String text = assistantMessage.getText();
                if (StrUtil.isNotBlank(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    // ==================== 流式入口 ====================

    /**
     * 普通文本流式对话（走原有路径）
     */
    public SseEmitter runStream(String userPrompt) {
        return super.runStream(userPrompt);
    }

    /**
     * 带图片的流式对话入口。
     * 有图片时强制用视觉 client（qwen-vl-plus），确保图片被正确处理。
     */
    public SseEmitter runStreamWithImage(String userPrompt, MultipartFile image) {
        this.hasImage = (image != null && !image.isEmpty());
        return super.runStreamWithImage(userPrompt, image);
    }
}
