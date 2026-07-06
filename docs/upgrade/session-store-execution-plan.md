# Session Store 执行计划

基于 [session-store-sqlite-persistence.md](session-store-sqlite-persistence.md) 设计方案，分 4 个 step 逐步实施。

---

## Step 1：创建 SessionStore 核心类（建库、建表、异步写）

### 目标
新建 `SessionStore.java`，完成 SQLite 初始化（建库、建表、PRAGMA）、异步 append 队列、后台 flush 线程。这是纯存储层，**本 step 不需要改任何已有文件**。

### 要做什么

1. **新建 `src/main/java/com/paicli/memory/SessionStore.java`**
   - 构造时：解析存储目录（优先级 `PAICLI_SESSION_DIR` > `paicli.session.dir` > `~/.paicli/sessions/`），`mkdirs()` 确保目录存在。
   - 构造时：打开 `sessions.db` 连接，设置 `PRAGMA journal_mode=WAL` 和 `PRAGMA synchronous=NORMAL`。
   - 构造时：执行 3 条 `CREATE TABLE IF NOT EXISTS`（session / session_entry / session_entry_fts）+ `CREATE INDEX IF NOT EXISTS`。
   - 构造时：`INSERT INTO session DEFAULT VALUES` 创建当前 session 行，取出 `getGeneratedKeys()` 自增 id 保存为 `currentSessionId`。
   - 构造时：启动 flush 用的 `ScheduledExecutorService`（单线程守护，`"paicli-session-flusher"`），以固定延迟 `500ms`（可由 `PAICLI_SESSION_FLUSH_INTERVAL_MS` / `paicli.session.flush.interval.ms` 覆盖）。

2. **append 方法**
   - `void append(MemoryEntry entry)`：将 entry 包装为内部数据结构（role 从 entry id 前缀推导，metadata 用 Jackson 序列化为 JSON），放入 `LinkedBlockingQueue`（容量 10_000，满了降级日志 warn 然后丢弃）。

3. **后台 flush 逻辑**
   - 每次定时任务触发：`queue.drainTo(buffer, 50)` 取一批。
   - 开事务 → 逐条 INSERT `session_entry` → 逐条 INSERT `session_entry_fts`（行号用 `last_insert_rowid()` 获取）→ commit。
   - 注意：FTS5 虚拟表不能批量 `INSERT`，必须逐条执行。
   - 异常处理：日志 warn，不丢队列（未取出的保留）。

4. **shutdown 钩子**
   - `close()` 方法（实现 `AutoCloseable`）：最后一次 flush 剩余队列 → `UPDATE session SET status='closed'` → 关闭连接 → 关闭线程池。
   - 附一个 `Runtime.getRuntime().addShutdownHook` 兜底（仅在`close()`还没被调过时执行）。
   - 允许 `PAICLI_SESSION_ENABLED=false` 跳过所有初始化（返回一个 no-op SessionStore）。

### 测试

新建 `src/test/java/com/paicli/memory/SessionStoreTest.java`，用 JUnit 5 `@TempDir` 做临时目录，测试：
- **建表**：构造后查 `SELECT name FROM sqlite_master WHERE type='table'` 确认 3 表 + 1 索引存在。
- **append + flush**：append 5 条后 `Thread.sleep(1500)`，查 session_entry count = 5，seq 为 1-5。
- **当前 session id**：`getCurrentSessionId()` 返回正确自增值。
- **关闭**：`close()` 后 session 表 status 变为 `closed`。

### 确认检查点
- `mvn test -Dtest=SessionStoreTest` 全绿
- 检查 `sessionStore.close()` 在正常路径被调用时，shutdown hook 不会重复执行（日志里没有重复 close 的 warn）

---

## Step 2：创建 SessionEntry 记录类 + SessionStore.search()

### 目标
新建 `SessionEntry.java` 记录类，并在 `SessionStore` 上实现 FTS5 检索方法。**本 step 仍然不改已有文件**。

### 要做什么

1. **新建 `src/main/java/com/paicli/memory/SessionEntry.java`**
   - `public record SessionEntry(int id, int sessionId, String sessionCreatedAt, int seq, String role, String content, String type, String metadata, int tokenCount)`。
   - 方法：
     - `toDisplayString()`：返回格式化摘要（前 200 字截断）。
     - `static fromResultSet(ResultSet rs)`：从 SQL 结果集构造（复用逻辑）。

2. **新增 `SessionStore.search(String query, int limit, Integer sessionId)` 方法**
   - 使用 `PreparedStatement` 执行 FTS5 MATCH 查询（方案文档第 4 节 SQL）。
   - FTS5 MATCH 语法保持原样传递用户输入；`limit` 做 1-50 clamp。
   - 结果按 FTS5 `rank`（BM25）升序排列。
   - 返回 `List<SessionEntry>`。

3. **FTS5 写入修正**（Step 1 可能漏掉的细节）
   - 确认 `INSERT INTO session_entry_fts(rowid, content, metadata) VALUES (?, ?, ?)` 使用 `e.id` 而非 `last_insert_rowid()`，因为 AUTOINCREMENT id 在 batch insert 中不保证连续。

### 测试

追加到 `SessionStoreTest`：
- **中文搜索**：append 几条中文内容（如 `"用户要求修改登录页面样式"`），search `"登录页面"` 返回命中条目。
- **空查询/无结果**：search `""` 或 search 不存在的词 → 返回空列表。
- **session_id 过滤**：构造到第二个 session（close 再 new），每个 session 写 2 条不同内容，search 全量 vs search `sessionId=1` 过滤。
- **BM25 排序**：写入 3 条不同相关度的内容，search 后检查命中顺序合理（匹配次数多的在前）。

### 确认检查点
- `mvn test -Dtest=SessionStoreTest` 全绿

---

## Step 3：MemoryManager 集成 SessionStore

### 目标
在 `MemoryManager` 中创建 `SessionStore`，在三个 `addXxx()` 方法中挂载 `sessionStore.append(entry)`，并新增 `searchSession()` 代理方法。同时给 `ToolRegistry` 添加搜索注入点。

### 要做什么

1. **修改 `MemoryManager.java`**
   - 新增字段：`private SessionStore sessionStore;`。
   - 构造器链的终点（`private MemoryManager(LlmClient, ContextProfile, LongTermMemory)`）中初始化：
     ```java
     this.sessionStore = new SessionStore();
     ```
   - 三个 add 方法中各加一行 `sessionStore.append(entry);`：
     - `addUserMessage`：在 `shortTermMemory.store(entry)` 之后加。
     - `addAssistantMessage`：同。
     - `addToolResult`：同。
   - 新增方法：
     ```java
     public List<SessionEntry> searchSession(String query, int limit, Integer sessionId) {
         return sessionStore.search(query, limit, sessionId);
     }
     ```
   - 新增 `public void close()` 方法，调用 `sessionStore.close()`。注意要幂等。
   - 新增 `public int getCurrentSessionId()` 方法，委托给 `sessionStore.getCurrentSessionId()`。

2. **修改 `ToolRegistry.java`**
   - 新增字段：`private BiFunction<String, Integer, List<SessionEntry>> sessionSearcher;`（简化为 `(query, limit)` 的搜索，暂不暴露 `sessionId` 参数到 setter，tools lambda 里再包一层）。
   - 更好的方式：用自定义函数式接口 `TriFunction<String, Integer, Integer, List<SessionEntry>>`（query, limit, sessionId）。看看项目中是否有现存的三参函数接口……如果没有，直接用 `Function<SessionSearchRequest, List<SessionEntry>>` 或直接用接口类型。\
     **实际方案**：加一个简单的 `setSessionSearcher(SessionSearcher searcher)`，其中 `SessionSearcher` 是一个 `@FunctionalInterface`：
     ```java
     @FunctionalInterface
     public interface SessionSearcher {
         List<SessionEntry> search(String query, int limit, Integer sessionId);
     }
     ```
     这个接口就定义在内存模块或 `ToolRegistry.java` 同级。

3. **修改 `Agent.java`**（注入点）
   - 在第 70 行附近加：
     ```java
     this.toolRegistry.setSessionSearcher(memoryManager::searchSession);
     ```

4. **修改 `AgentOrchestrator.java`**（注入点）
   - 在 `setScopedMemorySaver` 调用的旁边加：
     ```java
     this.toolRegistry.setSessionSearcher(memoryManager::searchSession);
     ```

5. **修改 `PlanExecuteAgent.java`**（注入点）
   - 找到 `setScopedMemorySaver` 的位置，加同样一行。

### 测试

- **不需要新测试文件**：因为 `MemoryManager` 集成只是把已有的 `SessionStore` 方法透传一层 + 在 add 时入队。Step 1/2 的单元测试已覆盖核心逻辑。
- **验证手段**：确保 `mvn test -Pquick` 回归全绿（现有测试不因 MemoryManager 新增字段而受影响）。

### 确认检查点
- `mvn test -Pquick` 全绿
- 手工验证：启动 CLI，随便说一句话 → exit → 用 `sqlite3 ~/.paicli/sessions/sessions.db "SELECT count(*) FROM session_entry;"` 看到数据已被写入。

---

## Step 4：注册 search_session 工具

### 目标
在 `ToolRegistry.registerMemoryTools()` 中注册 `search_session` 工具，Agent 即可在对话中自动调用。同时提供 CLI `/session search` 命令给用户直接使用。

### 要做什么

1. **修改 `ToolRegistry.registerMemoryTools()`**
   - 在 `save_memory` 之后注册 `search_session` 工具。
   - 工具签名：
     ```
     search_session(query, limit?, session_id?)
     ```
   - 内部 body：参数校验 → 调用 `sessionSearcher.search(query, limit, sessionId)` → 格式化结果为纯文本返回给 LLM。
   - 如果 `sessionSearcher == null`（未注入），返回 `"search_session 失败: 会话存储未初始化"`。

2. **修改 `Main.java`**（CLI 暴露 `/session` 命令，可选）
   - 在 `CliCommandParser` 中增加对 `/session search <query>` 的支持。
   - 或者保持简单：先不加 CLI 命令入口，只让 LLM 通过 tool 调用（因为 LLM 本身就是用户和功能的自然语言界面）。

   **推荐先不加 CLI 命令**：`search_session` 是 Agent 工具，LLM 在需要时会自动调用。Step 4 只聚焦于工具注册。

3. **格式化输出约定**
   - 每条结果格式：
     ```
     🔍 历史会话检索结果 (N 条):

     [Session #1 / 2026-07-02 14:30:00] user: 用户之前说过的前300字...
     [Session #1 / 2026-07-02 14:30:05] assistant: 助手回复的前300字...

     ---
     提示: 可用 session_id=1 过滤到某个会话
     ```
   - 如果用户 query 触发 FTS5 语法错误（如 `"` 不成对），捕获 `SQLiteException` 返回友好的错误提示。

### 测试

- **不需要新测试文件**：Step 2 的 `SessionStoreTest` 已覆盖 search 逻辑。
- **验证手段**：`mvn test -Pquick` 全绿。工具定义在 `ToolRegistry.getToolDefinitions()` 中出现 `search_session`。

### 确认检查点
- `mvn test -Pquick` 全绿
- 手工：启动 CLI → 说 "hello" → 说 "写一个 hello world" → 退出 → 重新启动 → 问 LLM "帮我用search_session查一下我刚才说的东西" → Agent 调用 `search_session` 成功找回之前的内容。

---

## 文件变更总览

| Step | 文件 | 类型 | 说明 |
|------|------|------|------|
| 1 | `src/main/java/com/paicli/memory/SessionStore.java` | 新建 | 建库/建表/异步写 |
| 1 | `src/test/java/com/paicli/memory/SessionStoreTest.java` | 新建 | 建表/append/flush/close 测试 |
| 2 | `src/main/java/com/paicli/memory/SessionEntry.java` | 新建 | 只读记录 |
| 2 | `src/main/java/com/paicli/memory/SessionStore.java` | 改 | 新增 search() 方法 |
| 2 | `src/test/java/.../SessionStoreTest.java` | 改 | 新增搜索测试 |
| 3 | `src/main/java/com/paicli/memory/MemoryManager.java` | 改 | 注入 SessionStore，3 个 add 方法加 append，新增 searchSession + close |
| 3 | `src/main/java/com/paicli/tool/ToolRegistry.java` | 改 | 新增 SessionSearcher 接口 + setSessionSearcher + 字段 |
| 3 | `src/main/java/com/paicli/agent/Agent.java` | 改 | 新增注入行 |
| 3 | `src/main/java/com/paicli/agent/AgentOrchestrator.java` | 改 | 新增注入行 |
| 3 | `src/main/java/com/paicli/agent/PlanExecuteAgent.java` | 改 | 新增注入行 |
| 4 | `src/main/java/com/paicli/tool/ToolRegistry.java` | 改 | registerMemoryTools() 中注册 search_session |
