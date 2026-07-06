# 兼容 OpenAI agent.md 快照方案设计

## 1. 背景

OpenAI 的 `agent.md` 是一个放在项目根目录的 Markdown 文件，作为 Agent 理解项目的稳定说明文档。与 PaiCLI 现有的体系对比：

| 特性 | PaiCLI 现有（PromptRepository + PromptAssembler） | OpenAI agent.md |
|------|--------------------------------------------------|----------------|
| 更新频率 | 每轮 ReAct 全部重建 | 会话启动时加载一次，作为快照固定 |
| 用户可编辑 | 通过 `.paicli/prompts/` 覆盖部分片段 | 直接编辑项目根的单文件 |
| 存储位置 | classpath resource + 目录覆盖 | 项目根 `agent.md` |
| 内容范围 | 工具定义、安全策略、行为约束等系统级指令 | 项目描述、代码规范、架构说明等用户级指令 |

**核心诉求：**
1. **快照机制**：每次会话启动时读取 `agent.md`，整个会话期间 content 固定不变
2. **自动更新**：项目结构发生实质性变动时（新增模块、改构建文件等），Agent 有能力更新 `agent.md`

---

## 2. 架构变更分析

### 2.1 当前体系的问题

当前的 system prompt 构建链路：

```
Agent.buildSystemPrompt()
  → PromptAssembler.assemble(mode, context)
    → [base.md] + [personalities/calm.md]
      + [modes/agent.md]
      + [approvals/suggest.md]
      + [## Project Context]    ← 每轮重建（memory + MCP resources）
      + [## Skills]             ← 每轮重建
      + [context-management.md] + [handoff.md]
```

每轮都会完整重新拼接，作为 `conversationHistory[0]` 发送给 LLM。这本身不是问题——但对 `agent.md` 来说，用户编辑后希望生效，同时又希望当前会话的 LLM 始终看到同一份 `agent.md`。

**根本矛盾：** 需要"会话内稳定"的同时，又需要"允许被更新"。

### 2.2 关键设计权衡

#### 2.2.1 快照的载体

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A: 嵌入 system prompt** | 将 agent.md 作为 system prompt 的一个固定段 | 全时段可见，权重高 | system prompt 有 token 上限；稳定段混入每轮重建的 system prompt 会加剧 token 浪费 |
| **B: 嵌入 conversationHistory** | 加载为一条独立的 system message 放在 history[0] 之后 | 不参与 system prompt 重建，保存/恢复 history 时自然携带 | 位置靠后可能被历史压缩清除 |
| **C: 独立 system role message** | 在 system prompt 之后、history 之前插入一条 role=system 的独立消息 | 可独立管理，不被压缩 | 部分 API 实现只认第一条 system message 或合并多条 |

**推荐 B：嵌入 conversationHistory 作为独立 system message。**

理由：
- 不与每轮重建的 system prompt 耦合，实现侵入最小
- 后续 Agent 快照恢复（save/restore conversationHistory）自然包含 agent.md 内容
- 多 system message 是 OpenAI/Anthropic 标准行为，不合并
- token 开销透明，在 `getContextStatus()` 中自动统计

#### 2.2.2 agent.md 的定位——"项目自述"而非"系统指令"

当前 `base.md` + `modes/agent.md` 已经承载了"Agent 如何工作"的系统指令。`agent.md` 应当定位为 **"项目自身如何被理解"** 的描述，例如：

```markdown
# agent.md

## 项目概述
PaiCLI 是一个 Java Agent CLI，对标 Claude Code。

## 技术栈
- Java 17+ / Maven
- JLine 4 终端交互
- 支持 GLM / DeepSeek / Kimi / Step 等模型

## 代码规范
- 控制层命名 `*Controller.java`，服务层 `*Service.java`
- 接口定义在 `*Api.java`，实现类叫 `*Impl.java`
- 使用 SLF4J 日志，不要 System.out 打日志

## 关键约定
- 配置走 `System.getProperty()` / 环境变量，默认值在构造器
- 测试用 `@Tag("quick")` 分组
```

这样 `agent.md` 和现有 system prompt 的职责清晰分离。

#### 2.2.3 快照的加载时机

```
Agent 构造 (new Agent)
  → 读取 projectRoot/agent.md（如果存在）
  → 反序列化/解析（可选 frontmatter）
  → 以 system message 形式加入 conversationHistory
  → 会话期间固定不变

会话过程中：
  如果用户手动编辑了 agent.md → 当前会话不受影响，下个会话生效
  如果 Agent 通过工具更新了 agent.md → 先写磁盘，再替换 conversationHistory 中对应消息
```

#### 2.2.4 谁应该更新 agent.md

两种模式：

| 模式 | 触发 | 行为 |
|------|------|------|
| **被动（用户编辑）** | 用户手动修改 `agent.md` | 下个会话生效，当前会话不变 |
| **主动（Agent 更新）** | 项目结构变化（新增 package、改构建文件等） | Agent 用 `write_file` 更新 `agent.md`，同时更新当前会话的快照 |

用户编辑是主模式，Agent 主动更新是辅助模式。Agent 主动更新应当基于用户的明确请求或 task 上下文推断，而非强制的自动行为。

---

## 3. 详细设计

### 3.1 新增文件结构

```
src/main/java/com/paicli/
  └── agent/
      ├── Agent.java                  ← 修改
      └── AgentMdSnapshot.java       ← 新增
```

`AgentMdSnapshot` 职责：
- 加载 `projectRoot/agent.md`（如果存在）
- 提供快照内容
- 支持替换（工具更新后替换快照）

### 3.2 AgentMdSnapshot 类设计

```java
package com.paicli.agent;

/** agent.md 快照：会话启动时加载一次，会话期间固定 */
public class AgentMdSnapshot {

    /** agent.md 插入到 conversationHistory 的索引位置 */
    public static final int CONVERSATION_INDEX_AFTER_SYSTEM = 1;

    private final Path projectRoot;
    private final Path agentMdPath;
    private String content;           // 可能为 null（文件不存在时）
    private boolean loaded;

    public AgentMdSnapshot(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.agentMdPath = projectRoot.resolve("agent.md");
    }

    /** 加载 agent.md（会话启动时调用一次） */
    public String load() { ... }

    /** 替换快照内容（Agent 工具更新后调用） */
    public String replace(String newContent) { ... }

    /** 当前快照内容 */
    public Optional<String> getContent() { ... }

    /** 快照是否已加载 */
    public boolean isLoaded() { ... }
}
```

### 3.3 Agent.java 修改点

**构造器增强：**

```java
private AgentMdSnapshot agentMdSnapshot;

// 在 Agent 构造器末尾：
agentMdSnapshot = new AgentMdSnapshot(Path.of(toolRegistry.getProjectPath()));
String mdContent = agentMdSnapshot.load();
if (mdContent != null) {
    // 插入为独立 system message
    conversationHistory.add(CONVERSATION_INDEX_AFTER_SYSTEM,
        LlmClient.Message.system(mdContent));
}
```

**更新入口：**

```java
/**
 * 工具完成写文件后调用，检查是否写的是 agent.md
 * 如果是，更新快照内容
 */
public void maybeRefreshAgentMdSnapshot() {
    agentMdSnapshot.refreshIfChanged(projectRoot.resolve("agent.md"));
}
```

**clearHistory() 调整：** 当前 clearHistory 只保留 `conversationHistory[0]`（system prompt），agent.md 的 system message 在 `[1]` 也应保留。

```java
public void clearHistory() {
    LlmClient.Message systemMsg = conversationHistory.get(0);
    LlmClient.Message agentMdMsg = agentMdSnapshot.isLoaded()
        ? conversationHistory.get(CONVERSATION_INDEX_AFTER_SYSTEM) : null;
    conversationHistory.clear();
    conversationHistory.add(systemMsg);
    if (agentMdMsg != null) {
        conversationHistory.add(agentMdMsg);
    }
    memoryManager.clearShortTerm();
}
```

### 3.4 ToolRegistry 的联动

`ToolRegistry.java` 需要在写完文件后通知 Agent 更新快照。

**方案：回调注入**

```java
// ToolRegistry 中：
private Consumer<Path> postWriteHook;

public void setPostWriteHook(Consumer<Path> hook) {
    this.postWriteHook = hook;
}

// executeTools() 中在 write_file 成功后：
if (postWriteHook != null && writtenPath.endsWith("agent.md")) {
    postWriteHook.accept(writtenPath);
}
```

### 3.5 与 PromptAssembler 的关系

`agent.md` **不经过 PromptAssembler**。它是独立的 system message，位于 `conversationHistory[1]`，和 `conversationHistory[0]` 的 system prompt 共存。

这样：
- PromptAssembler 完全不变
- `agent.md` 不参与每轮重建
- 两种内容职责清晰分离

### 3.6 token 开销透明

在 `Agent.getContextStatus()` 中，`system` role 的 token 统计会自动涵盖 agent.md 的 system message。无需额外逻辑。

---

## 4. 项目结构变更检测

### 4.1 变更检测的切入点

| 工具调用 | 结构可能变化 | 检测方式 |
|----------|-------------|---------|
| `write_file` 写入 `pom.xml` / `package.json` | 依赖变化 | 文件名匹配 + 内容摘要对比 |
| `write_file` 写入新 `.java` 文件 | 新增 package | 路径解析 new package |
| `execute_command` 执行 `mvn` / `npm` | 可能生成新目录 | 命令前缀匹配 |
| `create_project` | 整个项目新结构 | 创建后自动更新 |

### 4.2 实现策略

**简约原则**：不做自动的结构变化检测。而是：

1. **用户可要求 Agent 更新**：用户说"更新一下 agent.md"时，Agent 使用 `glob_files`/`grep_code` 扫描项目结构，用 `write_file` 写入新内容
2. **任务上下文推断**：Agent 在执行大型任务（如"添加新模块"）的流程中，自行判断是否需要更新 agent.md
3. **工具结果提示**：当 `write_file` 写入构建文件或创建新的 Java package 时，ToolRegistry 可以在结果中附加一条提示（不阻断执行），让 LLM 自行决定是否更新

```java
// 在 ToolRegistry 中为 write_file 添加轻量提示
// 不自动更新，只在结果尾部附加上下文：
if (path.endsWith("pom.xml") || path.endsWith("build.gradle")) {
    appendHint(result, "检测到构建文件变更，如有项目结构描述需要同步，可更新 agent.md。");
}
```

---

## 5. 实现计划

### Phase 1：核心快照机制（本实现）

1. 新建 `AgentMdSnapshot.java`
   - `load()`: 读取 `projectRoot/agent.md`
   - `getContent()`: 返回会话内快照
   - `replace(newContent)`: 替换快照

2. 修改 `Agent.java`
   - 构造器：加载 `agent.md` 并注入 conversationHistory
   - `clearHistory()`: 保留 agent.md 消息
   - 提供 `updateAgentMdSnapshot()` 入口

3. 修改 `ToolRegistry.java`
   - 写入 `agent.md` 后触发快照替换回调
   - 在 `write_file` 结果中附加轻量变更提示

### Phase 2：Agent 自动更新能力（后续可选）

- 扫描项目结构（目录树 + build 文件）的 tool
- agent.md 内容模板的约定格式
- 用户命令 `/sync-agent-md` 或类似入口

---

## 6. 未选方案及理由

### 方案 X：agent.md 经过 PromptRepository

理由拒绝：PromptRepository 用于系统级模板（classpath resource + 目录覆盖），其覆盖机制是为用户自定义系统提示设计的。`agent.md` 是项目级描述，放在项目根目录，语义上不属于"prompt 模板覆盖"。两者用途不同，不应耦合。

### 方案 Y：agent.md 作为 PromptContext 的字段注入

理由拒绝：这样 agent.md 会随 system prompt 每轮重建，浪费 token。它应该是稳定的 session-level 数据，而不是 per-turn 的上下文变量。

### 方案 Z：agent.md 内容作为 system prompt 的"base"扩展

理由拒绝：`base.md` 承载的是 Agent 的工具定义和安全策略，属于"无论如何都要有的系统指令"。`agent.md` 是用户描述项目的副文本，两者在系统提示中分处不同必要层级，合并会降低维护性。

---

## 7. 附录：OpenAI agent.md 参考

OpenAI 官方对 `agent.md` 的定位：
- 位于项目根目录的单文件 Markdown
- 描述项目目标、技术栈、代码约定、本地开发环境等
- Agent 在第一次对话时加载，保持在整个对话生命周期内
- 用户可随时编辑，下个会话生效
- Agent 可以在适当的时候更新它（例如添加新模块的说明）

参考：https://openai.com/index/introducing-agent-md/
