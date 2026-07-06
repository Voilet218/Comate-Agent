package com.paicli.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.JiebaSegmenter.SegMode;
import com.huaban.analysis.jieba.SegToken;
import com.paicli.util.JiebaSegmenterFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用文档分块器。
 * <p>
 * 三段递归分块策略（段落 → 句子 → Jieba 词边界）：
 * <ol>
 *   <li><b>段落级</b>：按连续空行（\n\n+）拆分</li>
 *   <li><b>句子级</b>：按句末标点（。！？!?\n）拆分，过短的相邻小句自动合并防碎片化</li>
 *   <li><b>词级</b>：按 Jieba 分词边界在接近上限的位置切分</li>
 * </ol>
 * <p>
 * 默认块大小 512 字符，相邻块间 100 字符重叠。
 */
public class DocChunker {
    private static final int DEFAULT_MAX_CHARS = 512;
    private static final int OVERLAP_CHARS = 100;

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\n+");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?\\n])\\s*");

    private final JiebaSegmenter segmenter;

    public DocChunker() {
        this.segmenter = JiebaSegmenterFactory.createSilently();
    }

    /**
     * 将文档内容分块
     *
     * @param filePath 文档路径
     * @param content  文档内容
     * @param docType  文档类型（txt/md）
     * @return 分块列表
     */
    public List<DocChunk> chunkDocument(String filePath, String content, String docType) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String title = extractTitle(content, filePath, docType);
        String text = content.trim();

        List<String> segments = chunkText(text, DEFAULT_MAX_CHARS);
        List<DocChunk> chunks = new ArrayList<>(segments.size());

        for (int i = 0; i < segments.size(); i++) {
            chunks.add(new DocChunk(filePath, title, docType, i + 1, segments.get(i)));
        }
        return chunks;
    }

    // ──────────────── 三段递归分块 ────────────────

    /**
     * 三段递归分块：段落 → 句子 → 词边界，然后合并 + 重叠
     */
    private List<String> chunkText(String text, int maxSize) {
        // Phase 1: 递归拆分至最小单元
        List<String> units = splitIntoUnits(text, maxSize);
        // Phase 2: 合并小单元为 ~maxSize 大小的块
        List<String> chunks = groupUnits(units, maxSize);
        // Phase 3: 相邻块间追加重叠
        return applyOverlap(chunks);
    }

    /**
     * 三级递归：段落 → 句子 → Jieba 词边界
     */
    private List<String> splitIntoUnits(String text, int maxSize) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.length() <= maxSize) {
            return List.of(text.trim());
        }

        /* Level 1: 按段落拆分 */
        String[] paragraphs = PARAGRAPH_PATTERN.split(text.trim());
        if (paragraphs.length > 1) {
            List<String> result = new ArrayList<>();
            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.length() <= maxSize) {
                    result.add(trimmed);
                } else {
                    result.addAll(splitBySentences(trimmed, maxSize));
                }
            }
            return result;
        }

        /* 无段落分隔 → 直接从句子级开始 */
        return splitBySentences(text.trim(), maxSize);
    }

    /**
     * 句子级拆分：按句末标点分割，过长句子进入词级拆分
     */
    private List<String> splitBySentences(String text, int maxSize) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }

        String[] parts = SENTENCE_SPLIT.split(text);
        if (parts.length <= 1) {
            return splitByWords(text, maxSize);
        }

        // 收集非空句子
        List<String> raw = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) raw.add(trimmed);
        }
        if (raw.isEmpty()) return List.of(text);

        // 合并过短小句（< 30 字符的与相邻句合并，防止碎片化）
        List<String> merged = coalesceShortSentences(raw, 30);

        // 递归处理长句
        List<String> result = new ArrayList<>();
        for (String sent : merged) {
            if (sent.length() <= maxSize) {
                result.add(sent);
            } else {
                result.addAll(splitByWords(sent, maxSize));
            }
        }
        return result;
    }

    /**
     * 词级拆分：利用 Jieba 分词边界，在接近 maxSize 的位置切分
     */
    private List<String> splitByWords(String text, int maxSize) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }

        List<String> result = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > maxSize) {
            List<SegToken> tokens = segmenter.process(remaining, SegMode.INDEX);
            if (tokens.isEmpty()) {
                // 保底：等长切分
                result.add(remaining.substring(0, maxSize));
                remaining = remaining.substring(maxSize).trim();
                continue;
            }

            // 找到第一个词边界 ≥ maxSize 的位置
            int splitPos = maxSize;
            for (SegToken token : tokens) {
                if (token.endOffset >= maxSize) {
                    splitPos = token.startOffset;
                    break;
                }
            }

            // 边界安全兜底
            if (splitPos <= 0 || splitPos >= remaining.length()) {
                splitPos = Math.min(maxSize, remaining.length());
            }
            if (splitPos == 0 || splitPos >= remaining.length()) {
                result.add(remaining.substring(0, Math.min(maxSize, remaining.length())).trim());
                remaining = remaining.substring(Math.min(maxSize, remaining.length())).trim();
                continue;
            }

            result.add(remaining.substring(0, splitPos).trim());
            remaining = remaining.substring(splitPos).trim();
        }

        if (!remaining.isEmpty()) {
            result.add(remaining);
        }
        result.removeIf(String::isBlank);
        return result;
    }

    // ──────────────── 合并与重叠 ────────────────

    /**
     * 合并过短小句，防止分块碎片化
     */
    private List<String> coalesceShortSentences(List<String> sentences, int minSize) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String sent : sentences) {
            // 如果加上当前句会超 maxSize，先 flush
            if (buf.length() > 0 && buf.length() + sent.length() > DEFAULT_MAX_CHARS) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }

            if (buf.length() > 0) buf.append("\n");
            buf.append(sent);

            // 达到最低长度要求就 flush（但保留短句继续追加）
            if (buf.length() >= minSize) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) result.add(buf.toString().trim());
        return result;
    }

    /**
     * 将小单元合并为接近 maxSize 的块（贪心合并）
     */
    private List<String> groupUnits(List<String> units, int maxSize) {
        if (units.isEmpty()) return List.of();
        if (units.size() == 1) return units;

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String unit : units) {
            if (!current.isEmpty() && current.length() + unit.length() + 1 > maxSize) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append("\n");
            current.append(unit);
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());

        return chunks;
    }

    /**
     * 相邻块之间追加重叠文本
     * <p>
     * 从第二块开始，每块在前面追加上一块末尾 {@link #OVERLAP_CHARS} 字符
     */
    private List<String> applyOverlap(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String overlap = prev.length() > OVERLAP_CHARS
                    ? prev.substring(prev.length() - OVERLAP_CHARS)
                    : prev;
            result.add(overlap + chunks.get(i));
        }
        return result;
    }

    // ──────────────── 标题提取 ────────────────

    /**
     * 提取文档标题
     * <ul>
     *   <li>MD 文件：首个 {@code # } 标题</li>
     *   <li>TXT 文件：首行非空行</li>
     *   <li>保底：文件名（不含扩展名）</li>
     * </ul>
     */
    static String extractTitle(String content, String filePath, String docType) {
        if (content == null || content.isBlank()) {
            return fileNameWithoutExt(filePath);
        }

        String[] lines = content.split("\\r?\\n");

        // MD: 查找首个 # 标题
        if ("md".equalsIgnoreCase(docType)) {
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ") || trimmed.startsWith("#\t")) {
                    return trimmed.substring(2).trim();
                }
            }
        }

        // TXT 或 MD 无标题：首行非空行
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        return fileNameWithoutExt(filePath);
    }

    private static String fileNameWithoutExt(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "untitled";
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String fileName = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
