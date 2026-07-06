package com.paicli.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档索引管理器。
 * <p>
 * 负责将文档文件分块、向量化并持久化到 VectorStore 的 doc_chunks 表。
 * 上传的文档会复制到 {@code ~/.paicli/docs/} 目录下统一管理。
 */
public class DocIndex {
    private static final Logger log = LoggerFactory.getLogger(DocIndex.class);

    /** 文档存储根目录 */
    static final String DOCS_DIR = System.getProperty("user.home") + "/.paicli/docs";

    private final EmbeddingClient embeddingClient;
    private final DocChunker chunker;
    private final DocProgressListener progressListener;

    @FunctionalInterface
    public interface DocProgressListener {
        void onProgress(String message);
        static DocProgressListener noop() { return msg -> {}; }
    }

    public DocIndex() {
        this(new EmbeddingClient(), DocProgressListener.noop());
    }

    public DocIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, DocProgressListener.noop());
    }

    public DocIndex(DocProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public DocIndex(EmbeddingClient embeddingClient, DocProgressListener progressListener) {
        this.embeddingClient = embeddingClient;
        this.chunker = new DocChunker();
        this.progressListener = progressListener == null ? DocProgressListener.noop() : progressListener;
    }

    /**
     * 上传并索引单个文档文件
     *
     * @param sourcePath 源文件路径
     * @return 索引结果
     */
    public DocIndexResult indexFile(String sourcePath) {
        Path source = Paths.get(sourcePath).toAbsolutePath().normalize();
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            String msg = "文件不存在或不是普通文件: " + sourcePath;
            emit("❌ " + msg);
            return new DocIndexResult(0, msg);
        }

        String fileName = source.getFileName().toString();
        String docType = detectDocType(fileName);
        if (docType == null) {
            String msg = "不支持的文档格式: " + fileName + "（仅支持 .txt / .md）";
            emit("❌ " + msg);
            return new DocIndexResult(0, msg);
        }

        emit("📄 上传文档: " + fileName);

        try {
            // 1. 读取内容
            String content = Files.readString(source);

            // 2. 复制到 docs 目录
            Path targetDir = Paths.get(DOCS_DIR);
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(fileName);
            // 同名文件覆盖
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            String storePath = target.toAbsolutePath().normalize().toString();

            // 3. 分块
            List<DocChunk> chunks = chunker.chunkDocument(storePath, content, docType);
            if (chunks.isEmpty()) {
                String msg = "文档内容为空，跳过索引";
                emit("⚠️ " + msg);
                return new DocIndexResult(0, msg);
            }

            emit("  ✂️ 分块: " + chunks.size() + " 块");

            // 4. Embedding
            List<VectorStore.DocChunkEntry> entries = new ArrayList<>(chunks.size());
            for (DocChunk chunk : chunks) {
                float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
                entries.add(new VectorStore.DocChunkEntry(chunk, embedding));
            }

            // 5. 持久化
            try (VectorStore store = new VectorStore(DOCS_DIR)) {
                store.clearDocByFile(storePath);
                store.insertDocChunks(entries);
                emit("✅ 文档索引完成: " + fileName + "（" + chunks.size() + " 块）");
                return new DocIndexResult(chunks.size(), "索引完成: " + fileName + "（" + chunks.size() + " 块）");
            }
        } catch (Exception e) {
            String msg = "文档索引失败: " + fileName + " - " + e.getMessage();
            emit("❌ " + msg);
            log.warn("doc index failed for file {}", sourcePath, e);
            return new DocIndexResult(0, msg);
        }
    }

    /**
     * 批量索引目录下所有支持的文档文件
     *
     * @param dirPath 目录路径
     * @return 索引结果
     */
    public DocIndexResult indexDirectory(String dirPath) {
        Path root = Paths.get(dirPath).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            String msg = "目录不存在: " + dirPath;
            emit("❌ " + msg);
            return new DocIndexResult(0, msg);
        }

        emit("🔍 扫描文档目录: " + root);

        List<Path> files = new ArrayList<>();
        collectDocFiles(root, files);

        if (files.isEmpty()) {
            String msg = "未找到支持的文档文件（.txt / .md）";
            emit("⚠️ " + msg);
            return new DocIndexResult(0, msg);
        }

        emit("📁 发现 " + files.size() + " 个文档文件");

        int totalChunks = 0;
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String fileName = file.getFileName().toString();
            emit(String.format("  [%d/%d] %s", i + 1, files.size(), fileName));

            DocIndexResult result = indexFile(file.toAbsolutePath().toString());
            if (result.chunkCount() > 0) {
                totalChunks += result.chunkCount();
                successCount++;
            } else {
                failCount++;
            }
        }

        String summary = String.format(
                "批量索引完成：成功 %d / 失败 %d，共 %d 个块", successCount, failCount, totalChunks);
        emit("✅ " + summary);
        return new DocIndexResult(totalChunks, summary);
    }

    /**
     * 删除文档索引
     *
     * @param filePath 文档路径
     */
    public void removeFile(String filePath) {
        try (VectorStore store = new VectorStore(DOCS_DIR)) {
            store.clearDocByFile(filePath);
            emit("🗑️ 已删除文档索引: " + filePath);
        } catch (Exception e) {
            log.warn("failed to remove doc index for {}", filePath, e);
        }
    }

    /**
     * 获取文档统计信息
     */
    public VectorStore.DocStats getStats() {
        try (VectorStore store = new VectorStore(DOCS_DIR)) {
            return store.getDocStats();
        } catch (Exception e) {
            log.warn("failed to get doc stats", e);
            return new VectorStore.DocStats(0);
        }
    }

    // ──────────── 内部方法 ────────────

    private void emit(String msg) {
        progressListener.onProgress(msg);
    }

    /**
     * 检测文档类型
     *
     * @return "txt", "md", 或 null（不支持）
     */
    static String detectDocType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".md")) return "md";
        return null;
    }

    /**
     * 递归收集支持的文档文件
     */
    private void collectDocFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (detectDocType(name) != null) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            emit("❌ 遍历目录失败: " + e.getMessage());
            log.warn("doc file traversal failed for root {}", root, e);
        }
    }

    // ──────────── 结果记录 ────────────

    public record DocIndexResult(int chunkCount, String message) {}
}
