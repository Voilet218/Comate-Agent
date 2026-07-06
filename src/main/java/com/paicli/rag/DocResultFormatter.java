package com.paicli.rag;

import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档检索结果展示格式化。
 * <p>
 * 提供 CLI 展示和 Tool（LLM 调用）两种输出格式。
 */
public final class DocResultFormatter {

    private DocResultFormatter() {}

    /**
     * 格式化结果用于 CLI 终端展示
     */
    public static String formatForCli(String query, List<VectorStore.DocSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "📭 未找到相关文档。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 找到 ").append(results.size()).append(" 个相关文档片段:\n\n");
        sb.append(buildSummary(query, results)).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.DocSearchResult r = results.get(i);
            sb.append(String.format("%d. [%s] (相似度: %.3f) %s\n",
                    i + 1,
                    r.title() != null ? r.title() : "无标题",
                    r.similarity(),
                    shortenPath(r.filePath())));
            if (r.docType() != null) {
                sb.append("   类型: ").append(r.docType()).append("\n");
            }
            sb.append("   块 #").append(r.chunkIndex()).append("\n");
            sb.append("   ").append(buildSnippet(r.content(), 120).replace("\n", "\n   "));
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 格式化结果用于 LLM Tool 调用返回
     */
    public static String formatForTool(String query, List<VectorStore.DocSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未找到相关文档。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("文档检索摘要:\n");
        sb.append(buildSummary(query, results)).append("\n\n");
        sb.append("检索结果:\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.DocSearchResult r = results.get(i);
            sb.append(String.format("%d. [%s] (相似度: %.3f) %s\n",
                    i + 1,
                    r.title() != null ? r.title() : "无标题",
                    r.similarity(),
                    shortenPath(r.filePath())));
            sb.append("   类型: ").append(r.docType()).append("  块 #").append(r.chunkIndex()).append("\n");
            sb.append("   ").append(buildSnippet(r.content(), 200).replace("\n", "\n   "));
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 构建搜索摘要（描述最相关的结果分布）
     */
    private static String buildSummary(String query, List<VectorStore.DocSearchResult> results) {
        if (results.isEmpty()) {
            return "- 没有命中任何文档片段。";
        }

        VectorStore.DocSearchResult top = results.get(0);
        Set<String> docTitles = results.stream()
                .map(r -> r.title() != null ? r.title() : Paths.get(r.filePath()).getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> queryTokens = RagQueryTokenizer.tokenize(query).stream()
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String topDoc = top.title() != null ? top.title() : shortenPath(top.filePath());
        String relatedDocs = docTitles.stream().limit(3).collect(Collectors.joining("、"));
        String tokenText = queryTokens.isEmpty() ? "自然语言语义" : String.join("、", queryTokens);

        StringBuilder sb = new StringBuilder("摘要:\n");
        sb.append("- 最相关的文档是「").append(topDoc).append("」（第 ").append(top.chunkIndex()).append(" 块）。\n");
        sb.append("- 当前结果主要集中在 ")
                .append(relatedDocs)
                .append(docTitles.size() > 3 ? " 等文档" : " 这些文档")
                .append("。\n");
        sb.append("- 排序综合参考了 ")
                .append(tokenText)
                .append(" 等关键词与语义相似度。");
        return sb.toString();
    }

    private static String buildSnippet(String content, int maxChars) {
        if (content == null || content.isBlank()) return "(无内容)";
        String normalized = content.trim().replace("\r\n", "\n");
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "...";
    }

    private static String shortenPath(String filePath) {
        if (filePath == null) return "";
        java.nio.file.Path path = Paths.get(filePath);
        int count = path.getNameCount();
        if (count <= 3) return filePath;
        return "..." + path.subpath(count - 3, count).toString();
    }
}
