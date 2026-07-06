package com.paicli.rag;

/**
 * 文档块数据模型
 *
 * @param filePath   文档路径（在 ~/.paicli/docs/ 下的相对路径）
 * @param title      文档标题（MD 首行 # 标题，或文件名）
 * @param docType    文档类型：txt / md
 * @param chunkIndex 块序号（从 1 开始）
 * @param content    块内容文本
 */
public record DocChunk(String filePath, String title, String docType,
                        int chunkIndex, String content) {

    /**
     * 生成用于 Embedding 的文本表示（标题 + 内容）
     */
    public String toEmbeddingText() {
        return (title != null && !title.isEmpty() ? title + "\n" : "") + content;
    }
}
