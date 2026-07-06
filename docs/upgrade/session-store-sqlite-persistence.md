# Session Store：基于 SQLite 的对话持久化与 FTS5 检索

## 背景

当前 `ConversationMemory` 使用 `LinkedHashMap` 仅在内存中维护短期对话上下文，进程退出后所有会话记录丢失。虽然已有 `LongTermMemory` 做 JSON 持久化，但它存的是"跨会话稳定事实"（去重精炼后），不是完整的原始对话流水。

需求：每次写入 `LinkedHashMap` 的同时异步写入本地 SQLite，并提供一个 `search_session` 工具供 Agent 检索历史会话记录。

## 目标

- 持久化：每次对话条目写入 `ConversationMemory` 时，异步写入 SQLite
- Session 划分：按 CLI 启动周期，每启动一次为 1 个 session，自动递增编号
- 检索：提供 `search_session` 工具（给 Agent 调用），基于 SQLite FTS5 做关键词检索
- 零侵入：不改变现有 `ConversationMemory` / `MemoryManager` 接口，只在其写入路径上挂一个旁路持久化组件

## 技术选型

| 层面 | 选择 | 理由 |
|------|------|------|
| 数据库 | SQLite（`org.xerial:sqlite-jdbc:3.49.1.0`） | 已在 `pom.xml` 中，零新增依赖；单文件，无需服务进程 |
| 全文检索引擎 | SQLite FTS5 | 内置于 sqlite-jdbc（SQLite >= 3.9.0 即支持）；支持中文拼音词元 + unicode61 tokenizer，不需要额外分词服务 |
| 异步写盘 | `ScheduledExecutorService` + 批量 flush | 延迟 500ms 批量写，兼顾性能与可靠性 |
| ID 生成 | 自增 session id + 自增 entry seq | 按启动周期划分，自动递增 |

## 总体架构

```
┌─────────────────────────────────────────────────┐
│ MemoryManager (已有)                             │
│  addUserMessage() → shortTermMemory.store()      │
│  addAssistantMessage() → shortTermMemory.store() │
│  addToolResult() → shortTermMemory.store()       │
└──────────────┬──────────────────────────────────┘
               │ 同一写入路径上挂载旁路
               ▼
┌──────────────────────────────────────────────────┐
│ SessionStore (新增)                               │
│  - enqueue(entry) → BlockingQueue                 │
│  - Flusher 线程 500ms 取一批 → batch INSERT        │
│  - search(query, limit, sessionId?) → List         │
└──────────────────────┬───────────────────────────┘
                       │ SQLite
                       ▼
┌──────────────────────────────────────────────────┐
│ ~/.paicli/sessions/sessions.db                    │
│  ├─ session(id INTEGER PK, created_at TEXT)       │
│  ├─ session_entry(id INTEGER PK AUTOINCREMENT,    │
│  │    session_id INTEGER FK, seq INTEGER,         │
│  │    role TEXT, content TEXT, type TEXT,          │
│  │    metadata TEXT, token_count INTEGER,          │
│  │    created_at TEXT)                             │
│  └─ session_entry_fts(                            │
│       content, metadata) USING fts5(tokenize='unicode61') │
└──────────────────────────────────────────────────┘
                       ▲
┌──────────────────────┴───────────────────────────┐
│ search_session Agent 工具（新增）                  │
│  参数: query, limit, session_id(可选)              │
│  在 ToolRegistry.registerMemoryTools() 中注册      │
└──────────────────────────────────────────────────┘
```

## 详细设计

### 1. SessionStore 类

**路径**: `src/main/java/com/paicli/memory/SessionStore.java`

```java
package com.paicli.memory;

/**
 * Session Store - 基于 SQLite 的对话持久化与 FTS5 检索。
 *
 * 职责：
 * 1. 在 MemoryManager 写入内存的同时，异步持久化到 SQLite
 * 2. 每个 CLI 启动周期自动分配一个自增 session ID
 * 3. 提供 FTS5 关键词检索接口，供 search_session 工具调用
 *
 * 线程安全：所有 public 方法可多线程调用。
 * 生命周期：由 MemoryManager 创建和关闭。
 */
public class SessionStore implements AutoCloseable {
    // 存储目录: ~/.paicli/sessions/
    // 数据库文件: sessions.db
    // 表: session, session_entry, session_entry_fts

    // 异步机制: BlockingQueue<MemoryEntry> + 单线程 ScheduledExecutorService
    // flush 间隔: 500ms
    // 批量大小: 最多 50 条/次 commit

    // 核心方法:
    //   int getCurrentSessionId()        // 当前 session 编号
    //   void append(MemoryEntry entry)   // 入队（由 MemoryManager 调）
    //   List<SessionEntry> search(String query, int limit, Integer sessionId)
    //   void close()                     // flush + 关闭连接
}
```

### 2. 数据库 Schema

```sql
-- session 表：每个 CLI 启动周期一行
CREATE TABLE IF NOT EXISTS session (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
    project    TEXT,                                -- 可选：当前项目路径
    model      TEXT,                                -- 可选：当前模型名
    status     TEXT    NOT NULL DEFAULT 'active'     -- active | closed
);

-- session_entry 表：每条对话条目一行
CREATE TABLE IF NOT EXISTS session_entry (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   INTEGER NOT NULL REFERENCES session(id),
    seq          INTEGER NOT NULL,                  -- 按 session 内递增序号
    role         TEXT    NOT NULL,                  -- user | assistant | tool
    content      TEXT    NOT NULL,
    type         TEXT    NOT NULL DEFAULT 'CONVERSATION',  -- CONVERSATION | FACT | SUMMARY | TOOL_RESULT
    metadata     TEXT,                              -- JSON 字符串（Map<String,String>）
    token_count  INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE INDEX IF NOT EXISTS idx_entry_session_seq
    ON session_entry(session_id, seq);

-- FTS5 全文搜索虚拟表
CREATE VIRTUAL TABLE IF NOT EXISTS session_entry_fts
    USING fts5(content, metadata, tokenize='unicode61');
```

Schema 变更管理：建表语句 `IF NOT EXISTS`，跟随代码首次初始化自动执行。

### 3. 异步写入策略

```
MemoryManager.addXxx()
  → shortTermMemory.store(entry)   // 同步，与现有行为一致
  → sessionStore.append(entry)     // 入队列，不阻塞

[后台 flusher 线程每 500ms 唤醒]
  → drain queue (最多 50 条)
  → batch INSERT into session_entry
  → UPDATE session_entry_fts (分开 INSERT，FTS5 不支持批量)
  → commit
```

- 启动时自动创建 session 行（`INSERT INTO session DEFAULT VALUES;`→ `getGeneratedKeys()` 取 id）
- flusher 线程为守护线程，不阻止 JVM 退出
- JVM shutdown hook 确保进程退出前最后一次 flush

### 4. FTS5 检索

```sql
SELECT e.id, e.session_id, s.created_at AS session_created_at,
       e.seq, e.role, e.content, e.type, e.metadata, e.token_count
FROM session_entry_fts f
JOIN session_entry e ON f.rowid = e.id
JOIN session s ON e.session_id = s.id
WHERE session_entry_fts MATCH ?
  AND (? IS NULL OR e.session_id = ?)
ORDER BY rank
LIMIT ?;
```

- `MATCH` 查询：用户输入的关键词由 FTS5 `unicode61` tokenizer 分词，支持 `"精确短语"` 和 `AND`/`OR` 组合（无需额外分词）
- 可选 `session_id` 过滤：限定只搜某个 session（默认全量）
- 结果按 `rank`（FTS5 BM25 相关性分）排序

### 5. SessionEntry 记录类

**路径**: `src/main/java/com/paicli/memory/SessionEntry.java`

```java
/**
 * Session 存储条的只读结果类，用于 search_session 工具返回值。
 * 不同于 MemoryEntry（内存级），SessionEntry 含 session_id/seq 等持久化字段。
 */
public record SessionEntry(
    int id,
    int sessionId,
    String sessionCreatedAt,
    int seq,
    String role,
    String content,
    String type,
    String metadata,
    int tokenCount
) {
    public String toDisplayString() {
        return "[Session #%d / %s @%s] %s: %s".formatted(
            sessionId, role, sessionCreatedAt,
            type, content.length() > 200 ? content.substring(0, 200) + "..." : content);
    }
}
```

### 6. MemoryManager 集成

在 `MemoryManager` 中增加 `SessionStore` 引用：

```java
public class MemoryManager {
    private SessionStore sessionStore;   // 新增

    // 构造时创建
    private void initSessionStore() {
        this.sessionStore = new SessionStore();
    }

    // 三个 add 方法追加写 session
    public void addUserMessage(String content) {
        MemoryEntry entry = ...;
        shortTermMemory.store(entry);
        sessionStore.append(entry);          // ← 新增
        compressIfNeeded();
    }

    public void addAssistantMessage(String content) {
        ...同上加 sessionStore.append(entry)
    }

    public void addToolResult(String toolName, String result) {
        ...同上加 sessionStore.append(entry)
    }

    // 新增：搜索历史 session
    public List<SessionEntry> searchSession(String query, int limit, Integer sessionId) {
        return sessionStore.search(query, limit, sessionId);
    }

    // close 时关闭 sessionStore
    public void close() {
        sessionStore.close();
    }
}
```

**不需要**修改 `ConversationMemory` 一行代码——`SessionStore` 是一个旁路组件，在 `MemoryManager` 层挂载。

### 7. search_session 工具

在 `ToolRegistry.registerMemoryTools()` 中注册：

```java
tools.put("search_session", new Tool(
    "search_session",
    "搜索历史会话记录。每个 CLI 启动周期为一个 session（自增编号），包含用户消息、助手回复和工具结果。基于 FTS5 全文检索，支持关键词匹配。",
    createParameters(
        new Param("query", "string", "搜索关键词，FTS5 unicode61 分词，支持 'AND'/'OR' 和 '精确短语'", true),
        new Param("limit", "integer", "最多返回条数，默认 10，上限 50", false),
        new Param("session_id", "integer", "限定搜索某个 session（可选，默认全量）", false)
    ),
    args -> {
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "search_session 失败: query 不能为空";
        }
        int limit = Math.min(parseInt(args.get("limit"), 10), 50);
        Integer sessionId = args.containsKey("session_id") ? parseInt(args.get("session_id"), -1) : null;
        if (sessionId != null && sessionId <= 0) sessionId = null;

        List<SessionEntry> results = memoryManager.searchSession(query, limit, sessionId);
        if (results.isEmpty()) {
            return "未找到匹配的历史会话记录。";
        }
        StringBuilder sb = new StringBuilder("🔍 历史会话检索结果 (").append(results.size()).append(" 条):\n\n");
        for (SessionEntry entry : results) {
            sb.append("  [Session ").append(entry.sessionId())
              .append(" #").append(entry.seq()).append(" / ")
              .append(entry.role()).append(" @")
              .append(entry.sessionCreatedAt()).append("]\n  ")
              .append(entry.content(), 0, Math.min(entry.content().length(), 300))
              .append(entry.content().length() > 300 ? "..." : "")
              .append("\n\n");
        }
        sb.append("---\n提示: 可用 session_id=%d 过滤到某个会话。".formatted(
            results.get(0).sessionId()));
        return sb.toString().trim();
    }
));
```

### 8. Agent 提示词注册

在 Agent 的 system prompt 工具列表中加入 `search_session` 的描述，让 LLM 知道在需要回忆过去对话时调用此工具。

（路径：Agent.java / 或 PromptAssembler 中动态注入的工具定义已由 `ToolRegistry.getToolDefinitions()` 自动覆盖，无需额外修改）

### 9. 存储位置与配置

- 默认位置：`~/.paicli/sessions/sessions.db`
- 环境变量 / JVM 属性覆盖：
  - `PAICLI_SESSION_DIR` / `paicli.session.dir`：修改 session 目录
  - `PAICLI_SESSION_FLUSH_INTERVAL_MS`：修改 flush 间隔（默认 500ms）
  - 若用户希望关闭持久化：`PAICLI_SESSION_ENABLED=false`

### 10. 数据安全与维护

- SQLite WAL 模式：`PRAGMA journal_mode=WAL;` 提升并发读写性能
- `synchronous=NORMAL`：性能与安全平衡
- 不自动删除旧 session。用户可通过 SQLite CLI 或未来 `/session clear` 手动清理
- session 行 status 在 clean close 时更新为 `closed`，崩溃时保持 `active`

## 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `src/main/java/com/paicli/memory/SessionStore.java` | **新建** | SQLite 持久化 + FTS5 检索核心类 |
| `src/main/java/com/paicli/memory/SessionEntry.java` | **新建** | 只读记录类 |
| `src/main/java/com/paicli/memory/MemoryManager.java` | 修改 | 注入 SessionStore，三个 add 方法追加写，新增 searchSession 方法 |
| `src/main/java/com/paicli/tool/ToolRegistry.java` | 修改 | `registerMemoryTools()` 中注册 `search_session` 工具 |
| `src/test/java/com/paicli/memory/SessionStoreTest.java` | **新建** | 单元测试 |

## 测试计划

| 测试 | 方法 | 验证点 |
|------|------|--------|
| 建表 | 构造 SessionStore 后检查表存在 | session, session_entry, session_entry_fts 三表 |
| 追加+flush | append 后等 1s，查 SQLite | 内容一致，seq 递增 |
| 跨 session 隔离 | 关闭再 new SessionStore，检查 session_id 自增 | 新 session id = old + 1 |
| FTS5 搜索 | 写入多条，search 中文关键词 | BM25 排序返回命中的条目 |
| 空查询 | search("", 10) | 返回空 |
| 关闭后搜索 | close() 后 search | 工具报错或优雅降级（重新打开连接） |
| 大量写入压力 | 500 条 append，检查 flush 后端到端完成 | 全部入库，无丢失 |

## 未覆盖场景（后续迭代考虑）

- 跨 session 跳转查询加速（当前基于 FTS5 rank 已足够，后续可加 session 级摘要索引）
- session 级联删除（`DELETE FROM session CASCADE`）
- 模糊时间范围过滤（如 `last_7_days`）
- 用 Rerank 做第二次语义排序（超出当前关键词检索的范畴）