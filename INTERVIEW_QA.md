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

这个回答既展示了"知道两个框架的区别"，又体现了"根据业务需求做技术选型"的工程思维，比"因为 Spring Boot 所以用 Spring AI"这种泛泛回答高一个层次。

