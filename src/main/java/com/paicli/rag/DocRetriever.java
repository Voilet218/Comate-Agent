package com.paicli.rag;

import java.sql.SQLException;
import java.util.*;

/**
 * 文档检索器：语义检索 + 关键词检索的统一入口。
 * <p>
 * 与 CodeRetriever 不同，没有代码类型加权和关系图谱。
 * 固定使用 {@link DocIndex#DOCS_DIR} 作为文档库根路径。
 */
public class DocRetriever implements AutoCloseable {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public DocRetriever() throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(DocIndex.DOCS_DIR);
    }

    public DocRetriever(EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(DocIndex.DOCS_DIR);
    }

    /**
     * 语义检索：用自然语言查询最相关的文档块
     */
    public List<VectorStore.DocSearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.searchDocs(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按标题/内容精确匹配
     */
    public List<VectorStore.DocSearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchDocsByKeyword(keyword);
    }

    /**
     * 混合检索：语义 + 关键词同时检索，合并去重排序
     */
    public List<VectorStore.DocSearchResult> hybridSearch(String query, int topK) throws Exception {
        // 去重 map：key = filePath + "#" + chunkIndex
        Map<String, VectorStore.DocSearchResult> merged = new LinkedHashMap<>();

        // 1. 语义检索（多取一些用于合并）
        int semanticLimit = Math.max(topK * 2, 10);
        for (VectorStore.DocSearchResult result : semanticSearch(query, semanticLimit)) {
            String key = result.filePath() + "#" + result.chunkIndex();
            // 语义结果已有 similarity，直接放
            if (!merged.containsKey(key)) {
                merged.put(key, result);
            }
        }

        // 2. 关键词检索
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.DocSearchResult result : keywordSearch(keyword)) {
                String key = result.filePath() + "#" + result.chunkIndex();
                VectorStore.DocSearchResult existing = merged.get(key);
                if (existing == null) {
                    merged.put(key, result);
                } else {
                    // 取更高的相似度
                    double best = Math.max(existing.similarity(), result.similarity());
                    merged.put(key, new VectorStore.DocSearchResult(
                            result.filePath(), result.title(), result.docType(),
                            result.chunkIndex(), result.content(), best));
                }
            }
        }

        // 3. 排序取 topK
        List<VectorStore.DocSearchResult> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(VectorStore.DocSearchResult::similarity).reversed());

        return ranked.size() > topK ? ranked.subList(0, topK) : ranked;
    }

    /**
     * 获取文档库统计
     */
    public VectorStore.DocStats getStats() throws SQLException {
        return vectorStore.getDocStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
