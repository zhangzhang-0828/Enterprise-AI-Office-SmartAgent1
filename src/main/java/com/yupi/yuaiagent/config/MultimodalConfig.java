package com.yupi.yuaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模态视觉模型配置。
 * <p>
 * 提供独立的 ChatModel Bean 用于视觉任务。
 * 当控制器传入图片时，会自动切换到此视觉模型（qwen-vl-plus）。
 */
@Configuration
@Slf4j
public class MultimodalConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    /**
     * 视觉模型 Bean（qwen-vl-plus），用于多模态图片理解场景。
     * 注意：qwen-vl-plus 同时支持纯文本和图文输入。
     */
    @Bean("dashscopeVisionChatModel")
    public ChatModel dashscopeVisionChatModel() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .build();
    }
}
