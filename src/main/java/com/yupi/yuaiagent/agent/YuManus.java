package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * kyrie的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
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

    public YuManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
        super(allTools);
        this.setName("yuManus");
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(8);
        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
