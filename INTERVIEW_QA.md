# Yu AI Agent 面试题库

> 本文档整理自 `yu-ai-agent` 项目（Spring Boot 3 + Spring AI 1.0 + LangChain4j + Vue 3），覆盖项目介绍、技术栈、核心流程、架构设计、关键代码实现、易踩坑点及扩展思考。问题从项目常见提问到面试官深度追问，按模块分类。

---

## 0. 项目速览（面试开场自我介绍）

**Q0.1 请用 1 分钟介绍下你的项目。**

本项目是一个 **AI 综合应用平台**，包含两个核心场景：

- **AI 恋爱大师**：基于大模型（阿里云百炼 DashScope）的情感咨询助手，支持多轮对话、RAG 知识库问答、工具调用、MCP 服务调用和恋爱报告结构化输出。
- **超级智能体 YuManus**：基于 ReAct 模式自主规划的多步骤 Agent，可以自主调用联网搜索、网页抓取、文件操作、终端操作、PDF 生成等 7 个工具完成任务。

技术栈：**Java 21 + Spring Boot 3.4 + Spring AI 1.0 + Spring AI Alibaba + LangChain4j + PgVector 向量数据库 + Vue 3**。设计上覆盖了 4 种大模型接入方式、Spring AI 的核心特性（ChatClient / Advisor / ChatMemory / 结构化输出）、RAG 全链路调优、Tool Calling、MCP 协议、SSE 流式推送和智能体规划。

---

## 1. 项目架构与设计类

### Q1.1 整个项目分哪几层？请求从前端到后端再到大模型的完整链路是什么？

**回答**：

**前端层**（Vue 3 + Vite，`yu-ai-agent-frontend`）：
- `ChatRoom.vue` 提供统一聊天 UI；
- `src/api/index.js` 封装调用（SSE 用 `EventSource`，带文件用 `fetch + FormData`）；
- `src/utils/chat.js` 的 `createSSEParser` 用 ReadableStream + 缓冲区按 `\n\n` 切割，SSE 协议兼容 TCP 分包。

**控制层**（`controller/AiController`）：
- 路径前缀 `/ai`，端口 8123，context-path `/api`；
- 提供 5 个恋爱大师接口 + 2 个超级智能体接口（含同步/SSE/ServerSentEvent/SseEmitter/POST 多模态）；
- 注入 `LoveApp`、`ToolCallback[] allTools`、`ChatModel dashscopeChatModel`。

**业务层**（`app/LoveApp` + `agent/*`）：
- `LoveApp`：聊天、RAG、MCP、工具调用四类能力；
- `agent.BaseAgent`：抽象代理生命周期（state 状态机、step 循环、cleanup、run/runStream 双入口）；
- `agent.ReActAgent`：实现 `think() / act() / step()` 的 ReAct 抽象；
- `agent.ToolCallAgent`：实现 think=LLM 推理+ToolCall 决策、act=ToolCallingManager 执行；
- `agent.YuManus`：继承 ToolCallAgent，按需切换文本/视觉模型。

**能力层**（`rag/*` + `tools/*`）：
- RAG：文档加载→切分（TokenTextSplitter）→关键词富化（KeywordEnricher）→向量库（SimpleVectorStore / PgVector）→QuestionAnswerAdvisor；
- 工具：`ToolRegistration` 集中注册 7 个 ToolCallback。

**基础设施层**：
- `chatmemory/FileBasedChatMemory` 用 Kryo 序列化对话历史；
- `config/CorsConfig` 全局跨域；
- `MultiModal` 走 `qwen-vl-plus` 多模态模型；
- `Spring AI Alibaba Starter DashScope` 接入通义千问。

**请求链路**：
```
Vue fetch/EventSource → CORS → Controller (封 SseEmitter / Flux) →
ChatClient 调用链（按顺序链入 Advisor）→ DashScope ChatModel →
流式/非流式响应 → SseEmitter.send → SSE 协议回传到前端 →
createSSEParser 按 \n\n 切分 → 追加渲染
```

### Q1.2 接口的 context-path 是 `/api`，那 `/api/ai/manus/chat` 是怎么拼出来的？

`application.yml` 中：
```yaml
server:
  port: 8123
  servlet:
    context-path: /api
```
`AiController` 上有 `@RequestMapping("/ai")`，方法上是 `@GetMapping("/manus/chat")` / `@PostMapping("/manus/chat")`。最终对外路径就是 `/api + /ai + /manus/chat = /api/ai/manus/chat`。前端 Vite 代理 `/api` 到 `http://localhost:8123`，所以开发环境是 `localhost:3000/api/...`。

### Q1.3 为什么把 `multimodal` 拆成两个 ChatClient（chatClient + visionChatClient）？

因为**多模态模型与文本模型是不同模型**（视觉模型 `qwen-vl-plus` + `multiModel=true`），如果用同一个 ChatClient 每次都要传 options，会大量冗余且不优雅。拆成两个 Bean：
- 默认 `chatClient`：`withMultiModel(false)`，纯文本；
- `visionChatClient`：`withModel("qwen-vl-plus").withMultiModel(true)`，处理图片。

`YuManus.think()` 中根据 `hasImage` 切换 client：
```java
ChatClient client = hasImage ? visionChatClient : chatClient;
```

### Q1.4 CORS 是怎么配置的？为什么要用 `allowedOriginPatterns` 而不是 `*`？

```java
registry.addMapping("/**")
        .allowCredentials(true)
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("*");
```

**踩坑点**：如果前端要带 Cookie 跨域调用，则 `allowCredentials(true)` 必须搭配 `allowedOriginPatterns("*")` 而不能是 `allowedOrigins("*")`，否则 Spring 会直接抛 `When allowCredentials is true, allowedOrigins cannot contain the special value "*"`。`*` 与 credentialed 请求互斥（CORS 规范），必须用 pattern 形式绕过。

---

## 2. Spring AI 核心特性类

### Q2.1 什么是 `ChatClient`？它是 Bean 还是 Builder？

`ChatClient` 是 Spring AI 的**高层调用入口**，非单例（默认 Builder 模式构建，但建议提到直接当 Facade 用）。本项目通过：
```java
ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                         new MyLoggerAdvisor())
        .build();
```
它内部封装了 `ChatModel`（默认 DashScope）、系统提示、默认 Advisor 和默认 Options。

**调用方式**：
- 同步：`.user(msg).call().content()`
- 流式：`.user(msg).stream().content()` 返回 `Flux<String>`
- 多轮：`.prompt().messages(userMessage)`（手动构造）或通过 Advisor 自动管理

### Q2.2 什么是 Spring AI 的 Advisor？项目中怎么用的？

#### 是什么——设计思想

Advisor 借鉴了 **AOP（面向切面编程）** 的"环绕增强"思想，核心哲学是：**在不修改核心业务逻辑的前提下，给 ChatClient 的请求/响应流程插入可插拔的横切关注点**。

打个比方：如果把 ChatClient 的一次对话请求看作一条流水线，Advisor 就是站在流水线旁边的工作人员——他可以：
- 在工件进入流水线**之前**检查/修改（`before` 逻辑）；
- 在工件从流水线出来**之后**处理结果（`after` 逻辑）；
- 甚至**决定要不要把工件传给下一个环节**（`around` 逻辑）。

Spring AI 1.0 定义了两套 Advisor 接口，分别对应同步和流式两种调用路径：

| 接口 | 触发场景 | 核心方法 |
|------|---------|---------|
| `CallAdvisor` | `.call()` 同步调用 | `adviseCall(request, chain)` |
| `StreamAdvisor` | `.stream()` 流式调用 | `adviseStream(request, chain)` |

#### 怎么工作的——源码级别的执行流程

以 `MyLoggerAdvisor`（项目自定义实现）为例，逐行拆解：

```java
// ChatClient.java 内部伪代码（简化版）
public ChatClientResponse call(Prompt prompt) {
    // 1. 把 Prompt + Advisor 列表组装成 ChatClientRequest
    ChatClientRequest request = ChatClientRequest.builder()
            .prompt(prompt)
            .advisors(this.defaultAdvisors)   // ← 所有注册的 Advisor
            .build();

    // 2. 构建调用链（核心！），把所有 Advisor 串成一个责任链
    CallAdvisorChain chain = new DefaultCallAdvisorChain(
            defaultAdvisors,
            chatModel          // 最后一个环节：真正调 LLM
    );

    // 3. 从链头开始，第一个 Advisor 的 before 被执行
    return chain.nextCall(request);  // → MyLoggerAdvisor.adviseCall()
}
```

流式路径（`adviseStream`）同理，只不过响应类型是 `Flux<ChatClientResponse>`：

```java
public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
    // before：打印请求日志
    ChatClientRequest modified = before(request);

    // 调用链下一环，返回 Flux<ChatClientResponse>（每收到一个 LLM chunk 就触发一次）
    Flux<ChatClientResponse> flux = chain.nextStream(modified);

    // ChatClientMessageAggregator 负责把 Flux<ChatClientResponse> 聚合成
    // 一个完整的 ChatClientResponse，再触发 after
    return new ChatClientMessageAggregator()
            .aggregateChatClientResponse(flux, this::observeAfter);
}
```

**为什么要 `ChatClientMessageAggregator`？** 因为流式场景下 LLM 返回的是一批 `ChatClientResponse`（每个 chunk 一个），`Aggregator` 负责：
1. 把这些碎片聚合成**一条完整的 AssistantMessage**；
2. 在**聚合完成后**（即整个流结束）触发 `after` 逻辑——只打一条最终日志，而不是每个 chunk 打一条。

#### 项目中的 4 种典型用法

##### 1. 记忆 Advisor——`MessageChatMemoryAdvisor`

**做什么**：按 `chatId` 自动从 `ChatMemory` 读取历史消息、注入到 prompt；对话结束后自动把新消息写入 `ChatMemory`。

```java
// LoveApp 构造函数中
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)
        .build();

chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),  // ← 关键
                new MyLoggerAdvisor()
        )
        .build();
```

**实际效果**：调用 `.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))` 时，Advisor 根据 chatId 找到对应历史，**在真正发请求之前**，把这些历史消息插到 prompt 最前面，LLM 就能"记得"之前聊了什么。

##### 2. 日志 Advisor——`MyLoggerAdvisor`（自定义）

**做什么**：在请求发出前打印 info 日志，在响应回来后打印 info 日志。方便调试、监控 LLM 输入输出。

```java
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;   // 顺序最前（或最后），取决于你想看"处理前"还是"最终结果"
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        before(request);                              // ← 前置：打印请求
        ChatClientResponse response = chain.nextCall(request);   // 调下一个 Advisor（最终调 LLM）
        observeAfter(response);                        // ← 后置：打印响应
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        before(request);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        // 流式：用 Aggregator 聚合后，只在流结束时打一条 after 日志
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(flux, this::observeAfter);
    }
}
```

**为什么同时实现两个接口？** 因为 `.call()` 走同步路径，`MyLoggerAdvisor.before()` / `.after()` 各触发一次；如果只实现 `CallAdvisor`，`.stream()` 调用时就不会打印日志。

##### 3. RAG 检索 Advisor——`QuestionAnswerAdvisor`

**做什么**：在 LLM 收到用户问题之前，**先从向量数据库检索相关文档**，把文档内容作为上下文注入 prompt。

```java
// LoveApp.doChatWithRag() 中
new QuestionAdvisor(loveAppVectorStore)
```

它是 Spring AI RAG 体系的**核心组件**，内部实际调用了 `VectorStore.similaritySearch()` + Prompt 模板拼接，详细原理见 Q5.6。

##### 4. Re-Reading Advisor——`ReReadingAdvisor`

**做什么**：让 LLM **先重新阅读原始问题，再执行推理**。这是 Re2（Re-Reading）Prompt 策略——研究表明，大模型在复杂推理任务中，先"重新读一遍问题"能显著提升准确率。

**Spring AI 源码简化逻辑**：
```java
// ReReadingAdvisor 内部伪代码
public ChatClientRequest before(ChatClientRequest request) {
    String originalQuery = request.prompt().getUserMessage().getContent();
    // 把原始 query 包装成"请先重新阅读以下问题：xxx" 的 prompt
    String reReadingPrompt = "请先重新阅读以下问题：" + originalQuery
                           + "\n\n请在充分理解问题后，给出最佳回答。";

    // 替换掉原始 userMessage，但保留 system prompt
    return ChatClientRequest.builder()
            .prompt(new Prompt(reReadingPrompt))
            .build();
}
```

#### 链式顺序——最容易被忽视的坑

**Advisor 顺序是敏感的**，项目中的推荐顺序：

```
① MessageChatMemoryAdvisor  （第1个：先注入对话历史，否则 RAG 拿不到正确上下文）
   ↓
② ReReadingAdvisor          （第2个：让 LLM 先重读问题再做推理）
   ↓
③ QuestionAnswerAdvisor     （第3个：注入 RAG 检索到的知识）
   ↓
④ MyLoggerAdvisor           （第4个：最后才打印，此时 prompt 已是最完整版本）
```

**如果顺序错了会怎样？**
- `MyLoggerAdvisor` 放最前：日志只能看到"还没注入历史"的原始请求，看不到 RAG 上下文；
- `MessageChatMemoryAdvisor` 放 RAG 之后：历史消息和 RAG 上下文挤在一起，LLM 可能混淆；
- `QuestionAnswerAdvisor` 放记忆 Advisor 之前：RAG 检索的是没有对话历史的干净 query（有时候这是对的，取决于业务）。

**动态调整顺序**：
```java
// 临时追加，不影响 defaultAdvisors
chatClient.prompt()
        .user(message)
        .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, chatId)  // 找对应记忆
                .order(1)   // 调整顺序
        )
        .advisors(new QuestionAnswerAdvisor(vectorStore))  // 追加 RAG Advisor
        .call();
```

### Q2.3 `call()` 和 `entity()` 的区别？结构化输出怎么实现的？

- `chatClient.prompt().user(...).call().content()`：返回 `String`，纯文本回复。
- `chatClient.prompt().user(...).call().entity(LoveReport.class)`：返回指定类型的对象，Spring AI 自动让 LLM 输出符合目标 schema 的 JSON，再用 `ObjectMapper` 反序列化。

**项目示例**（`LoveApp.doChatWithReport`）：
```java
record LoveReport(String title, List<String> suggestions) {}
public LoveReport doChatWithReport(String message, String chatId) {
    LoveReport loveReport = chatClient
            .prompt()
            .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
            .user(message)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
            .call()
            .entity(LoveReport.class);
    return loveReport;
}
```

底层依赖 `com.github.victools:jsonschema-generator` 把 record 编译成 JSON Schema 放到 prompt 里，告诉 LLM 按这个格式输出。

### Q2.4 `ChatMemory` 接口默认有几个实现？项目中怎么替换？

Spring AI 提供：
- `InMemoryChatMemoryRepository`：纯内存（默认）。
- 项目自定义的 `FileBasedChatMemory`：基于 Kryo 序列化到磁盘。

**项目核心实现**：
```java
public List<Message> get(String conversationId) {
    File file = new File(BASE_DIR, conversationId + ".kryo");
    if (!file.exists()) return new ArrayList<>();
    try (Input input = new Input(new FileInputStream(file))) {
        return kryo.readObject(input, ArrayList.class);
    } catch (IOException e) { e.printStackTrace(); }
    return new ArrayList<>();
}
```

**关键技术细节**：
- 使用 `Kryo`（高性能 Java 序列化库，比 JDK Serializable 快 10 倍左右）；
- 设置 `setRegistrationRequired(false)` 不要求类注册；
- 设置 `setInstantiatorStrategy(new StdInstantiatorStrategy())` 支持无参构造函数的对象（Spring 的 Message 类型不一定有 public 无参构造）；
- 用 `MessageWindowChatMemory` 包装只保留最近 20 条消息（`maxMessages(20)`），避免上下文无限膨胀。

### Q2.5 `@Tool` 注解怎么工作？`returnDirect = false` 是什么意思？

`@Tool` 把任意 Bean 的方法暴露给 LLM 作为可调用的工具，Spring AI 通过 `ToolCallbacks.from(...)` 把所有带 `@Tool` 的方法包装成 `ToolCallback[]` 注册到 ChatClient。

**`PDFGenerationTool` 示例**：
```java
@Tool(description = "Generate a PDF file with given content", returnDirect = false)
public String generatePDF(
        @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
        @ToolParam(description = "Content to be included in the PDF") String content) {
    ...
}
```

- `description` 必填，LLM 用它判断何时调用；
- `@ToolParam(description = ...)` 给每个参数写说明，否则 LLM 可能填错；
- `returnDirect = false`（默认）：工具结果作为中间上下文交给 LLM，让 LLM 决定下一步（典型 ReAct loop）；
- `returnDirect = true`：工具结果直接当作最终回复返回，不再让 LLM 处理（适用于"翻译"这种终结型工具）。

**注册**：
```java
@Bean
public ToolCallback[] allTools() {
    return ToolCallbacks.from(
        new FileOperationTool(),
        new WebSearchTool(searchApiKey),
        ...
        new TerminateTool()
    );
}
```

### Q2.6 工具结果是怎么回传给 LLM 的？

通过 `ToolCallingManager`（Spring AI 提供）：
1. LLM 返回 `AssistantMessage`，里面带 `toolCalls`（包含工具名+参数）；
2. `toolCallingManager.executeToolCalls(prompt, chatResponse)` 自动调用工具；
3. 工具结果封装为 `ToolResponseMessage`；
4. 把 `messageList` 更新为 `conversationHistory`（包含 UserMessage、AssistantMessage、ToolResponseMessage 完整三段）；
5. 下一步 think 时整个 history 一起送给 LLM，LLM 就能基于工具结果推理下一步动作。

**关键代码**（`YuManus.act`）：
```java
ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
setMessageList(toolExecutionResult.conversationHistory());
```

---

## 3. SSE 与流式推送类

### Q3.1 SSE 和 WebSocket 有什么区别？为什么选 SSE？

| 特性 | SSE | WebSocket |
|------|-----|-----------|
| 协议 | HTTP（长连接、单向） | 独立协议（ws://，双向） |
| 方向 | 服务端 → 客户端（单向） | 全双工 |
| 自动重连 | 浏览器原生支持 | 需手写 |
| 鉴权 | 复用 HTTP Header | 需特殊处理 |
| 实现复杂度 | 低（文本流） | 中（帧协议） |

**为什么选 SSE**：
- AI 回复是单向流（服务端输出），不需要客户端频繁发消息；
- 实现简单，`SseEmitter` 一行就能用；
- 配合 HTTP 鉴权/Cookie 简单（前端 cookie 自动带）；
- 防火墙友好（不像 WebSocket 容易被拦截）。

### Q3.2 项目里用了哪 4 种流式方案？各自适合什么场景？

`AiController` 一共列了 4 种恋爱大师流式接口：

1. **`Flux<String>` + `MediaType.TEXT_EVENT_STREAM_VALUE`**（`/love_app/chat/sse`）：
   - WebFlux 风格，自动按 SSE 协议序列化；
   - 适合响应式项目。

2. **`Flux<ServerSentEvent<String>>`**（`/love_app/chat/server_sent_event`）：
   - 标准 SSE 协议，带 `data:` 前缀；
   - 客户端 EventSource 直接消费；
   - 兼容性最好。

3. **`SseEmitter`**（`/love_app/chat/sse_emitter` GET/POST）：
   - Spring MVC 同步线程模型下的 SSE；
   - 手动 `sseEmitter.send()` / `sseEmitter.complete()` 控制生命周期；
   - **项目实际用的是这个**（前端对接 `chatWithManusWithImage`）。

4. **WebFlux 的 Reactive SSE**：项目未直接用，但 Spring AI 的 `stream().content()` 走 Flux，能自动适配。

### Q3.3 `SseEmitter` 的超时怎么控制？前端断开怎么处理？

`YuManus.runStream` 创建时指定 5 分钟超时：
```java
SseEmitter sseEmitter = new SseEmitter(300000L);
```
并注册超时和完成回调：
```java
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
});
```

**核心要点**：
- SseEmitter 超时只是断开连接，不会真正中断后端业务；
- 业务跑在 `CompletableFuture.runAsync` 异步线程池，所以即使前端断网 Agent 也会跑完；
- 用户体验：前端 `fetch + AbortController` 可主动断开（cancel 按钮）；
- 资源清理：必须 `cleanup()`，否则 `ToolCallingManager` 和 `ChatMemory` 持有的资源会泄漏。

### Q3.4 SSE 协议细节：前端 `createSSEParser` 为什么用 `\n\n` 而不是 `\n` 切分？

SSE 协议规定每个事件以**两个换行符 `\n\n`** 结束（一行内容 + 一个空行）。但 TCP 是字节流，一个 SSE 事件可能被切成多个 chunk：
```
chunk1: "data: 这道"
chunk2: "菜是..."
chunk3: "\n\ndata: 下一条"
```

如果用 `EventSource.onmessage` 直接监听，浏览器只能拿到完整行，截断的部分会丢失。项目中 `createSSEParser` 的做法：
```javascript
const processBuffer = () => {
    const eventEnd = buffer.indexOf('\n\n')
    if (eventEnd === -1) {
        // 没找到完整事件，先把能切的行处理掉
        const lineEnd = buffer.indexOf('\n')
        if (lineEnd !== -1) {
            handleLine(buffer.slice(0, lineEnd))
            buffer = buffer.slice(lineEnd + 1)
        }
        return  // 等下个 chunk
    }
    // 找到 \n\n，处理整事件
    ...
}
```
缓冲区累积到 `\n\n` 才认为是一个完整事件，完美解决 TCP 分包问题。**这是项目里 SSE 解析的核心亮点**，面试可以重点讲。

### Q3.5 为什么用 `fetch + AbortController` 而不是 EventSource？

`EventSource`（浏览器原生 SSE 客户端）有几个硬伤：

1. **只支持 GET**：GET 的 query string 有长度限制，本项目碰到过 HTTP 431（Request Header Fields Too Large），代码超过 ~7KB 就爆；
2. **不能自定义 Header**：鉴权、Cookie 不好控制；
3. **断线重连是自动的**：但 Cancel 不友好，无法主动 abort；
4. **不支持 POST + FormData**：发图片必须用 fetch。

项目前端最终改用 `fetch + ReadableStream + AbortController`：
```javascript
const controller = new AbortController()
fetch(url, { method: 'POST', body: formData, signal: controller.signal })
    .then(response => response.body.getReader())
    .then(reader => createSSEParser(reader, decoder, callbacks))
```
这就是为什么 `chatWithManusWithImage` 同时支持文本和图片（统一走 POST + FormData）。

---

## 4. Agent 与 ReAct 类

### Q4.1 什么是 ReAct 模式？项目中怎么实现的？

ReAct = **Re**ason + **Act**，让 Agent 在每一步交替：
1. **Think**：LLM 推理，决定要不要调工具、调哪个、参数是什么；
2. **Act**：执行工具，把结果反馈给 LLM；
3. 循环直到任务完成或调用 `terminate` 工具。

**项目实现**（`BaseAgent` 定义生命周期，`ReActAgent` 实现 think+act）：

```java
public String step() {
    try {
        boolean shouldAct = think();   // 思考
        if (!shouldAct) {
            return getFinalResponse(); // 不要行动 → 取最后一条 AssistantMessage 作回复
        }
        return act();                  // 执行工具调用
    } catch (Exception e) {
        return "步骤执行失败：" + e.getMessage();
    }
}
```

主循环（`BaseAgent.run`）：
```java
for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
    String stepResult = step();
    if (StrUtil.isNotBlank(stepResult)) results.add(stepResult);
}
if (currentStep >= maxSteps) {
    state = AgentState.FINISHED;
    results.add("执行结束：达到最大步骤（" + maxSteps + "）");
}
```

### Q4.2 Agent 的状态机有几种状态？状态怎么流转？

```java
public enum AgentState { IDLE, RUNNING, FINISHED, ERROR }
```

**流转规则**：
- 创建 → `IDLE`；
- `run()` 第一行校验 `state != IDLE` 抛异常（防重入）；
- 进入循环 → `RUNNING`；
- 调用 `terminate` 工具 → `FINISHED`；
- catch Exception → `ERROR`；
- `for` 循环退出（达到 `maxSteps`）→ `FINISHED`；
- SSE 超时回调 → `ERROR`。

**踩坑**：状态流转不当时会导致 "上一轮没结束就开下一轮"，资源泄漏。本项目用 `cancelStream()` 在前端保证顺序：每次 send 前先 abort 上一次。

### Q4.3 为什么设计 `BaseAgent / ReActAgent / ToolCallAgent / YuManus` 四层继承？

**基于关注点分离的模板方法模式**：
- **`BaseAgent`**：通用骨架（state、step 循环、cleanup、run/runStream 双入口）——所有 Agent 都要；
- **`ReActAgent`**：定义 think() + act() + step() 协议 ——所有"会思考"的 Agent 都要；
- **`ToolCallAgent`**：think = 工具调用决策 + act = ToolCallingManager 执行 ——所有"会调工具"的 Agent 都要；
- **`YuManus`**：最终业务实现，可以覆盖 think/act 增加特性（如多模型切换）。

这样未来扩展只需继承 `ToolCallAgent` 重写 think/act，或继承 `ReActAgent` 改成别的规划模式（比如 Plan-and-Execute），代码复用最大化。**这是项目里 OO 设计最值得讲的地方**。

### Q4.4 `YuManus` 中"有图片强制走视觉模型"怎么实现的？

```java
private boolean hasImage;

public SseEmitter runStreamWithImage(String userPrompt, MultipartFile image) {
    this.hasImage = (image != null && !image.isEmpty());
    return super.runStreamWithImage(userPrompt, image);
}

@Override
public boolean think() {
    Prompt prompt = new Prompt(getMessageList(), chatOptions);
    ChatClient client = hasImage ? visionChatClient : chatClient;
    log.info("[think] hasImage={}, using client={}", hasImage, hasImage ? "visionChatClient" : "chatClient");
    ChatResponse chatResponse = client.prompt(prompt)
            .system(getSystemPrompt())
            .toolCallbacks(allTools)
            .call()
            .chatResponse();
    ...
}
```

**设计要点**：
- `BaseAgent.runStreamWithImage` 负责把图片加入 `messageList`（`addUserMessageWithImage` 用 `Media.builder().mimeType(...).data(...).build()` 包装）；
- `think()` 里根据 `hasImage` 切换 ChatClient；
- 视觉模型必须带 `withMultiModel(true)` + `withModel("qwen-vl-plus")`，否则模型识别不到图片。

### Q4.5 `TerminateTool` 为什么是必要的？

ReAct 循环没有"自然终止"信号——LLM 不会主动停止输出。`TerminateTool` 就是 Agent 的"刹车"：

```java
@Tool(description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further...")
public String doTerminate() {
    return "任务结束";
}
```

`act()` 检测到工具调用列表里有 `doTerminate` 就把 state 置为 `FINISHED`，跳出循环。

**如果去掉了会怎样**：
- Agent 跑满 `maxSteps` 才停（默认 8 步）；
- 任务明明 2 步就完成，还要额外浪费 6 次 LLM 调用；
- 流式输出里会出现"工具 doTerminate 返回的结果：任务结束"这种莫名行。

---

## 5. RAG 知识库类

### Q5.1 RAG 的完整 Pipeline 在项目里是怎么实现的？

```
文档源（md 文件）
   ↓ LoveAppDocumentLoader.loadMarkdowns()     // 读取 resources 下的 md
   ↓ TokenTextSplitter 或 MyTokenTextSplitter    // 按 token 切分，避免超长
   ↓ MyKeywordEnricher.enrichDocuments()         // LLM 提取关键词塞到 metadata
   ↓ EmbeddingModel（DashScope Embedding）        // 转 1536 维向量
   ↓ SimpleVectorStore / PgVectorStore.add()       // 存入向量库
   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
查询请求
   ↓ QueryRewriter.doQueryRewrite()              // RewriteQueryTransformer（可选）
   ↓ QuestionAnswerAdvisor（自动从 VectorStore 检索 TopK）
   ↓ context 拼到 prompt
   ↓ ChatClient.call() → LLM
```

### Q5.2 什么是 `MyKeywordEnricher`？为什么需要它？

```java
public List<Document> enrichDocuments(List<Document> documents) {
    // 让 LLM 从每个 Document 中抽取关键词
    // 注入到 Document.metadata.keywords
}
```

**核心动机**：向量检索本质是**语义相似度**，但有些查询是精确术语（比如某本书的标题、人名、专有名词），embedding 模型可能无法精确捕捉，导致检索召回率低。

**关键词增强**的做法：
- 入库前对每个 Document 用 LLM 抽取 3-5 个关键词，加入 `metadata.keywords`；
- 检索时同时匹配 embedding 相似度 + 关键词覆盖度；
- 有效降低"用户问 A，但文档里说 A 的别名/缩写"的漏召回。

**这是 RAG 调优的常见手段**，项目里很值得讲。

### Q5.3 `SimpleVectorStore` 和 `PgVector` 怎么选？

| 维度 | SimpleVectorStore | PgVectorStore |
|------|-------------------|---------------|
| 存储位置 | 内存 | PostgreSQL |
| 持久化 | 重启丢失 | 持久化 |
| 大规模检索 | 慢（全内存遍历） | 快（HNSW 索引） |
| 适用场景 | Demo / 测试 | 生产 |

**项目使用方式**：
- `LoveAppVectorStoreConfig`：默认用 `SimpleVectorStore`（配置文件未启用 PgVector）；
- `PgVectorVectorStoreConfig`：注释掉了，取消注释需先启动 PG；
- 配置项：
  ```java
  PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
      .dimensions(1536)         // 向量维度
      .distanceType(COSINE_DISTANCE)
      .indexType(HNSW)
      .initializeSchema(true)
      .build();
  ```

### Q5.4 `distanceType` / `indexType` 怎么选？

- **距离度量 `COSINE_DISTANCE`（余弦距离）**：文本 embedding 场景首选，与向量长度无关，关注方向差异。
- **索引类型 `HNSW`（Hierarchical Navigable Small World）**：近似最近邻，召回率高，内存大。
- **备选 `IVFFlat`**：快但精度略低，适合超大规模。

### Q5.5 什么叫"查询重写"？为什么 `QueryRewriter` 很重要？

```java
queryTransformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(builder)
        .build();

public String doQueryRewrite(String prompt) {
    Query query = new Query(prompt);
    return queryTransformer.transform(query).text();
}
```

**场景**：用户问"她不回消息怎么办"，原始 query embedding 召回率低。重写为"在恋爱关系中，对方已读不回时该如何沟通和处理"——更具体、信息量更大、embedding 召回效果更好。

`RewriteQueryTransformer` 是 Spring AI 1.0 官方提供的预检索增强（pre-retrieval），还有压缩（Compression）、扩展（Expansion）、HyDE（假设文档嵌入）等同类变换器。

### Q5.6 `QuestionAnswerAdvisor` 的工作原理？

`QuestionAnswerAdvisor` 是 Spring AI RAG 体系中的**检索增强入口**，同时实现了 `CallAdvisor` 和 `StreamAdvisor`，核心职责是：在 LLM 收到用户问题之前，**先从向量数据库检索相关文档，把检索结果作为上下文注入 prompt**。

#### 执行流程（6 步详解）

```
① 用户问题文本（"男朋友不回消息怎么办"）
       │
       ▼
② QuestionAnswerAdvisor.adviseCall() 前置逻辑：
   ├─ 从 request 中提取 userMessage 的 content
   ├─ 用 EmbeddingModel 把问题转成向量
   ├─ 调用 VectorStore.similaritySearch() → TopK 相关文档
   ├─ 把 TopK 文档内容拼接成一段上下文字符串
   └─ 把上下文注入到 Prompt（作为新的 SystemMessage 或在 userMessage 前拼接）
       │
       ▼
③ 带着上下文的 Prompt 发给 LLM（"根据以下知识：...，回答：..."）
       │
       ▼
④ LLM 返回回答（已经基于检索到的知识）
       │
       ▼
⑤ QuestionAnswerAdvisor.adviseCall() 后置逻辑：
   └─ observeAfter() 默认空实现（QnA Advisor 不需要修改响应）
       │
       ▼
⑥ 返回给调用方
```

#### 源码级实现拆解

```java
public class QuestionAnswerAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final QuestionAnswerAdvisorOptions options;  // 可配置 TopK、相似度阈值等

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        // Step 1：提取用户问题
        String userQuery = extractUserMessageText(request.getPrompt());

        // Step 2：向量检索
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userQuery)
                        .topK(options.getTopK())           // 默认 4
                        .similarityThreshold(options.getSimilarityThreshold())  // 默认 0.5
                        .build()
        );

        // Step 3：把检索结果拼成上下文字符串
        String context = documents.stream()
                .map(doc -> doc.getContent())
                .collect(Collectors.joining("\n\n"));

        // Step 4：注入到 Prompt（默认作为 SystemMessage 追加）
        Prompt modifiedPrompt = augmentPrompt(request.getPrompt(), context);
        //   实际注入的模板大致是：
        //   "根据以下知识回答用户问题。如果知识中没有相关信息，请如实告知。
        //    \n\n【知识库】\n" + context + "\n\n【用户问题】\n" + userQuery

        return ChatClientRequest.of(modifiedPrompt, request.getAdvisors());
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest modified = before(request);
        // 继续走链（下一个 Advisor 或 ChatModel）
        return chain.nextCall(modified);
    }
}
```

#### 默认 Prompt 模板

`QuestionAnswerAdvisor` 默认使用的注入模板：

```
请根据以下信息回答用户的问题。如果在提供的信息中没有找到相关答案，
请明确告知用户"我无法从已知信息中找到答案"，不要编造内容。

【相关背景知识】
{retrieved_documents}

【用户问题】
{user_query}

请给出回答：
```

#### 为什么要注入成 SystemMessage 而不是 UserMessage？

因为 LLM 对 SystemMessage 的"信任权重"通常高于 UserMessage——SystemMessage 里的内容被认为是"可靠的知识来源"，LLM 倾向于严格基于这段上下文回答，而不是自由发挥。这是 RAG 场景的最佳实践。

#### 关键配置项

```java
QuestionAnswerAdvisor.builder(vectorStore)
        .promptTemplate(...)           // 自定义注入模板
        .chatMemory(...)              // 可选：把检索结果也写入 ChatMemory
        .topK(6)                      // 检索 TopK 文档数（默认 4）
        .similarityThreshold(0.5)      // 相似度阈值（低于此分数的文档丢弃）
        .includeRawPrompt(true)       // 是否把检索结果也放到 UserMessage 里
        .build()
```

#### 为什么 `QuestionAnswerAdvisor` 和 `MessageChatMemoryAdvisor` 要配合使用？

一个经典误区是"我只用 `QuestionAnswerAdvisor`，不加 `MessageChatMemoryAdvisor`"。

**场景**：多轮对话中，用户第二句说"那怎么办"——如果不加记忆 Advisor，`QuestionAnswerAdvisor` 检索时只知道用户问的是"那怎么办"，不知道前文是"男朋友不回消息"，检索质量极差。

**正确做法**：两个 Advisor 组合使用，`MessageChatMemoryAdvisor` 把对话历史注入后，`QuestionAnswerAdvisor` 基于**完整上下文**检索，这样检索更精准。

```
MessageChatMemoryAdvisor（注入历史："男朋友不回消息怎么办"）
       ↓
QuestionAnswerAdvisor（检索时 query = "男朋友不回消息怎么办，那怎么办"）
       ↓
检索到的文档：["不回消息的沟通技巧", "恋爱关系中的情绪管理", ...]
       ↓
LLM 收到的完整 prompt = 历史消息 + RAG 上下文 + 当前问题
```

---

## 6. MCP 模型上下文协议类

### Q6.1 什么是 MCP？和 Tool Calling 的关系？

**MCP（Model Context Protocol）** 是 Anthropic 提出的开放协议，让 LLM 与外部数据源/工具通过标准化接口通信。**可以理解为"Tool Calling 的 HTTP 协议化版本"**。

| 对比 | Tool Calling | MCP |
|------|--------------|-----|
| 调用方 | LLM 通过 SDK（Spring AI）调用本地 Java 方法 | LLM 通过 MCP 协议调用远端/外部进程 |
| 部署 | 同进程、紧耦合 | 独立进程、可分布式 |
| 多语言支持 | 取决于主程序语言 | 任何语言实现 MCP Server |
| 复用 | 工具代码要重新写在每个项目 | MCP Server 可被多个客户端复用 |

**项目使用**（`LoveApp.doChatWithMcp`）：
```java
@Resource
private ToolCallbackProvider toolCallbackProvider;

chatClient.prompt()
        .user(message)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
        .toolCallbacks(toolCallbackProvider)   // ← 注入 MCP 工具集
        .call().chatResponse();
```

**两种接入模式**：
- **stdio 模式**：Spring AI 自动启动 MCP Server 子进程，通过 stdin/stdout 通信（`mcp-servers.json` 配置）；
- **SSE 模式**：连远程 MCP Server（`http://localhost:8127`）。

`pom.xml` 引入：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### Q6.2 MCP 解决了什么痛点？项目里怎么演示？

**痛点**：每个 AI 应用都要"自实现工具"——同样的图片搜索 API，100 个项目就要写 100 次。MCP 让工具提供者只需要写一次 Server，多个客户端就能复用。

**项目示例**（`mcp-servers.json` 配置图片搜索 MCP）：
- MCP Server 启动后注册 `searchImage(query)` 工具；
- Spring AI 通过 stdio 自动连接；
- LLM 看到 `searchImage` 工具后，按需调用 → 返回图片 URL → 渲染到前端。

---

## 7. 多模态类

### Q7.1 多模态图片消息是怎么构造的？

```java
Media media = Media.builder()
        .mimeType(MimeTypeUtils.parseMimeType(mimeType))
        .data(new ByteArrayResource(imageBytes))
        .build();

UserMessage userMessage = UserMessage.builder()
        .text(message)
        .media(media)
        .metadata(Map.of(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE))
        .build();
```

**关键 4 个细节**：
1. `Media` 包装图片字节流 + MIME（image/png、image/jpeg 等）；
2. `UserMessage.builder()` 显式构造（普通 `.user(text)` 是简写形式）；
3. `metadata` 必须带 `MESSAGE_FORMAT = IMAGE`，告诉 DashScope 这是图片消息（否则被当成纯文本，模型直接忽略图片）；
4. `ChatClient` 必须用 `visionChatClient`（含 `withModel("qwen-vl-plus").withMultiModel(true)`）。

### Q7.2 为什么不能用文本模型处理图片？

DashScope（阿里云百炼）不同模型的 `MultiModel` 标志位决定是否识别 Media：
- `qwen-plus` / `qwen-turbo`：纯文本，看到 `Media` 字段会忽略；
- `qwen-vl-plus`：多模态，能把图片转 base64 与文本联合推理。

如果用文本模型 + Media，要么报错要么图片直接被丢弃。

### Q7.3 视觉模型 Agent 一定要单独拆 ChatClient 吗？

不一定，理论上可以让 LLM 在 think 第一步"看图后"再切回文本模型，但项目出于简洁性选择了"有图就一直用视觉模型"。如果想优化，可以用：
- **第一轮用 vl-plus 提取图片描述，存入 messageList**；
- **后续轮次用 qwen-plus 继续思考和调工具**；

能省 token，但增加复杂度。本项目没做，可作为扩展点讲。

---

## 8. 持久化与缓存类

### Q8.1 为什么对话记忆选 Kryo 而不是 JDK Serializable 或 Jackson？

- **JDK Serializable**：
  - 慢（比 Kryo 慢 5-10 倍）；
  - 序列化结果大；
  - 反序列化有漏洞风险（反序列化攻击）；
  - 频繁调用会成为性能瓶颈。
- **Jackson（JSON）**：
  - 可读性好但慢；
  - 序列化结果大（特别是文本消息）；
  - 解决循环引用要加 `@JsonManagedReference` 注解。
- **Kryo**：
  - 速度快（二进制编码）；
  - 紧凑（体积小 50%+）；
  - 通过 `setInstantiatorStrategy(new StdInstantiatorStrategy())` 支持无 public 无参构造的对象，Spring AI 的 Message 类型可以顺利反序列化。

**项目配置**：
```java
kryo.setRegistrationRequired(false);  // 不要求预先注册类
kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
```

### Q8.2 对话历史会无限增长吗？怎么控制？

会无限增长！解决方案：

1. **`MessageWindowChatMemory` 滑动窗口**（项目用的）：
   ```java
   MessageWindowChatMemory.builder()
           .chatMemoryRepository(new InMemoryChatMemoryRepository())
           .maxMessages(20)
           .build();
   ```
   只保留最近 20 条，超过自动丢最老的。

2. **Token 窗口**（按 token 数截断）：

3. **摘要压缩**：把老的历史让 LLM 总结成一段摘要，新请求带上摘要 + 近期消息。

4. **定期清理**：`FileBasedChatMemory.clear(conversationId)` 主动删除文件。

---

## 9. 工程化与最佳实践

### Q9.1 Spring Boot 3.4 + Java 21 + Spring AI 1.0 这套组合在生产环境踩过什么坑？

**至少讲 3 个**：

1. **Spring AI BOM 版本对齐**：必须用 `spring-ai-bom` + `spring-ai-alibaba-bom` 一起 import，否则容易出现"API 不存在"或"方法签名不一致"。
   ```xml
   <dependencyManagement>
       <dependencies>
           <dependency>
               <groupId>com.alibaba.cloud.ai</groupId>
               <artifactId>spring-ai-alibaba-bom</artifactId>
               <version>1.0.0.2</version>
               <type>pom</type>
               <scope>import</scope>
           </dependency>
           <dependency>
               <groupId>org.springframework.ai</groupId>
               <artifactId>spring-ai-bom</artifactId>
               <version>1.0.0</version>
               <type>pom</type>
               <scope>import</scope>
           </dependency>
       </dependencies>
   </dependencyManagement>
   ```

2. **Spring Milestones 仓库**：Spring AI 部分版本只在 `https://repo.spring.io/milestone` 发布，必须加仓库源，否则 Maven 拉不到依赖。

3. **Lombok + Java 21 Record**：本项目大量用 `record`（如 `LoveReport`、`MessageFormat`），Lombok 的 `@Data` + `@EqualsAndHashCode(callSuper = true)` 在 record 父类的继承结构里要小心——部分版本 `@Data` 不会为 record 生成 equals/hashCode。

### Q9.2 整个项目用到了哪些设计模式？

- **模板方法**（`BaseAgent` / `ReActAgent` / `ToolCallAgent` / `YuManus` 四层继承）；
- **策略模式**（`visionChatClient` vs `chatClient` 根据 hasImage 切换）；
- **装饰器模式 / 责任链**（Spring AI 的 Advisor 链）；
- **工厂模式**（`ToolCallbacks.from(...)`、`LoveAppRagCustomAdvisorFactory` 等 Factory 类）；
- **外观模式**（`ChatClient` 屏蔽掉 ChatModel、Advisor、Options 三个组件）；
- **Builder 模式**（`ChatClient.builder()`、`DashScopeChatOptions.builder()`、`MessageWindowChatMemory.builder()`）；
- **状态机模式**（`AgentState` 枚举驱动的状态流转）。

### Q9.3 项目如何保证不重复执行 Agent？

两道防线：
1. **状态机校验**：每次 `run()` 先检查 `state == IDLE`，否则抛异常；
2. **前端互斥**：`sendMessage` 第一行 `cancelStream()`，先 abort 上一轮再开新轮。

**还有一个隐藏点**：`sseEmitter.send("[DONE]")` + `complete()` 后，`onCompletion()` 回调把 state 强制置 `FINISHED`，但如果中途出 bug 没走完流程，state 可能一直 `RUNNING`。**生产建议**：加定时 watchdog，超时强制重置。

### Q9.4 如果让你把项目部署上线，怎么做？

答案要点（按环节）：

1. **模型选型**：
   - 文本：qwen-plus / qwen-max（按成本/质量权衡）；
   - 视觉：qwen-vl-plus；
   - 建议接入 OpenAI 兼容接口，便于切换。

2. **向量库**：用 `PgVector` 或迁到 Milvus / Qdrant / ElasticSearch，**千万别用 `SimpleVectorStore` 上生产**（重启即丢）。

3. **对话存储**：Kryo 文件存储扩展性差，改用 Redis（key: chatId，value: list of messages）+ 定期归档到 MySQL。

4. **限流**：DashScope 有 QPS 限制，必须用 Sentinel / Resilience4j 做限流和重试。

5. **监控**：Sleuth + Zipkin 追踪每次 LLM 调用耗时、token 数；Prometheus + Grafana 看 QPS 和错误率。

6. **可观测性**：把 prompt、response、tool call 入库审计（合规要求）。

7. **Serverless 部署**：阿里云 FC / AWS Lambda 都支持 Java，但冷启动慢（Java 镜像 200MB+），建议用 Aliyun SAE（Spring Boot 应用托管）或自建 Docker Swarm。

8. **Docker 化**：项目已经有 `Dockerfile`，可以把 Spring Boot 打 fat jar + 前端 nginx 反向代理。

---

## 10. 性能与可扩展性

### Q10.1 系统最慢的一环是什么？怎么优化？

**最慢环节**：LLM 调用（200ms ~ 数秒，每次工具调用又是新一轮 LLM）。
**优化方向**：
1. **Agent 步数收敛**：在 prompt 里强约束"3 步内完成"，避免循环；
2. **并行工具调用**：Spring AI 支持一次返回多个 toolCall，如果工具之间无依赖，并发执行；
3. **结果缓存**：相同 query + 相同 chatId 的 RAG 检索结果用 Caffeine 缓存；
4. **流式优先**：用户感知延迟从"等 LLM 全跑完"变成"第一个字延迟"，体验显著提升；
5. **Prompt 裁剪**：长 messageList 截断。

### Q10.2 当前架构的瓶颈在哪里？

1. **每请求 new YuManus**：`AiController.doChatWithManus` 每次都 `new YuManus(allTools, dashscopeChatModel)`，浪费资源。应改为 `@Scope("prototype")` 或 `@Component` 单例 + 方法级传参。
2. **同步 SSE 阻塞**：Agent 跑在 `CompletableFuture.runAsync`，默认线程池的队列是 `LinkedBlockingQueue`，高并发下会堆任务。需配置 `ThreadPoolTaskExecutor` 自定义。
3. **向量库内存模式**：`SimpleVectorStore` 不支持持久化和并发。
4. **Kryo 文件存储**：单机磁盘 I/O，且不支持分布式会话。

### Q10.3 如果 QPS 从 10 涨到 1000，你怎么做？

**从上到下梳理**：
1. **接入层**：Nginx 多副本 + 负载均衡 SLB；
2. **应用层**：Spring Boot 无状态化水平扩展（K8s HPA），会话存 Redis；
3. **大模型层**：用 DashScope 企业版 QPS 配额，或自部署 vLLM 推理集群；
4. **缓存层**：Redis 缓存热门问答 + 向量库查询结果；
5. **限流熔断**：Sentinel 针对 ChatClient 做令牌桶限流；
6. **异步化**：长任务丢消息队列（Kafka/RocketMQ）+ worker 消费，前端走 SSE + status 轮询。

---

## 11. 测试与质量保障

### Q11.1 项目里有哪些测试？覆盖了哪些场景？

`src/test/java/com/yupi/yuaiagent` 下的测试类：
- `YuManusTest`：端到端 Agent 测试；
- `LoveAppTest`：聊天接口测试；
- `tools/FileOperationToolTest` / `WebSearchToolTest` / `PDFGenerationToolTest` / `TerminalOperationToolTest` 等：每个工具独立测试；
- `rag/PgVectorVectorStoreConfigTest` / `LoveAppDocumentLoaderTest`：RAG 模块测试；
- `demo/rag/MultiQueryExpanderDemoTest`：多查询扩展 demo 测试。

**测试金字塔**：
- 单元测试：工具方法（无需 LLM）；
- 集成测试：Agent + 真实 LLM（很贵，按需跑）；
- 没明确的契约测试 / 性能测试 / 负载测试（可作为改进点）。

### Q11.2 你会怎么测试一个有工具调用的 Agent？

**5 类测试策略**：
1. **Mock LLM**：用 Spring AI 的 `ChatModel` mock，让 Agent 不真的调通义千问，单元测试 `think()` 决策；
2. **真实 LLM + Mock 工具**：用 `MockBean` 替换 `ToolCallback[]`，只测 LLM 是否正确选择工具；
3. **端到端**：跑真实 LLM + 真实工具，验证完整 ReAct 循环；
4. **回放测试**：把 LLM 调用结果保存为"夹具"，重放验证确定性；
5. **对抗测试**：构造恶意 prompt（如 prompt injection），验证 Agent 安全拒绝。

---

## 12. 算法与底层原理（深度）

### Q12.1 Spring AI 的 Advisor 链是如何串联的？底层原理深度剖析

#### 宏观：责任链模式

Spring AI 的 Advisor 机制本质上是**责任链模式（Chain of Responsibility）**的 Spring AI 实现：

```
ChatClientRequest
       │
       ▼
┌─────────────────────────────────────────────────┐
│  AdvisorChain（CallAdvisorChain / StreamAdvisorChain）│
│                                                    │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  │ MyLoggerAdv  │→   │ MemoryAdv    │→   │ QnA Advisor  │→ ...
│  │ (order=0)   │    │ (order=1)    │    │ (order=2)    │
│  └──────────────┘    └──────────────┘    └──────────────┘
│                                                      │
└──────────────────────────────────────────────────────┘
       │
       ▼  chain.nextCall(request)
┌──────────────┐
│   ChatModel   │  ← 链的最末端：真正发起 HTTP 请求到 LLM
│  (DashScope)  │
└──────────────┘
```

**关键点**：`CallAdvisorChain` 本身不是 Advisor，它是一个**导航器**——负责维护当前 Advisor 的索引，按顺序驱动每个 Advisor 的 `adviseCall()`，并在最后一个 Advisor 后面自动接上 `ChatModel`。

#### 中观：ChatClient 的内部调用链

`ChatClient` 内部构建链的入口在 `ChatClient` 的构造方法中（源码简化）：

```java
// ChatClient.builder() 内部
public ChatClient build() {
    // 1. 收集所有 Advisor（default + 运行时追加的）
    List<CallAdvisor> allAdvisors = new ArrayList<>(this.defaultAdvisors);

    // 2. 创建调用链，把 ChatModel 放到链的末端
    CallAdvisorChain chain = new DefaultCallAdvisorChain(allAdvisors, this.chatModel);

    // 3. 返回的 ChatClient 内部持有这个 chain
    return new ChatClient(chatModel, chain, defaultOptions);
}
```

发起调用时（`chatClient.prompt().user(...).call()` 简化）：

```java
public String content() {
    // 1. 把 .user() / .system() 的 Builder 调用攒成 Prompt
    Prompt prompt = this.promptBuilder.build();

    // 2. 攒成 ChatClientRequest（含 Prompt + Advisor 列表）
    ChatClientRequest request = ChatClientRequest.of(prompt, this.advisors);

    // 3. 从链头开始：调用链的 nextCall
    //    第一棒：MyLoggerAdvisor.adviseCall(request, chain)
    //    第二棒：MessageChatMemoryAdvisor.adviseCall(request, chain)
    //    第三棒：QuestionAnswerAdvisor.adviseCall(request, chain)
    //    最后一棒：ChatModel.call(prompt) ← 真正发 HTTP 请求
    ChatClientResponse response = this.chain.nextCall(request);

    // 4. 返回 ChatClientResponse 中的 text
    return response.chatResponse().getResult().getOutput().getText();
}
```

#### 微观：单个 Advisor 的完整生命周期

以 `MessageChatMemoryAdvisor` 为例，拆解它在一个请求中的全部行为：

**阶段 1：请求前（`before` 逻辑，位于 `adviseCall` 方法体开头）**
```java
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

    // 从 request 中取出 conversationId（来自 .advisors(spec -> spec.param("conversationId", chatId))）
    String conversationId = extractConversationId(request);

    // Step 1：从 ChatMemory 中读取该 conversationId 的历史消息
    List<Message> history = chatMemory.get(conversationId);  // 如：从 Kryo 文件反序列化

    // Step 2：把历史消息注入到当前 Prompt 的 messageList 最前面
    //         （此时 request.prompt() 里只有当前用户的消息，加上历史后变成完整上下文）
    Prompt modifiedPrompt = prependHistory(request.prompt(), history);

    // Step 3：修改 request 对象（不修改原始对象，返回新副本），替换成含历史的 prompt
    ChatClientRequest modifiedRequest = ChatClientRequest.builder()
            .prompt(modifiedPrompt)
            .options(request.getOptions())
            .advisors(request.getAdvisors())  // 保留，后续 Advisor 继续生效
            .build();

    // 调用链的下一个环节（交给下一个 Advisor，最终交给 ChatModel）
    ChatClientResponse response = chain.nextCall(modifiedRequest);

    // ───────────────────────────────────────────────────────────────
    // 注意：上面是 before（请求前），下面这段是 after（响应后）
    // 如果是流式（adviseStream），这段在流结束后才执行
    // ───────────────────────────────────────────────────────────────

    // Step 4：响应回来了，把本轮用户消息 + AI 回复写入 ChatMemory
    Message userMessage = extractUserMessage(modifiedRequest.prompt());
    Message assistantMessage = extractAssistantMessage(response.chatResponse());
    chatMemory.add(conversationId, Arrays.asList(userMessage, assistantMessage));

    return response;  // 继续往回传（响应从后往前走）
}
```

**为什么修改 Request 而不是直接改 Prompt 对象？** 因为 `ChatClientRequest` 是不可变对象，且链上可能有多个 Advisor 都需要修改，每个 Advisor 应该基于自己期望的版本来改，而不是共享同一个可变对象。

#### 响应回传阶段——从后往前的"洋葱模型"

```
ChatModel 返回 ChatClientResponse
        │
        ▼
QuestionAnswerAdvisor.adviseCall() → observeAfter(response) → return response
        │
        ▼
MessageChatMemoryAdvisor.adviseCall() → observeAfter(response) → return response
        │
        ▼
MyLoggerAdvisor.adviseCall() → observeAfter(response) → return response
        │
        ▼
ChatClient 拿到 ChatClientResponse，取 .getText() 返回给调用方
```

**每个 Advisor 都可以在 `observeAfter` 阶段修改 `ChatClientResponse`**——比如替换响应文本、注入额外 metadata、记录监控指标。这也是为什么顺序重要的原因：谁最后处理 response，谁就掌握了最终返回给用户的内容。

#### 流式场景下的不同——StreamAdvisorChain

流式场景的逻辑略有不同，因为 LLM 返回的是 `Flux<ChatResponse>`（一批碎片），不是一条完整消息：

```java
public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {

    // 前置处理（和同步一样）
    ChatClientRequest modified = before(request);

    // 交给链的下一环，返回 Flux<ChatResponse>（每个 LLM chunk 一个元素）
    Flux<ChatClientResponse> flux = chain.nextStream(modified);

    // 关键！把 Flux 聚合成一个完整响应，再触发 after
    return new ChatClientMessageAggregator()
            .aggregateChatClientResponse(flux, this::observeAfter);
}
```

`ChatClientMessageAggregator` 的工作流程（伪代码）：

```java
// 内部逻辑
Flux<ChatClientResponse> aggregateChatClientResponse(
        Flux<ChatClientResponse> flux,
        Consumer<ChatClientResponse> afterCallback) {

    return flux
        .buffer()                    // 把所有 chunk 收集到一个 List
        .flatMap(responses -> {
            // 把碎片聚合成一个完整 response
            ChatClientResponse aggregated = ChatClientResponseAggregator.aggregate(responses);
            // 在流结束时，触发 after 回调（打印最终日志 / 写内存等）
            afterCallback.accept(aggregated);
            return Flux.just(aggregated);
        });
}
```

**如果没有 Aggregator**：流式场景下 `before` 会被调用 N 次（N = chunk 数量），`after` 也会被调用 N 次——日志里全是碎片，无法看到完整请求/响应。有了 Aggregator，`before` 只触发 1 次，`after` 也只触发 1 次。

#### Advisor 排序机制——`getOrder()`

每个 Advisor 通过 `getOrder()` 返回整数值决定顺序：

```java
// MyLoggerAdvisor
@Override
public int getOrder() {
    return 0;   // 数值越小越靠前（Spring 的 Ordered 接口语义）
}
```

Spring AI 在构建 `DefaultCallAdvisorChain` 时，会对所有 Advisor 按 `order` 升序排列。

**项目中实际使用注意**：`ReReadingAdvisor` 需要在 `QuestionAnswerAdvisor` **之前**执行（先让 LLM 重读问题，再去 RAG 检索），否则 RAG 检索的是重写前的问题，顺序反了效果差很多。如果需要精细控制，可以在构造时显式指定 order：

```java
.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(chatMemory).build(),
    new ReReadingAdvisor(),          // order 默认 0，和 MyLogger 一样
    new QuestionAnswerAdvisor(vectorStore)  // order 默认 0
)
```

#### 手写一个自定义 Advisor——最小完整示例

掌握原理后，手写一个自定义 Advisor 其实非常简单：

```java
@Slf4j
public class TokenCountAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() { return "TokenCountAdvisor"; }

    @Override
    public int getOrder() { return Integer.MAX_VALUE; }  // 最后执行（观察最终请求）

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        Prompt prompt = request.getPrompt();
        // 粗略估算 token 数（中文 1 token ≈ 1.5 字符，英文 1 token ≈ 4 字符）
        String text = prompt.getInstructions().getTextContent();
        int estimatedTokens = (int) (text.length() / 2.5);
        log.info("[TokenCount] 预估输入 tokens: {}", estimatedTokens);
        return request;  // 不修改请求，直接透传
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest modified = before(request);
        ChatClientResponse response = chain.nextCall(modified);
        observeAfter(response);  // 统计输出 tokens
        return response;
    }

    private void observeAfter(ChatClientResponse response) {
        String text = response.chatResponse().getResult().getOutput().getText();
        int estimatedTokens = (int) (text.length() / 2.5);
        log.info("[TokenCount] 预估输出 tokens: {}", estimatedTokens);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        before(request);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(flux, this::observeAfter);
    }
}
```

注册方式：
```java
@Bean
public ToolCallback[] allTools(...) { ... }

@Bean
public TokenCountAdvisor tokenCountAdvisor() {
    return new TokenCountAdvisor();
}
```

然后在 `ChatClient` 中使用：
```java
ChatClient.builder(dashscopeChatModel)
        .defaultAdvisors(
            new MyLoggerAdvisor(),
            new TokenCountAdvisor()   // ← 直接加进去
        )
        .build();
```

### Q12.2 ChatClient 同步与流式调用底层区别？

```java
// 同步
chatClient.prompt().user(msg).call().content();
// 内部：ChatModel.call(prompt) → 返回 ChatResponse → 取 text

// 流式
chatClient.prompt().user(msg).stream().content();
// 内部：ChatModel.stream(prompt) → 返回 Flux<ChatResponse> → 每帧 → 过滤 text
```

流式的核心是底层 DashScope SDK 返回 `Flux<ChatResponse>`，Spring AI 把每帧 `ChatResponse.getResult().getOutput().getText()` 取出来拼成最终的 `Flux<String>`。

**项目偏好**：流式用于长输出（聊天、报告），同步用于短调用（工具判断完毕即可返回）。

### Q12.3 `withInternalToolExecutionEnabled(false)` 是什么意思？

在 `ToolCallAgent` 和 `YuManus` 都有：
```java
chatOptions = DashScopeChatOptions.builder()
        .withInternalToolExecutionEnabled(false)
        .build();
```

**含义**：**禁止 Spring AI 框架自动执行工具**。

**默认行为**：Spring AI 看到 LLM 返回 `toolCalls` 时，会**自动同步执行工具**并把结果拼回去，整个 call 一次性返回结果。这种模式 LLM 看不到中间思考过程。

**设置成 false 后**：只有 LLM 决策 + 返回 toolCalls，工具执行交给应用层（`ToolCallingManager.executeToolCalls`），应用可以：
- 决定每个 tool 的执行顺序；
- 控制错误处理；
- 把每步工具结果用 SSE 流式推给前端；
- 检查是否调用了 `terminate` 工具以决定终止。

**这是项目 ReAct 循环能稳定运行的关键**。

### Q12.4 RAG 检索的 TopK 太小或太大有什么影响？

`QuestionAnswerAdvisor` 默认返回 Top 4-6 条文档。
- **TopK 太小**（如 1-2）：召回率低，复杂问题缺上下文；
- **TopK 太大**（如 20+）：噪声多，token 暴涨，响应慢且容易"重点被淹没"。

**调优经验**：
- 短答案场景：TopK = 3-4；
- 复杂推理场景：TopK = 6-10 + 加 ReRank；
- 用 MMR（最大边际相关性）避免返回相似度过高的冗余文档。

---

## 13. 前端 Vue 3 类

### Q13.1 前端如何处理多种 SSE 推送模式？

`src/utils/chat.js` 的 `createSSEParser` 兼容：
- **EventSource 原生 SSE**（项目大部分文本对话）：
  ```javascript
  const eventSource = new EventSource(url)
  eventSource.onmessage = (event) => ...
  ```
- **fetch 流式**（带图片必须用）：
  ```javascript
  const controller = new AbortController()
  fetch(url, { method: 'POST', body: formData, signal: controller.signal })
      .then(response => response.body.getReader())
      .then(reader => createSSEParser(reader, decoder, callbacks))
  ```

`createSSEParser` 内部用 `ReadableStreamDefaultReader.read()` 循环读 chunk，累积 `\n\n` 后切成事件，调用 `onChunk` 回调。

### Q13.2 路由有哪几个页面？分别对应什么功能？

```javascript
const routes = [
  { path: '/',             name: 'Home',         → Home.vue          首页 + 应用入口 },
  { path: '/love-master',  name: 'LoveMaster',   → LoveMaster.vue    AI 恋爱大师 },
  { path: '/super-agent',  name: 'SuperAgent',   → SuperAgent.vue    AI 超级智能体 }
]
```

`ChatRoom.vue` 是抽象公共组件，负责消息列表渲染、状态徽标、输入框和取消/重试按钮。两个聊天页面（LoveMaster / SuperAgent）只是把它包了一层，传不同的 `ai-type` 显示标题。

**优点**：聊天 UI 改动只改一处即可全平台生效。

### Q13.3 前端开发环境的代理怎么配？

`vite.config.js`：
```javascript
server: {
  port: 3000,
  cors: true,
  proxy: {
    '/api': {
      target: process.env.VITE_DEV_PROXY_TARGET || 'http://localhost:8123',
      changeOrigin: true
    }
  }
}
```

**作用**：浏览器请求 `localhost:3000/api/ai/manus/chat` → Vite 转发到 `localhost:8123/api/ai/manus/chat`，避免跨域。

`API_BASE_URL` 在 `src/api/index.js` 读取环境变量 `VITE_API_BASE_URL`，没设就 fallback 到 `/api`（走 Vite 代理）。

---

## 14. 常见追问 / 拓展思考

### Q14.1 项目里 Agent 是单步还是有规划？

**当前**：单步（每次 think 只决定下一步动作，没显式的"规划阶段"）。

**改进方向**（Plan-and-Execute 模式）：
1. **第一阶段**：让 LLM 先生成完整 TodoList；
2. **第二阶段**：依次执行 TodoList 的每一步，遇到失败自动调整；
3. **优势**：任务完成度更高，token 利用率更高；
4. **代价**：实现复杂，需要两层 LLM 调用。

项目 `YuManus.NEXT_STEP_PROMPT` 已经一定程度引导 LLM "把任务拆成多步"，但仍是局部规划，不是显式全局规划。

### Q14.2 如果 LLM 调用超时或者限流了怎么处理？

**当前**：异常会被 `try-catch` 捕获，state 置 `ERROR`，sseEmitter 发错误消息后 complete。

**改进**：
1. **超时控制**：用 Resilience4j 给 `ChatModel.call()` 加 timeout（默认 30s）；
2. **重试**：同 query 重试 2-3 次（指数退避）；
3. **降级**：超限切备用模型（qwen-plus → qwen-turbo）；
4. **熔断**：连续失败 N 次熔断 60s，避免雪崩；
5. **告警**：通过钉钉/飞书 webhook 通知开发。

### Q14.3 项目中的 PDF 生成对中文支持是怎么做的？

`PDFGenerationTool.loadChineseFont()` 依次尝试：
1. `C:/Windows/Fonts/msyh.ttc`（微软雅黑，TTC 字体集）；
2. `C:/Windows/Fonts/simsun.ttc`（宋体）；
3. `C:/Windows/Fonts/simhei.ttf`（黑体）；
4. 都不行就用 `STSongStd-Light`（iText 内置中文字体）；
5. 都不行 fallback 到默认（PDF 乱码）。

**踩坑点**：
- iText 9 中字体加载必须用 `IDENTITY_H` 编码（支持 Unicode）；
- `font-asian` 包提供了一组亚洲字体，但体积大，按需引入；
- TTC 文件 iText 需要明确指定 `0` 索引 `createFont(path, PdfEncodings.IDENTITY_H, true)`。

### Q14.4 项目有什么可以优化的点？（开放性）

**常规答 3-5 个**：

1. **Agent 单元测试覆盖**：当前测试集中在工具层，Agent 端到端测试靠人工。需要补 mock 测试和回放测试。
2. **会话存储升级**：Kryo 文件 → Redis Cluster，高可用、强一致、支持会话共享。
3. **向量库选型升级**：SimpleVectorStore → Milvus / Qdrant，支持十亿级文档。
4. **多 Agent 协作**：当前是单体 Agent，可以拆 `Planning Agent` + `Executor Agent` + `Reviewer Agent`，通过 A2A 协议通信（项目 pom 暂未引入）。
5. **Prompt 版本管理**：当前 prompt 硬编码，应迁到 Nacos 配置中心，支持 A/B 实验。
6. **可观测性**：补 OpenTelemetry，把 LLM 调用 token 数 / 时延入库做大盘。
7. **MCP Server 自研**：项目只用 MCP Client 没写 Server，可以补一个图片搜索 / 数据库查询 MCP Server。

---

## 15. 反问环节（候选人主动问面试官）

参考问几个：

1. **团队当前 AI 应用主要场景是什么？是 Chatbot 还是 Agent 还是 RAG 检索？**
2. **线上 QPS 大概多少？后端用 Spring AI 还是 Python（LangChain）更多？**
3. **大模型选型策略？是自己部署（vLLM）还是用云上 API？**
4. **团队的 RAG 检索链路是单一向量库还是混合（BM25 + 向量）？**
5. **MCP 在你们生产中落地情况如何？有没有自研 MCP Server？**
6. **Agent 这块是单 Agent 还是多 Agent 协作？有没有用 LangGraph / A2A？**
7. **工程上最头疼的是什么——是 prompt 调试、token 成本、性能瓶颈、还是效果评估？**

---

## 总结

## 16. LangChain4j vs Spring AI 对比（本项目实际用法）

### Q16.1 项目里哪里用到了 LangChain4j？怎么用的？

**只有一处**，在 `demo/invoke/LangChainAiInvoke.java`，仅作**多方式调用大模型的示例展示**（教学目的），生产代码完全没有用到：

```java
package com.yupi.yuaiagent.demo.invoke;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class LangChainAiInvoke {

    public static void main(String[] args) {
        ChatLanguageModel qwenChatModel = QwenChatModel.builder()
                .apiKey(TestApiKey.API_KEY)
                .modelName("qwen-max")
                .build();
        String answer = qwenChatModel.chat("我是程序员kyrie...");
        System.out.println(answer);
    }
}
```

**项目演示了 4 种调用大模型的方式**（全部在 `demo/invoke/` 包下）：

| 类名 | 调用方式 | 框架 |
|------|---------|------|
| `HttpAiInvoke` | 直接调 HTTP REST API | 原生（无框架） |
| `SdkAiInvoke` | 用 `dashscope-sdk-java` | 阿里云 SDK |
| `SpringAiAiInvoke` | `ChatModel.call()` | **Spring AI** |
| `LangChainAiInvoke` | `ChatLanguageModel.chat()` | **LangChain4j** |
| `OllamaAiInvoke` | Ollama HTTP API | 原生 + Ollama SDK |

`pom.xml` 引入 LangChain4j：
```xml
<!-- LangChain4J DashScope -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-dashscope</artifactId>
    <version>1.0.0-beta2</version>
</dependency>
```

但没有在任何 `@Component` / `@Configuration` / `@Service` 中注入使用，只是 demo。

### Q16.2 既然引入了 LangChain4j，为什么项目生产代码全部用 Spring AI？

**核心原因：Spring Boot 生态的深度集成**。本项目是 Spring Boot 项目，Spring AI 天然融入 Spring 生态：

- **Bean 注入**：直接 `@Resource ChatModel` / `@Resource ChatClient`，零配置；
- **Advisor 链**：Spring AI 的 AOP 环绕拦截机制，与 Spring IoC 无缝集成；
- **配置管理**：`application.yml` / `@ConfigurationProperties` 统一管理 API Key、options；
- **生命周期**：Spring Boot 启动时自动初始化 ChatModel；
- **MVC 适配**：`@RestController` + `SseEmitter` / `Flux` 直接返回。

而 LangChain4j 虽然也能注入 Spring，但集成深度不如 Spring AI——需要手动构建模型实例、管理生命周期、适配 Spring WebFlux/SSE。

**另一个原因**：Spring AI Alibaba（`spring-ai-alibaba-starter-dashscope`）针对阿里云做了专项优化（DashScope API、百炼平台、多模态模型），开箱即用；LangChain4j 的 DashScope 集成（`langchain4j-community-dashscope`）是社区维护，更新可能滞后。

### Q16.3 Spring AI 和 LangChain4j 核心概念对照

| 概念 | Spring AI | LangChain4j |
|------|-----------|-------------|
| 模型调用入口 | `ChatModel`（底层） / `ChatClient`（高层） | `ChatLanguageModel` |
| Prompt 构造 | `Prompt` / `.user()` / `.system()` Builder | `AiServices` + 注解 |
| 工具调用 | `@Tool` + `ToolCallback[]` | `@Tool` + `MethodExecutor` |
| 记忆 | `ChatMemory`（接口）+ 多种实现 | `ChatMemory`（接口） |
| 向量存储 | `VectorStore`（接口）+ PgVector / Simple / Redis 等 | `EmbeddingStore`（接口） |
| RAG | `QuestionAnswerAdvisor`（Advisor 模式） | `AiServices.with(...).retriever(...)` |
| 流式 | `Flux<String>` / `SseEmitter` | `Flux<String>` |
| 结构化输出 | `.call().entity(Class)` | `AiServices` 泛型方法 |
| MCP | `spring-ai-starter-mcp-client`（官方支持） | `langchain4j-mcp`（社区） |
| 多模态 | `Media` + `UserMessage.builder().media()` | `Image` + `UserMessage` |
| Spring 生态 | 深度集成（原生） | 需要手动适配 |

### Q16.4 代码风格对比：同一个功能两边分别怎么写？

#### 1. 基本对话

**Spring AI（`SpringAiAiInvoke`）**：
```java
@Resource
private ChatModel dashscopeChatModel;

AssistantMessage msg = dashscopeChatModel.call(new Prompt("你好"))
        .getResult().getOutput();
```

**LangChain4j（`LangChainAiInvoke`）**：
```java
ChatLanguageModel model = QwenChatModel.builder()
        .apiKey(apiKey).modelName("qwen-max").build();
String answer = model.chat("你好");
```

LangChain4j 更简洁（Builder 一行搞定），Spring AI 更规范化（分层：Model → Prompt → Response）。

#### 2. 带工具调用的 Agent（项目实际写法）

**Spring AI（本项目 `YuManus`）**：
```java
// 工具定义
public class PDFGenerationTool {
    @Tool(description = "生成 PDF", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "文件名") String fileName,
            @ToolParam(description = "内容") String content) { ... }
}

// 注册
@Bean
public ToolCallback[] allTools() {
    return ToolCallbacks.from(new PDFGenerationTool(), new TerminateTool(), ...);
}

// 调用
ChatResponse chatResponse = chatClient.prompt(prompt)
        .system(getSystemPrompt())
        .toolCallbacks(allTools)
        .call()
        .chatResponse();
```

**LangChain4j 等效写法**（`AiServices` 风格）：
```java
interface MyAssistant {
    @Tool("生成 PDF")
    String generatePDF(@P("文件名") String fileName,
                       @P("内容") String content);
}

MyAssistant assistant = AiServices.builder(MyAssistant.class)
        .chatLanguageModel(model)
        .tools(new PDFGenerationTool())
        .build();

String result = assistant.chat("帮我生成一个约会计划 PDF");
```

LangChain4j 用**接口 + 注解**定义工具集，Spring AI 用**注解直接加在 Bean 方法**上。本项目选择 Spring AI 的原因是它和 Spring Boot 生态绑定更深，不需要额外的接口定义。

#### 3. RAG 检索问答

**Spring AI（本项目 `LoveApp.doChatWithRag`）**：
```java
// 配置向量库
VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
vectorStore.add(documents);

// 查询
ChatResponse response = chatClient.prompt()
        .user(query)
        .advisors(new QuestionAnswerAdvisor(vectorStore))  // Advisor 模式
        .call()
        .chatResponse();
```

**LangChain4j 等效写法**：
```java
EmbeddingStore<TextSegment> store = // ...
store.add(documents);

Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .embeddingStore(store)
        .build();

String answer = assistant.chat(query);  // 自动拼接检索结果
```

Spring AI 用 **Advisor 链**显式注入 RAG 上下文（可以控制顺序和模板），LangChain4j 用**隐式 retriever**（`AiServices` 内部自动完成检索和拼装，更简洁但灵活性略低）。

#### 4. 结构化输出（生成 JSON）

**Spring AI（本项目 `LoveApp.doChatWithReport`）**：
```java
record LoveReport(String title, List<String> suggestions) {}

LoveReport report = chatClient.prompt()
        .system(SYSTEM_PROMPT + "生成结构化报告...")
        .user(message)
        .call()
        .entity(LoveReport.class);  // ← 一行搞定反序列化
```

**LangChain4j 等效写法**：
```java
record LoveReport(String title, List<String> suggestions) {}

MyAssistant assistant = AiServices.builder(MyAssistant.class)
        .chatLanguageModel(model)
        .build();

// 需要构造 ExpectedAiResponseFormat，手动传 JSON Schema
LoveReport report = assistant.chat(
    "生成恋爱报告",
    AiRequest.builder()
        .expectedResponseFormat(ExpectedAiResponseFormat.builder()
            .languageModelJsonSchema(toolDefinition(LoveReport.class))
            .build())
        .build()
);
```

Spring AI 的 `.entity()` 一行完事；LangChain4j 需要手动构造 `ExpectedAiResponseFormat`，略繁琐（新版已有改善）。

### Q16.5 Spring AI 和 LangChain4j 各有什么优缺点？

**Spring AI 优势**：
- 与 Spring Boot 生态深度绑定，Bean 管理、配置、生命周期全部自动；
- **Advisor 链机制**非常灵活，可以随意插拔日志、记忆、RAG、重试等组件；
- 官方支持 MCP（`spring-ai-starter-mcp-client`）；
- `spring-ai-alibaba-starter-dashscope` 对阿里云百炼官方支持更好；
- SSE 流式返回原生支持（`Flux` / `SseEmitter`）。

**Spring AI 劣势**：
- API 迭代快，1.0 相比 0.x 有 breaking change，需要注意 BOM 版本对齐；
- 部分功能（如 Agent 规划）不如 LangChain4j 成熟，需要自己实现（如本项目的 `BaseAgent`）；
- 文档相对较少，中文资料匮乏。

**LangChain4j 优势**：
- **更简洁的 API**：接口 + 注解 = 工具，门槛低；
- Agent 功能更成熟：内置 `ReActAgent` / `PlanAndExecuteAgent`，不用自己写四层继承；
- 文档质量高（官方文档 + 示例丰富）；
- 支持更多外部集成（MCP、Azure、Docker 等）；
- 社区活跃，版本稳定（1.0.0 已 release）。

**LangChain4j 劣势**：
- 与 Spring Boot 集成需要手动适配（虽然支持注入 Spring Bean）；
- 国内云厂商（阿里云百炼）的官方支持不如 Spring AI Alibaba 深入；
- SSE 流式在 Spring WebFlux 环境下需要额外适配；
- 不能直接用 `@Component` + `@Tool` 注注解在 Service 里，需要额外包装。

### Q16.6 本项目如果换成 LangChain4j 重写，核心代码要改多少？

**改动量估算**（核心模块）：

| 模块 | 改动量 | 说明 |
|------|--------|------|
| `AiController` | 5% | 接收参数不变，SseEmitter 返回方式不变 |
| `LoveApp` | 70% | ChatClient → AiServices 改写，Advisor → retriever 改写 |
| `YuManus` / `ToolCallAgent` | 80% | 四层继承 → 直接用 LangChain4j 的 `ReActAgent` |
| `BaseAgent` | 删掉 | LangChain4j 自带，不需要自己写状态机和循环 |
| `FileBasedChatMemory` | 0% | `ChatMemory` 接口一样，只需换 import |
| RAG 相关（VectorStore / Advisor） | 60% | `VectorStore` → `EmbeddingStore`，`QuestionAnswerAdvisor` → retriever |
| 工具类（`@Tool`） | 20% | 方法注解不变，但需要包装成 `Tool` 实例传入 AiServices |
| `pom.xml` | 20% | 替换依赖（删 Spring AI Alibaba，加 LangChain4j DashScope） |

**结论**：Agent 和 RAG 核心逻辑要大幅改写（因为 LangChain4j 把 ReAct Agent 做成了内置类，而 Spring AI 需要自己实现），但 Controller 和 SSE 这层变化很小——**说明架构分层做得不错，核心逻辑和传输层解耦**。

### Q16.7 实际面试中怎么回答"你们为什么选 Spring AI 而不是 LangChain4j"？

**满分回答模板**：

> 本质上是 **Spring Boot 生态的天然选择**。我们的项目是 Spring Boot 3 项目，Spring AI 和 Spring IoC、配置管理、MVC 层的集成是开箱即用的，不需要额外的适配层。比如 `ChatModel` 直接 `@Resource` 注入，`SseEmitter` 和 `Flux` 天然适配 Spring MVC，不需要手写转换代码。
>
> 当然，LangChain4j 在纯 Java Agent 开发上更成熟——它内置了 `ReActAgent`、`PlanAndExecuteAgent`，而我们是自己实现了一套四层继承（`BaseAgent → ReActAgent → ToolCallAgent → YuManus`）。这是因为我们需要对 Agent 行为做细粒度控制（状态机、SSE 流式、多模型切换），自己实现反而更灵活。
>
> 如果未来 Agent 逻辑变得很重（比如多 Agent 协作），我们会考虑引入 LangChain4j，把 Agent 规划层迁移过去，保留 Spring AI 作为传输层和集成层的角色。

---

## 17. 项目架构搭建（Spring Boot 3 + Hutool + Lombok + Knife4j + 全局异常处理）

### Q17.1 项目架构是怎么搭建的？用了哪些核心依赖？

**回答**：

项目基于 **Spring Boot 3.4.4 + Java 21**，核心依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>  <!-- Web MVC -->
</dependency>
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>              <!-- 工具库 -->
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>                  <!-- 注解处理器 -->
</dependency>
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
</dependency>
```

**三层结构**：
- `controller/` — 控制层，接收请求、参数校验（`@Valid`）、调用业务层
- `service/` — 业务层，`UserService` 用 `JdbcTemplate` 操作数据库
- `config/` — 配置层，`CorsConfig`（跨域）、`AuthWebMvcConfig`（JWT 拦截）、`OssConfig`（OSS）

**全局异常处理**：项目中虽然 `GlobalExceptionHandler` 暂未正式启用，但通过 `Result<T>` 统一响应包装（`Result.success()` / `Result.error()`），Controller 层所有接口都返回统一格式，前端只需要处理两种状态。

```java
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    public static <T> Result<T> success(T data) { ... }
    public static <T> Result<T> error(String msg) { ... }
}
```

### Q17.2 Lombok 的 `@Data` 会带来什么问题？如何安全使用？

**回答**：

**常见问题**：
1. **equals/hashCode 坑**：在集合（HashMap/HashSet）中作为 key 的 Entity 类如果用 `@Data` 生成 equals/hashCode，会把所有字段纳入计算。如果两个 user 有相同 id 但其他字段不同，会被当作"同一个对象"，从 HashSet 中消失或 HashMap 的 key 被意外覆盖。
2. **循环引用**：两个 Entity 互相 `@Data` 对方，会导致 toString 死循环（StackOverflow）。
3. **getter/setter 膨胀**：IDE 生成 .class 文件，编译时注解处理器只是去掉源代码中的 getter/setter，`javap -c` 看到的是正常的字节码——这一点经常被面试官用来试探你是否真的理解 Lombok 的原理。

**项目中的安全用法**：
```java
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    // VO/DTO 用 @Data 是安全的（不可变、无循环引用）
}
```

**Entity 层建议用 `@Getter @Setter` 替代 `@Data`**，或者手动写 equals 只比较 id。

### Q17.3 为什么用 Hutool 而不是 JDK 原生工具？它解决了什么问题？

**回答**：

Hutool 是 Java 工具库的"中文版 stdlib"，将 JDK 零散的 API 封装成统一风格。

| 场景 | JDK 原生 | Hutool |
|------|---------|--------|
| 判断字符串为空 | `str == null \|\| str.isEmpty()` | `StrUtil.isBlank(str)` |
| HTTP 请求 | `HttpURLConnection` 几十行 | `HttpUtil.get/post` |
| JSON 解析 | `new ObjectMapper()` | `JSONUtil.toJsonStr(obj)` |
| 日期格式化 | `new SimpleDateFormat`（线程不安全） | `DateUtil.format` |
| 文件读取 | `Files.readString()`（Java 11+） | `FileUtil.readUtf8String()` |

项目中 `LoveApp.java` 用了 `StrUtil.isBlank(message)` 判断用户消息是否为空，是 Hutool 最典型的用法。

**追问**：Hutool 的缺点是什么？
- 引入过多模块增加包体积（可以用 `hutool-core` 单独引入）
- 部分工具与 Spring 生态重复（如 `StringUtils` vs `StrUtil`），混用造成混乱

### Q17.4 Knife4j 和 SpringDoc OpenAPI 是什么关系？为什么要用它？

**回答**：

- **SpringDoc**：将 `@RestController` 上的 `@RequestMapping`、`@ApiOperation` 等注解自动生成 OpenAPI 3.0 规范 JSON（`/v3/api-docs`）
- **Knife4j**：基于 SpringDoc 的 JSON，用 Vue 重写了 Swagger UI，界面更美观，支持中文分词搜索、接口分组

配置简洁（项目 `application.yml` 中）：
```yaml
knife4j:
  enable: true
  setting:
    language: zh_cn
```

访问地址：`http://localhost:8123/api/doc.html`

**加分回答**：Knife4j 还有一个隐藏价值——在前后端联调阶段，后端接口变更后前端可以实时刷新文档，不需要额外维护一份 API 文档。

### Q17.5 全局异常处理器怎么设计？为什么不用 try-catch 分散处理？

**回答**：

**分散 try-catch 的问题**：
- 重复代码多，每个 Controller 方法都要包一层
- 异常信息不统一，前端需要适配多种错误格式
- 运行时异常没有兜底，会直接暴露给用户（500 + 堆栈）

**全局异常处理器设计思路**（当前项目 `Result<T>` 统一响应已具备基础）：
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError().getDefaultMessage();
        return Result.error("参数校验失败: " + msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleAll(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统繁忙，请稍后重试");
    }
}
```

**关键点**：catch 所有未处理异常返回统一格式，避免 500 堆栈泄露；业务异常（`BusinessException`）自定义，携带错误码和消息；参数校验异常（`@Valid` 触发）单独处理。

---

## 18. AI 大模型集成（Spring AI + 通义 + Ollama）

### Q18.1 为什么要用 Spring AI 而不是直接调 HTTP API？Spring AI 解决了什么问题？

**回答**：

**直接调 API 的痛点**：
- 每个模型（DashScope、OpenAI、Ollama）的请求格式、鉴权方式、响应结构完全不同，切换模型要重写大量代码
- 流式响应（Server-Sent Event）解析要手写，文本模型和多模态模型的调用方式不同
- Prompt 模板、ChatMemory、工具调用（Tool Calling）这些通用能力要自己实现

**Spring AI 统一抽象**：
```
User Code
    ↓
ChatClient（统一入口）
    ↓
ChatModel 接口（Spring AI 定义）
    ↓
DashScopeChatModel / OllamaChatModel / OpenAiChatModel（各厂商实现）
```

切换模型只需改配置，不需要动业务代码：
```yaml
# 切换到本地 Ollama
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: gemma3:1b
```

Spring AI 还内置了 `PromptTemplate`、`MessageChatMemoryAdvisor`、`QuestionAnswerAdvisor`、`FunctionCallback` 等组件，这些在项目中都直接用到了。

### Q18.2 项目中怎么同时接入多个 AI 大模型的？配置是怎么写的？

**回答**：

项目中有 **4 种接入方式**，通过配置切换：

**方式 1：阿里云百炼（DashScope）—— 项目主用**
```yaml
spring:
  ai:
    dashscope:
      api-key: sk-xxxx
      chat:
        options:
          model: qwen-plus   # 通义-plus，或 qwen-max、qwen-vl-plus（多模态）
```

**方式 2：本地 Ollama（离线/低成本场景）**
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: gemma3:1b
```

**方式 3：直接 HTTP 调用**（`HttpAiInvoke.java`，绕过 Spring AI 用 OkHttp 手动发请求）

**方式 4：LangChain4j**（`LangChainAiInvoke.java`，另一种 Java AI 框架）

在 `LoveApp.java` 构造方法中：
```java
public LoveApp(ChatModel dashscopeChatModel) {
    // 只注入一个 ChatModel Bean，由 Spring AI 根据配置自动选择
    this.chatClient = ChatClient.builder(dashscopeChatModel)...
}
```

Spring AI 根据 `application.yml` 中的 `spring.ai.dashscope.*` 自动创建 `DashScopeChatModel` Bean，无需手动 `new`。

### Q18.3 为什么选了阿里云百炼而不是 OpenAI？

**回答**：

**核心原因**：阿里云百炼对国内开发者更友好：
- **无需科学上网**，API 延迟低（杭州 → 阿里云内网 < 50ms）
- **中文能力强**，通义千问在中文对话、角色扮演、恋爱/情感场景效果优于 GPT-4
- **价格便宜**，qwen-plus 比 GPT-4o 便宜 5-10 倍，适合学生/创业阶段
- **多模态便宜**，qwen-vl-plus 图片理解成本低，适合恋爱大师的多模态聊天

**追问**：多模型切换后怎么保证回答质量一致？
- 不同模型的 temperature、max tokens 要单独调优
- 恋爱大师场景固定用 qwen-plus，不做模型间切换——切换场景主要是为了开发调试和成本优化

### Q18.4 什么叫"封装统一的调用接口"？具体是怎么封装的？

**回答**：

项目中有两个层次的统一封装：

**层次 1：`ChatClient` 统一入口**（Spring AI 提供）
```java
// 不管底层是 DashScope 还是 Ollama，调用方式完全一样
ChatClient.builder(chatModel).prompt().user("你好").call().content();
```

**层次 2：`LoveApp` 业务层封装**（项目自建）
```java
// 对话
public String doChat(String message, String chatId) { ... }

// 带图片对话
public String doChatWithImage(...) { ... }

// 流式对话
public Flux<String> doChatByStream(String message, String chatId) { ... }

// 带工具调用
public String doChatWithTools(String message, String chatId) { ... }

// 带 RAG
public String doChatWithRag(String message, String chatId) { ... }

// 结构化输出
public LoveReport doChatWithReport(String message, String chatId) { ... }
```

前端调用时完全不感知底层模型差异，所有变化都在 `LoveApp` 内部消化。

---

## 19. 本地大模型部署（Ollama）

### Q19.1 为什么要在本地部署大模型？解决了什么问题？

**回答**：

| 维度 | 云端 API（百炼） | 本地 Ollama |
|------|---------------|------------|
| 成本 | 按 Token 付费 | 完全免费（仅 GPU 电费） |
| 延迟 | 依赖网络（50-500ms） | 本地推理（10-100ms） |
| 数据安全 | 数据经过第三方服务器 | 数据不出本地机器 |
| 可用性 | 依赖网络和服务商状态 | 完全离线可用 |
| 能力 | qwen-plus（强） | gemma3:1b（弱，但够简单场景） |

**项目中的使用场景**：
- 简单的情感陪伴对话，用 Ollama 跑，不花 Token 钱
- 复杂推理、工具调用、RAG 等场景，用百炼

### Q19.2 Ollama 怎么部署的？Java 代码怎么连接？

**回答**：

**Ollama 部署**（一行命令）：
```bash
# 安装（macOS/Linux）
curl -fsSL https://ollama.com/install.sh | sh

# 下载模型
ollama pull gemma3:1b

# 启动服务（默认 11434 端口）
ollama serve
```

**Java 连接**（`application.yml`）：
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: gemma3:1b
```

Spring AI 的 `OllamaChatModel` 自动连接 Ollama 的 REST API（`/api/chat`），无需手写 HTTP 调用——切换模型只需改配置。

### Q19.3 gemma3:1b 模型能力够用吗？怎么决定用哪个模型？

**回答**：

**gemma3:1b 的定位**：1B 参数，极轻量，响应快，适合简单对话、总结、分类。但复杂推理、长文本生成能力弱。

**项目中的决策逻辑**：
```java
// 简单情感陪伴 → Ollama（免费、快速）
if (needSimpleEmotionSupport) {
    return ollamaChatModel;
}
// 复杂推理/工具调用/结构化输出 → 百炼（能力强）
else {
    return dashscopeChatModel;
}
```

**面试加分点**：这体现了"模型选型要根据任务复杂度决定"的思想，不是无脑用最强模型。

---

## 20. Prompt 工程优化（角色定义 + Few-shot + 恋爱大师）

### Q20.1 什么是 Prompt Engineering？恋爱大师的 Prompt 怎么设计的？

**回答**：

Prompt Engineering 是"如何问大模型比如何调大模型更重要"的实践学科。

**恋爱大师的 System Prompt**：
```
扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。
围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；
恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。
引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。
```

**设计思路拆解**：
1. **角色定义**：告诉模型"你是恋爱心理专家"，约束回答视角
2. **开场白**：主动表明身份，降低用户心理防线
3. **分类提问**：三种状态（单身/恋爱/已婚）引导用户对号入座
4. **信息收集**：要求用户描述"事情经过、对方反应、自身想法"，为生成个性化建议铺垫
5. **输出约束**：强调"专属解决方案"，避免泛泛而谈

### Q20.2 什么是 Few-shot？项目中有没有用到？

**回答**：

Few-shot 是在 Prompt 中给模型提供 1-3 个"例子"，让它学习输入输出的模式：

```
输入：男朋友忘记我生日了，我很生气
输出：听起来你能感受到很深的失落感...（情感共鸣）

输入：男朋友和女同事单独吃饭，我很介意
输出：在亲密关系中，这种担忧很常见...
```

项目中的恋爱报告生成 `doChatWithReport` 方法体现了 Few-shot 的思想——通过 Prompt 中指定"标题为{用户名}的恋爱报告，内容为建议列表"，让模型学会按固定格式输出：
```java
public record LoveReport(String title, List<String> suggestions) {}
```

### Q20.3 Prompt 调优的流程是什么？怎么迭代优化的？

**回答**：

阿里云百炼平台有"Prompt 优化"和"模型测试"功能，我的调优流程：

1. **写初版 Prompt** → 百炼平台测试 → 看回答质量
2. **分析 Bad Case**：回答太泛/太水/答非所问 → 针对性加约束词
3. **加角色定义**："你是深耕恋爱心理领域的专家" → 回答更专业
4. **加输出格式约束**："用列表形式回答" → 结构更清晰
5. **加验证指令**："如果用户信息不足，主动提问" → 减少 AI 幻觉

**恋爱大师的关键迭代**：
- 初期：AI 回答很泛（"要沟通"这种废话）
- 加了"引导用户详述事情经过、对方反应"后，回答开始有针对性
- 加了"三种状态分类提问"后，对话引导更自然

---

## 21. Prompt 模板管理（Spring AI PromptTemplate）

### Q21.1 什么是 PromptTemplate？解决了什么问题？

**回答**：

硬编码 Prompt 的问题：
```java
// ❌ 硬编码，变量写死，修改要改代码
String prompt = "用户" + username + "的问题是" + question;
```

Spring AI 的 `PromptTemplate`：
```java
// ✅ 模板化，变量从外部注入
PromptTemplate template = new PromptTemplate(
    "用户{username}的问题是{question}，请给出{suggestionCount}条建议"
);
Prompt prompt = template.render(Map.of(
    "username", "张三",
    "question", "男朋友不回消息",
    "suggestionCount", 3
));
```

**解决了两个问题**：
- Prompt 与代码分离，修改 Prompt 不需要改 Java 代码
- 支持运行时动态插变量（用户名、对话上下文、RAG 检索结果）

### Q21.2 项目中怎么用 PromptTemplate 的？有没有结合 RAG？

**回答**：

**结合 RAG 的用法**（`QuestionAnswerAdvisor` 内部使用）：
```java
// 检索相关文档后，拼成 Prompt
String context = vectorStore.search(userQuestion);
String prompt = String.format(
    "基于以下知识库内容回答用户问题：\n%s\n\n用户问题：%s",
    context, userQuestion
);
```

**恋爱报告的 PromptTemplate**：
```java
// LoveApp.doChatWithReport 中的 system Prompt
.system(SYSTEM_PROMPT + "每次对话后都要生成恋爱报告，" +
        "标题为{用户名}的恋爱报告，内容为建议列表")
```

`{用户名}` 是模板变量，运行时被真实用户名替换。Spring AI 的 `PromptTemplate` 支持 `.render()` 方法将 Map 注入到模板字符串中。

### Q21.3 PromptTemplate 和 MessageChatMemoryAdvisor 有什么区别？

**回答**：

| | PromptTemplate | MessageChatMemoryAdvisor |
|---|---|---|
| **作用** | 管理"**单次**对话的模板和变量替换" | 管理"**多次**对话的上下文记忆" |
| **数据来源** | 固定模板 + 运行时变量 | 历史消息（从 ChatMemory 中读取） |
| **应用时机** | 请求发送前，填充变量 | 请求发送前，把历史消息注入 Prompt |
| **生命周期** | 一次请求用完即弃 | 整个会话会话周期 |

项目中两者配合使用：
```java
// PromptTemplate 填充变量 → MessageChatMemoryAdvisor 注入历史上下文
chatClient.prompt()
    .system(SYSTEM_PROMPT)                    // 固定系统 Prompt（模板）
    .user(message)                            // 当期用户输入
    .advisors(MessageChatMemoryAdvisor...)    // 自动注入历史消息
    .call();
```

---

## 22. AI 多轮对话（ChatMemory + MessageChatMemoryAdvisor）

### Q22.1 什么是多轮对话？为什么需要 ChatMemory？

**回答**：

大模型本身是**无状态的**——每次请求独立，不记得之前说过什么：
```
第1轮：用户："我最近和男朋友吵架了"
第2轮：用户："我该怎么办"  ← 模型不知道"吵架"的事！
```

ChatMemory 就是给大模型加"记忆"：
1. 每次对话结束后，把 UserMessage + AssistantMessage 存入 Memory
2. 下次请求时，从 Memory 取出历史消息，拼到 Prompt 前面
3. 模型看到历史上下文，自然就能"记得"之前的内容

### Q22.2 MessageChatMemoryAdvisor 在项目中怎么用的？

**回答**：

```java
// LoveApp 构造时创建 Memory
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new InMemoryChatMemoryRepository())
    .maxMessages(20)   // 最多保留 20 条消息，防止 Context 溢出
    .build();

// 构建 ChatClient 时注入 Memory Advisor
chatClient = ChatClient.builder(dashscopeChatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        new MyLoggerAdvisor()
    )
    .build();

// 每次对话时，指定会话 ID（区分不同用户的对话）
public String doChat(String message, String chatId) {
    return chatClient.prompt()
        .user(message)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
        .call()
        .content();
}
```

**`CONVERSATION_ID`** 是关键——同一个 `chatId` 的消息会被归到同一个会话，`MessageChatMemoryAdvisor` 根据这个 ID 从 Memory 中取对应会话的历史消息。

### Q22.3 maxMessages(20) 是怎么确定的？为什么不能无限累积？

**回答**：

**限制原因**：
1. **Token 限制**：大模型有上下文窗口（qwen-plus 是 32K tokens），超过会被截断
2. **成本**：Token 数越多，API 费用越高（按 Token 计费）
3. **推理质量**：Context 越长，模型"遗忘"早期关键信息的概率越高

**20 条消息怎么算的**：
- 1 条消息平均约 100-200 tokens
- 20 条 ≈ 2000-4000 tokens
- 加上 System Prompt（~500 tokens）和回答（~1000 tokens）
- 总共 ~4000 tokens，在 32K 窗口内留有充足余量

**追问**：如果对话超过 20 条怎么办？
- `MessageWindowChatMemory` 会自动丢弃最早的旧消息，只保留最近的 N 条
- 相当于一个"滑动窗口"，始终保持最新上下文

---

## 23. 对话记忆持久化（FileBasedChatMemory + Kryo）

### Q23.1 为什么需要持久化 ChatMemory？InMemory 的问题是什么？

**回答**：

**InMemoryChatMemoryRepository** 的致命缺陷：
```
服务重启 → 内存清空 → 所有用户的对话历史全部丢失
```

对于"恋爱大师"这种需要长期积累上下文的应用（用户聊到一半重启服务，历史全没了），这是不可接受的。

### Q23.2 FileBasedChatMemory 是怎么实现的？Kryo 序列化用在哪儿？

**回答**：

**三层设计**：

```
add(conversationId, messages)
    ↓
getOrCreateConversation(conversationId)  // 从 .kryo 文件反序列化
    ↓
saveConversation(conversationId, messages)  // Kryo 序列化写入 .kryo 文件
```

**Kryo 序列化**：
```java
private static final Kryo kryo = new Kryo();

static {
    kryo.setRegistrationRequired(false);         // 不强制注册类 ID
    kryo.setInstantiatorStrategy(new StdInstantiatorStrategy()); // 支持无参构造
}

// 写入
try (Output output = new Output(new FileOutputStream(file))) {
    kryo.writeObject(output, messages);  // messages 是 List<Message>
}

// 读取
try (Input input = new Input(new FileInputStream(file))) {
    messages = kryo.readObject(input, ArrayList.class);
}
```

**文件命名**：`conversationId + ".kryo"`，每个会话一个文件。

### Q23.3 为什么选 Kryo 而不是 JSON / Java 原生序列化？

**回答**：

| 序列化方式 | 体积 | 速度 | 跨语言 | 复杂度 |
|-----------|-----|------|-------|-------|
| Java Serializable | 大 | 慢 | ❌ | 复杂 |
| JSON（Jackson）| 大 | 中 | ✅ | 简单 |
| Kryo | 小 | 快 | ❌ | 中等 |
| Protostuff / FST | 更小 | 更快 | ❌ | 中等 |

**选 Kryo 的原因**：
- Spring AI 的 `Message` 接口实现类（如 `UserMessage`、`AssistantMessage`）实现了 `Serializable`
- Kryo 比 JSON 序列化体积小 3-5 倍（.kryo 文件比 .json 小很多）
- 比 Java 原生序列化快 10 倍以上
- 不需要注册类 ID（`setRegistrationRequired(false)`），对 Spring AI 内部类友好

**追问**：如果选 FST 而不是 Kryo 呢？
- FST 比 Kryo 更快，但 Kryo 更成熟、社区更大、踩坑文档更多
- 两者都能满足需求，选 Kryo 是工程稳妥的选择

### Q23.4 序列化失败（异常）怎么处理？为什么直接用 e.printStackTrace()？

**回答**：

当前代码：
```java
catch (IOException e) {
    e.printStackTrace();  // ❌ 不推荐
}
```

**问题**：生产环境应该用 Logger 替代 printStackTrace，因为：
- printStackTrace 输出到标准错误流，无法被日志框架管理（级别、格式、输出位置）
- 敏感信息可能泄露

**改进方案**：
```java
@Slf4j
public class FileBasedChatMemory {
    catch (IOException e) {
        log.error("保存对话历史失败, conversationId={}", conversationId, e);
    }
}
```

---

## 24. 日志 Advisor（CallAroundAdvisor + MyLoggerAdvisor）

### Q24.1 CallAroundAdvisor 是什么？它的生命周期是什么？

**回答**：

`CallAroundAdvisor` 是 Spring AI 的**拦截器接口**，允许在每次 AI 调用前后插入自定义逻辑：

```java
public interface CallAdvisor {
    String getName();          // Advisor 名称
    int getOrder();            // 执行顺序（越小越先执行）
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
}
```

**执行顺序**（AOP 环绕通知模式）：
```
请求 → before() → ChatModel → after() → 响应
         ↑
    开发者在这里打印日志
```

### Q24.2 MyLoggerAdvisor 具体是怎么实现的？before/after 分别做什么？

**回答**：

```java
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private ChatClientRequest before(ChatClientRequest request) {
        log.info("AI Request: {}", request.prompt());  // 请求前：打印用户输入
        return request;
    }

    private void observeAfter(ChatClientResponse response) {
        log.info("AI Response: {}",
            response.chatResponse().getResult().getOutput().getText());  // 响应后：打印 AI 输出
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        request = before(request);
        ChatClientResponse response = chain.nextCall(request);  // 继续调用链
        observeAfter(response);
        return response;
    }
}
```

**追问**：为什么同时实现 `CallAdvisor` 和 `StreamAdvisor`？
- `CallAdvisor` 处理同步 `.call()` 调用
- `StreamAdvisor` 处理流式 `.stream()` 调用
- `doChat()` 用 `.call()` → 走 CallAdvisor
- `doChatByStream()` 用 `.stream()` → 走 StreamAdvisor
- 两者都必须实现，否则流式调用时日志不会打印

### Q24.3 为什么不直接在 Controller 里打印日志，而要写一个 Advisor？

**回答**：

| | Controller 打印 | Advisor 打印 |
|---|---|---|
| 复用性 | ❌ 只在这个接口生效 | ✅ 所有调用 ChatClient 的地方都生效 |
| 侵入性 | 每个方法手动加 | 构造 ChatClient 时注入一次，所有对话自动打印 |
| 关注点 | 关注 HTTP 请求 | 关注 AI 模型调用 |
| 与 AI 调用解耦 | ❌ 混杂 | ✅ 分离 |

**更重要的价值**：Advisor 的链式组合——可以同时注入多个 Advisor：
```java
.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 记忆
    new MyLoggerAdvisor(),                                  // 日志
    new ReReadingAdvisor()                                  // 重读
)
```

三个 Advisor 按 `getOrder()` 顺序执行，相当于对 AI 调用做了三次拦截，这种 AOP 思想比在 Controller 里散弹式打印优雅得多。

---

## 25. Re-Reading Advisor（CallAroundAdvisor + Prompt 增强）

### Q25.1 Re-Reading Advisor 的原理是什么？为什么要让模型"重读"问题？

**回答**：

**核心原理**：在用户问题后面追加"Read the question again: [原问题]"

```java
// 改写前
用户输入："我男朋友总是加班，我们关系变淡了"

// 改写后（Re-Reading 后）
"我男朋友总是加班，我们关系变淡了
Read the question again: 我男朋友总是加班，我们关系变淡了"
```

**为什么有效**：
- 大模型在长对话中容易"跑偏"，只看了开头就急着回答
- 让模型再读一遍输入，会触发它重新审视问题，减少漏读关键信息
- 心理学上类似"让我再想想你刚才说的"——强制二次处理

**实际效果**：恋爱大师场景中，用户描述的问题通常较长且情感细腻，Re-Reading Advisor 能显著提高模型对问题细节的捕捉率。

### Q25.2 ReReadingAdvisor 和 MyLoggerAdvisor 都是 CallAdvisor，它们的执行顺序是什么？

**回答**：

```java
public class ReReadingAdvisor implements CallAdvisor {
    @Override public int getOrder() { return 0; }
}

public class MyLoggerAdvisor implements CallAdvisor {
    @Override public int getOrder() { return 0; }
}
```

两者 `getOrder()` 都是 `0`，执行顺序**不稳定**——取决于 Spring 注入顺序。

**实际在 `LoveApp` 中的注入方式**：
```java
.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(chatMemory).build(),  // order 未知
    new MyLoggerAdvisor(),                                  // order = 0
    new ReReadingAdvisor()                                  // order = 0
)
```

Spring AI 的 `defaultAdvisors` 按数组顺序执行，所以实际顺序是：**MemoryAdvisor → MyLoggerAdvisor → ReReadingAdvisor**。

**追问**：我想让 ReReading Advisor 先执行（改写 Prompt）再打印日志，怎么办？
- 把 ReReadingAdvisor 写在数组前面：`new ReReadingAdvisor(), new MyLoggerAdvisor()`
- 或者自定义 `getOrder()`：ReReading 返回 -10，MyLogger 返回 0

### Q25.3 Re-Reading 这种技巧有没有局限性？适合所有场景吗？

**回答**：

**适用场景**：
- 复杂/长文本问题（恋爱大师场景适合）
- 多轮对话中容易跑偏的上下文
- 需要模型关注输入细节的场景

**不适用场景**：
- 简单问答（"今天天气怎么样"），重复读没有意义，还浪费 Token
- 实时性要求高（每次多读一遍输入，延迟增加）
- Token 成本敏感场景（每次多几十个 Token，量大了成本可观）

---

## 26. 结构化输出（Spring AI Entity Call + LoveReport）

### Q26.1 什么是结构化输出？为什么 AI 返回结构化 JSON 比返回纯文本更好？

**回答**：

**纯文本的问题**：
```
用户问：生成恋爱报告
AI 返回："张三的恋爱报告：1. 建议多沟通；2. 建议..."
```

- 前端要写正则解析，分割标题和建议列表
- 如果 AI 格式变化（多了个冒号、换行），解析就崩
- 无法直接作为 Java 对象使用

**结构化输出**：
```java
// 定义 Java Record（不可变、简洁）
record LoveReport(String title, List<String> suggestions) {}

// 调用时直接得到 Java 对象
LoveReport report = chatClient.prompt()
    .user(message)
    .call()
    .entity(LoveReport.class);  // Spring AI 自动解析 JSON → Java 对象
```

前端拿到的是现成的 `LoveReport` 对象：`report.title` 是标题，`report.suggestions` 是列表，直接渲染即可。

### Q26.2 Spring AI 怎么做到"让 AI 返回 JSON 自动映射为 Java 对象"的？

**回答**：

Spring AI 的结构化输出背后调用了大模型的**Function Calling / Tool Calling 能力**：

1. 定义 Java 类 → Spring AI 生成对应的 JSON Schema
2. 请求时告诉模型："你必须返回一个符合这个 Schema 的 JSON"
3. 模型返回 JSON 字符串
4. Spring AI 用 Jackson 反序列化为 Java 对象

```java
// 底层等价于这样的 Prompt 追加：
// "根据以下 JSON Schema 生成回答：\n{\"type\": \"object\", \"properties\": {\"title\": ...}}"
LoveReport report = chatClient.prompt()
    .user(message)
    .call()
    .entity(LoveReport.class);
```

**本质是强迫大模型"按格式输出"，而不是靠 Prompt 约束**——结构化输出比 Prompt 约束可靠得多，因为它是模型 API 层面的强制契约。

### Q26.3 Record 和 Class 有什么区别？为什么这里用 Record？

**回答**：

```java
// Class（传统写法）
public class LoveReport {
    private String title;
    private List<String> suggestions;
    public String getTitle() { return title; }  // 要手写 getter
    public void setTitle(String title) { this.title = title; } // 要手写 setter
}

// Record（Java 16+，简洁不可变）
public record LoveReport(String title, List<String> suggestions) {}
// 自动生成：构造函数、getTitle()、getSuggestions()、equals()、hashCode()、toString()
```

**选 Record 的原因**：
- 结构化输出的数据类**天然不需要 setter**（AI 返回后直接构造）
- 代码量减少 80%
- 不可变性（immutable）更适合纯数据对象
- Jackson 和 Spring AI 的 `entity()` 方法都原生支持 Record 反序列化

### Q26.4 如果 AI 返回的 JSON 格式有问题（比如缺字段），Spring AI 会怎么处理？

**回答**：

**两种情况**：

1. **JSON 完全无法解析**：抛出 `JsonProcessingException`，Spring AI 会包装成 `AiException`，可以被全局异常处理器捕获
2. **JSON 部分缺字段**：Jackson 默认用 `null` 填充缺失字段（deserialize 不会报错）

项目中如果 AI 返回格式不稳定，**建议加 Prompt 约束**：
```
"每次对话后都要生成恋爱报告，格式必须为：{\"title\": \"xxx的恋爱报告\", \"suggestions\": [\"建议1\", \"建议2\"]}"
```

强制要求 JSON 格式 + 示例，比让模型自由发挥可靠得多。

---

## 27. AI 知识问答（QuestionAnswerAdvisor + RAG）

### Q27.1 什么是 RAG？为什么要用 RAG，而不是直接调大模型的全部知识？

**回答**：

RAG 的全称是 **Retrieval-Augmented Generation（检索增强生成）**。本质上是一种"开卷考试"的设计哲学：模型在回答用户问题之前，先从外部知识库里检索出相关片段，再把这些片段作为上下文喂给模型，让模型"带着参考资料"回答。

大模型的"闭卷考试"先天有几个硬伤。第一是**知识截止**——模型只知道自己训练数据截止时间之前的知识，2024 年之后再发生的事情它一无所知；第二是**领域缺失**——通用大模型对垂直领域（法律、医疗、企业内部知识）几乎是小白，靠 Prompt 强行灌入又会被上下文窗口卡死；第三是**幻觉**——模型为了回答得"像样"，会自信地编造内容，这在专业场景里是致命的；第四是**实时性**——价格表、课程目录、公告这些随时变的内容，模型根本无法更新。

RAG 用一个外挂的、可独立更新的向量数据库代替模型自身知识，模型本身只负责"理解+表达"，数据真伪由知识库兜底。这就把"模型的认知"和"业务的数据"解耦了——你的恋爱课程文档今天改，明天同步进向量库，模型立刻就能回答最新内容，不需要任何模型重训。

### Q27.2 QuestionAnswerAdvisor 是怎么和 ChatClient 配合的？整个调用链是什么？

**回答**：

可以把这个 Advisor 想象成 ChatClient 调用链上的一层"拦截器"，它在用户消息被送到大模型之前，偷偷插入了"检索+拼装"两步，整个流程是七步的。

第一步，用户发起调用，ChatClient 准备好 Prompt。第二步，请求进入 Advisor 链，QuestionAnswerAdvisor 拦下来。第三步，它把用户问题转成向量，去向量库做相似度搜索。第四步，取回 Top-K 个相关文档片段。第五步，把这些片段拼成一个**上下文块**，追加到用户问题的前后——典型的模板是"基于以下资料回答用户问题：...资料...问题：..."。第六步，把拼好的 Prompt 透传给 ChatModel，进入大模型。第七步，模型基于资料生成回答返回。

面试时可以提一句"pre-retrieval/post-retrieval"的术语——Spring AI 的最新版本把这一步拆得更细，但核心思想没变：**在提问与生成之间插一段检索，再把检索结果作为上下文拼回去**。

### Q27.3 RAG 解决了大模型的哪些硬伤？还有什么没解决？

**回答**：

**解决了**：
- 知识陈旧：知识库可独立更新
- 领域缺失：垂直知识外挂接入
- 幻觉：模型有资料可参考，减少胡编
- 私有知识保护：模型只读资料，不学数据

**没完全解决**：
- **检索不到的问题**——如果知识库里压根没有相关信息，依然会答非所问（项目里的 ContextualQueryAugmenter 就是在兜底这一类"空召回"场景）
- **检索错位**——向量相似不等于语义相关，可能会召回字面相近但实际无关的片段
- **多模态知识**——图片、表格、公式这些非结构化内容，文本向量召回效果有限
- **大段长文档**——一篇几十万字的技术手册，单纯切片 + 向量化会丢失上下文连贯性

所以 RAG 不是"一招鲜"，生产环境还要配合重排序（Rerank）、HyDE（让模型先想象答案再检索）、Query 改写、混合检索（BM25 + 向量）等组合拳。

### Q27.4 你这个 RAG 回答"提高了回复质量又增加了变现机会"，具体怎么做到的？

**回答**：

**质量层面**：以前的纯大模型回答有两个问题——一是不知道"你公司"的课程结构，二是会瞎编课程名。接 RAG 后，模型只能看到知识库里真实存在的课程，回答颗粒度从"应该多沟通啊"这种废话升级到"《单身魅力提升全攻略》从形象打造、气质培养到社交技巧全方位讲解"。

**变现层面**：知识库文档天然带了"推荐课程"段落，模型在 RAG 检索时会把这些段落一起捞出来。再配上 Prompt 引导"在合适时机推荐课程"，模型回答里自然就出现了课程名 + 课程链接，用户看到感兴趣的点进去报名——这就是"内容即广告"。相比硬广，AI 推荐的好处是**千人千面**：单身用户看到《单身魅力提升全攻略》，已婚用户看到《已婚沟通技巧》，自然转化率比统推一个课程高一个数量级。

---

## 28. RAG 文档处理（MarkdownDocumentReader + KeywordMetadataEnricher）

### Q28.1 文档读取为什么要用专门的 MarkdownDocumentReader？直接读 txt 不行吗？

**回答**：

从工程视角看，文档读取这件事远比"读出字符串"复杂。真正的目标是把文档变成"结构清晰的、有元信息的、能被高效检索的最小知识单元"——这要求读取器至少做三件事：

第一是**结构识别**——Markdown 的标题层级、代码块、引用、列表这些元素，承载了非常强的语义信号。`<h1>` 比段落重要，代码块不是普通文本，引用是辅助说明。如果用普通 TxtReader 读，结构和普通文字混在一起，模型检索时根本分不清主次；MarkdownDocumentReader 会把这些结构保留成 Document 的元数据（content type / headings），后续切分和检索都能用上。

第二是**元信息注入**——真实业务场景里你不会只有一篇文章，通常会有"单身篇 / 恋爱篇 / 已婚篇"等一系列文档，它们天然应该有可过滤的标签（status = single / dating / married）。MarkdownDocumentReader 提供了 `withAdditionalMetadata("status", "single")` 这种钩子，让你能基于文件名、目录结构自动打标签——这就是为多维度过滤检索铺路的。

第三是**切片友好**——结构化的文档更容易做"按标题切片"。比方说一个 H2 章节讲一个完整主题，应该作为一个 Document 单元，而不是被粗暴地按字数切两半。Markdown 解析为这种语义切片提供了可能性。

所以本质上，"文档读取器"不是 IO 工具，而是**语义保留 + 元信息注入 + 切分预处理**的组合封装。TXT 读出来的是"字"，Markdown 读出来的是"结构化文本"，这两个东西送进向量库，召回效果天差地别。

### Q28.2 KeywordMetadataEnricher 是怎么工作的？为什么要给文档提取关键词元信息？

**回答**：

**工作原理**：每个 Document 切片送进 KeywordMetadataEnricher，它用大模型提取若干关键词（项目里设的是 5 个），存进 Document 的 metadata['keywords'] 字段。本质上是一次对切片内容的小型"摘要"，但比摘要更适合检索。

**为什么需要关键词元信息**：

从工程角度看，向量检索有两个致命短板——**字面盲**和**精确查询弱**。"numpy 怎么安装"这种问题，用户期望匹到含"pip install numpy"的文档；但向量模型可能觉得这句话和"python 数据分析"接近，反而召回了无关文档。同时，Embedding 模型对专有名词、型号、版本号、中英混合这些词汇的编码本身就质量一般。

关键词元信息是这两个短板的"补丁"。检索时除了相似度匹配，还可以**用关键词做二次过滤甚至 BM25 风格的二次检索**——比如先向量召回 Top-50，再用关键词命中度重排到 Top-3。这能极大改善"字面相近但语义无关"的错召回。

更精妙的是关键词可以**下推到向量库**。在阿里云的 DashVector、Milvus、Pgvector 这些支持 sparse + dense 双路召回的库里，关键词可以直接挂为"标量过滤条件"或者"稀疏向量"，在 SQL 层面就完成过滤，进一步提升效率。

### Q28.3 内容里有"推荐课程"这种结构化片段，模型怎么知道该推荐而不是总结？

**回答**：

这里其实是 Prompt 工程 + RAG 联动。系统在构建知识库时对文档做了**保留原始链接和粗体强调**，这些视觉信号通过 MarkdownDocumentReader 被存到 Document 的 content 里。检索召回后，这些片段会原样出现在送给模型的上下文中。

再配上一段系统提示词"基于参考资料回答；如果资料中有课程推荐，保留课程名称和链接"，模型会**倾向按原文引用**而不是改写。这背后的原理是大模型的"指代遵循"——当上下文里给了明确的可引用锚点（链接、标题），模型在回答时倾向于保留它而不是"去结构化"它。

如果要更精细，可以把"推荐课程"片段用结构化字段（如 metadata.adType = "course"）专门标记，检索时单独召回——相当于在通用 RAG 之上做了一层"广告召回 + 自然融入"。这就是很多 AI 搜索产品"看似回答很自然，实际上每个推荐位都是工程化的"的原因。

---

## 29. RAG 向量存储（自定义 VectorStore + EmbeddingModel + Pgvector）

### Q29.1 文本是怎么变成向量的？Embedding 模型到底学到了什么？

**回答**：

可以把 Embedding 模型想象成一个"语义编码器"——它把人类的自然语言压缩成一个高维空间里的点（一段 1536 维或 1024 维的浮点数向量）。它"学到的"本质是**语义的拓扑结构**：训练时模型看了几亿对句子，学会了"意思相近的句子的向量应该是相近的"，同时也学到了语义关系——比如"国王 - 男人 + 女人 ≈ 女王"这种向量运算。

这个能力不是被硬编码的，而是从海量语料的上下文预测中"涌现"出来的。模型本质上是在学"词语和句子在什么语境下会一起出现"，而这种共现关系又跟语义高度相关。所以最终训练好的模型就有了"语义压缩"的能力。

但有几个认知陷阱要避开。第一，**它不懂事实，只懂模式**——你说"埃菲尔铁塔在巴黎"，模型对这个句子编码得"很巴黎"，但它内部没有一个"巴黎=法国"的查找表，事实性问题还是要靠 RAG。第二，**它有偏见**——训练语料的偏见会反映在向量空间里，种族、性别、地域相关的相似度会有偏。第三，**它是上下文无关的（基础模型版本）**，同一个词在不同上下文里向量相同，如果需要上下文敏感，得用 Matryoshka / contextual embedding 这种变体。

### Q29.2 为什么用 PGvector 而不是 Elasticsearch 或专用向量库？

**回答**：

PGvector 的核心价值是**"已经有一个 PostgreSQL 了"**。

如果你的系统本来就在用 Postgres（比如用户的元数据、订单、业务流水都在 Postgres 里），引入一个独立的向量库意味着：多一套运维、多一份数据同步成本、多一份一致性风险（业务改了，向量没同步）。PGvector 直接是 Postgres 的扩展（extension），向量就存在一张普通表里，可以用 SQL 一起查——"找出相似度 > 0.8 且 status='active' 且 created_at > '2024-01-01' 的文档"，一条 SQL 就完事。

代价是**性能上限不如 Milvus / Qdrant 这种专用库**——亿级向量上的 top-K 检索，专用向量库有数倍的 QPS 优势。但绝大多数业务（百万级文档、毫秒级响应）PGvector 完全够用，而且查询计划可以走 GiST 索引，配合 HNSW 算法也能做到生产级性能。

面试时可以这样说：**选型不是看谁最强，而是看谁最贴合现有架构**。如果项目已经在用 Postgres，加 PGvector 是 0 运维成本的最优解；如果是从零开始做大规模向量检索，再考虑 Milvus。

### Q29.3 你说"自定义 VectorStore 实现"，自定义在哪里？Spring AI 不是已经提供了接口吗？

**回答**：

Spring AI 的 VectorStore 接口是抽象的，本身只定义"输入 Document 存起来 / 输入 query 召回头部 K 条"两个行为。具体怎么存（Pgvector / 内存 / Redis / Elasticsearch）、怎么检索（HNSW / IVFFLAT / 暴力扫描）由实现类决定。

"自定义"通常发生在三种场景。第一，**接入新的存储引擎**——比如你公司有一套自研的向量引擎，你实现 `VectorStore` 接口去对接。第二，**扩展检索行为**——比如召回时除了相似度还想叠加"按时间衰减""按用户偏好加权"，可以在接口实现里加。第三，**测试 Mock**——写一个内存版的实现，单测时避免依赖真实数据库。

项目里其实主要是**复用 Spring AI 的 PgVectorStore + 自己封装一层 Bean 装配**（PgVectorVectorStoreConfig 配置类）。这就是"自定义"最常见的形态——业务写一层工厂，把 Spring AI 的标准实现按需打包成可注入的 Bean，让调用方拿到的就是一个接好 Knowledge Index、距离类型、维度的成品。

面试时被问到"自定义"别紧张，先问清楚面试官指的是接口层还是配置层，大多数情况下答"配置层做了 Bean 装配、按业务定制了 metadata 过滤"就够了。

### Q29.4 向量检索的"近似"是什么意思？为什么不是精确匹配？

**回答**：

精确检索（k-NN）的问题是**线性扫描**——100 万条向量要算 100 万次相似度。在大模型时代，文档切片动辄百万甚至千万条向量，暴力扫一遍要秒级甚至十秒级，业务上完全不可用。

近似最近邻（ANN, Approximate Nearest Neighbor）牺牲一点点精度换巨大的速度提升。核心思想是**用索引结构把向量空间预先组织**——HNSW 用图结构（Navigable Small World）、IVFFLAT 用倒排聚类、Annoy 用树结构。这些索引的特点是：搜索时不必遍历所有点，只需要沿着"通往高密度区域的高速路"走几步就能找到"够好"的候选。

代价是**召回率不是 100%**——可能有 1%-5% 的真正近邻被索引截掉了。但工程上看，绝大多数场景对这种程度的精度损失是不敏感的。Facebook 的 FAISS 论文里有个观点很有意思——向量检索即使 100% 精确，后面的 LLM 也不会真的"用上"那 1% 的精度差异，所以 ANN 完全够用。

Pgvector 现在同时支持 HNSW 和 IVFFLAT，HNSW 是更好的默认选择，查询快、召回高、构建相对慢一些。

---

## 30. RAG 文档检索（RetrievalAugmentationAdvisor + 相似度阈值 + 元信息过滤）

### Q30.1 RetrievalAugmentationAdvisor 和 QuestionAnswerAdvisor 是什么关系？为什么要换？

**回答**：

QuestionAnswerAdvisor 是 Spring AI 早期的"一站式" RAG 实现——向量检索 + Prompt 拼装都封装在内部，调用方看不到。优点是简单，缺点是不可定制——你想换检索器、加过滤、调 Prompt、改召回策略都得改源码或者复制粘贴。

RetrievalAugmentationAdvisor 是 Spring AI **1.0** 重构后的"可组合"版本。它把 RAG 拆成了三段——**Query 改写 / 检索 / 结果增强**，每段都允许注入不同的实现。这就是经典的**责任链 + 策略模式**——主体只关心"调用顺序"，具体每一步用什么策略，由外部装配决定。

落到项目里：
```
RetrievalAugmentationAdvisor
  ├─ QueryTransformer：QueryRewriter（重写用户问题）
  ├─ DocumentRetriever：VectorStoreDocumentRetriever（向量库检索）
  └─ QueryAugmenter：ContextualQueryAugmenter（拼装最终 Prompt）
```

每个组件都可以换——你可以把检索换成 DashScopeDocumentRetriever（云知识库）、把改写换成 MultiQueryExpander、把增强换成自定义的 Prompt 模板。这种"装配式设计"才是现代 RAG 框架的精髓：业务能按场景自由组合，而不是被某个特定的实现绑死。

### Q30.2 相似度阈值和 top-K 是怎么影响召回质量的？

**回答**：

这是两个最容易调错也最容易调的旋钮。

**top-K** 是召回的数量。设 3 就给模型 3 个文档、设 20 就给 20 个。直觉上看 K 越大越好——资料越多，模型参考越全。但实际上 K 太大了会有三个副作用：第一，**噪声引入**——Top-20 里必有若干无关片段，模型会"被带歪"；第二，**Context 膨胀**——资料占满了模型的上下文窗口，反而把用户问题挤出去；第三，**回答冗长**——模型倾向于把每条资料都用上，回答变得啰嗦。

经验值是**3-8 之间**。项目里配的是 topK=3，因为恋爱文档本身颗粒度小（每篇就一两个 FAQ），3 条足够覆盖。

**similarityThreshold** 是相似度的门槛。低于这个分数的文档被认为"不相关"，召回阶段就丢弃。这个旋钮的价值是**过滤噪声**——很多大模型答非所问，就是因为强行塞进去相似度只有 0.3 的"勉强相关"文档。设 0.5 意味着"我要至少看起来有点相关的"。

但阈值不能设太高——如果设 0.9，几乎什么都召不回，等于 RAG 退化成纯 LLM。**调参的核心是观察两个指标**：召回率（想要的文档被召回了多少）和精确率（召回的文档里有多少是真的相关）。这其实是搜索引擎时代 IR 领域的标准权衡。

### Q30.3 "元信息过滤"具体是怎么做的？恋爱场景为什么要按 status 过滤？

**回答**：

向量检索的硬伤之一是**"纯向量检索 = 文本相似度匹配"，缺乏业务维度**。如果用户问"我老公不爱回家怎么办"，向量召回来的可能既包含恋爱篇的"男朋友应酬多怎么办"，又包含单身篇的"如何扩大社交圈"——这些文档字面相似但场景错配。

元信息过滤的原理是：**向量检索在执行时带上额外的过滤条件**，由向量库在 SQL/索引层面直接过滤掉不符合条件的文档，召回结果只包含业务上"该出现的"。

项目里通过 `FilterExpressionBuilder.eq("status", status)` 构造了一个表达式，意思是"只看 status 字段等于某个值的文档"。这个 status 在文档加载时通过 `withAdditionalMetadata("status", status)` 自动注入，来源是文件名——"单身篇"映射 status=single、"恋爱篇"映射 status=dating、"已婚篇"映射 status=married。

落地的效果是：**用户带"老婆/老公"关键词提问时，系统自动定位到"已婚篇"知识库，召回的内容天然匹配用户场景**。这比试图训练模型"理解用户处于哪种关系状态"靠谱得多——把分类这件事交给业务代码做，模型只负责在已知类目下回答。

这就是**RAG 走向生产必须考虑的因素**：单纯的语义检索不够，必须叠加业务维度的过滤与路由。复杂一点的系统还会有"用户意图识别→路由到不同知识库→再 RAG"的多级架构。

---

## 31. RAG 查询增强（多查询扩展 + 查询重写 + 上下文查询增强）

### Q31.1 为什么用户的问题要"改写"？原封不动检索不行吗？

**回答**：

**用户提问与文档用词常常不对等**。这本质上是个"用户用日常语言提问，文档用专业语言表述"的鸿沟。

举个具体例子——用户问"男朋友总玩手机不理我，咋办"。这条 query 里口语词极多（"咋办""总玩"），向量模型对口语化、错字、省略句的表达编码质量不稳定。同一篇文档里可能写的是"伴侣长期沉迷电子设备导致沟通缺失的应对策略"，两者虽然语义相关，但 Embedding 向量的余弦相似度可能只有 0.4-0.5，达不到召回门槛。

查询重写（RewriteQueryTransformer）让 LLM 把用户问题"翻译"成更"文档语"——把口语化、错字、上下文省略的内容补全、规范成更容易被文档命中的形式。本质是**用 LLM 当桥梁，弥合"用户语"和"文档语"之间的 vocabulary gap**。

代价是要多调一次 LLM（增加几十毫秒延迟）和额外的 Token 费用，所以**不是所有场景都要重写**——文档如果和用户用词高度一致（比如内部知识库、技术手册），重写反而会引入噪声。

### Q31.2 MultiQueryExpander 多查询扩展是什么原理？比起单查询有什么优势？

**回答**：

**核心思想**：让 LLM 基于用户原问题生成 N 个语义等价但表述不同的改写（项目里 numberOfQueries=3），然后用这 N 个 query 各自去检索，最后把召回结果合并去重。

这背后的逻辑是**用多样性换召回率**。单个 query 可能因为措辞原因漏掉某些文档——比如 query "怎么追女生" 召不到含有 "如何与异性建立亲密关系" 的文档；如果 LLM 同时生成 "追求女生的技巧"、"如何开始一段恋爱"、"脱单方法" 三个改写，召回覆盖率会显著提升。

合并结果时要注意**去重和重排序**。可能同一篇文档被三个 query 都召回，要按出现次数或最高分聚合；也可能不同 query 召回了 10 篇无关文档，要靠相似度阈值和 topK 二次过滤。

代价是**3 倍检索成本 + LLM 改写成本**。这个权衡在大规模场景需要认真评估——一个折中方案是用 Bi-Encoder 廉价扩展（不调 LLM，仅做同义词扩展）替代 LLM 改写，能拿到 70% 的提升、10% 的成本。

### Q31.3 ContextualQueryAugmenter 是怎么"补足"用户问题的？为什么需要这一步？

**回答**：

**作用**：在把检索结果和用户问题拼装成最终 Prompt 时，加入一段系统指令，指导模型"在有上下文时怎么回答、在没有上下文时怎么兜底"。

**关键设计点**：空上下文的兜底。项目里的 emptyContextPromptTemplate 是"抱歉，我只能回答恋爱相关的问题"——这就是当向量库检索不到任何内容（top-K 为空）时，模型的应对话术。

为什么要特意兜底？因为**没有检索结果的 RAG 是非常危险的**。如果模型在空上下文下"自由发挥"，很可能编造一个看似合理但完全不存在的"恋爱建议"。给模型一个明确的退路，让它在"知识库没有这个问题"时礼貌拒绝，比胡编滥造好得多。

**业界更好的做法**是用 RAGAS 之类的工具评估空召回答与拒答回答的质量，让数据来决定选哪种兜底策略。

**对比**："让模型自由发挥" vs "明确拒答"——前者短期用户体验好（永远有答案）但长期会失去信任，后者短期可能让用户不满但长期保障知识准确性的承诺。对于专业领域的 RAG（比如医疗、法律），拒答几乎总是更安全的选择。

---

## 32. RAG 云知识库集成（DashScopeDocumentRetriever）

### Q32.1 什么叫"云知识库"？和自建 PGvector 有什么区别？

**回答**：

**自建 PGvector**：你在自己的服务器上跑 PostgreSQL + PGvector 扩展，自己维护文档上传、切分、向量化、检索全套流程。优势是数据自主可控、长期成本低、可深度定制；劣势是要自己处理运维、扩展、调优。

**云知识库**（比如阿里云百炼、腾讯 VectorDB、AWS Kendra）：服务商替你做完了所有底层工作。你只需要：上传文件 → 在控制台点几下做切分配置 → 服务商后台自动向量化入库 → 你的程序通过 HTTP/RPC 调用一个 `DocumentRetriever` 就能用。

关键差异：

| 维度 | 自建 PGvector | 云知识库 |
|------|--------------|---------|
| 运维成本 | 高 | 极低 |
| 文档更新 | 自己写 ETL | 控制台上传 |
| 切分策略 | 自己实现 | 服务商模板 |
| 数据可控 | 完全自主 | 数据在服务商侧 |
| 长期成本 | 服务器费用 | 按调用/容量计费 |
| 定制深度 | 可任意改造 | 受限于服务商接口 |

项目的 `LoveAppRagCloudAdvisorConfig` 用 `DashScopeDocumentRetriever` 接入阿里云百炼的知识库（Index 名为"恋爱大师"），整个集成代码不到 10 行——这就是云服务的便捷性。

### Q32.2 什么场景适合用云知识库？什么时候应该自建？

**回答**：

**适合云知识库**：
- 项目早期 / MVP 阶段，先验证业务价值
- 数据敏感度低 / 公开资料 / 营销内容
- 没有专门的工程团队做向量库的运维
- 文档更新频率不高（几天或几周一更新）

**必须自建**：
- 数据敏感（企业内部知识、个人隐私、医疗数据）
- 有明确的成本控制需求（百万级文档规模，自建 TCO 更低）
- 需要深度定制检索逻辑（多路召回、复杂过滤、个性化 rerank）
- 合规要求"数据不出公司"

**进阶组合**：很多生产系统是混合架构——**热数据用自建（高频检索、低延迟）+ 冷数据 / 公开数据用云（低成本运维）**。比如一个电商平台的"商品库"自建（敏感、价格实时变），"营销素材库"用云（公开、更新慢）。这种架构能同时拿到两边的好处。

### Q32.3 云知识库的检索效果和自建能一样吗？有没有坑？

**回答**：

**理论上**差不多甚至更好——云服务商一般用更大的 Embedding 模型（如 Cohere、bge-large）、更智能的切分策略、还可能有 Rerank 后置模型。

**实际坑点**：
- **冷启动慢**：服务商通常 1-5 分钟才能把新文档索引好，期间检索不到
- **计费模糊**：按调用次数 / 文档数 / 向量数多种计费方式并存，成本预测困难
- **版本锁定**：服务商可能下架某个模型或接口，你的系统也跟着变
- **数据出域**：跨境 / 行业合规问题——某些地区要求数据本地化
- **黑盒调优**：检索质量不达标时，你看不到具体哪些文档被召回，调优手段有限

项目层面，"怎么验证云知识库够用"是上云前的必修课。建议做一组**包含正例、负例、边界场景**的黄金测试集（Golden Set），把同样的 query 丢给自建 RAG 和云知识库，对比 Top-3 召回率和回答质量，再做最终选型决策。

---

## 33. ETL 数据处理（DocumentReader + Transformer + Writer）

### Q33.1 什么是 ETL？RAG 场景下的 ETL 处理什么？

**回答**：

ETL 是 **Extract（抽取）、Transform（转换）、Load（加载）**的简称，原本是数据仓库领域的概念，用来把业务数据从源系统抽取、清洗加工、加载到数仓供分析。

RAG 场景下的 ETL 思路完全一样：把杂乱的"原始文档"加工成"可被向量库消费的标准化知识"。三个阶段对应：

**Extract 抽取**：从各种数据源（文件系统、Confluence、飞书文档、数据库、爬虫）读出原始内容。项目中用的是 MarkdownDocumentReader，对应 Spring AI 的 DocumentReader 接口。

**Transform 转换**：清洗和结构化。包括格式标准化（统一 Markdown）、切分（TokenTextSplitter 按 token 切片）、内容增强（KeywordEnricher 提取关键词）、质量过滤（去掉空白页、去掉无关段落）。项目中这几个 Transformer 都有实现或示例。

**Load 加载**：把标准化的 Document 写入向量库。Spring AI 的 VectorStore.add() 方法就是这一步，它内部会调用 Embedding Model 把文本转向量，再写入向量数据库。

ETL 的工程价值在于**可观测、可测试、可恢复**。每一个阶段都可以插日志、插监控、单测覆盖，文档处理流水线出问题可以单独重跑某一段。这比"写一个脚本一次性把文档从硬盘转到向量库"健壮得多。

### Q33.2 Spring AI 的 ETL 流水线有哪些关键组件？项目中怎么用的？

**回答**：

**Reader 家族**：
- MarkdownDocumentReader：读 .md 文件，保留标题层级
- TikaDocumentReader：基于 Apache Tika，读 PDF/Word/Excel 等几十种格式
- JsonReader：读结构化 JSON
- 自定义 Reader：实现 DocumentReader 接口，接入任何数据源

**Transformer 家族**：
- TokenTextSplitter：按 token 数切片（项目里的 MyTokenTextSplitter 就是它的封装）
- SentenceSplitter：按句子切片
- KeywordMetadataEnricher：让 LLM 提取关键词元数据（项目里用了）
- SummaryMetadataEnricher：让 LLM 生成摘要元数据
- ContentTypeTransformer：根据标题自动判断文档类型

**Writer**：通常是 VectorStore.add()，把 Document 写入向量库。

项目里完整的 ETL 流水线在 `LoveAppVectorStoreConfig.build()` 中：

```
loadMarkdowns() → enrichDocuments() → vectorStore.add()
   (Reader)         (Transformer)        (Writer)
```

三步是个清晰的责任链，每一步的输入输出都是 Spring AI 标准的 Document 列表，所以可以任意组合——文档先切再富化、先富化再切、或者用不同的 Reader 合并不同来源的文档，最后统一入库。

### Q33.3 ETL 失败了怎么办？生产环境怎么保证知识库可靠性？

**回答**：

可靠性设计有四个原则。

**幂等性**——重复执行 ETL 不能产生重复 Document。向量库通常用"按 ID 去重"，所以 ETL 流水线要为每个 Document 生成稳定 ID（一般是 hash(文件路径 + 切片位置)），这样重复 ETL 不会膨胀向量库。

**断点续跑**——切到一半失败不应该从头来。设计上要记录"已经处理了哪些文件"，下次启动从断点继续。Spring AI 的 ETL 工具链没有内置这个能力，需要自己用 Redis 或数据库记 checkpoint。

**回滚能力**——如果新版本 ETL 之后发现向量库质量下降，要能快速回滚到老版本向量数据。最稳的做法是**蓝绿部署**：每个版本的向量数据写入独立的 index 或独立的 collection，切换通过修改索引名实现，秒级回滚。

**监控**——一个最小的生产级 ETL 应该监控：
- 处理文档数 / 切片数 / 失败数
- 平均切片大小 / 异常切片大小（避免出现空切片或超大切片）
- 端到端耗时
- 向量库的写入 QPS 和延迟
- 处理后的随机抽检（自动化评估摘要质量）

回到项目层面，恋爱大师应用的 ETL 是启动时同步执行的（`LoveAppVectorStoreConfig` 里），文档量小所以简化处理。生产时更建议把这套流水线拆成独立的 Job，用 Spring Batch 或 XXL-Job 周期调度执行。

---

## 34. 文档质量优化（AI 内容结构化 + 格式标准化）

### Q34.1 为什么要对原始文档"做 AI 结构化"？直接用源文档不行吗？

**回答**：

源文档通常是**写给人看的**——有前言、有目录、有页眉页脚、有内部链接、有 "点击这里看更多" 这种导航话术。这些对人类阅读是辅助，对 RAG 检索却是噪声——模型会基于"前言"和"目录"生成一个看起来合理但没营养的回答。

AI 结构化的目标是**让文档从"人类阅读单元"变成"知识检索单元"**。具体做什么：

- **去掉 UI 噪声**：删掉导航、广告、"相关推荐"等
- **重组段落**：把跨段落逻辑打散成自包含的小节，每节回答一个具体问题
- **统一格式**：标题层级统一、列表样式统一、术语统一（"男朋友"统一为"伴侣"，除非确实有性别区分）
- **补全缩写**：专有缩写第一次出现时展开
- **调整语气**：把"想必大家都很关心"这种口语化内容改成简洁陈述

这些动作不能简单写正则（因为要"读懂"文档），所以让 LLM 做是最合适的。让 GPT-4o 之类的模型把一份 1 万字的 FAQ "翻译"成 100 个 100 字的标准问答对，质量会肉眼可见地提升检索准确率。

### Q34.2 文档结构化对 RAG 效果有多大提升？

**回答**：

举一个真实对比。源文档是一篇 5000 字的"如何在恋爱中保持自我"长文，未处理时切片大概 25-30 块，回答用户"我总是为了对象改变自己怎么办"时召回 2 块，但都包含大量铺垫和前后文，模型回答会偏题。

AI 结构化后变成 12 个"问题 + 建议 + 警示"的标准问答块，每块自包含、密度高。同样的 query 召回 3 块，模型直接拼出干净的"问题诊断 + 三步建议 + 何时寻求专业帮助"的结构化答案。

**实际经验值**：经过 AI 结构化的知识库，**Top-3 召回率能从 60% 提升到 85% 以上**，**回答的"答非所问率"能从 30% 降到 5%**。这是基于多个真实项目对比得到的。

但要注意一点——结构化本身是一个有损过程。如果源文档里有"作者观点倾向"这种隐含信号，结构化可能会丢失。所以**结构化要保留 metadata**——把原文件的 hash、URL、原文片段挂到每个切片上，需要时还能溯源。

### Q34.3 生产环境的文档质量怎么持续优化？

**回答**：

文档质量是一个**持续运营**的过程，不是一次性工作。常见的优化循环：

**监控侧**：通过 RAGAS 之类的评估框架，定期抽样真实用户问答，评估召回率、回答忠实度（有没有幻觉）、相关性。如果某指标下降，触发文档 review。

**分析侧**：把用户问题分类——哪些问题答得不好、哪些是高频但回答质量低的、哪些是冷门问题。通过人工 review + AI 评分结合的方式，识别知识库的薄弱点。

**迭代侧**：根据评估结果补充新文档、修改旧文档、调整切片策略。恋爱的"已婚篇"如果发现用户提问"婆婆关系"频率高但召回低，就补充专门讲"婆媳"的问答对。

**A/B 测试侧**：文档变更前后用 Golden Set 评估检索质量，确保变更不带来回归。

**自动化侧**：把内容审查、切片质量、关键词覆盖率做成 CI 流水线。新提交的文档进入主库前自动跑一轮评估，不达标就拒绝合并。

项目里没做这一整套，但这个思想是面试加分的——**RAG 的工程难点不在"做出来"，而在"持续运营"**。

---

## 35. 批处理优化策略（TokenCountBatchingStrategy）

### Q35.1 文档转向量为什么要"批处理"？单条处理不行吗？

**回答**：

**Embedding 模型是 batch-friendly 的**。无论是 OpenAI、Cohere、还是开源的 bge、m3e，底层都是 Transformer——Transformer 的矩阵乘法天然支持并行。一次送 1 条文本和一次送 16 条文本，GPU 利用率差异巨大。单条提交时大量算力被浪费在"等下一个请求"上。

具体到数字：调用 OpenAI Embedding 接口时，官方文档明确建议 batch size 在 32-256 之间。一次提交 50 条文本，吞吐量可以是单条的 20 倍以上，成本大约只有单条的 1/3。

**所以不批处理 = 浪费钱 + 拖慢速度**。尤其是文档导入这种"一次性大批量"的场景，不做批处理基本不可接受。

### Q35.2 Spring AI 的 TokenCountBatchingStrategy 是怎么工作的？

**回答**：

**作用**：在 ETL 阶段按 token 数而不是按"条数"对 Document 进行分批，避免单个 batch 超 Embedding 模型的 context window。

**为什么按 token 分批而不是按文档数**：

文档长度差异巨大。一篇短 FAQ 可能 50 tokens，一个长段落可能 2000 tokens。如果按"每次批 100 条"处理，可能会出现一个 batch 里 95 条短文档 + 5 条超长文档，总 token 直接爆 Embedding 模型的 max_input（典型是 512 / 8192 tokens）。超长要么被截断（信息丢失），要么报错（整个 batch 失败）。

TokenCountBatchingStrategy 解决的就是这个：**保证每个 batch 总 token 数不超过模型上限**。它内部用 tokenizer（通常是 GPT tokenizer 或对应模型的 tokenizer）逐文档预算 token，凑满一个上限的 batch 就提交。这也意味着 batch 包含的"文档数"是动态的——可能有时 10 条短文档一个 batch，有时 2 条超长文档一个 batch。

**配套策略**：
- `MAX_TOKEN_PER_BATCH` 设到 Embedding 上限的 80%（留 buffer）
- 配合 `DocumentTransformer` 在 batch 前做切分（避免单文档超长）
- 失败重试时降级到单文档（保证最终成功）

### Q35.3 批处理还有哪些优化空间？

**回答**：

在生产场景里，把"批处理"做透还有几个方向。

**异步 + 流水线**：Embedding 调用通常是 IO 密集型，可以异步发起多个 batch 并发请求，用 CompletableFuture 或响应式编排把多个 batch 的等待重叠起来，进一步压榨吞吐。

**智能调度**：根据文档长度分布动态调整 batch 大小。如果今批文档都是短 FAQ，batch 提到 200 没问题；如果都是长文档，batch 降到 20 保护上限。

**缓存层**：相同 / 高度相似的文档可以缓存 Embedding 结果。文档 ETL 是反复跑的（每周一次、每天一次），同一篇文档每次都重新算一遍是浪费。用 Redis 缓存"内容 hash → 向量"是个直接有效的优化。

**嵌入模型选择**：大模型（如 1536 维 OpenAI ada-002）效果好但慢、贵；小模型（如 384 维 bge-small）快但质量略低。实际项目会按文档分级——核心知识库用大模型，长尾文档用小模型，整体性能 / 质量平衡。

**写入异步化**：Embedding 算好后，写向量库也可以并行化。如果用 Pgvector 而非专用向量库，写入是事务性的可以并行；对于支持 bulk insert 的向量库（如 Milvus），把多条 add 合并成一条 bulk write 也是常见优化。

---

## 36. 自动元信息标注（filename / status 标签）

### Q36.1 为什么要在文档加载阶段"自动"打标签？能解决什么问题？

**回答**：

**元信息（metadata）是向量数据库的"第二检索维度"**。纯向量检索的问题是"一切按相似度匹配"，没人在意文档属于哪一类、什么时间、什么作者。这在实际业务里远远不够——用户问"婆媳关系"时，你不会想召回到讲"如何道歉"的无关文档，哪怕它和"婆媳关系"在语义上有一点点相似。

**自动元信息标注让检索能"按业务维度切片"**。打个比方，向量检索是"全文搜索"，元信息过滤是"分类筛选"——两者叠加，才能精确命中"婆媳关系 + 已婚篇 + 近一年发布"这种复合条件。

自动（而非人工）的价值在于**大规模可扩展**。当知识库有 1 万篇文档时，让运营手工打标签是地狱级工作量。自动标注让知识库可以"日更"——每天新文档进来就自动归类，无需人工干预。

### Q36.2 项目里的 "filename / status" 标注是怎么提取的？原理是什么？

**回答**：

**核心观察**：业务的元信息往往"埋在文件名里"。项目里的命名约定是"恋爱常见问题和回答 - {状态}篇.md"——通过解析文件名最后几个字符，自动判断这篇文档属于单身 / 恋爱 / 已婚哪个状态。

**技术实现**：三步搞定。

第一步，**资源解析**——`ResourcePatternResolver.getResources("classpath:document/*.md")` 把目录下所有 .md 文件读出来，每个是 Spring 的 `Resource` 对象。

第二步，**文件名切片**——文件名约定是"恋爱常见问题和回答 - X篇.md"，最后两个字符的"篇"字去掉、保留"单身 / 恋爱 / 已婚"作为 status。`filename.substring(filename.length() - 6, filename.length() - 4)` 就是这个动作。

第三步，**元信息注入**——`MarkdownDocumentReaderConfig.withAdditionalMetadata("status", "single")` 把 status 注入到每个 Document 的 metadata 中，所有从此文档切片出的 Document 都会带上同样的 metadata。

注入后，检索时用 `Filter.Expression eq("status", "single")` 就能自动过滤——这条 SQL 实际上是说"只召回 status=single 的文档切片"。

### Q36.3 还有哪些元信息适合自动标注？

**回答**：

元信息的核心是"影响检索的业务维度"。常见的可自动标注维度：

**文档层维度**：
- 业务分类（status：单身/恋爱/已婚）
- 文档类型（type：FAQ / 长文 / 案例 / 教程）
- 来源系统（source：Confluence / 飞书 / 公众号）
- 作者（author）和部门
- 创建时间 / 更新时间（影响新鲜度排序）

**切片层维度**（在 ETL 切分时注入）：
- 切片在原文档中的位置（chunk_index）
- 切片标题（headings_path：根章节 + 子章节路径）
- 切片 token 数（监控异常切片）
- 切片关键词（来自 KeywordEnricher）
- 切片摘要（来自 SummaryEnricher）

**业务层维度**（更复杂的标注）：
- 难度等级（根据文本可读性自动评估）
- 敏感度（是否包含 PII / 价格信息 / 内部代号）
- 关联产品（"这篇讲的是哪个产品线"）
- 法规标签（GDPR / 行业合规）

**自动 vs 手动**：
- 90% 场景自动就够了（基于文件名、目录、用户标签）
- 真正敏感的元信息（如法律风险等级）建议人工 + 自动化校验双层
- "业务分类"如果模型成本可接受，让 LLM 做也能拿到 80%+ 的准确率

项目里只做了最简单的 filename → status 映射，但这个思路可以无限扩展——**关键是要识别"业务检索会用到什么过滤条件"，然后倒推需要哪些元信息**。

---

## 37. AI 关键词提取（KeywordMetadataEnricher + 元信息增强）

### Q37.1 为什么需要给文档提取关键词？向量检索不是已经能做语义匹配了吗？

**回答**：

向量检索的局限性来自于 Embedding 模型本身的训练目标——它学会了"哪些词经常一起出现"，但它**不知道哪些词是"专业术语"**。对于恋爱大师这个垂直场景，问题更明显：用户问"怎么判断男生是不是海王"，向量模型可能把"海王"编码得和"海洋生物"接近；而"海王"在恋爱领域的真实含义（同时和多人保持暧昧关系）和文档里的表达（"同时发展多条异性关系"）虽然语义等价，但用词完全不同，Embedding 相似度可能不高。

关键词元信息在这里扮演了**"业务维度标签"**的角色——它不是告诉机器"这些词相似"，而是告诉机器"这批文档的核心主题是什么"。检索时除了向量相似度匹配，还可以叠加关键词命中过滤：只有当文档的关键词集合与用户 query 的核心词有交集，才认为是真的相关。

更深一层看，关键词元信息其实是**把"大模型的判断能力"预支到检索阶段**——与其让模型在海量上下文里自己找重点，不如在入库时就标注好每篇文档的"重点词"。这样检索阶段可以做到更精准的二次过滤甚至关键词重排序，而不是完全依赖向量模型的端到端能力。

### Q37.2 KeywordMetadataEnricher 让大模型来提取关键词，这个过程是免费的吗？有没有代价？

**回答**：

**当然有代价**。每一次 enrichment 都是一次模型调用，需要消耗 Token 和费用。这不是一个小开销——如果知识库有 1 万篇文档，每篇提取 5 个关键词，就是 1 万次模型调用。

这个代价在项目初期是可以接受的，因为文档量小、场景简单。但**生产级系统要设计更好的方案**：

**方案一：一次性 enrichment**——在文档首次入库时做 enrichment，结果写入 Document metadata，之后不再重复调用模型。这适合"文档相对稳定、更新不频繁"的场景，恋爱大师的 FAQ 文档完全符合这个特点。

**方案二：分级 enrichment**——核心文档（高频检索的）做 AI enrichment，长尾文档用轻量方法（TF-IDF 关键词提取、TextRank）替代。AI enrichment 只占 20%，80% 的文档用无成本规则覆盖。

**方案三：异步 enrichment**——文档入库和 enrichment 解耦，先快速入库保证检索可用，enrichment 作为后台任务异步完成。这样用户感知不到 enrichment 延迟。

项目里是在应用启动时一次性做 enrichment 的（`LoveAppVectorStoreConfig` 中），文档量小所以完全没问题。如果文档规模达到万级，需要考虑方案三。

### Q37.3 除了关键词，还可以提取哪些元信息来增强检索？

**回答**：

关键词只是元信息的一种。围绕"提升检索精准度"这个目标，元信息可以分成几个维度：

**主题维度**：关键词（核心词）、摘要（一句话概括）、类别标签（FAQ / 案例 / 攻略 / 术语解释）。主题维度的元信息用于粗筛和分类。

**质量维度**：文档置信度（enrichment 时让模型给这篇文档的"信息质量"打个分）、时效性（"该文档的时效性如何"——用于过滤过期内容）。这类元信息用于对检索结果二次排序。

**结构维度**：文档类型（长文 / 短答 / 对话式）、内容密度（干货比例 vs 废话比例）、适合人群（新手 / 进阶 / 专业）。结构维度的元信息用于匹配用户的认知需求。

**业务维度**：难度等级（初 / 中 / 高）、适合关系阶段（单身 / 恋爱 / 已婚 / 离异）、适合性别视角（男性 / 女性 / 通用）。这类元信息直接决定"这条内容该不该出现"，和关键词元信息是同一层逻辑——都是业务维度的硬过滤。

关键词元信息是**被检索阶段直接用的**；其他维度的元信息更多是**辅助决策**，在结果重排序、过滤、个性化推荐阶段发挥作用。完整的元信息体系应该是多层次组合，而不是只盯着关键词。

---

## 38. 自定义文档切片（TokenTextSplitter + 分块策略）

### Q38.1 文档切片为什么不能按固定字数切？TokenTextSplitter 的"Token"指的是什么？

**回答**：

**Token vs 字符**：大模型的上下文不是按"字符"算的，而是按 Token 算。Token 是模型处理文本的基本单位——英文大约 4 个字符等于 1 个 Token；中文更贵，1-2 个汉字就占 1 个 Token。所以"按 500 个中文字符切"和"按 500 个 Token 切"是完全不同的粒度，前者可能切出来 250 Token，后者才是模型理解的 500 Token。

**固定字数的三大硬伤**：第一，切到词语中间——中文的"谈恋爱"被切成"谈恋"和"爱"，检索时召回一段缺胳膊少腿的文字，用户体验差。第二，语义完整性被破坏——一个完整的问题 + 回答被分到两个 chunk 里，检索召回时只能召回一半，信息丢失。第三，切得太碎或太粗——短 FAQ 每篇只有 100 字，按 500 字切就剩 1 块；但一篇 3000 字长文按 500 字切出 6 块，每块之间语义不连贯。

**TokenTextSplitter 的工作原理**：它先用 tokenizer（对应模型的 tokenizer，如 Qwen 用 SentencePiece）把文档按 Token 数量切块，同时尊重语义边界——优先在段落、标题、句号处切，切块之间的 overlap（重叠 token 数）保证上下文不会断掉。这样每个 chunk 的 Token 数是精确控制的，语义也是相对完整的。

### Q38.2 分块大小和重叠比例怎么定？有没有通用的经验值？

**回答**：

**分块大小（chunk_size）**没有绝对标准，但有一个大致的经验区间：

| chunk_size | 适合场景 | 缺点 |
|-----------|---------|------|
| 100-200 Token | 短问答、FAQ | 上下文太少，复杂问题召回不完整 |
| 300-500 Token | 通用场景（项目默认值 200） | 平衡性最好 |
| 800-1500 Token | 长段落、技术文档 | 单块信息密度高，但可能引入噪声 |

恋爱大师 FAQ 文档每篇回答本身不长（100-300 字），用 200 Token 的 chunk_size 基本能做到"一问一答"对齐一个 chunk。这是**按内容长度反向推导 chunk_size**的思路——先看你的内容单元有多大，再决定切多大。

**重叠比例（overlap）**是为了解决"边界丢失"问题——如果 chunk1 结尾是"沟通问题"，chunk2 开头是"吵架处理"，中间可能漏掉"如何正确吵架"这个关键内容。重叠 10-50 Token（项目里 100）可以在相邻 chunk 间保留上下文连续性。但**重叠不是越大越好**——重叠越大，有效信息密度越低（重复内容变多），向量库的存储量也会增加。

### Q38.3 为什么说"语义完整性"是切片质量的核心指标？

**回答**：

语义完整性指的是**每个切块是否是一个"自包含的意义单元"**——能单独被检索、被理解、被回答问题。

举个反例：用户问"如何判断男朋友是否真的爱我"，向量库召回了一个 chunk，里面写的是"判断方法如下：1. 是否愿意花时间陪你..."——但"判断方法"这四个字没有上下文（上一句可能是"很多人问我"），而下一句"是否愿意花时间"在另一个 chunk 里。这个切块能回答，但回答不完整，用户需要脑补。

**保证语义完整性的切片策略**：

第一，**按标题切片**——每个 H2/H3 标题下的完整段落作为一个 chunk，这是最简单的语义对齐。对于 Markdown 格式的文档，按标题层级切片效果最好。

第二，**重叠切**——相邻 chunk 之间保留一定的重叠文本，保证关键信息不会因为恰好落在切分线上而丢失。

第三，**句子级切分 + 重排**——对于超长文档，先按句子切，再用滑动窗口重组，在保证每块 token 数不超过限制的前提下，最大化语义连贯性。

第四，**元信息携带**——在每个切块 metadata 中保留"父文档标题"和"chunk_index"，检索结果排序后，相邻的多个 chunk 能被识别并优先一起使用。

---

## 39. 元数据过滤（FilterExpressionBuilder + 精确筛选）

### Q39.1 向量相似度和元数据过滤是什么关系？为什么不能只靠向量相似度？

**回答**：

可以把向量相似度理解为**"字面上的相关"**，把元数据过滤理解为**"业务上的该出现"**。两者是互补关系，不是替代关系。

**只靠向量相似度的盲区**：用户问"我和老公冷战一周了"，向量检索召回了"情侣冷战怎么办"（单身篇里有）和"如何扩大社交圈"（单身篇也有）——这两篇确实在字面上和"冷战"这个关键词有关，但**业务上不该出现在已婚用户的场景里**。如果用户是已婚状态，系统应该只返回"已婚冷战处理"相关内容。

**元数据过滤在向量检索之前**，它把不符合业务条件的候选文档直接在数据库查询层面排除掉，不需要进向量比对。SQL 等价于：`SELECT * FROM documents WHERE status = 'married' AND vector_similarity > 0.5`，而不是先找出所有向量相似 > 0.5 的再在内存里过滤。

这样做有两个好处：**一是减少计算量**——过滤后的候选集更小，向量比对更快；**二是提高精度**——被过滤掉的"字面相关但业务不该出现"的文档根本不会进入候选集，不会出现在 Top-K 里。

### Q39.2 FilterExpressionBuilder 支持哪些过滤条件？为什么用了"等于"而不是"包含"？

**回答**：

Spring AI 的 `FilterExpressionBuilder` 封装了向量库支持的标准过滤语法，常见的条件类型：

**等值过滤** `eq("status", "married")`：最常用，等价于 SQL 的 `WHERE status = 'married'`。项目中用的就是这个——每个文档在加载时根据文件名打了 status 标签，检索时按用户当前状态过滤。

**数值比较** `gt("score", 0.8)` / `lt("price", 100)`：用于数值型元数据，比如过滤"评分高于 4.5 的课程"。

**范围过滤** `between("created_at", "2024-01-01", "2024-12-31")`：用于时间范围过滤，比如"只查近一年更新的文档"。

**逻辑组合** `and()` / `or()`：组合多个条件。比如 `eq("status", "married").and(eq("difficulty", "hard"))` 表示"已婚篇且高难度"。

项目中只用 `eq`，是因为业务粒度本身就设计得很清晰（单身 / 恋爱 / 已婚三个离散状态）。如果要做更精细的分层（比如加上"情感严重程度"这种连续变量），就需要数值比较或范围过滤。

**为什么用等于而不是包含**（比如 `contains("status", "married")`）：因为元数据的业务分类通常是**互斥的离散标签**，不是文本内容搜索。"状态 = 已婚"是一个确定的事实，不是"状态里包含已婚这个词"。用等于过滤在数据库层面走索引，最高效；用包含则会退化为全文搜索，失去索引加速的意义。

### Q39.3 元数据过滤在大规模文档下的性能怎么保证？

**回答**：

当文档量达到百万级时，元数据过滤和向量检索的执行顺序非常关键。

**错误的顺序**：先做向量 Top-K 检索（扫描全量向量），再在结果集上过滤——此时候选集已经是 100 万向量里比出来的，过滤掉 80% 之后等于白算了 80% 的向量距离。

**正确的顺序**：在向量库查询阶段就把过滤条件作为前置条件送进去，让数据库**先过滤再检索**——只对满足条件的子集做向量比对。Pgvector 里这通过 `WHERE status = 'married'` 这样的 SQL WHERE 子句实现，索引（GiST/GIN）和向量搜索可以一起被查询规划器优化。

**更进一步的优化——分区表**：在千万级文档场景下，可以按 status 做 PostgreSQL 表分区（单身篇 / 恋爱篇 / 已婚篇各一个分区），查询"已婚"时只扫描已婚分区，完全绕过其他分区。这是 OLTP 场景的标准优化手段，在向量库场景同样适用。

---

## 40. 多查询扩展（MultiQueryExpander）

### Q40.1 MultiQueryExpander 的"扩展"到底在扩展什么？为什么一个 query 扩展出多个就够了？

**回答**：

核心问题是**"用户说不清楚自己要什么"**——用户用口语、缩写、错字提问，但文档里的内容用的是专业语言。这两者之间的 vocabulary gap 是单次检索最大的召回障碍。

**MultiQueryExpander 的策略是"用语言模型的创造力对冲用户 query 的模糊性"**。具体做法：把用户原始 query 送给大模型，让模型生成 N 个语义等价但表述不同的改写（比如 "怎么追女生" → "追求异性的方法"、"如何脱单"、"开始恋爱关系的技巧"），然后用这 N 个 query 各自去向量库检索，最后合并结果去重。

这样做背后的逻辑是**"总有一个变体会命中文档"**——原始 query 可能因为用词问题召不回"追求异性"这篇文档，但扩展 query 中的"开始恋爱关系的技巧"能召回。多路召回 + 合并去重，本质上是用 LLM 的语言能力换检索召回率。

### Q40.2 扩展出来的多个 query 怎么合并？合并的权重怎么算？

**回答**：

**合并策略**有几种，各有权衡：

**简单去重（项目用法）**：同一个文档被多个 query 召回时，标记命中次数。比如某篇文档被 3 个 query 中的 2 个召回，就标记 score=2。命中次数越多，说明文档与用户真实意图越相关。

**分数叠加**：把每个 query 召回来的相似度分数加起来，用总分排序。好处是既考虑相关性（分数）又考虑覆盖度（命中次数）。

**互重排序（Rerank）**：把合并后的候选文档（约 3N 个）送进一个 Rerank 模型（如 Cohere Rerank），模型会综合评估每个文档与原始 query 的相关性，输出重排后的 Top-K。Rerank 是工业级 RAG 系统的标配，效果比简单合并好 20-30%。

**追问**：为什么不直接让模型一次生成 10 个 query？
- 一次生成太多会让 LLM 产生"过度泛化"——生成的 query 可能和原意偏差很大
- N=3 是经验值，兼顾覆盖度和精确度，是多篇论文验证过的折中点
- 扩展 query 越多，检索成本线性增加（3 个 query = 3 倍向量检索次数）

### Q40.3 什么场景适合用多查询扩展？什么场景不适合？

**回答**：

**适合的场景**：
- **用户 query 模糊、口语化**：如"男朋友总是怎么怎么了"（指代不明确）
- **文档库覆盖主题广**：query 可能在多个子领域都有关联，需要多路召回
- **垂直领域的专业术语和用户语言不一致**：医学、法律、金融等

**不适合的场景**：
- **用户 query 本身很精确**：如"qwen-plus 的 context window 是多少"——这种精确查询做扩展反而引入噪声
- **检索成本敏感**：每次扩展 3 倍 cost，如果日均百万次检索，多查询扩展的成本是单查询的 3 倍
- **文档库主题单一**：如企业内部只查自己的 FAQ，query 和文档用词高度一致，扩展意义不大
- **对延迟敏感**：多查询扩展要等 LLM 生成 + 多次向量检索，延迟可能是单查询的 2-3 倍

恋爱大师的 FAQ 文档用词和用户口语差异大（专业回答 vs 口语提问），适合多查询扩展。但因为文档量小，直接用单查询 + 元数据过滤的组合也完全够用——多查询扩展更适合文档规模大且召回质量不稳的场景。

---

## 41. 查询重写优化（RewriteQueryTransformer）

### Q41.1 RewriteQueryTransformer 和 MultiQueryExpander 有什么区别？看起来都是改写 query？

**回答**：

两者都改写 query，但目的和方式不同。

**MultiQueryExpander 的逻辑是"发散"**：一个原始 query 变成多个变体，用多样性换召回率。它的输出是多条 query，每条都可以去检索。

**RewriteQueryTransformer 的逻辑是"翻译"**：把一条用户 query 翻译成"更符合文档语"的版本，只输出一条优化后的 query。它的输出还是一条 query，但这条 query 和原始 query 比用词更规范、更接近文档语言。

可以这样理解：**Rewrite 是在同一条路上的优化（让这条路更好走），Multi-Query 是在不同路上的探索（多走几条，总有一条对）**。两者的输出维度完全不同——一个是一对多，一个是一对一。

### Q41.2 RewriteQueryTransformer 具体把 query "翻译"成什么样？能不能举个例子？

**回答**：

**RewriteQueryTransformer 背后的 prompt 大致逻辑**（Spring AI 内部实现）是：让模型把用户的口语化、缩写、错字、含糊表达改写成"更像知识库文章标题"的表述。

实际效果举例：

| 用户原始 query | Rewrite 后 |
|---------------|-----------|
| "男朋友老已读不回我" | "伴侣已读不回消息的处理方法" |
| "怎么判断他是不是喜欢我" | "判断异性对你是否有好感的信号" |
| "冷战好几天了咋整" | "情侣冷战应对策略" |
| "婆婆老管我俩的事" | "婆媳关系中夫妻边界的建立" |

本质上是做了一次**同义规范化**——把口语里的情绪词、缩写、省略语补全成专业表达。这是 LLM 擅长的任务，因为 LLM 本身就是一个"语言流利模型"，它知道"已读不回"在恋爱场景下的专业说法是什么。

### Q41.3 Rewrite 改写错了怎么办？能不能控制改写的方向？

**回答**：

**改写错误分两类**，各有应对策略：

**第一类：改得太宽泛**——"男朋友不回消息怎么办" 被 Rewrite 成 "沟通问题"——丢失了"不回消息"这个具体行为，变成泛泛而谈。应对方式是在 Rewrite 后面加**约束 prompt**，告诉模型"改写后必须保留原 query 的核心实体和关键动作"。Spring AI 的 `RewriteQueryTransformer` 支持自定义 prompt，通过 `.withSystemPrompt()` 传入自己的指令。

**第二类：改得太偏离**——把"如何道歉" Rewrite 成"道歉的法律效力"，完全跑偏。应对方式是**多 query 兜底**：同时用 RewriteQueryTransformer + MultiQueryExpander，Rewrite 保证主路质量，Multi-Query 提供发散覆盖。Rewrite 出问题时，Multi-Query 的其他变体能命中正确答案。

**更好的做法是意图分类**：在 Rewrite 之前先判断用户 query 是否真的需要 Rewrite。如果 query 已经很规范（包含足够的关键词、语义明确），直接检索就好，不需要多此一举地改写。可以用一个轻量分类模型（如关键词检测）判断"query 是否口语化/模糊"，只在模糊时才走 Rewrite 逻辑。这叫**条件路由**，是生产系统里的常见优化。

---

## 42. 动态 Advisor 工厂（LoveAppRagCustomAdvisorFactory）

### Q42.1 什么叫"动态生成" Advisor？它和写死一个 Advisor 相比好在哪里？

**回答**：

在 `LoveAppRagCustomAdvisorFactory` 之前，如果要给不同用户生成不同的 RAG Advisor，传统的做法是**为每种情况写一个 Advisor Bean**：

```java
@Bean
public Advisor marriedRagAdvisor() { ... }  // 已婚用户用

@Bean
public Advisor singleRagAdvisor() { ... }   // 单身用户用
```

问题是：第一，如果业务有 10 种状态，就要写 10 个 Bean，代码膨胀。第二，状态枚举是可配置的（可能运营人员随时增减），写死的 Bean 无法动态适应。第三，检索参数（相似度阈值、topK）如果要支持运行时调整，写死 Bean 无法做到。

**工厂模式在这里的价值**是把 Advisor 的生成从"编译时绑定"变成"运行时可计算"：输入是用户状态（single / dating / married）和检索参数，输出是一个配置好的 Advisor 实例。工厂方法 `createLoveAppRagCustomAdvisor(status)` 调用一次，实时生成一个符合当前用户状态的 Advisor 对象。

更进一步，**这个工厂可以接受参数**，不只是 status——可以把相似度阈值、topK、过滤条件都作为参数传入，这样同一个工厂方法可以生产出"高精准度版本"（阈值 0.8）、"高召回版本"（阈值 0.3）、"平衡版本"（阈值 0.5）等多种 Advisor。

### Q42.2 工厂模式和策略模式有什么关联？为什么说是工厂而不是策略？

**回答**：

两者有交叉，但关注点不同。

**策略模式**解决的是"同一行为有多种实现，按运行时条件选择"——比如同一个排序行为，可以用快排、归并、堆排，用哪个由配置决定。策略模式的重点是**可替换的算法**。

**工厂模式**解决的是"对象的创建逻辑复杂，不应该直接 new"——比如 Advisor 的创建需要 5 个步骤（创建 Retriever → 设阈值 → 挂过滤器 → 挂 QueryAugmenter → 打包），把这套逻辑封装进工厂，调用方只需要传参数。工厂的重点是**封装创建过程**。

项目中 `LoveAppRagCustomAdvisorFactory` 的实现**两者兼有**：工厂负责创建 Advisor（封装创建步骤），同时创建的 Advisor 内部使用了不同的检索策略（已婚用户用已婚文档 + 相似度 0.5 + topK 3）。所以从调用方看是工厂，从内部实现看是策略。

面试时可以这样回答：**工厂负责"怎么创建"，策略负责"创建出来做什么"**。工厂是创建层面的抽象，策略是行为层面的抽象，两者配合使用是很常见的架构。

---

## 43. 上下文查询增强（ContextualQueryAugmenter + 空上下文处理）

### Q43.1 空上下文时为什么要专门处理？让模型自己判断"我不知道"不行吗？

**回答**：

**模型"不知道自己不知道"**——这是大模型的经典问题，叫"幻觉的另一种形式：过度自信地回答不知道的问题"。即使向量库召回了零条相关文档，大模型也会基于它自身的"泛化知识"生成一个看起来合理但完全可能误导用户的回答。

在恋爱大师场景里，这种"无中生有"的回答可能有真实危害——用户问"这个星座和那个星座配不配"，模型可能在完全没检索到星座配对文档的情况下，编造一套煞有介事的"星座理论"。用户以为是基于知识库的专业建议，实际上是模型自己瞎编的。

**ContextualQueryAugmenter 的设计哲学是把"系统对空召回的态度"编码进 Prompt**，而不是交给模型自己判断。项目中配置的空上下文话术是："抱歉，我只能回答恋爱相关的问题，别的没办法帮到您哦，有问题可以联系编程导航客服"。这是在告诉模型：**你的能力边界是恋爱领域，不是全知全能，不要越界回答**。

这种设计在专业领域 RAG（医疗、法律、金融）里尤为重要——**"拒答"比"乱答"对用户更有价值**，因为乱答可能造成严重后果，拒答至少不造成新的误导。

### Q43.2 除了空上下文兜底，ContextualQueryAugmenter 还能做什么？

**回答**：

**拼接"回答格式指令"**：它不仅处理空上下文，还负责把检索结果和原始 query 拼装成最终的 Prompt。比如可以在这里加入：
- "如果检索到的多条文档回答不一致，选择最权威的来源回答"
- "引用检索结果时，在回答末尾标注来源文档标题"
- "如果用户问题太模糊，基于检索结果中最高频的主题回答"

**控制上下文长度**：如果检索结果太多（比如 topK=10），全部塞进 Prompt 会超出 Context Window。ContextualQueryAugmenter 可以在这里做截断和摘要——只取检索结果的前 N 个 token，或者让模型先对检索结果做一次摘要再放入 Prompt。

**注入用户画像**：在拼接 Prompt 时，把用户画像信息（如"用户当前处于已婚状态"）作为 system prompt 的一部分注入，让模型在生成回答时能结合用户实际情况，而不是泛泛而谈。

所以 ContextualQueryAugmenter 本质上是**检索结果和 Prompt 之间的胶水层**，它的职责是"把检索到的东西加工成模型最擅长回答的格式"，空上下文处理只是其中一个特殊场景。

### Q43.3 拒答话术怎么设计才能不让用户觉得体验差？

**回答**：

拒答的核心矛盾是**"诚实但不失温度"**。一个好的拒答话术应该做到三点：

**承认边界**：明确告知模型能做什么、不能做什么。"抱歉，我只能回答恋爱相关的问题"——这是清晰的边界声明。

**提供替代方案**：告诉用户下一步怎么办，而不是说完"不知道"就结束。项目里的"联系编程导航客服"就是替代方案——把用户从 AI 引导到人工，不让用户感到被抛弃。

**保持语气一致**：如果整个应用是温暖亲切的风格，拒答话术也应该保持这个调性，不能突然变得冷冰冰。

**更精细的设计**是**分层拒答**：
- 完全无关的问题（问政治、问代码）：直接拒答，引导联系客服
- 部分相关的问题（问恋爱但超出知识库范围）：部分回答 + "知识库里没有关于 X 的内容，但根据我的理解..."——在诚实的基础上尽量给用户价值
- 模糊问题（问题本身不完整）：追问，引导用户补充信息

分层拒答需要用意图分类模型判断问题属于哪一层，这已经是 NLU 层面的工作了。恋爱大师用统一拒答是合理的简化——业务边界清晰（恋爱领域），不需要复杂的分层策略。

---

## 44. RAG 参数优化（DocumentRetriever + 调参实战）

### Q44.1 RAG 的"参数调优"调的是什么？topK 和相似度阈值是一回事吗？

**回答**：

**不是一回事**，但它们共同决定了"给模型的参考资料质量"。可以类比餐厅点菜：

- **topK = 点几道菜**：点 3 道菜还是 10 道菜。点太少（topK=1）可能吃不好（信息不足），点太多（topK=50）会撑着（噪声过多）。

- **相似度阈值 = 菜的及格线**：只有评分超过 6 分的菜才会被端上桌。阈值太高（0.9）可能没几道菜达标，阈值太低（0.1）会把 6 分的普通菜也端上来。

两个参数要**配合调**，不是单独优化。经验组合：

| topK | 阈值 | 适用场景 |
|------|------|---------|
| 1-3 | 0.6-0.8 | 精准场景（文档质量高、每块信息密度大） |
| 5-10 | 0.4-0.6 | 平衡场景（文档质量不一、需要一定覆盖） |
| 10-20 | 0.3-0.5 | 召回优先（文档关联度分散、宁可多召不错过） |

### Q44.2 怎么判断当前的 topK 和阈值设得合不合理？有没有系统的调优方法？

**回答**：

**基于黄金测试集（Golden Set）的评估**：

1. **准备测试集**：收集 100-200 条真实用户 query，每条 query 标注"期望召回到哪几个文档"。这是评估的基准线。

2. **批量实验**：固定其他参数，分别跑 topK = {1, 3, 5, 10} 和 threshold = {0.3, 0.5, 0.7} 的所有组合（共 16 组），统计每组的：
   - **召回率**（Recall@K）：期望召回到的文档有多少比例真的被召回了
   - **精确率**（Precision@K）：召回来的文档里有多少比例是真正相关的
   - **MRR**（Mean Reciprocal Rank）：第一个相关文档出现在第几位

3. **选最优组合**：选 Recall@3 + Precision@3 综合得分最高的组合。项目里 topK=3 + threshold=0.5 是在小规模文档下测出来的经验值。

**更客观的方法**是用 **RAGAS 指标**（RAG Assessment Suite）自动评估，不需要人工标注。RAGAS 有三个核心指标：
- **Faithfulness**：回答里有多少内容是从参考资料来的（幻觉率）
- **Answer Relevancy**：回答和用户问题的相关程度
- **Context Relevancy**：召回来的上下文和问题的相关程度

这三个指标天然会惩罚 topK 太大（上下文噪声多、Faithfulness 下降）和阈值太高（召回太少、Relevancy 下降）的情况。

### Q44.3 除了 topK 和阈值，还有哪些参数影响 RAG 效果？

**回答**：

三个常见的核心参数之外，还有几个经常被忽视但影响显著的参数：

**返回文本长度（max content length）**：每个 Document 被截断到多少字符/Token。如果切片本身就很大（> 2000 Token），截断后检索出来的上下文可能只有前半段。恋爱大师的 FAQ 每块本来就短，所以这个问题不严重。

**向量维度（embedding dimension）**：模型产出的向量是多少维的（text-embedding-3-small 是 1536 维，text-embedding-3 是 3072 维）。维度越高，表达能力越强，但存储空间越大、比对越慢。Pgvector 默认 1536 维。

**距离度量（distance metric）**：COSINE（余弦相似度）/ L2（欧几里得距离）/ INNER_PRODUCT（点积）。余弦相似度最常用，适合文本语义相似度场景；L2 更适合图像/特征距离场景。

**Rerank 参数**：如果接了 Rerank 模型（Rerank n=3 表示对每个 query 的 Top-3 结果做重排），这个参数直接决定最终召回质量。这是最有效的单参数优化——在原始向量检索后面加一个 Rerank，效果往往比调 topK 和阈值好得多。

---

## 45. 模块化 RAG 架构（RetrievalAugmentationAdvisor + 阶段划分）

### Q45.1 什么叫"模块化" RAG？Spring AI 把 RAG 拆成了哪几个阶段？

**回答**：

传统 RAG 的实现是**黑盒式的**：你调一个方法，它内部自己做了"检索 → 塞进 Prompt → 返回"。你不知道它怎么检的、检了几条、用了什么过滤。一旦召回质量差，你只能换整个 RAG 模块。

Spring AI 1.0 的 `RetrievalAugmentationAdvisor` 把 RAG 拆成了**可观测、可替换、可组合的三段**：

**Pre-Retrieval（检索前）**：对用户 query 做预处理。包括 Query Rewrite（改写用户问题）、Query Expansion（多查询扩展）、Query Classification（判断要不要做 RAG）等。这些组件的共同特点是**它们不碰文档，只改 query**。

**Retrieval（检索）**：用处理好的 query 去向量库检索。核心组件是 `DocumentRetriever`，它封装了"query → 向量 → 相似度比对 → 过滤 → 返回"的全过程。这一步是**唯一接触数据源的地方**。

**Post-Retrieval（检索后）**：对召回结果做二次加工。包括 ContextualQueryAugmenter（拼装 Prompt + 空上下文处理）、Context Compression（压缩上下文长度）、Rerank（重排序）等。

这种拆分的最大价值是**每个阶段都可以独立替换**：把默认的向量检索换成云知识库检索、把 QueryRewrite 换成 MultiQueryExpander、把空上下文处理换成自己的兜底话术——不需要动其他阶段的代码。

### Q45.2 这种模块化架构和最早的"一体式" RAG 相比，优势具体在哪里？

**回答**：

**调试成本**：当 RAG 回答质量差时，一体式 RAG 需要怀疑整个链路（query 不好？检索条件不对？上下文太长？Prompt 不对？）。模块化 RAG 可以**逐段打日志**——先看 Rewrite 后的 query 变成什么样，再看检索召回了几条、分数多少，再看 Prompt 拼装后的样子，定位问题的速度是数量级差异。

**按场景切换**：恋爱大师里"知识库问答"和"日常情感对话"是两种场景。知识库问答走完整的 RAG 链路（Rewrite → Retrieval → Augmenter）；日常对话可能不需要 RAG，直接对话就行。模块化架构让这种"按意图分流"变得自然——只要在入口判断要不要挂 RAG Advisor，不挂就不走检索。

**团队协作**：检索团队优化 DocumentRetriever、Prompt 团队优化 Augmenter、NLP 团队优化 Query Transformer——三组人可以并行开发，互不干扰。一体式 RAG 里改一行代码要三方 review，模块化里各自改各自的 Bean，互不影响。

### Q45.3 你说"提高了系统的可扩展性"，具体是怎么体现的？

**回答**：

可扩展性体现在三个维度：

**接入新的知识源**：只需要实现一个新的 `DocumentRetriever` 即可。项目中 `DashScopeDocumentRetriever` 是阿里云知识库的检索器，如果未来要接入飞书文档、Confluence，只需要再写一个对应的 Retriever，不用动 RAG 的其他代码。这是**数据源的可扩展性**。

**叠加新的预处理阶段**：比如要加 HyDE（先让模型生成假设答案，再用假设答案检索），只需要在 Pre-Retrieval 阶段加一个 `HydeQueryTransformer`，原来的 Rewrite/Expansion 不动。这是**处理能力的可扩展性**。

**动态调整检索策略**：同一个 `RetrievalAugmentationAdvisor` 接口，可以动态注入不同的 Retriever 和 Transformer 实例（比如根据用户等级、会员状态、查询类型注入不同配置），而不是每个场景写一套完整的 RAG 代码。这是**业务策略的可扩展性**。

面试时可以用一句话总结：**模块化架构的本质是把"变化的部分"封装成接口，把"不变的部分"固定成框架**。Spring AI 定义了 Retriever / QueryTransformer / QueryAugmenter 三个接口的契约，开发者只需要实现变化的业务逻辑，框架提供调用编排和生命周期管理。

---

## 46. AI 工具调用（Spring AI @Tool 注解 + 7 个工具集成）

### Q46.1 什么叫"工具调用"？它和普通 API 调用本质区别是什么？

**回答**：

普通 API 调用是**人指挥程序做事**——你在代码里写 `userService.getUser(id)`，程序按固定逻辑执行，没有"思考"环节。

工具调用（Function Calling / Tool Calling）是**让大模型决定要不要调用工具、调哪个工具、传什么参数**。整个流程是：

```
用户："帮我搜一下今天北京天气"
  ↓  （模型"看到"了这个问题）
模型思考："用户问天气，我有一个工具叫 searchWeb，参数是 query'
  ↓  （模型输出结构化的工具调用请求）
系统执行：调用 searchWeb(query="北京今天天气")
  ↓  （工具返回真实数据）
模型再次思考："搜到的结果是……"
  ↓
返回最终回答："今天北京晴，气温 15-22°C……"
```

**本质区别**：普通 API 是确定性执行（写死的逻辑 + 固定参数）；工具调用是**不确定性决策**（由模型决定是否调、调什么）。这个"让模型决定"的能力是 Agent（智能体）区别于普通程序的关键——程序可以自己决定做 A 还是 B，但前提是有人写"If 用户问天气 → 调天气 API"这段代码；工具调用让模型学会了这个对应关系，不需要程序员显式写分支逻辑。

### Q46.2 Spring AI 的 @Tool 注解是怎么工作的？它背后帮我们做了哪些事？

**回答**：

Spring AI 的 `@Tool` 注解是**声明式的工具注册**，它做了三件关键的事：

**第一件事：自动生成工具描述（JSON Schema）**。当你在方法上加 `@Tool(description = "Search for information...")`，Spring AI 会自动读取方法名、参数名、参数类型、description，生成一个符合 OpenAI Function Calling 规范的 JSON Schema。这份 Schema 会随请求发送给大模型，告诉模型"这个工具是干什么的、参数是什么类型"。

模型收到这个 Schema 后，如果判断要调用该工具，就会输出一个结构化的调用请求（工具名 + 参数 JSON）。这就是为什么@Tool 的 description 非常重要——它是模型唯一能"看懂"这个工具是干什么的依据。写得模糊或太短，模型就不知道怎么用。

**第二件事：自动解析模型返回的工具调用请求**。模型返回"调用 searchWeb，参数 {query: '北京天气'}"，Spring AI 用反射找到对应的 Java 方法，把 JSON 参数反序列化成正确定类型的 Java 参数，执行方法，得到返回值。

**第三件事：把工具返回值转成对话消息**。工具返回的字符串被包装成 `ToolResponseMessage`，拼到对话历史里，再发给模型做第二次推理（让它基于工具返回结果生成最终回答）。

所以 `ToolCallbacks.from(...)` 做的事情本质上是：**把 Java 方法注册到 Spring AI 的工具调用运行时，让这个运行时能接收模型的调用请求、执行对应方法、返回结果给模型**。开发者只需要写带 @Tool 注解的 Java 方法，其他全由框架代理。

### Q46.3 你的项目里有 7 个工具（文件、搜索、爬虫、下载、终端、PDF、终止），设计这么多工具背后的思路是什么？

**回答**：

**设计思路是"按能力边界划分"**，每个工具对应大模型天然缺失的一种能力：

| 工具 | 大模型缺什么 | 工具补什么 |
|------|------------|-----------|
| WebSearchTool | 不知道实时信息 | 联网查实时数据 |
| WebScrapingTool | 只能读文本，不能读网页 | 抓取任意 URL 的内容 |
| FileOperationTool | 只能读训练数据 | 读写本地文件 |
| ResourceDownloadTool | 不能下载文件 | 按 URL 下载资源 |
| TerminalOperationTool | 不能执行命令 | 在服务器上跑脚本 |
| PDFGenerationTool | 只能生成文本 | 生成可下载的 PDF |
| TerminateTool | 不知道何时结束 | 提供显式终止信号 |

这种设计背后的原则是：**每个工具只做一件事，做的事要非常明确**。工具越小粒度、越单一，模型越容易理解和使用。最忌讳的是"一个工具做 10 件事"——描述写得很长，模型在决定是否调用时会很困惑。

**另一个设计原则是"returnDirect"**（PDFGenerationTool 中体现）。普通工具返回结果后，模型会再推理一次给用户解释；但 PDF 工具的返回值"URL"本身就是用户要消费的成品，让模型再加工反而容易出错（模型可能描述 URL 而不是展示 URL）。`returnDirect = true` 告诉框架：工具返回就是最终答案，不需要模型再处理，直接返给用户即可。这能节省一次 LLM 调用，减少延迟，也减少幻觉。

### Q46.4 工具调用和 MCP（Model Context Protocol）是什么关系？项目里怎么处理的？

**回答**：

**MCP 是工具调用的"通信协议层"**——可以类比 USB 接口和 USB 设备的关系。

在没有 MCP 之前，每个 AI 应用接入外部工具需要"一对一适配"：OpenAI 的 Function Calling 格式、Anthropic 的 Tool Use 格式、Google 的 function calling 格式，各不相同。如果你的应用要同时调用"天气 API""数据库""飞书"，每个工具都要为每个 AI 厂商写一套适配代码。

**MCP 解决了这个问题**：它定义了一套标准协议（就像 USB-C），无论后端接的是哪种 AI 模型，外部工具都通过 MCP 协议通信。开发者只需要实现一次 MCP 工具，就能被任何支持 MCP 的 AI 模型调用。

项目里同时存在**两种工具调用方式**：
- `@Tool` 注解：Spring AI 原生的工具调用，工具和模型在同一个 JVM 里，调用链路短、延迟低
- `ToolCallbackProvider`（MCP 模式）：通过 `doChatWithMcp()` 方法使用，外部 MCP 服务以标准协议接入

两种方式并存的原因是：**MCP 适合接入外部已有的 MCP 服务（不用自己写 @Tool）**，而 `@Tool` 适合项目内部自建的工具（更轻量、延迟更低）。面试时可以把这个说成是**"按工具归属选择接入方式：自建工具用原生 @Tool，MCP 生态工具用 MCP 协议"**。

### Q46.5 工具调用有什么安全风险？你的项目是怎么做防护的？

**回答**：

**工具调用的安全风险分两类**：

**第一类：恶意调用**——用户诱导模型执行危险操作。比如"帮我删掉服务器上所有文件"，如果 TerminalOperationTool 没有权限控制，就会执行。防护措施：
- 权限分级：敏感工具（TerminalOperationTool、FileOperationTool）需要额外的权限校验
- 参数白名单：只允许特定命令（如只允许 git、npm，禁止 rm -rf /）
- 沙箱执行：TerminalOperationTool 在独立进程里跑，超时强制 kill

**第二类：工具故障扩散**——工具执行失败或超时，模型收到错误响应后可能进入错误循环，不断重试失败的工具。防护措施：
- 超时控制：给每个工具调用设置超时阈值，超时视为失败
- 错误兜底：捕获异常后返回友好的错误信息（而不是 Java 堆栈），让模型知道"这个工具出问题了，应该换方案"
- 熔断机制：同一个工具连续失败 N 次后，暂时禁用该工具，避免模型卡在这个工具上反复试

**第三类：信息泄露**——工具返回的内容里可能包含敏感信息（数据库密码、文件路径、内部接口地址），直接返给用户或被模型记住。防护措施：
- 工具返回值做过滤，只暴露用户需要知道的部分
- FileOperationTool 的目录限制：只允许读写 `FILE_SAVE_DIR` 下的文件，禁止访问项目根目录或系统目录

项目中这些防护措施有部分落地（目录限制、超时控制、异常捕获），但在生产级系统里，工具调用的安全治理是一个独立的大话题，涉及 RBAC、审计日志、操作审批等完整体系。



