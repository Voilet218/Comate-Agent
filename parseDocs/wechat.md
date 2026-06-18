# PaiCLI 微信通道模块解析

> 模块路径：`src/main/java/com/paicli/wechat/`
> 
> 文件总数：22 个 Java 文件
>
> 定位：基于微信 iLink Bot API 的本地消息通道，实现手机微信 ↔ PaiCLI Agent 的双向通信

---

## 目录

1. [整体架构](#整体架构)
2. [消息流转全景](#消息流转全景)
3. [类逐个解析](#类逐个解析)
4. [启动流程](#启动流程)
5. [安全策略体系](#安全策略体系)

---

## 整体架构

### 架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                          微信服务器                                    │
│              iLink Bot API (ilinkai.weixin.qq.com)                    │
└──────────┬────────────────────────────────────┬──────────────────────┘
           │ POST /ilink/bot/getupdates          │ POST /ilink/bot/sendmessage
           │ (长轮询拉消息)                       │ (推送回复消息)
           ▼                                     ▲
┌──────────────────────────────────────────────────────────────────────┐
│                        IlinkClient.java                              │
│              HTTP 通信层，封装所有 iLink API 调用                       │
└──────────────────────────┬───────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    WechatMessageLoop.java                             │
│         主循环线程：长轮询 → 去重 → 排队 → 提交 Agent → 发回结果         │
└──┬──────────┬──────────┬──────────┬──────────┬──────────────────────┘
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
   排队      命令     Agent     发消息      Typing
   队列      解析     会话      渲染器      指示器
   Queue    Parser   Session   Renderer   sendTyping
```

### 数据流简图

```
微信APP ──→ iLink API ──→ IlinkClient ──→ MessageLoop ──→ Queue
                                                              │
                                                              ▼
                                                         Agent.run()
                                                              │
                                                              ▼
                                                         WechatTerminalRenderer
                                                              │
                                                              ▼
                                                         WechatRenderer.flushBuffer()
                                                              │
                                                              ▼
                                                         IlinkClient.sendText()
                                                              │
                                                              ▼
                                                         iLink API ──→ 微信APP
```

---

## 消息流转全景

### 消息从微信来（收消息路径）

```
步骤 1: WechatMessageLoop.run()
          ↓
步骤 2: client.getUpdates(account, timeoutMs)
          → POST ilink/bot/getupdates (长轮询，最多挂 35 秒)
          ↓
步骤 3: 返回 WechatUpdate { ret, nextSyncBuf, messages[] }
          ↓
步骤 4: 遍历 messages[]
          → seenMessageIds 去重
          → 校验 fromUserId == boundUserId
          → WechatCommandParser.parse() 判断是否斜杠命令
          → 旁路命令（/help /status /pause /resume /stop）直接处理
          → 普通消息入 queue.add(message)
          ↓
步骤 5: drainQueue() 从 queue.poll() 取出一条
          ↓
步骤 6: session.submit(prompt)
          → WechatAgentSession 内部 executor.submit(() -> agent.run(prompt))
          → Agent 开始 ReAct 循环（调用 LLM → 执行工具 → 继续 LLM）
```

### 消息回复给微信（发消息路径）

```
步骤 1: Agent.run() 产出文本
          ↓
步骤 2: WechatTerminalRenderer.appendAssistantContentDelta(delta)
          → 流式接收，攒到 buffer
          → 遇自然边界（句号/换行/900字符）触发 flush
          ↓
步骤 3: WechatRenderer.flushBuffer()
          → filterMarkdown() 去掉 ANSI 码、格式化 Markdown
          → split() 按 3800 字符切分（微信单条长度限制）
          → 对每个 chunk 调用 sender.send(chunk)
          ↓
步骤 4: WechatMessageLoop.send(text, contextToken)
          → new WechatRenderer(chunk -> client.sendText(account, boundUserId, token, chunk))
          ↓
步骤 5: IlinkClient.sendText(account, toUserId, contextToken, text)
          → 拼 JSON: { msg: { from_user_id, to_user_id, item_list: [{type:1, text_item:{text}}] } }
          → POST ilink/bot/sendmessage
          → Header: Authorization: Bearer <bot_token>
          ↓
步骤 6: 微信服务器 → 手机微信收到消息
```

### 完整时序图

```
微信手机               iLink API              IlinkClient         MessageLoop           Agent              LLM
  │                      │                       │                    │                   │                │
  │  扫码登录             │                       │                    │                   │                │
  │◄────────────────────►│   get_bot_qrcode       │                    │                   │                │
  │                      │   get_qrcode_status   │                    │                   │                │
  │                      │                       │                    │                   │                │
  │  发消息"查天气"       │                       │                    │                   │                │
  │─────────────────────►│                       │                    │                   │                │
  │                      │ 长轮询等待35秒          │                    │                   │                │
  │                      │◄──────────────────────│ getUpdates()        │                   │                │
  │                      │── ─ ─ ─ ─ ─ ─ ─ ─ ─ ─►│                    │                   │                │
  │                      │  返回消息列表           │                    │                   │                │
  │                      │                       │───────────────────►│ handle()          │                │
  │                      │                       │                    │ 去重 + 入队         │                │
  │                      │                       │                    │                   │                │
  │                      │                       │                    │ drainQueue()       │                │
  │                      │                       │                    │──────────────────►│ submit()       │
  │                      │                       │                    │                   │                │
  │                      │                       │                    │                Agent.run()         │
  │                      │                       │                    │                   │───── prompt───►│
  │                      │                       │                    │                   │◄─── response──│
  │                      │                       │                    │                   │               │
  │                      │                       │                    │  (ReAct 循环)       │               │
  │                      │                       │                    │                   │               │
  │                      │                       │                    │◄──────────────────│ 返回结果文本    │
  │                      │                       │                    │                   │                │
  │                      │                       │                    │ send()             │                │
  │                      │                       │◄───────────────────│ WechatRenderer     │                │
  │                      │                       │                    │ flushBuffer()      │                │
  │                      │                       │                    │                   │                │
  │                      │◄──────────────────────│ sendText()          │                   │                │
  │                      │  POST sendmessage      │                    │                   │                │
  │◄─────────────────────┤                       │                    │                   │                │
  │ 收到"今天25°C"       │                       │                    │                   │                │
```

---

## 类逐个解析

### 数据模型类（Records）

---

#### 1. `WechatAccount.java`

**职责**：存储微信账号的认证凭证和状态。

```java
public record WechatAccount(
    String token,        // iLink Bot Token，扫码确认后获取
    String accountId,    // iLink Bot ID，微信侧分配
    String baseUrl,      // API 基础地址，默认 https://ilinkai.weixin.qq.com
    String boundUserId,  // 绑定的微信用户 ID（扫码者的微信身份）
    String workspace,    // PaiCLI 工作区路径
    String syncBuf,      // getUpdates 的增量同步缓冲区，用于消息游标
    String createdAt     // 创建时间
)
```

**关键点**：
- `syncBuf` 是消息轮询的游标，每次 `getUpdates` 返回新的 `syncBuf`，下次轮询带上它来增量拉取
- `withSyncBuf()` / `withBoundUserId()` / `withWorkspace()` 是不可变更新方法（record 风格）

---

#### 2. `WechatQrLogin.java`

**职责**：扫码登录的二维码信息。

```java
public record WechatQrLogin(
    String qrcodeId,  // 二维码 ID，用于后续轮询扫码状态
    String qrcodeUrl  // 二维码图片 URL，可在终端渲染展示
)
```

---

#### 3. `WechatLoginResult.java`

**职责**：扫码登录的最终结果。

```java
public record WechatLoginResult(
    boolean connected,  // 是否成功连接
    boolean expired,    // 是否过期
    String status,      // 状态码文本（confirmed/expired/scanned等）
    String token,       // 认证用的 Bot Token
    String accountId,   // Bot 账号 ID
    String baseUrl,     // API 基础地址
    String userId,      // 绑定的微信用户 ID
    String message      // 状态描述/错误信息
)
```

---

#### 4. `WechatMessage.java`

**职责**：从微信收到的单条消息。

```java
public record WechatMessage(
    String messageId,           // 消息 ID（用于去重）
    String fromUserId,          // 发送者微信用户 ID
    String contextToken,        // 上下文令牌（微信侧用于关联同一会话）
    String text,                // 文本内容
    List<WechatMediaItem> mediaItems  // 附件（图片/文件/语音）
)
```

---

#### 5. `WechatMediaItem.java`

**职责**：消息中的媒体附件描述（当前 MVP 阶段仅存元数据，未实现 CDN 下载）。

```java
public record WechatMediaItem(
    String type,             // 媒体类型: image / file
    String fileName,         // 文件名（仅文件类型有）
    String mimeType,         // MIME 类型
    String encryptQueryParam, // CDN 加密查询参数
    String aesKey            // 媒体文件 AES 解密密钥
)
```

**关键点**：`isImage()` 方法判断是否为图片类型。

---

#### 6. `WechatUpdate.java`

**职责**：`getUpdates` API 的响应封装。

```java
public record WechatUpdate(
    int ret,                       // 返回码（-14 表示会话过期）
    String errMsg,                 // 错误描述
    String nextSyncBuf,            // 下一次请求用的 syncBuf
    Long nextLongPollTimeoutMs,    // 服务器建议的下次长轮询超时时间
    List<WechatMessage> messages   // 本次拉取到的消息列表
)
```

---

#### 7. `WechatPolicyDecision.java`

**职责**：微信通道的安全策略决策结果。

```java
public record WechatPolicyDecision(
    boolean allowed,  // 是否允许
    String reason     // 拒绝原因（允许时为空字符串）
)
```

- `allow()` → 允许
- `deny(reason)` → 拒绝并记录原因

---

#### 8. `WechatPolicyConfig.java`

**职责**：微信通道的安全策略配置。

```java
public record WechatPolicyConfig(
    Path workspaceRoot,          // 工作区根路径
    List<String> commandAllowlist,  // execute_command 白名单
    List<String> mcpAllowlist,     // MCP 工具白名单
    int dailyTurnLimit,            // 每日调用上限（默认 50）
    int singleTurnTokenBudget      // 单轮 Token 上限（默认 64k）
)
```

- `forWorkspace(workspaceRoot)` 工厂方法创建默认配置

---

#### 9. `WechatPaths.java`

**职责**：微信通道的本地文件系统路径规划。

```
~/.paicli/wechat/
├── accounts/           # 账号凭证 JSON 文件
│   └── latest.json
├── sessions/           # 会话持久化数据
├── media/              # 媒体文件缓存
├── logs/               # daemon 运行日志
│   ├── stdout.log
│   └── stderr.log
└── paicli-wechat.pid   # daemon 进程 PID 文件
```

- 可通过系统属性 `paicli.wechat.dir` 或环境变量 `PAICLI_WECHAT_DIR` 自定义根路径

---

### 通信层

---

#### 10. `IlinkClient.java`

**职责**：微信 iLink Bot API 的 HTTP 客户端。**整个微信通道的网络通信基础**。

**核心 API（共 7 个方法）**：

| 方法 | API 端点 | 用途 |
|------|----------|------|
| `startQrLogin(botType)` | `get_bot_qrcode` | 获取登录二维码 |
| `pollQrStatus(qrcodeId)` | `get_qrcode_status` | 轮询扫码状态 |
| `getUpdates(account, timeoutMs)` | `getupdates` | **长轮询拉消息** |
| `sendText(account, toUserId, ctxToken, text)` | `sendmessage` | **发送文本消息** |
| `sendTyping(account, toUserId, ctxToken, status)` | `sendtyping` | 发送"正在输入"状态 |
| `notifyStart(account)` | `msg/notifystart` | 通知微信 Bot 开始运行 |
| `notifyStop(account)` | `msg/notifystop` | 通知微信 Bot 停止运行 |

**实现细节**：
- 基于 OkHttp，连接超时 15 秒，读取超时 40 秒
- 所有请求 Header 带 `AuthorizationType: ilink_bot_token`、随机 `X-WECHAT-UIN`
- `getUpdates` 是长轮询：服务器会挂起连接直到有新消息或超时
- `sendTyping` 需要先调用 `getconfig` 获取 `typing_ticket`，再用该 ticket 发送 typing 状态
- `parseMessages()` 内部解析消息的 `item_list`（支持 text / voice / image / file 类型）

---

### 主循环与调度

---

#### 11. `WechatMessageLoop.java`

**职责**：微信通道的核心主循环。**这是整个模块的引擎**，负责：

1. 长轮询拉消息
2. 消息去重（`seenMessageIds` HashSet）
3. 斜杠命令分类处理（旁路 vs 排队）
4. 消息排队串行化（`queue` ArrayDeque）
5. 提交给 Agent 执行
6. 结果发回微信
7. Typing 状态管理

**核心循环（简化）**：

```java
while (!stopped) {
    completeCurrentIfDone();      // Agent 跑完了？发结果
    drainQueue();                 // 队列有消息？提交给 Agent
    refreshTypingIfNeeded();      // 5 秒刷新一次 typing
    
    WechatUpdate update = client.getUpdates(account, timeoutMs);
    // 长轮询：没消息就卡在这，有消息才返回
    
    for (WechatMessage message : update.messages()) {
        handle(message);          // 去重 → 判断命令类型 → 入队/旁路
    }
}
```

**关键设计决策**：
- **单线程串行**：一次只处理一条消息，Agent 跑完才处理下一条
- **旁路命令跳过队列**：`/help`, `/status`, `/pause`, `/resume`, `/stop` 立即执行，不排队
- **会话过期处理**：`ret == -14` 时等待 60 秒重试，不崩溃
- **Typing 刷新**：每 5 秒发一次 `sendTyping` 保持微信"正在输入..."状态

**旁路命令表**：

| 命令 | 行为 |
|------|------|
| `/help` | 返回帮助文本 |
| `/status` | 返回连接状态、队列长度、Agent 状态 |
| `/pause` | 暂停消费队列消息 |
| `/resume` | 恢复消费 |
| `/stop` | 取消当前 Agent 任务 |

**排队命令表**：

| 命令 | 行为 |
|------|------|
| `/clear` | 清空 Agent 历史 |
| `/compact` | 手动压缩上下文 |
| 普通文本 | 作为 prompt 提交给 Agent |

---

#### 12. `WechatAgentSession.java`

**职责**：微信消息与 PaiCLI Agent 之间的桥梁。为每条微信消息创建一个 Agent 执行上下文。

```java
// 单线程池，一次只跑一个 Agent 任务
ExecutorService executor = Executors.newSingleThreadExecutor();

public Future<String> submit(String prompt) {
    running = executor.submit(() -> agent.run(prompt));
    return running;
}

public String awaitCurrent() {
    String result = running.get();
    // 处理 CancellationException / InterruptedException / ExecutionException
    return result;
}
```

**创建 Agent 的完整流程**：
1. 加载 `PaiCliConfig`（获取 API Key、模型配置等）
2. 通过 `LlmClientFactory.createFromConfig(config)` 创建 LLM 客户端
3. 创建 `WechatPolicyConfig` → `WechatPolicyDecider` → `WechatToolRegistry`（安全策略链路）
4. 创建 `WechatTerminalRenderer`（输出渲染层，对接微信消息发送）
5. 创建 `Agent`（核心 ReAct 循环）
6. 设置 `setReturnFinalResponseWhenStreamed(true)`（流式输出时也保留最终结果）

**状态管理**：
- `isRunning()` → Agent 是否正在执行
- `hasCompletedRun()` → 本轮是否执行完毕
- `cancel()` → 取消当前任务（同时取消 CancellationToken 和 Future）
- `clear()` → 清空 Agent 对话历史
- `compact()` → 手动压缩历史上下文（返回压缩前后 token 数）

---

### 命令解析

---

#### 13. `WechatCommandParser.java`

**职责**：解析微信消息中的斜杠命令。

```java
public enum Type {
    NONE,       // 普通消息，不是命令
    UNKNOWN,    // 不认识的斜杠命令
    HELP, CLEAR, COMPACT, MODEL, CWD, STATUS, SEND,
    PAUSE, RESUME, STOP
}
```

**命令分类**：
- `bypassQueue = true`：立即执行，不排队（HELP, STATUS, PAUSE, RESUME, STOP, UNKNOWN）
- `bypassQueue = false`：排队等待 Agent 空闲（CLEAR, COMPACT, MODEL, CWD, SEND, NONE）

**解析规则**：
- 以 `/` 开头视为命令
- 空格前为命令名，空格后为 payload
- `/stop` 和 `/cancel` 同义

---

### 渲染与消息发送

---

#### 14. `WechatMessageSender.java`

**职责**：函数式接口，定义发送消息的契约。

```java
@FunctionalInterface
public interface WechatMessageSender {
    void send(String text) throws IOException;
}
```

**实际绑定**：在 `WechatMessageLoop` 中绑定为：

```java
// WechatMessageLoop.java:56
text -> send(text)

// 内部展开为：
chunk -> client.sendText(account, account.boundUserId(), token, chunk)
```

---

#### 15. `WechatRenderer.java`

**职责**：实现 `Renderer` 接口，将 Agent 输出格式化后通过微信发送。

**核心流程**（`flushBuffer()`）：

```
buffer(原始文本)
     ↓
filterMarkdown() → WechatTextFormatter.format()
     ↓
去 ANSI 颜色码 + 格式化 Markdown 子集
     ↓
split() → 按 3800 字符切分（行尾断词优先）
     ↓
sender.send(chunk) → 逐段发送
```

**常量**：
- `MAX_CHARS = 3800`：微信单条消息最大字符数

**覆盖的 Renderer 接口方法**：

| 方法 | 行为 |
|------|------|
| `appendToolCalls()` | **空实现** — 微信侧不推送工具调用进度 |
| `appendDiff()` | **空实现** — 微信侧不推送文件 diff |
| `updateStatus()` | **空实现** — 微信无底部状态栏 |
| `promptApproval()` | **直接拒绝** — 微信通道不支持交互式 HITL 审批 |
| `openPalette()` | **返回 -1** — 微信侧无选择面板 |
| `appendLine()` / `append()` | 追加文本到 buffer |
| `flushBuffer()` | 格式化并发送 buffer 中的所有内容 |

**内部类 `WechatOutputStream`**：
- 字节流 → 遇换行 drain 到 buffer → 触发 `append()`

---

#### 16. `WechatTerminalRenderer.java`

**职责**：双重渲染器——同时输出到**本地终端**和**微信**。

**设计模式**：**装饰器模式**，包装一个本地 `Renderer` 和一个微信 `WechatRenderer`。

**流式刷新策略**：

```
buffer 状态                 触发条件
─────────────────────────────────────────────────
240 ~ 899 字符    遇自然边界 + 距上次 flush > 2s
900 ~ 2999 字符   遇自然边界立即 flush
≥ 3000 字符        强制立即 flush（不管是否自然边界）
```

**自然边界判断**（`endsAtNaturalBoundary()`）：
- 以句号/感叹号/问号/冒号/换行结束
- `\n\n` 空行分隔
- 代码块结束 `\n\`\`\``
- **不会**在未闭合的代码块或表格中间断句

**关键方法**：

| 方法 | 作用 |
|------|------|
| `appendAssistantContentDelta(delta)` | Agent 流式输出 → 微信 buffer → 按策略 flush |
| `finishAssistantContent()` | Agent 输出结束 → 强制 flush 全部内容 |
| `resetWechatStream()` | 新任务开始前重置微信流状态 |
| `consumeSentContentFlag()` | 判断本轮是否发送过内容（避免重复发送） |

---

#### 17. `WechatTextFormatter.java`

**职责**：Markdown 文本格式化适配器——将 Agent 输出的 Markdown 转换成微信可读的纯文本格式。

**核心转换规则**：

| Markdown 语法 | 微信输出 |
|---------------|----------|
| `# 标题` | `**标题**`（粗体） |
| `**粗体**` | `**粗体**`（保留粗体） |
| `*斜体*` | 中文内容去掉斜体标记 |
| 代码块 ` ```java ` | 保留 `\`\`\`` + 内容 |
| `[链接](url)` | 保留原样 |
| `![图片](url)` | **移除**（微信不支持） |
| `~~删除线~~` | 移除标记 |
| `| 表格 |` | 转为 `- **列名**：值` 格式 |
| `→ 长流程` | 自动换行 |
| `▪ ` / `■ ` 项目符号 | 移除 |

**特殊处理**：
- **CJK 斜体豁免**：中文、日文、韩文内容的 `*斜体*` 标记自动去掉（微信阅读体验更好）
- **代码 fence 语言判断**：只有已知编程语言（Java/JSON/XML/Bash/等）才保留 fence，否则当成普通文字
- **长行断行**：包含 `→` 的行超长时自动按 `→` 换行
- **ANSI 颜色码清除**：先由 `WechatRenderer.stripAnsi()` 清除

---

### 安全策略层

---

#### 18. `WechatToolRegistry.java`

**职责**：微信通道的 `ToolRegistry` 子类，在所有工具调用前插入安全策略拦截。

**执行链路**：

```
LLM 调用工具
     ↓
executeToolOutput(name, argumentsJson)
     ↓
WechatPolicyDecider.decide(name, argumentsJson)
     ↓
┌─ 允许 → super.doExecuteTool(name, argumentsJson) ← 实际执行
└─ 拒绝 → 记录 AuditLog + 返回 "微信通道策略拒绝: ..."
```

---

#### 19. `WechatPolicyDecider.java`

**职责**：决定某个工具调用是否允许在微信通道执行。

**策略矩阵**：

| 工具名 | 策略 |
|--------|------|
| `read_file`, `list_dir`, `glob_files`, `grep_code`, `search_code`, `web_search`, `web_fetch`, `browser_status` | ✅ **允许**（只读内置工具） |
| `execute_command` | ❌ **默认拒绝**（需 commandAllowlist 白名单精确匹配） |
| `write_file`, `create_project` | ✅ **允许**（受 PathGuard 限制在工作区内） |
| `revert_turn` | ❌ **拒绝**（不允许远程回滚） |
| `browser_connect`, `browser_disconnect` | ❌ **拒绝**（不允许远程切换浏览器会话） |
| `mcp__*` | ❌ **默认拒绝**（需 mcpAllowlist 白名单） |
| 其他 | ❌ **拒绝** |

---

### 启动与账号管理

---

#### 20. `WechatCommandMain.java`

**职责**：微信通道的命令行入口。处理 `paicli wechat <子命令>`。

**子命令一览**：

| 子命令 | 功能 | 关键行为 |
|--------|------|----------|
| `setup` | 扫码绑定微信账号 | 获取二维码 → 轮询扫码 → 保存账号凭证 |
| `start` | 前台启动微信通道 | 加载账号 → 启动 `WechatMessageLoop.run()`（阻塞） |
| `status` | 查看绑定状态 | 显示账号 ID / 工作区 / daemon PID |
| `daemon start` | 后台启动守护进程 | fork Java 进程 + 写 PID 文件 |
| `daemon stop` | 停止守护进程 | 读 PID → process.destroy() |
| `daemon restart` | 重启守护进程 | stop + start |
| `daemon status` | 查看 daemon 状态 | 检查 PID 文件 + 进程存活 |
| `daemon logs` | 查看 daemon 日志 | 显示 stdout.log 最后 100 行 |

**setup 流程详解**：

```
WechatCommandMain.setup()
     ↓
询问工作区路径（默认当前目录）
     ↓
IlinkClient.startQrLogin("3")  → 获取二维码
     ↓
TerminalQrRenderer.print()     → 终端显示二维码（ANSI / 内联图片）
     ↓
IlinkClient.pollQrStatus()     → 循环轮询，最多等 5 分钟
     ↓
扫码确认 → 拿到 token / accountId / userId
     ↓
WechatAccountStore.createAccount() + save()
     ↓
绑定完成
```

---

#### 21. `WechatAccountStore.java`

**职责**：微信账号凭证的本地持久化存储。

**存储位置**：`~/.paicli/wechat/accounts/latest.json`

**方法**：

| 方法 | 用途 |
|------|------|
| `loadLatest()` | 读取最新保存的账号 |
| `save(account)` | 保存账号到 JSON 文件 |
| `createAccount(...)` | 构造新的 WechatAccount，设置创建时间 |
| `mediaDir()` | 获取媒体文件缓存目录（自动创建） |

**安全措施**：
- 目录和文件设置 POSIX 权限：仅 owner 可读写（跨平台兼容，不支持时静默忽略）

---

#### 22. `TerminalQrRenderer.java`

**职责**：在终端渲染微信登录二维码。

**两种渲染模式**：

| 模式 | 条件 | 输出 |
|------|------|------|
| **ANSI 字符** | 兜底方案 | 用 `▀` 半块字符 + 前景/背景色拼接二维码 |
| **内联图片** | iTerm2/WezTerm/Warp 等支持 | OSC 1337 协议内嵌 PNG 图片 |

**技术实现**：
- 基于 Google ZXing 库生成 QR 码 `BitMatrix`
- 内联图片通过 `]1337;File=inline=1;base64,...` 终端转义序列显示
- 检测 `TERM_PROGRAM` 环境变量判断是否支持内联图

---

## 启动流程

### 前台启动（`wechat start`）

```
WechatCommandMain.start()
     ↓
WechatAccountStore.loadLatest() → 读取 ~/.paicli/wechat/accounts/latest.json
     ↓
new WechatMessageLoop(client, store, account)
     ↓
WechatMessageLoop.run()
     ↓
IlinkClient.notifyStart() → 通知微信侧 Bot 上线
     ↓
new WechatAgentSession(account, sender, renderer)
     ├── LlmClientFactory.createFromConfig() → 创建 LLM 客户端
     ├── WechatPolicyConfig.forWorkspace()   → 创建策略配置
     ├── WechatToolRegistry(new WechatPolicyDecider()) → 创建安全策略
     ├── WechatTerminalRenderer(localRenderer, sender) → 创建渲染器
     └── new Agent(client, registry)         → 创建 PaiCLI Agent
     ↓
进入 getUpdates 长轮询主循环
```

### 后台守护进程（`wechat daemon start`）

```
WechatCommandMain.daemonStart()
     ↓
ProcessBuilder → fork 新 Java 进程
     ↓
新进程执行: java ... com.paicli.cli.Main wechat start
     ↓
stdout/stderr 重定向到 ~/.paicli/wechat/logs/{stdout,stderr}.log
     ↓
写 PID 到 paicli-wechat.pid
```

---

---

## iLink Bot API 参考（完整入参/出参）

> 基础地址：`https://ilinkai.weixin.qq.com`
>
> 公共 Header：所有请求（除二维码相关）必须带 `Authorization: Bearer <bot_token>` 和 `AuthorizationType: ilink_bot_token`、`X-WECHAT-UIN: <随机4字节base64>`

---

### 1. `get_bot_qrcode` — 获取登录二维码

**请求**：

```
POST /ilink/bot/get_bot_qrcode?bot_type=3
```

```json
// 请求体：{}
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `bot_type` | query | string | 否 | 默认 `3`，ClawBot 类型标识 |

**响应**：

```json
{
  "qrcode": "qr_xxxxx",
  "qrcode_img_content": "https://weixin.qq.com/qrcode?data=...",
  "qrcode_id": "qr_xxxxx",
  "qrcode_url": "https://weixin.qq.com/qrcode?data=..."
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `qrcode` / `qrcode_id` | string | 二维码 ID，后续轮询凭此 ID |
| `qrcode_img_content` / `qrcode_url` | string | 二维码图片 URL（可在终端渲染） |

---

### 2. `get_qrcode_status` — 轮询扫码状态

**请求**：

```
POST /ilink/bot/get_qrcode_status?qrcode=qr_xxxxx
```

```json
// 请求体：{}
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `qrcode` | query | string | ✅ | 上一步拿到的 qrcodeId |

**响应**（待扫码/已扫码）：

```json
{ "status": "scanned", "retmsg": "" }
```

**响应**（已确认 — 目标响应）：

```json
{
  "status": "confirmed",
  "bot_token": "ilink_bot_xxx",
  "ilink_bot_id": "bot_xxx",
  "baseurl": "https://ilinkai.weixin.qq.com",
  "ilink_user_id": "wx_xxx"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `scanned` 已扫码未确认 / `confirmed` ✅ 确认 / `expired` 过期 |
| `bot_token` | string | **后续所有 API 的认证凭证** |
| `ilink_bot_id` | string | Bot 账号 ID |
| `baseurl` | string | API 基础地址（通常就是默认地址） |
| `ilink_user_id` | string | 绑定的微信用户 ID（谁扫的码） |
| `retmsg` | string | 状态描述 |

**响应**（已过期）：

```json
{ "status": "expired", "retmsg": "qrcode expired" }
```

---

### 3. `getupdates` — 长轮询拉消息 ⭐

**请求**：

```
POST /ilink/bot/getupdates
```

```json
{
  "get_updates_buf": ""
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `get_updates_buf` | string | ✅ | 游标/书签，首次传空字符串，后续传上次响应返回的值 |

**响应**（有消息时）：

```json
{
  "ret": 0,
  "errmsg": "",
  "get_updates_buf": "next_cursor_xxx",
  "longpolling_timeout_ms": 35000,
  "msgs": [
    {
      "from_user_id": "wx_xxx",
      "to_user_id": "bot_xxx",
      "context_token": "ctx_xxx",
      "message_id": "msg_xxx",
      "seq": 10001,
      "message_type": 2,
      "message_state": 2,
      "item_list": [
        {
          "type": 1,
          "text_item": {
            "text": "你好"
          }
        },
        {
          "type": 3,
          "image_item": {
            "mime_type": "image/jpeg",
            "media": {
              "encrypt_query_param": "xxx",
              "aes_key": "base64xxx"
            }
          }
        }
      ]
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `ret` | int | 返回码，`0` 正常，`-14` 会话过期需重新绑定 |
| `errmsg` | string | 错误消息 |
| `get_updates_buf` | string | **新的游标，下次请求必须带上** |
| `longpolling_timeout_ms` | long | 服务器建议的下次长轮询超时时间 |
| `msgs[].from_user_id` | string | 消息发送者（微信用户 ID） |
| `msgs[].to_user_id` | string | 消息接收者（Bot ID） |
| `msgs[].context_token` | string | 上下文令牌，用于关联同一会话 |
| `msgs[].message_id` | string | 消息唯一 ID（可用于去重） |
| `msgs[].item_list[].type` | int | 消息项类型：`1` 文本 / `3` 图片 / `34` 语音 / `49` 文件 |
| `msgs[].item_list[].text_item.text` | string | 文本内容（type=1 时） |
| `msgs[].item_list[].image_item.media.encrypt_query_param` | string | CDN 加密查询参数（type=3 时） |
| `msgs[].item_list[].image_item.media.aes_key` | string | AES 解密密钥（type=3 时） |

**响应**（无消息，超时）：

```json
{
  "ret": 0,
  "get_updates_buf": "same_cursor",
  "msgs": []
}
```

**响应**（会话过期）：

```json
{
  "ret": -14,
  "errmsg": "session expired"
}
```

---

### 4. `sendmessage` — 发送文本消息 ⭐

**请求**：

```
POST /ilink/bot/sendmessage
```

```json
{
  "msg": {
    "from_user_id": "bot_xxx",
    "to_user_id": "wx_xxx",
    "client_id": "paicli-1718000000-a1b2c3",
    "message_type": 2,
    "message_state": 2,
    "context_token": "ctx_xxx",
    "item_list": [
      {
        "type": 1,
        "text_item": {
          "text": "你好，这是回复"
        }
      }
    ]
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `msg.from_user_id` | string | ✅ | Bot 账号 ID（WechatAccount.accountId） |
| `msg.to_user_id` | string | ✅ | 目标微信用户 ID（WechatAccount.boundUserId） |
| `msg.client_id` | string | ✅ | 客户端消息 ID，用于去重，格式通常为 `paicli-{时间戳}-{随机hex}` |
| `msg.message_type` | int | ✅ | 固定 `2` |
| `msg.message_state` | int | ✅ | 固定 `2` |
| `msg.context_token` | string | 否 | 上下文令牌（来自 getupdates 返回的 context_token） |
| `msg.item_list[].type` | int | ✅ | `1` 文本 / `3` 图片 / `34` 语音 / `49` 文件 |
| `msg.item_list[].text_item.text` | string | ✅ | 要发送的文本内容（type=1 时） |

**响应**：

```json
{
  "ret": 0,
  "errmsg": ""
}
```

---

### 5. `getconfig` — 获取对话配置

**请求**：

```
POST /ilink/bot/getconfig
```

```json
{
  "ilink_user_id": "wx_xxx",
  "context_token": "ctx_xxx"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ilink_user_id` | string | ✅ | 微信用户 ID |
| `context_token` | string | 否 | 上下文令牌 |

**响应**：

```json
{
  "typing_ticket": "ticket_xxx",
  "ret": 0
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `typing_ticket` | string | 用于后续 sendTyping 的凭证 |
| `ret` | int | 返回码 |

---

### 6. `sendtyping` — 发送输入状态

**请求**：

```
POST /ilink/bot/sendtyping
```

```json
{
  "ilink_user_id": "wx_xxx",
  "typing_ticket": "ticket_xxx",
  "status": 1
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ilink_user_id` | string | ✅ | 微信用户 ID |
| `typing_ticket` | string | ✅ | 来自 getconfig 的 ticket |
| `status` | int | ✅ | `1` 开始输入 / `2` 停止输入 |

**响应**：

```json
{
  "ret": 0
}
```

---

### 7. `notifystart` — Bot 上线通知

**请求**：

```
POST /ilink/bot/msg/notifystart
```

```json
{}
```

**响应**：

```json
{
  "ret": 0
}
```

**作用**：通知微信服务器这个 Bot 已启动，可以开始接收消息推送。

---

### 8. `notifystop` — Bot 下线通知

**请求**：

```
POST /ilink/bot/msg/notifystop
```

```json
{}
```

**响应**：

```json
{
  "ret": 0
}
```

---

### API 生命周期顺序

```
① get_bot_qrcode ──→ ② get_qrcode_status ──→ 拿到 bot_token
                                                      │
                ┌─────────────────────────────────────┘
                ▼
        ③ notifystart ──→ 循环 { ④ getupdates → 处理消息 → ⑤ sendmessage }
                │
                ▼
        ⑧ notifystop
```

辅助 API（⑤ `getconfig` + ⑥ `sendtyping`）在主循环中按需调用。

---

## 安全策略体系

微信通道的安全策略分为三层，拦截顺序如下：

```
LLM 请求执行工具
      ↓
① WechatToolRegistry.executeToolOutput()
   → 调用 WechatPolicyDecider.decide()
      ↓
② WechatPolicyDecider 决策矩阵
   → 只读工具: 允许
   → execute_command: 仅白名单匹配允许
   → write_file: 允许（后续 PathGuard 拦截）
   → MCP: 仅白名单匹配允许
   → 其他: 拒绝
      ↓
③ 下层控制
   → PathGuard: 限制文件操作在 workspace 内
   → CommandGuard: 命令黑名单辅助
   → AuditLog: 所有工具调用审计日志
      ↓
最终决定
```

**与终端交互式 HITL 的区别**：

| 维度 | 终端模式 | 微信通道 |
|------|---------|---------|
| 审批方式 | 弹窗交互 y/n | **非交互式，策略自动决策** |
| execute_command | 可手动批准 | 默认拒绝，仅白名单 |
| MCP 工具 | 可手动批准 | 默认拒绝，仅白名单 |
| HITL 审批接口 | 弹窗 | `WechatRenderer` 直接返回拒绝 |

---

## 文件依赖关系图

```
WechatCommandMain.java
    ├── IlinkClient.java
    │   ├── WechatQrLogin.java
    │   ├── WechatLoginResult.java
    │   ├── WechatMessage.java
    │   │   └── WechatMediaItem.java
    │   └── WechatUpdate.java
    ├── WechatAccountStore.java
    │   ├── WechatPaths.java
    │   └── WechatAccount.java
    ├── WechatMessageLoop.java
    │   ├── IlinkClient.java
    │   ├── WechatAccountStore.java
    │   ├── WechatAgentSession.java
    │   │   ├── WechatToolRegistry.java
    │   │   │   ├── WechatPolicyDecider.java
    │   │   │   │   ├── WechatPolicyConfig.java
    │   │   │   │   └── WechatPolicyDecision.java
    │   │   ├── WechatTerminalRenderer.java
    │   │   │   ├── WechatRenderer.java
    │   │   │   │   ├── WechatMessageSender.java
    │   │   │   │   └── WechatTextFormatter.java
    │   │   │   └── (本地 Renderer 装饰)
    │   │   └── Agent.java (来自 agent/ 包)
    │   ├── WechatCommandParser.java
    │   │   └── WechatMessage.java
    │   └── WechatRenderer.java
    └── TerminalQrRenderer.java
```

---

## 总结

| 维度 | 说明 |
|------|------|
| **通信协议** | 微信 iLink Bot API（HTTP + JSON + 长轮询） |
| **收消息** | `POST ilink/bot/getupdates` 长轮询，增量 syncBuf |
| **发消息** | `POST ilink/bot/sendmessage`，JSON 封装 |
| **执行模型** | 单线程串行，一次只处理一条消息 |
| **安全策略** | 非交互式策略决策，只读工具默认允许，写操作受白名单管控 |
| **渲染链路** | Agent → WechatTerminalRenderer → WechatRenderer → IlinkClient.sendText() → 微信 |
| **持久化** | `~/.paicli/wechat/` 存放账号、日志、媒体缓存 |
| **运行模式** | 前台（阻塞进程）/ 后台（daemon 守护进程） |
