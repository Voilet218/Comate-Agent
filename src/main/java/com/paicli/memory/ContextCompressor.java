package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 上下文压缩器 - 当对话过长时，自动压缩旧消息
 *
 * 压缩策略：
 * 1. Map-Reduce：先将旧消息分片摘要（Map），再合并摘要（Reduce）
 * 2. 保留最近 N 轮完整消息（不压缩）
 * 3. 压缩后的摘要回注到 ConversationMemory
 */
public class ContextCompressor {
    private LlmClient llmClient;
    private final int retainRecentRounds;

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

            各片段摘要：
            %s

            请用中文输出合并摘要，控制在300字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            请从以下对话中提取“跨会话仍然成立、未来复用仍有价值”的稳定事实，格式为每行一条：
            - 用户偏好和习惯
            - 项目信息（名称、路径、技术栈）
            - 重要决策和约定

            只保留用户明确说明、或工具/代码库可验证的信息。
            绝对不要提取以下内容：
            - 当前这一轮让你执行的临时任务、步骤、todo
            - 一次性的文件名、目录名、输出要求
            - 模型自己的猜测、纠错、提醒、推断
            - “用户想要/需要/让我/请你...” 这类请求句

            对话内容：
            %s

            请每行一条事实，不要多余解释。
            """;
    private static final String COMPRESS_TOOLRESULT_PROMPT ="""
            请将以下工具调用记录压缩成一条紧凑摘要，保留关键信息：
            - 调用的工具名称和目的
            - 关键参数（文件名、搜索模式、URL 等）
            - 执行结果概要（找到/写入/返回了什么）

            压缩要求：
            - 每条工具调用压缩为一行：[工具名] 动作描述 → 结果
            - 丢弃完整文件内容、错误堆栈、中间临时变量
            - 对后续决策有影响的调用在行末标注 (⚠️)

            工具调用记录：
            %s

            请用中文输出，单条不超过 80 字。
            """;

    private static final List<String> EPHEMERAL_FACT_PREFIXES = List.of(
            "用户想", "用户要", "用户需要", "用户请求", "帮我", "让我",
            "新建", "创建", "删除", "修改", "生成", "补充要求", "当前这一轮", "本次任务"
    );

    private static final List<String> SPECULATION_CUES = List.of(
            "可能", "应该", "猜测", "推测", "笔误", "提醒"
    );

    private static final List<String> DURABLE_FACT_HINTS = List.of(
            "用户偏好", "用户习惯", "喜欢", "倾向", "项目", "仓库", "路径", "技术栈",
            "版本", "模型", "接口", "配置", "环境变量", "命令", "约定", "规则", "默认"
    );

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public ContextCompressor(LlmClient llmClient) {
        this(llmClient, 3);
    }

    /**
     * @param llmClient          LLM 客户端
     * @param retainRecentRounds 保留最近 N 轮完整消息不压缩
     */
    public ContextCompressor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    /**
     * 三段式压缩对话记忆
     *
     * <ol>
     *   <li><b>Stage 1</b> — 以 user message 为边界，对工具输出做 LLM 摘要</li>
     *   <li><b>Stage 2</b> — 对整段旧记忆做 map-reduce 摘要（原本的逻辑）</li>
     *   <li><b>Stage 3</b> — 直接抛弃除了最近 {@code retainRecentRounds} 轮的所有旧记忆</li>
     * </ol>
     *
     * 每阶段压缩后检查 token 总数，若 &lt; 短期记忆预算的 50% 则提前结束。
     *
     * @param memory 短期记忆
     * @return 压缩描述或摘要，如果不需要压缩则返回 null
     */
    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() <= retainRecentRounds) {
            return null; // 条目太少，不需要压缩
        }

        // 分割：旧消息 vs 近期消息（必须拷贝，因为后面会 clear 底层集合）
        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        int targetTokens = (int) (memory.getMaxTokens() * 0.50);

        // ================================================================
        // Stage 1 — 以 user message 为边界，对工具输出做 LLM 摘要
        // ================================================================
        List<MemoryEntry> stage1Old = compressToolResultsByUserBoundary(oldEntries);
        if (stage1Old != oldEntries) { // 有工具调用被压缩
            rebuildMemory(memory, stage1Old, recentEntries);
            if (memory.getTokenCount() < targetTokens) {
                int groups = countToolGroupsInDiff(oldEntries, stage1Old);
                return String.format("[Stage 1] 已将 %d 组工具调用压缩为摘要（%d → %d tokens）",
                        groups, totalTokens(oldEntries), memory.getTokenCount());
            }
            // Stage 1 不够，继续 Stage 2（基于已压缩的 stage1Old）
            oldEntries = stage1Old;
        }

        // ================================================================
        // Stage 2 — 对整段旧记忆做 map-reduce 摘要（原本的逻辑）
        // ================================================================
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (!chunkSummaries.isEmpty()) {
            String finalSummary = chunkSummaries.size() == 1
                    ? chunkSummaries.get(0)
                    : reducePhase(chunkSummaries);

            MemoryEntry summaryEntry = new MemoryEntry(
                    "summary-" + UUID.randomUUID().toString().substring(0, 8),
                    "[历史对话摘要] " + finalSummary,
                    MemoryEntry.MemoryType.SUMMARY,
                    null,
                    MemoryEntry.estimateTokens(finalSummary)
            );

            rebuildMemory(memory, List.of(summaryEntry), recentEntries);
            if (memory.getTokenCount() < targetTokens) {
                return finalSummary;
            }
        }

        // ================================================================
        // Stage 3 — 直接抛弃除了最近 1 轮的所有旧记忆
        // ================================================================
        List<MemoryEntry> lastRound = trimToLastRound(recentEntries);
        memory.clear();
        for (MemoryEntry entry : lastRound) {
            memory.store(entry);
        }
        return String.format("[Stage 3] 已丢弃旧记忆，仅保留最近 1 轮对话（%d → %d tokens）",
                totalTokens(recentEntries) + totalTokens(oldEntries), memory.getTokenCount());
    }

    /**
     * 从对话中提取关键事实，存入长期记忆
     */
    public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
        if (entries.isEmpty()) return List.of();

        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry entry : entries) {
            conversation.append(resolveSource(entry).toUpperCase(Locale.ROOT))
                    .append("(").append(entry.getType()).append("): ")
                    .append(entry.getContent()).append("\n\n");
        }

        try {
            String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个信息提取助手，只输出关键事实，不输出其他内容。"),
                    LlmClient.Message.user(prompt)
            );

            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            String factsText = response.content();

            List<String> facts = new ArrayList<>();
            for (String line : factsText.split("\n")) {
                String fact = normalizeFactLine(line);
                if (isPersistentFactCandidate(fact)) {
                    facts.add(fact);

                    // 存入长期记忆
                    MemoryEntry factEntry = new MemoryEntry(
                            "fact-" + UUID.randomUUID().toString().substring(0, 8),
                            fact,
                            MemoryEntry.MemoryType.FACT,
                            java.util.Map.of("source", "fact_extractor"),
                            MemoryEntry.estimateTokens(fact)
                    );
                    longTermMemory.store(factEntry);
                }
            }
            return facts;
        } catch (IOException e) {
            System.err.println("⚠️ 事实提取失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Map 阶段：将旧消息分片，每片独立摘要
     */
    private List<String> mapPhase(List<MemoryEntry> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5; // 每片 5 条消息
        List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);

        for (List<MemoryEntry> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            for (MemoryEntry entry : chunk) {
                chunkText.append(entry.getType()).append(": ")
                        .append(entry.getContent()).append("\n\n");
            }

            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<LlmClient.Message> messages = List.of(
                        LlmClient.Message.system("你是一个对话摘要助手。"),
                        LlmClient.Message.user(prompt)
                );

                LlmClient.ChatResponse response = llmClient.chat(messages, null);
                summaries.add(response.content());
            } catch (IOException e) {
                System.err.println("⚠️ 摘要生成失败: " + e.getMessage());
                // 降级：直接截取前 200 字
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }

        return summaries;
    }

    /**
     * Reduce 阶段：合并多个摘要
     */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);

        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个摘要合并助手。"),
                    LlmClient.Message.user(prompt)
            );

            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            System.err.println("⚠️ 摘要合并失败: " + e.getMessage());
            // 降级：直接拼接
            return String.join("；", summaries);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    // ========================================================================
    //  Stage 1 辅助方法 — 以 user message 为边界压缩工具输出
    // ========================================================================

    /**
     * 以 user message 为边界将条目分组，对每组内的 TOOL_RESULT 做 LLM 摘要。
     *
     * @return 压缩后的新列表；若没有工具调用则返回原引用
     */
    private List<MemoryEntry> compressToolResultsByUserBoundary(List<MemoryEntry> entries) {
        List<List<MemoryEntry>> groups = partitionByUserBoundary(entries);

        List<MemoryEntry> result = new ArrayList<>();
        boolean changed = false;

        for (List<MemoryEntry> group : groups) {
            List<MemoryEntry> toolResults = new ArrayList<>();
            List<MemoryEntry> others = new ArrayList<>();

            for (MemoryEntry entry : group) {
                if (entry.getType() == MemoryEntry.MemoryType.TOOL_RESULT) {
                    toolResults.add(entry);
                } else {
                    others.add(entry);
                }
            }

            if (toolResults.isEmpty()) {
                result.addAll(group);
            } else {
                String compressed = callToolResultCompressor(toolResults);
                MemoryEntry compressedEntry = new MemoryEntry(
                        "summary-tool-" + UUID.randomUUID().toString().substring(0, 8),
                        "[工具调用摘要] " + compressed,
                        MemoryEntry.MemoryType.SUMMARY,
                        java.util.Map.of("source", "compressor", "stage", "1"),
                        MemoryEntry.estimateTokens(compressed)
                );
                result.addAll(others);
                result.add(compressedEntry);
                changed = true;
            }
        }

        return changed ? result : entries;
    }

    /**
     * 以 user message 为分隔符，将条目列表按"轮次"分组。
     * 每个新组从一个 user 消息开始（或从头直到第一个 user 消息）。
     */
    private List<List<MemoryEntry>> partitionByUserBoundary(List<MemoryEntry> entries) {
        List<List<MemoryEntry>> groups = new ArrayList<>();
        List<MemoryEntry> current = new ArrayList<>();

        for (MemoryEntry entry : entries) {
            if (isUserMessage(entry) && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        // 如果没有任何 user 消息，整段作为一组
        if (groups.isEmpty() && !entries.isEmpty()) {
            groups.add(new ArrayList<>(entries));
        }

        return groups;
    }

    /** 判断一个条目是否为用户消息 */
    private boolean isUserMessage(MemoryEntry entry) {
        String source = entry.getMetadata().get("source");
        if (source != null && !source.isBlank()) {
            return "user".equals(source);
        }
        return entry.getId().startsWith("user-");
    }

    /** 调用 LLM 对一组 TOOL_RESULT 做摘要 */
    private String callToolResultCompressor(List<MemoryEntry> toolResults) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : toolResults) {
            sb.append(entry.getContent()).append("\n\n");
        }

        try {
            String prompt = String.format(COMPRESS_TOOLRESULT_PROMPT, sb);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个工具调用记录压缩助手。"),
                    LlmClient.Message.user(prompt)
            );
            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            System.err.println("⚠️ 工具调用摘要失败: " + e.getMessage());
            // 降级：截断
            String fallback = sb.toString();
            return fallback.substring(0, Math.min(300, fallback.length()));
        }
    }

    // ========================================================================
    //  通用辅助方法
    // ========================================================================

    /** 用 old 和 recent 两部分重建记忆（先 clear 再 store） */
    private void rebuildMemory(ConversationMemory memory,
                               List<MemoryEntry> oldPart,
                               List<MemoryEntry> recentPart) {
        memory.clear();
        for (MemoryEntry entry : oldPart) {
            memory.store(entry);
        }
        for (MemoryEntry entry : recentPart) {
            memory.store(entry);
        }
    }

    /** 计算一组条目的总 token 数 */
    private static int totalTokens(List<MemoryEntry> entries) {
        return entries.stream().mapToInt(MemoryEntry::getTokenCount).sum();
    }

    /** 粗略统计 stage1Old 相比 oldEntries 减少了多少组工具条目 */
    private static int countToolGroupsInDiff(List<MemoryEntry> oldEntries,
                                              List<MemoryEntry> stage1Old) {
        return oldEntries.size() - stage1Old.size();
    }

    /**
     * 从尾部向前扫描，只保留最近 1 轮对话（以 user message 为边界）。
     * 即从最后一个 user 消息（含）到列表末尾的全部条目。
     * 如果找不到 user 消息则保留全部。
     */
    private static List<MemoryEntry> trimToLastRound(List<MemoryEntry> entries) {
        int lastUserIdx = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            String source = entries.get(i).getMetadata().get("source");
            if (source != null && "user".equals(source)) {
                lastUserIdx = i;
                break;
            }
            if (entries.get(i).getId().startsWith("user-")) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx == -1) {
            return new ArrayList<>(entries); // 没有 user 消息，全保留
        }
        return new ArrayList<>(entries.subList(lastUserIdx, entries.size()));
    }

    private String resolveSource(MemoryEntry entry) {
        String source = entry.getMetadata().get("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        if (entry.getId().startsWith("user-")) {
            return "user";
        }
        if (entry.getId().startsWith("assistant-")) {
            return "assistant";
        }
        if (entry.getId().startsWith("tool-")) {
            return "tool";
        }
        return "unknown";
    }

    private String normalizeFactLine(String line) {
        String fact = line == null ? "" : line.trim();
        if (fact.startsWith("- ")) {
            fact = fact.substring(2);
        } else if (fact.startsWith("• ")) {
            fact = fact.substring(2);
        }
        return fact.trim();
    }

    private boolean isPersistentFactCandidate(String fact) {
        if (fact == null || fact.length() <= 5) {
            return false;
        }

        String normalized = fact.toLowerCase(Locale.ROOT);
        for (String prefix : EPHEMERAL_FACT_PREFIXES) {
            if (normalized.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        for (String cue : SPECULATION_CUES) {
            if (normalized.contains(cue.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        if (normalized.contains("：") || normalized.contains(":")) {
            return true;
        }

        for (String hint : DURABLE_FACT_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }
}
