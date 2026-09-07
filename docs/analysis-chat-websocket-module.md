# AI对话 + WebSocket 实时通信模块 —— 源码精读分析报告

> 分析对象：`ChatHandler` / `ChatWebSocketHandler` / `DeepSeekClient` / `ConversationService` / `WebSocketConfig` / `ChatController` / `ConversationController` / `Conversation` / `Message` / `ConversationRepository`
> 补充核对的关联文件：`JwtUtils`、`SecurityConfig`、`HybridSearchService`、`AiProperties`、`TokenCacheService`、前端 `frontend/src/store/modules/chat/index.ts`、`frontend/src/views/chat/modules/input-box.vue`、`frontend/build/config/proxy.ts`
> 结论分级：✅ 与简历/文档一致 ｜ ⚠️ 部分一致但实现与描述有出入 ｜ ❌ 与描述不符或为缺陷

---

## 一、模块总览

**职责一句话**：基于 Spring WebSocket + Redis + DeepSeek 流式接口（SSE），实现 RAG 对话全链路——会话获取/创建、历史加载、带权限混合检索（top-5）、Prompt 组装、LLM 逐 token 流式生成并实时推送、完成判定、历史持久化（Redis 最近 20 条）、客户端可中断生成、断线清理。

| 文件 | 行数 | 角色 |
|---|---|---|
| `handler/ChatWebSocketHandler.java` | 138 | WebSocket 消息入口：连接建立/消息分发/停止指令识别/断开清理 |
| `service/ChatHandler.java` | 422 | 对话编排核心：会话 ID、历史、检索、流式回调、完成检测、持久化、stop 标志 |
| `client/DeepSeekClient.java` | 167 | DeepSeek OpenAI 兼容 `/chat/completions` 流式（SSE）客户端 |
| `config/WebSocketConfig.java` | 23 | 注册 WS 端点 `/chat/{token}` |
| `controller/ChatController.java` | 82 | 提供 `GET /api/chat/websocket-token`（停止指令令牌）＋一段死代码 WS handler |
| `controller/ConversationController.java` | 247 | REST 查询对话历史（Redis 读取 + 时间过滤） |
| `service/ConversationService.java` | 112 | JPA 版对话落库（**全项目无调用方，死代码**） |
| `model/Conversation.java` | 33 | JPA 实体 `conversations` 表（仅被死代码路径使用） |
| `entity/Message.java` | 11 | 纯 DTO（role/content），非 JPA 实体（仅被死代码 RedisRepository 使用） |
| `repository/ConversationRepository.java` | 39 | 三个派生查询方法（仅被 ConversationService 使用） |

---

## 二、核心类精读

### 1. ChatWebSocketHandler（handler）

**职责**：Spring WebSocket 消息入口，继承 `TextWebSocketHandler`，负责连接注册、消息类型分发（停止指令 vs 普通聊天）、断开清理。

**关键方法**：

- `afterConnectionEstablished`（L38-43）：`extractUserId(session)` 得到用户名，`sessions.put(userId, session)`。
  - ⚠️ **`sessions` 这个 ConcurrentHashMap 全类只写不读**（没有任何 `sessions.get` 调用点），实际消息处理都靠 `session` 对象直接传递，该 map 基本是无效代码。
  - ⚠️ 以 `userId`（用户名）为 key：同一用户开两个标签页时第二个连接**覆盖**第一个；关闭任一个都会 `sessions.remove(userId)` 把另一个也"移除"（虽然 map 本身没被读，无实际影响，但语义上是错的）。
- `handleTextMessage`（L51-89）：先尝试解析 JSON——若 `type=stop` **且** `_internal_cmd_token` 等于 `INTERNAL_CMD_TOKEN`，调 `chatHandler.stopResponse(userId, session)` 并 return；否则（含 JSON 解析失败）一律当作普通聊天消息，调 `chatHandler.processMessage(userId, payload, session)`（L82）。
- `extractUserId`（L104-120）：`session.getUri().getPath()` 按 `/` 切分取最后一段作为 JWT，调 `jwtUtils.extractUsernameFromToken(jwtToken)`；**解不出用户名时不拒绝连接**，而是直接返回 token 字符串当 userId（L113-116）。→ 握手"鉴权"实际是"宽容失败"。
- `afterConnectionClosed`（L92-97）：`sessions.remove(userId)`，记录关闭状态。

**关键代码细节/边界**：
- `INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000`（L26）：**进程级静态常量，JVM 启动时生成一次，整个生命周期内固定**，不是用户级、不是短时令牌。
- 停止指令的"鉴权"仅凭这一个全局字符串比对（L66），无用户绑定、无过期。

### 2. ChatHandler（service）—— 模块核心

**职责**：编排"取会话→取历史→混合检索→组装上下文→流式调 LLM→逐块推送→判定完成→持久化→支持 stop"。

**内部状态**（均以 `session.getId()` 为 key 的 ConcurrentHashMap，L40-44）：
- `responseBuilders`：每个会话累积完整响应的 StringBuilder；
- `responseFutures`：跟踪响应完成的 CompletableFuture；
- `stopFlags`：停止标志。

**关键方法**：

- `processMessage(String userId, String userMessage, WebSocketSession session)`（L55-222）：
  1. `getOrCreateConversationId(userId)` 取/建会话 ID（L59）；
  2. 初始化 builder 与 future（L63-66）；
  3. `getConversationHistory(conversationId)` 加载历史（L69）；
  4. `searchService.searchWithPermission(userMessage, userId, 5)` —— **top-5 检索，带权限过滤**（L73）；
  5. `buildContext(searchResults)` 组上下文（L77）；
  6. `deepSeekClient.streamResponse(userMessage, context, history, onChunk, onError)`（L81-99）：
     - `onChunk`：先 `responseBuilder.append(chunk)`（builder 可能已被清理，判空），再无条件 `sendResponseChunk(session, chunk)`；
     - `onError`：`handleError` → `sendCompletionNotification` → future 异常完成 → 清理两个 map；
  7. 启动 `new Thread` 轮询判定完成（L102-209，详见"完成检测"）。
- `getOrCreateConversationId`（L230-243）：读 Redis `user:{userId}:current_conversation`，不存在则 `UUID.randomUUID()` 新建并设 **7 天 TTL**。**注意：这里的 userId 是 JWT subject（用户名）**；ConversationController 查询时用数据库 ID 与用户名两种格式都试（见下），说明 key 命名口径前后不一致。
- `getConversationHistory`（L250-266）：读 Redis `conversation:{conversationId}`（JSON 数组），解析失败返回空列表。
- `updateConversationHistory`（L274-307）：把 user 与 assistant 两条消息（带 `timestamp`）追加进历史，**超过 20 条用 `subList(size-20, size)` 截断**（L296-298），写回 Redis，TTL 7 天（L302）。
- `buildContext`（L309-327）：格式 `[i] (文件名) 内容\n`；**单片段超过 300 字符截断**（L315-322）；无结果返回 `""`，由 DeepSeekClient 用"（本轮无检索结果）"占位。
- `sendResponseChunk`（L334-350）：**先查 stopFlags，为 true 直接跳过**（L337-340）；否则发 `{"chunk":"..."}`（L343-346）。异常仅记录日志，不中断流。
- `sendCompletionNotification`（L352-369）：发 `{"type":"completion","status":"finished","message":"响应已完成","timestamp":...,"date":...}`。
- `handleError`（L371-382）：发 `{"error":"AI服务暂时不可用，请稍后重试"}`。
- `stopResponse`（L387-421）：`stopFlags.put(sessionId, true)`（L392）→ 回 `{"type":"stop","message":"响应已停止",...}`（L397-405）→ **另起一个线程 2 秒后清除 stop 标志**（L412-420）。
  - ⚠️ stop 只挡"推送"，不挡"生成"：WebClient 的订阅没有被取消，SSE 流继续在后台跑，且完成线程仍会把**完整回答**写入历史。

**完成检测（重点边界，L102-209）**：
- 先 `Thread.sleep(3000)` 等 API 首包；取 builder 当前长度后 `sleep(2000)` 再比长度，**不变即判定完成**；
- 若还在增长，循环最多 5 轮 `sleep(5000)+sleep(2000)` 观察；仍不停止则"强制完成"（L174-192）；
- 最坏等待 ≈ 3s + 2s + 5×(5s+2s) = **约 40 秒**后强制完成并持久化。
- 问题：a) 不依赖 SSE `[DONE]` 或 Flux 的 complete 回调（`subscribe` 没暴露 onComplete）；b) 完成线程与响应式回调线程**并发读写同一个 StringBuilder，无同步**；c) 若某 token 间隔超过 2 秒，会在流未结束时误判完成、提前发 completion 并持久化部分内容，之后迟到的 chunk 仍会推给客户端（因为 `sendResponseChunk` 无条件调用）但**不会落库**——展示与持久化不一致；d) 每条消息 `new Thread`，无线程池复用；e) 用户连发两条消息时第二个 `processMessage` 会覆盖第一个的 builder/future，两条完成线程同时跑。

### 3. DeepSeekClient（client）

**职责**：封装 DeepSeek（OpenAI 兼容）`POST /chat/completions` 流式调用。

- 构造（L27-42）：`WebClient.builder().baseUrl(deepseek.api.url)`；**apiKey 为空时不加 Authorization 头**（L34-36），兼容本地 Ollama（yml 注释里也写了 `http://localhost:11434/v1`）。
- `streamResponse`（L44-62）：`webClient.post().uri("/chat/completions").bodyValue(request).retrieve().bodyToFlux(String.class).subscribe(onChunk, onError)`。
  - 说明：对 `text/event-stream` 响应，`bodyToFlux(String.class)` 会把每条 SSE 的 `data:` 载荷解码为一个 String 元素，因此 `processChunk` 收到的就是一行 JSON（或 `[DONE]`）。
  - ⚠️ **没有保留 `Disposable`**（订阅句柄被丢弃），因此无法从外部取消流；也没有 onComplete 回调，完成只能靠 ChatHandler 的轮询线程；WebClient 未配置超时，理论上可挂起。
- `buildRequest`（L64-92）：`model`、`messages`、`stream=true`、`temperature`/`top_p`/`max_tokens`（来自 `AiProperties.Generation`，yml 默认 0.3/0.9/2000）。
- `buildMessages`（L94-141）：① system 消息 = `rules` + `<<REF>>` + 检索上下文（无结果时用"（本轮无检索结果）"）+ `<<END>>`；② 追加历史 messages；③ 当前用户问题。
- `processChunk`（L143-166）：`[DONE]` 直接 return（L146-149）；`mapper.readTree(chunk)` 取 `choices[0].delta.content`（L153-158）；空内容不回调；**解析异常只记日志**（L163-165）。

### 4. ConversationService（service）

**职责**：JPA 版对话记录（`conversations` 表）。
- `recordConversation`（L31-41）：按用户名找 User，new Conversation 存 question/answer。
- `getConversations`（L51-71）：管理员且 username="all" 查全表，否则按 userId（可带时间范围）。
- `getAllConversations`（L82-111）：先校验当前用户 role==ADMIN，否则 403；可指定目标用户。
- ❌ **`recordConversation` 全项目没有任何调用方**——聊天链路只写 Redis，MySQL 版对话历史是死代码（对简历需谨慎：不能说"对话历史落 MySQL"）。

### 5. WebSocketConfig（config）

- `registerWebSocketHandlers`（L19-22）：`registry.addHandler(chatWebSocketHandler, "/chat/{token}").setAllowedOrigins("*")`。
- 无 `HandshakeInterceptor`、无 SockJS fallback、无握手阶段校验、Origin 全放行（代码注释也承认"生产环境应该限制"）。

### 6. ChatController（controller）

- `@Component @RestController @RequestMapping("/api/v1/chat")`，**同时 `extends TextWebSocketHandler` 且覆写了 `handleTextMessage`（L29-48）**，但该 controller **并未被注册为 WS handler**（WebSocketConfig 只注册了 ChatWebSocketHandler）→ 这段 `handleTextMessage` 是**死代码**（旧设计残留；其中 `userId = session.getId()` 的口径也与现网不同）。
- `getWebSocketToken`（L53-81）：返回 `{code, message, data:{cmdToken}}`，cmdToken 即 `ChatWebSocketHandler.getInternalCmdToken()`。
  - ⚠️ 该端点叫 "websocket-token" 但**不是握手令牌**，而是"停止指令令牌"；且 SecurityConfig 对它 `permitAll`（匿名可拿）。

### 7. ConversationController（controller）

- `GET /api/v1/users/conversation`（L44-108）：从 `Authorization` 头剥掉 `Bearer ` 后用 `jwtUtils.extractUsernameFromToken` 拿用户名（L54）。
- 依次尝试三种 Redis key 前缀找会话 ID（L68-85）：`user:{dbId}:current_conversation`、`user:{username}:current_conversation`、`user:{dbId}:current_conversation`（前两种其实重复/冗余）——**这是对 ChatHandler 用 username 写 key 的"兼容补丁"**。
- `getConversationsFromRedis`（L113-206）：读 `conversation:{id}`，按 `timestamp`（格式 `yyyy-MM-dd'T'HH:mm:ss`）做时间过滤，返回 `{code,message,data:[{role,content,timestamp}]}`。
- `parseDateTime`（L211-244）：手写多种日期格式兼容（标准/无秒/无分秒/纯日期）。

### 8. Conversation / 9. Message / 10. ConversationRepository

- `Conversation`：`@Entity @Table("conversations")`，`id` 自增、`user` ManyToOne(LAZY)、`question`/`answer` TEXT、`@CreationTimestamp timestamp`，索引 `user_id`、`timestamp`。仅被 ConversationService 使用（死代码链路）。
- `Message`：纯 Lombok DTO（role/content），**不是 JPA 实体**，仅被 `RedisRepository`（同样无调用方的死代码）使用。
- `ConversationRepository`：`findByUserIdAndTimestampBetween` / `findByUserId` / `findByTimestampBetween`，仅被 ConversationService 使用。

---

## 三、核心流程（完整调用链）

```
① 握手连接
   前端: useWebSocket(`/proxy-ws/chat/${store.token}`)          [chat store L17, autoReconnect]
     → Vite proxy(ws:true) 重写 /proxy-ws → 后端 /chat/{token}   [proxy.ts L51-52]
     → WebSocketConfig 注册端点 /chat/{token}                     [WebSocketConfig L20]
     → ChatWebSocketHandler.afterConnectionEstablished
         extractUserId(): path 末段 = JWT → jwtUtils.extractUsernameFromToken()
             (extractClaimsIgnoreExpiration: 验签名、忽略过期)      [JwtUtils L131-139, L255-269]
         sessions.put(username, session)                          [L40]
     ※ 无握手拦截器、无 token 有效性/黑名单校验；解不出用户名也放行

② 用户发消息
   前端: wsSend(input.message) 明文                              [input-box.vue L49]
     → handleTextMessage: payload 不以 "{" 开头 → 普通消息
     → chatHandler.processMessage(userId=用户名, payload, session) [L82]

③ processMessage 主链路                                         [ChatHandler L55-222]
   a. getOrCreateConversationId
        Redis GET user:{username}:current_conversation
        无 → UUID + SET 7 天 TTL                                 [L230-243]
   b. 初始化 responseBuilders/responseFutures（key=session.getId()）[L63-66]
   c. getConversationHistory
        Redis GET conversation:{uuid} → JSON List<{role,content,timestamp}>（≤20 条）[L250-266]
   d. HybridSearchService.searchWithPermission(msg, username, 5)   [L73]
        KNN 召回窗口 recallK = topK×30 = 150                        [HybridSearchService L89-95]
        bool: must match(textContent) + filter(本人 OR public OR 组织标签) [L97-118]
        rescore: BM25(queryWeight 0.2 / rescoreQueryWeight 1.0)     [L121-132]
        size(topK=5) → top-5 SearchResult                          [L133]
   e. buildContext: "[1] (文件名) 片段…" 单片段≤300 字符            [L309-327]
   f. DeepSeekClient.streamResponse(msg, context, history, onChunk, onError)
        POST /chat/completions  body: {model, messages, stream:true, temperature, top_p, max_tokens}
        messages = [system(rules + <<REF>>context<<END>>)] + history + [user 当前问题]  [DeepSeekClient L94-141]
        bodyToFlux(String) 逐条 SSE data 解码
        onChunk → builder.append + sendResponseChunk → WS {"chunk":"..."}  逐 token 推送
        onError → handleError {"error":...} + completion + future 异常完成    [L90-99]
   g. 完成检测线程（new Thread）
        3s 首检 → 2s 观察 → 长度不变=完成；否则 5×(5s+2s)；仍不停 → 强制完成  [L102-209]
        完成时: sendCompletionNotification {"type":"completion","status":"finished",...}
              → updateConversationHistory(追加 user+assistant, 截 20 条, 7 天 TTL)
              → 清理 builder/future

④ 停止生成（客户端中断）
   前端 isSending 时: GET chat/websocket-token → {cmdToken}            [input-box.vue L35-38]
     → wsSend {"type":"stop","_internal_cmd_token":cmdToken}
     → handleTextMessage: type=stop 且 token 匹配 INTERNAL_CMD_TOKEN     [L66]
     → chatHandler.stopResponse: stopFlags(sessionId)=true
        → WS {"type":"stop","message":"响应已停止",...}
        → 2s 后线程清除 stopFlags                                        [L387-421]
     → 后续 onChunk 在 sendResponseChunk 被 stop 标志拦截（不再推给前端）
     ⚠️ DeepSeek 流未取消；完成线程仍会把完整回答写入 Redis 历史

⑤ 断线处理
   afterConnectionClosed → sessions.remove(userId)                      [L92-97]
   ※ 后端无心跳/无补发；前端 autoReconnect 自动重连（重连后不自动拉取 Redis 历史，
     前端列表为内存态）
```

**消息 JSON 格式汇总**

| 方向 | 报文 |
|---|---|
| 服务端→客户端（流式） | `{"chunk":"..."}` |
| 服务端→客户端（完成） | `{"type":"completion","status":"finished","message":"响应已完成","timestamp":<ms>,"date":"..."}` |
| 服务端→客户端（停止确认） | `{"type":"stop","message":"响应已停止","timestamp":<ms>,"date":"..."}` |
| 服务端→客户端（错误） | `{"error":"AI服务暂时不可用，请稍后重试"}` |
| 客户端→服务端（聊天） | 明文消息文本 |
| 客户端→服务端（停止） | `{"type":"stop","_internal_cmd_token":"WSS_STOP_CMD_xxx"}` |
| Redis 历史（`conversation:{uuid}`） | `[{"role":"user|assistant","content":"...","timestamp":"yyyy-MM-dd'T'HH:mm:ss"}, ...]`（≤20 条） |
| REST 取停止令牌 | `GET /api/chat/websocket-token` → `{code,message,data:{cmdToken}}` |
| REST 查历史 | `GET /api/v1/users/conversation?start_date=&end_date=` → `{code,message,data:[{role,content,timestamp}]}` |

---

## 四、工程细节与亮点（面试可讲点，含诚实的问题点）

1. **令牌设计（可讲但需如实）**
   - 亮点：WS 连接不依赖 Security 过滤器链（`/chat/**`、`/ws/**` 在 SecurityConfig L49 `permitAll`），改由业务层从 URL path 取 JWT 自行解用户名，规避了"WebSocket 握手无法带自定义 Header"的限制；停止指令用 `_internal_cmd_token` 防误触。
   - 缺陷：`websocket-token` 返回的是**进程级静态常量**（`WSS_STOP_CMD_ + 毫秒%1000000`），非用户级、非短时；`extractUsernameFromToken` 用 `extractClaimsIgnoreExpiration`（忽略过期、不查 Redis 缓存/黑名单），**过期或已登出的 token 仍能建立 WS 连接**；`setAllowedOrigins("*")` 无 Origin 校验。
2. **Redis 会话与历史缓存**
   - key：`user:{username}:current_conversation`（值=会话 UUID，TTL 7 天）、`conversation:{uuid}`（历史 JSON，TTL 7 天）；JWT 相关另有 `jwt:valid:`、`jwt:user:`、`jwt:refresh:`、`jwt:blacklist:` 前缀（TokenCacheService）。
   - 条数限制：历史截断保留最近 **20 条**（L296-298）；上下文片段 ≤300 字符、top-5 → system prompt 参考文本 ≈ 1.5KB 上限，配合 max_tokens=2000 控制上下文窗口。
3. **并发会话管理**
   - 三个 ConcurrentHashMap 以 `session.getId()` 隔离；同一用户多标签页以 username 为 key 的 `sessions` 会互相覆盖（map 本身只写不读，无实际危害但属坏味道）。
   - 每个消息 `new Thread` 做完成检测 + `stopResponse` 又 `new Thread` 延迟清标志 → 无线程池、线程泛滥风险；完成线程与响应式回调无同步，存在竞态。
4. **stop flag 实现**
   - 置位即挡 `sendResponseChunk`（L337-340），2 秒后自动清除（防止影响下一轮消息）。
   - 局限：只挡"推送"，**不取消 DeepSeek 流**（`subscribe` 的 Disposable 未保留），且历史仍持久化完整回答——面试被问"真的停了吗"要能答出这一层。
5. **DeepSeek 流式（SSE）解析与转发**
   - `bodyToFlux(String.class)` 把每条 `data:` 载荷解码为 String；`processChunk` 跳过 `[DONE]`、取 `choices[0].delta.content`，空内容过滤，再以 `{"chunk":...}` 包装转发，前端按 chunk 累加。
   - 可讲改进：用 `doOnComplete`/`doOnError` 替代轮询、保留 `Disposable` 支持真取消、给 WebClient 配 `responseTimeout`/`readTimeout`、加重试与退避。
6. **错误处理与超时**
   - 双通道：DeepSeek onError → `handleError`（`{"error":...}`）+ completion；WS handler 外层 catch → `sendErrorMessage`；前端 watch 到 `data.error` 置 `assistant.status='error'`。
   - 超时：无 WebClient 超时；"完成检测线程"最坏约 40 秒强制完成，能兜底但可能截断长回答。
7. **心跳**：❌ 后端无 Ping/Pong 心跳与空闲清理；仅前端 `@vueuse/core useWebSocket autoReconnect` 自动重连。
8. **混合检索 top-5 的实现细节**（值得讲）：KNN 召回窗口 150（5×30）→ bool must 关键词命中 + 权限 filter（本人/公开/组织标签，组织标签支持层级展开与多标签 should）→ BM25 rescore（KNN 0.2 / BM25 1.0 权重，And 操作符）→ `size(5)`；向量生成失败降级纯文本检索。
9. **Prompt 工程**：system 里用 `<<REF>>...<<END>>` 显式包裹参考上下文，无结果给占位文案；rules 与分隔符、生成参数全部可配置（`ai.prompt.*`、`ai.generation.*`）。

---

## 五、与简历声称的验证（逐条核对）

| 简历/文档声称 | 结论 | 代码事实 |
|---|---|---|
| "WebSocket 逐 token 实时推送" | ✅ 一致 | `sendResponseChunk` 每个 chunk 包装成 `{"chunk":"..."}` 立即发送（ChatHandler L334-350）；前端 watch `wsData` 按 chunk 累加（input-box.vue L26-29） |
| "客户端主动中断生成" | ⚠️ 部分一致 | 前端发送 `{"type":"stop","_internal_cmd_token":cmdToken}`（input-box.vue L38），后端置 stopFlags 停止**推送**（ChatHandler L337-340）；但 DeepSeek 流未取消、完整回答仍写入历史——"中断"只发生在展示层 |
| "历史消息存 Redis 最后 20 条" | ✅ 一致 | `updateConversationHistory` 追加后 `subList(size-20,size)` 截断（L296-298），写 `conversation:{id}` 7 天 TTL |
| "检索 top-5" | ✅ 一致 | `searchService.searchWithPermission(userMessage, userId, 5)`（L73）；ES `size(topK)`（HybridSearchService L133） |
| "前端先调 websocket-token 获取短时令牌，作为 query 参数建连，handler 在 connect 时校验"（CLAUDE.md） | ❌ 不符 | ① WS URL 是 `/proxy-ws/chat/${store.token}`，**token 是 JWT 且放在 path 里**，不是 query 参数（chat store L17）；② `/api/chat/websocket-token` 返回的是**静态停止指令令牌**（ChatWebSocketHandler L26，ChatController L53-81），与握手无关；③ connect 阶段 `extractUserId` 只解用户名、**不校验有效性/过期/黑名单**（JwtUtils L131-139 走 `extractClaimsIgnoreExpiration`），无效 token 也放行（L113-116） |
| 对话历史落 MySQL（隐含） | ❌ 未实现 | `ConversationService.recordConversation`（L31-41）**无任何调用方**；聊天链路只写 Redis。JPA 表 `conversations`、`ConversationRepository`、`Conversation`、`Message`、`RedisRepository` 均为未被聊天模块接入的死代码 |

**额外发现（面试前务必知道）**：
- `ChatController extends TextWebSocketHandler` 的 `handleTextMessage` 是死代码（未被注册为 WS handler）。
- `ChatWebSocketHandler.sessions` 只写不读；多标签页同用户会覆盖。
- userId 口径混乱：ChatHandler 以 JWT subject（用户名）为 Redis key 前缀，ConversationController 却按"数据库 ID / 用户名"三种格式轮询兼容（L68-85）。
- 完成检测靠"长度是否增长"的轮询线程而非 SSE 完成信号，存在误判/截断风险。
- 全链路无心跳、无幂等、无消息序号、无断线补偿。

---

## 六、面试官可能追问的问题清单（附代码事实依据）

1. **"你的流式响应是怎么判断'生成完成'的？为什么不用 SSE 的 [DONE]？"**
   事实：DeepSeekClient.processChunk 能识别 `[DONE]`（DeepSeekClient L146-149）但没有把"结束"作为回调暴露；ChatHandler 用一个新线程"3 秒首检 + 2 秒观察长度是否增长"，最长约 40 秒后强制完成（ChatHandler L102-209）。可答改进：`bodyToFlux(...).doOnComplete(...)`、保留 `Disposable`、用 `Mono.timeout`。同时承认缺陷：token 间隔 >2s 会被误判完成，导致提前发 completion、部分内容落库。
2. **"点停止后，AI 真的停了吗？"**
   事实：没有。`stopResponse` 只置 `stopFlags`（L392），`sendResponseChunk` 据此跳过发送（L337-340）；`streamResponse` 的 `subscribe` 未保留 Disposable（DeepSeekClient L58-61），SSE 流继续在后台消费；完成线程仍会 `updateConversationHistory` 持久化**完整回答**（L129）。前端只是本地置 finished 并清空空消息（input-box.vue L40-41）。
3. **"同一用户开两个浏览器标签页会怎样？"**
   事实：`sessions.put(userId, session)` 以用户名为 key，后连接覆盖先连接（L40）；关闭任一个 `sessions.remove(userId)`（L94）。且 `sessions` 从未被读取，消息处理只依赖传入的 session 对象；若两个标签页同时发消息，第二个 `processMessage` 会覆盖第一个的 `responseBuilders/responseFutures`（L63-66），并有两个完成检测线程并发跑。
4. **"WebSocket 连接怎么鉴权？token 过期了还能连吗？"**
   事实：`/chat/**` 在 SecurityConfig 中 `permitAll`（L49），不经过 JWT 过滤器；连接建立时从 URL path 末段取 JWT，`extractUsernameFromToken` 内部走 `extractClaimsIgnoreExpiration`——**验签名但忽略过期、不查 Redis 缓存与黑名单**（JwtUtils L255-269）；解不出用户名甚至直接放行并把 token 当 userId（ChatWebSocketHandler L113-116）。诚实承认：这是安全短板，应改为握手拦截器 + 校验有效性与黑名单。
5. **"`/api/chat/websocket-token` 是什么令牌？为什么匿名可访问？"**
   事实：它返回 `INTERNAL_CMD_TOKEN`——JVM 启动时生成一次的**进程级静态字符串**（ChatWebSocketHandler L26），用途是给前端发 `{"type":"stop",...}` 时做口令校验（L66）；SecurityConfig 对该路径 `permitAll`（L61）。它不是用户级短时令牌，命名有误导性。
6. **"历史为什么只存 Redis 不落 MySQL？20 条是怎么定的？"**
   事实：聊天链路只写 Redis（`conversation:{id}`，7 天 TTL，L274-307）；JPA 版 `ConversationService.recordConversation` 无调用方（死代码）。20 条截断在 L296-298。可延伸讲：Redis 适合热历史、TTL 天然过期、MySQL 版本可用于审计/后台报表但当前未接入。
7. **"检索是怎么做的？为什么是 5 条？这些片段怎么进 Prompt？"**
   事实：`searchWithPermission(msg, userId, 5)`（L73）→ ES KNN 召回窗口 `topK*30=150` → bool `must match` + `filter(本人|public|组织标签)` → BM25 rescore（权重 0.2/1.0）→ `size(5)`（HybridSearchService L86-135）；`buildContext` 组 `[i] (文件名) 片段`，片段截 300 字符（L309-327）；system prompt 用 `<<REF>>...<<END>>` 包裹，无结果给占位文案（DeepSeekClient L109-120）。可讲"为什么 KNN 先召回大窗口再 BM25 精排"。
8. **"错误和超时怎么处理？DeepSeek 挂了会怎样？"**
   事实：onError → `handleError` 发 `{"error":"AI服务暂时不可用..."}` + 发 completion（L90-99）；WS handler 外层 catch 也发错误（L84-88）；前端据此置 `status='error'`（input-box.vue L25）。超时：WebClient 无超时配置；完成检测线程最长约 40 秒强制完成兜底（L143-192）。可答改进：给 WebClient 配超时 + 重试。
9. **"有心跳和断线重连吗？"**
   事实：后端**没有**任何 Ping/Pong 心跳或空闲清理；前端用 `@vueuse/core useWebSocket` 的 `autoReconnect: true`（chat store L18）。断线后无消息补偿/序号，前端列表是内存态，刷新后需通过 REST 接口从 Redis 拉历史。
10. **"多线程/并发上有哪些隐患？"**
    事实：每条消息 `new Thread` 做完成检测（L102）、每次 stop 又 `new Thread` 延迟清标志（L412-420）——无线程池；完成线程与响应式 onChunk 并发读写同一个 StringBuilder（L86/L119）无同步；`sendResponseChunk` 由响应式线程调用，而 stop 标志由其他线程写——靠 ConcurrentHashMap 保证可见性，但判定逻辑无原子性。可答改进：每会话单飞队列/原子状态机/线程池。
