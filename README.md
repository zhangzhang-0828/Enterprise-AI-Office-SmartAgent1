# AI 超级智能体项目

## 项目介绍

本项目开发了两个核心功能：**AI 恋爱大师**和**超级智能体 YuManus**。

**AI 恋爱大师**：基于 AI 大模型解决用户的情感问题，支持多轮对话、对话记忆持久化、RAG 知识库检索、工具调用和 MCP 服务调用。

**超级智能体 YuManus**：具有自主规划能力的 AI Agent，可以根据用户需求自主推理和行动，支持联网搜索、文件操作、网页抓取、资源下载、终端操作、PDF 生成等多种工具调用，直到完成目标。

## 功能特性

- **AI 恋爱大师**：多轮对话、RAG 知识库问答、结构化输出（生成恋爱报告）、对话记忆持久化
- **超级智能体**：ReAct 模式自主规划、多工具协作、SSE 流式输出、多模态支持
- **工具集**：联网搜索、文件操作、网页抓取、资源下载、终端操作、PDF 生成
- **MCP 服务**：图片搜索等外部服务集成

## 技术栈

- **后端**：Java 21 + Spring Boot 3 + Spring AI + LangChain4j
- **数据库**：PgVector 向量数据库
- **AI 模型**：阿里云百炼（DashScope）/ Ollama 本地部署
- **协议**：Tool Calling、MCP 模型上下文协议、SSE 异步推送
- **前端**：Vue 3 + Vite

## 项目结构

```
yu-ai-agent-master/
├── src/main/java/com/yupi/yuaiagent/
│   ├── agent/          # AI 智能体核心
│   ├── app/            # 业务应用（恋爱大师）
│   ├── controller/     # 接口层
│   ├── config/        # 配置类
│   ├── tools/         # 工具集
│   └── rag/           # RAG 知识库
└── yu-ai-agent-frontend/
    └── src/           # Vue 3 前端
```

## 快速开始

### 环境要求

- JDK 21+
- Node.js 18+
- Docker（可选，用于 PgVector 向量数据库）

### 后端启动

```bash
# 克隆项目
git clone https://github.com/liyupi/yu-ai-agent.git
cd yu-ai-agent-master

# 配置 API Key（修改 application.yml 中的 dashscope.api-key）

# 启动应用
mvn spring-boot:run
```

### 前端启动

```bash
cd yu-ai-agent-frontend
npm install
npm run dev
```

## 核心接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/love_app/chat/sse_emitter` | POST | 恋爱大师对话（支持图片） |
| `/api/ai/manus/chat` | POST | 超级智能体对话（支持图片） |
