package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.McpManus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 支持 MCP 协议的超级智能体入口
 *
 * <p>核心思路：复用现有 YuManus 的 ReAct 循环，只把"工具来源"从本地 ToolRegistration
 * 换成 Spring AI 自动注入的 MCP 工具集。
 * <p>Bean 注入说明：
 * <ul>
 *   <li>{@link SyncMcpToolCallbackProvider} 由 Spring AI 自动配置 {@code McpToolCallbackAutoConfiguration}
 *       在 yml 配置了 {@code spring.ai.mcp.client.*} 时才会注入；用 {@link ObjectProvider}
 *       懒获取，让 MCP 未启用时项目仍能正常启动。</li>
 *   <li>本 controller 不做对话记忆（每次请求新建一个 McpManus），便于隔离不同用户的会话上下文，
 *       与 AiController 中 {@code /manus/chat} 的处理方式一致。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/ai/mcp_manus")
public class McpController {

    /**
     * MCP 工具回调 provider；通过 ObjectProvider 实现"可有可无"的注入。
     */
    @Resource
    private ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider;

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 同步聊天：本地工具 + MCP 工具合并后交给 McpManus。
     * <p>设计取舍：MCP 智能体场景通常需要外部工具（写文件、查 DB、调外部 API），
     * 但仍然保留本地工具（如 PDF 生成）作为通用能力，所以这里把所有可用工具都注入。
     * 如果只想用纯 MCP 工具，把 localTools 去掉即可。
     */
    @GetMapping("/chat")
    public SseEmitter doChatWithMcpManus(String message) {
        ToolCallback[] tools = loadAllTools();
        McpManus mcpManus = new McpManus(tools, dashscopeChatModel);
        return mcpManus.runStream(message);
    }

    /**
     * 带图片的 MCP 智能体对话（POST，支持图片上传）。
     * <p>内部仍复用父类的 runStreamWithImage 逻辑：hasImage=true 时切换到视觉模型。
     */
    @PostMapping("/chat")
    public SseEmitter doChatWithMcpManusWithImage(
            @RequestParam("message") String message,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        ToolCallback[] tools = loadAllTools();
        McpManus mcpManus = new McpManus(tools, dashscopeChatModel);
        return mcpManus.runStreamWithImage(message, image);
    }

    /**
     * 仅 MCP 工具的纯净入口（不包含本地工具），便于只想测试 MCP 工具时调用。
     */
    @GetMapping("/chat/mcp_only")
    public SseEmitter doChatWithMcpOnly(String message) {
        SyncMcpToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
        ToolCallback[] mcpTools = provider != null ? provider.getToolCallbacks() : new ToolCallback[0];
        if (mcpTools.length == 0) {
            log.warn("[McpController] MCP 未启用或未发现任何 MCP 工具，请检查 application.yml 中 spring.ai.mcp.client 配置");
        }
        McpManus mcpManus = new McpManus(mcpTools, dashscopeChatModel);
        return mcpManus.runStream(message);
    }

    /**
     * 把 MCP 工具加载出来。
     * <p>因为 yml 当前注释掉了 MCP 配置，所以默认情况下这里返回空数组；
     * 取消注释并填写 mcp-servers.json 后，本方法会自动拿到 MCP server 注册的工具。
     *
     * @return ToolCallback[]，可能长度为 0（不代表出错）
     */
    private ToolCallback[] loadAllTools() {
        SyncMcpToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
        if (provider == null) {
            log.warn("[McpController] 未发现 MCP ToolCallbackProvider，确认 application.yml 已启用 spring.ai.mcp.client 配置");
            return new ToolCallback[0];
        }
        ToolCallback[] mcpTools = provider.getToolCallbacks();
        log.info("[McpController] 从 MCP 加载到 {} 个工具", mcpTools.length);
        return mcpTools;
    }
}