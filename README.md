# Comate — Terminal-First Agent IDE

> 类 Claude Code 的智能编程 Agent CLI，基于 Java 17 + Maven 构建。

Comate 是一个运行在终端中的 AI 编程助手，支持多种国内大模型（GLM、DeepSeek、Kimi、Step），提供 ReAct、Plan-and-Execute、Multi-Agent 三种 Agent 模式，内置 MCP 协议、文档检索（RAG）、记忆系统、人工审批（HITL）等能力。

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+

### 配置

复制 `.env.example` 为 `.env`，填入至少一个 LLM 的 API Key：

```bash
cp .env.example .env
# 编辑 .env，配置 LLM_API_KEY 等
```


## 项目结构

```
.
├── pom.xml                          # Maven 构建（fat JAR）
├── .env / .env.example              # 环境变量配置
├── src/
│   ├── main/java/com/paicli/        # 主源码（24 个功能模块）
│   ├── main/resources/              # 提示词模板、技能文件
│   └── test/java/com/paicli/        # 单元测试
├── docs/
│   ├── upgrade/                     # 三期升级计划
│   ├── RAG-docs/                    # RAG / System Prompt 设计文档
│   └── test/                        # 项目总结文档
└── .claude/                         # Claude 配置
```

### 功能模块

| 模块 | 包名 | 职责 |
|------|------|------|
| **CLI 入口** | `cli` | 主循环、斜杠命令(/model,/plan,/hitl等40+)、JLine 行编辑、三种 Agent 模式切换 |
| **Agent 编排** | `agent` | ReAct 推理循环、Plan-and-Execute、Multi-Agent（规划者+执行者+检查者） |
| **LLM 客户端** | `llm` | 多模型抽象接口，支持 GLM / DeepSeek / Kimi / Step，流式输出、多模态、Prompt Caching |
| **工具系统** | `tool` | 内置工具注册与调度（读写文件、搜索、命令执行等），MCP 工具自动注册 |
| **MCP 协议** | `mcp` | Model Context Protocol 实现（JSON-RPC 2.0、stdio/HTTP 传输、Resource 引用） |
| **记忆系统** | `memory` | 双层记忆：短期（对话上下文）+ 长期（SQLite 持久化），上下文压缩 |
| **计划引擎** | `plan` | 复杂任务分解为多步执行计划 |
| **人工审批** | `hitl` | Human-in-the-Loop 审批，支持终端/渲染器两种交互方式 |
| **网络搜索** | `web` | 多搜索引擎（SerpApi/SearXNG/智谱）、网页抓取与正文提取 |
| **提示词管理** | `prompt` | 多层 system prompt 组装（身份→工具→审批→记忆→技能→上下文） |
| **Git 快照** | `snapshot` | 基于 JGit 的 Side-Git 快照，支持 turn 级撤销 |
| **技能系统** | `skill` | 三层扫描（builtin → user → project），动态启用/禁用 |
| **配置管理** | `config` | JSON 配置文件加载/保存，环境变量覆盖 |
| **上下文分析** | `context` | Token 预算计算、压缩阈值控制 |
| **微信互通** | `wechat` | 微信端远程连接agent|

### 模块依赖关系

```
用户输入 → cli(Main) → prompt(提示词组装)
                          ↓
                       agent(Agent编排) → llm(多模型)
                       /    |    \
                      /     |     plan(计划引擎)
                     /      |
                   tool(工具注册与调度)
                 /    |     \
                web  mcp    rag
                 \    |     /
               policy(安全策略) ←→ hitl(人工审批)
                       |
               snapshot(快照)  memory(记忆)

  支撑: config, context, image, util
  扩展: skill, tui, lsp, wechat, runtime(API/任务)
```


## docs / upgrade — 升级计划


### 第一期：Session Store — SQLite 对话持久化

**文档**: `session-store-sqlite-persistence.md`

当前 `ConversationMemory` 使用内存 `LinkedHashMap` 维护对话上下文，进程退出后会话数据丢失。长期记忆存的也是"去重精炼后的事实"，而非完整的原始对话流水。

**升级内容**：
- 每次对话条目写入内存时，**异步旁路写入 SQLite**
- 每个 CLI 启动周期为 1 个 **Session**，自动递增编号
- 基于 SQLite **FTS5** 实现全文关键词检索
- 提供 `search_session` Agent 工具，可在对话中检索历史会话
- 存储位置：`~/.paicli/sessions/sessions.db`
- 零侵入：不改变现有 `ConversationMemory` / `MemoryManager` 接口

**技术选型**：SQLite（已有依赖）+ FTS5 + 异步 flush（500ms 批量写）

### 第二期：文档 RAG（Document RAG）

**状态**: 已实现，代码分布在 `src/main/java/com/paicli/rag/` 下

项目新增了**文档 RAG** 能力——支持用户上传 `.txt` / `.md` 文档文件到知识库，构建向量索引后通过语义检索或关键词检索查询相关内容。

**核心组件**：

| 组件 | 文件 | 职责 |
|------|------|------|
| **DocIndex** | `DocIndex.java` | 文档索引管理器：上传文档到 `~/.paicli/docs/` → 分块 → Embedding → 持久化 |
| **DocChunker** | `DocChunker.java` | 三段递归分块（段落 → 句子 → Jieba 词边界），512 字符/块，100 字符重叠 |
| **DocChunk** | `DocChunk.java` | 文档块数据模型 |
| **DocRetriever** | `DocRetriever.java` | 混合检索：语义检索（向量余弦相似度）+ 关键词检索（LIKE）+ 合并排序 |
| **DocResultFormatter** | `DocResultFormatter.java` | 检索结果格式化（CLI / LLM Tool 两种输出） |

**Agent 工具**：

| 工具 | 用途 |
|------|------|
| `upload_document` | 上传文档到知识库并建立索引（支持 .txt / .md） |
| `search_docs` | 在文档知识库中搜索相关片段，支持自然语言查询 |

**CLI 命令**：`/doc <路径>` — 批量索引目录下的所有文档文件。

**技术流程**：上传 → 复制到 `~/.paicli/docs/` → 三段递归分块 → Embedding API 向量化 → 存入 `doc_chunks` 表（SQLite）→ 检索时混合搜索（向量 + 关键词）→ 按相似度排序返回。

### 第三期：兼容 OpenAI agent.md 快照方案

**文档**: `agent-md-snapshot-design.md`

参考 OpenAI 的 `agent.md` 机制（项目根目录的 Markdown 文件，Agent 理解项目的稳定说明文档），设计 PaiCLI 的兼容方案。

**核心设计**：
- **快照机制**：会话启动时读取 `agent.md`，整个会话期间内容固定不变
- **独立 system message**：以 `conversationHistory[1]` 的独立 system 消息存在，不参与每轮 system prompt 重建
- **区分定位**：`agent.md` 描述"项目自身如何被理解"（技术栈、代码规范、架构说明），现有 system prompt 描述"Agent 如何工作"
- **支持更新**：用户手动编辑后下个会话生效；Agent 通过 `write_file` 写入后当前会话立即替换快照

**新增文件**：`AgentMdSnapshot.java`
**修改文件**：`Agent.java`（构造器注入 + clearHistory 保留）、`ToolRegistry.java`（写文件后回调通知）

**两种更新模式**：
- 被动：用户编辑 → 下个会话生效
- 主动：Agent 检测项目结构变化（新增模块、改构建文件等）→ 更新 `agent.md` 

---
