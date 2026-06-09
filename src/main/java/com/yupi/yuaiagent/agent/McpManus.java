package com.yupi.yuaiagent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 支持 MCP 协议工具的超级智能体
 *
 * <p>实现思路（项目原有设计已经为此做了准备）：
 * <ul>
 *   <li>Spring AI 自动配置 {@code McpToolCallbackAutoConfiguration} 在 yml 配置了
 *       {@code spring.ai.mcp.client.*} 之后，会注入名为 {@code mcpToolCallbacks}
 *       的 {@code SyncMcpToolCallbackProvider}（implements {@code ToolCallbackProvider}）。</li>
 *   <li>本类只做一件事：调用 provider.getToolCallbacks() 拿到 MCP 服务注册的
 *       {@link ToolCallback}[]，然后直接复用父类 {@link YuManus} 的 ReAct 循环。</li>
 *   <li>这意味着所有循环检测、stuck prompt、多轮工具调用逻辑都是零侵入继承的，
 *       业务侧只需要把 MCP 工具列表传进来。</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 * // 1. 通过 Spring 注入拿到 MCP 工具（懒加载，MCP 未启用时也不会报错）
 * ObjectProvider<SyncMcpToolCallbackProvider> provider = ...;
 *
 * // 2. 拼出可用工具列表（本地工具 + MCP 工具）
 * ToolCallback[] localTools = toolRegistration.allTools();
 * ToolCallback[] mcpTools = provider.getIfAvailable() != null
 *         ? provider.getIfAvailable().getToolCallbacks() : new ToolCallback[0];
 * ToolCallback[] merged = Stream.concat(Arrays.stream(localTools), Arrays.stream(mcpTools))
 *         .toArray(ToolCallback[]::new);
 *
 * // 3. 复用 YuManus 的所有能力
 * McpManus agent = new McpManus(merged, dashscopeChatModel);
 * return agent.runStream(message);
 * }</pre>
 */
@Slf4j
public class McpManus extends YuManus {

    /**
     * MCP 模式下的 system prompt：和 YuManus 整体一致，仅多了一段对 MCP 工具来源的说明，
     * 让 LLM 在收到工具列表时能正确理解它能使用哪些外部能力。
     */
    private static final String MCP_SYSTEM_PROMPT = """
            你是 McpManus，一名支持 MCP（Model Context Protocol）协议的 AI 超级智能体。
            除了通用工具外，你还可以通过 MCP 协议调用用户挂载的任何外部 MCP Server 工具
            （例如本地 stdio 子进程、远程 SSE 服务等）。

            默认始终使用简体中文回答，除非用户明确要求使用英文或其他语言。
            默认直接用简洁、有帮助的自然语言回复用户。
            只有在确实能帮助完成用户请求时才调用工具。
            除非用户明确要求导出、保存、下载、打印，或生成 PDF / 文档 / 报告，否则不要生成任何文件。
            对于情感支持、日常问答、头脑风暴、解释说明、学习建议等轻量级请求，直接回答，不要调用工具。
            如果调用了工具，请用简体中文向用户总结有效结果。
            当用户请求已经完成时，调用 terminate 工具结束本轮任务。
            """;

    private static final String MCP_NEXT_STEP_PROMPT = """
            请先判断用户是否真的需要工具。
            如果用户没有明确要求生成文件、PDF、报告、导出内容或其他外部动作，就不要调用文件生成类工具。
            对于简单对话请求，请直接用简体中文回答，然后调用 terminate 工具结束。
            MCP 工具通常有副作用（写文件、调用第三方服务、发送消息等），
            在用户没有明确要求时不要主动调用。
            """;

    /**
     * 构造器签名和父类完全一致，复用父类的 ReAct 循环 / 工具调用 / 循环检测逻辑。
     *
     * @param mcpTools           MCP 服务注入的工具列表（必须是已经合并了本地 + MCP 的 ToolCallback[]）
     * @param dashscopeChatModel 通义大模型客户端
     */
    public McpManus(ToolCallback[] mcpTools, ChatModel dashscopeChatModel) {
        super(mcpTools, dashscopeChatModel);
        // 覆盖父类的 prompt，标记自己是 MCP 模式
        this.setName("mcpManus");
        this.setSystemPrompt(MCP_SYSTEM_PROMPT);
        this.setNextStepPrompt(MCP_NEXT_STEP_PROMPT);
        log.info("[McpManus] 初始化完成，工具总数={}",
                mcpTools == null ? 0 : mcpTools.length);
    }
}