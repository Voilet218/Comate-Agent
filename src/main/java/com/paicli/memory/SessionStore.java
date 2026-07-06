package com.paicli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    // ---- 可配置项 ----
    private static final String DIR_PROPERTY = "paicli.session.dir";
    private static final String DIR_ENV = "PAICLI_SESSION_DIR";
    private static final String ENABLED_PROPERTY = "paicli.session.enabled";
    private static final String ENABLED_ENV = "PAICLI_SESSION_ENABLED";
    private static final String FLUSH_INTERVAL_PROPERTY = "paicli.session.flush.interval.ms";
    private static final String FLUSH_INTERVAL_ENV = "PAICLI_SESSION_FLUSH_INTERVAL_MS";
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 500;
    private static final int MAX_BATCH_SIZE = 50;
    private static final int QUEUE_CAPACITY = 10_000;

    private static final String DB_FILE_NAME = "sessions.db";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection conn;
    private final int currentSessionId;
    private final BlockingQueue<PersistTask> queue;
    private final ScheduledExecutorService flusher;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile int seqCounter;

    /**
     * No-op 实例：当 PAICLI_SESSION_ENABLED=false 时使用。
     */
    private SessionStore(boolean noop) {
        this.conn = null;
        this.currentSessionId = -1;
        this.queue = null;
        this.flusher = null;
    }

    public SessionStore() {
        this(resolveStorageDir());
    }

    public SessionStore(File storageDir) {
        if (!isEnabled()) {
            log.info("SessionStore 已禁用 (PAICLI_SESSION_ENABLED=false)，不持久化");
            // fall through to no-op path below
            conn = null;
            currentSessionId = -1;
            queue = null;
            flusher = null;
            return;
        }

        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }
            File dbFile = new File(storageDir, DB_FILE_NAME);
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace("\\", "/");
            this.conn = DriverManager.getConnection(url);

            // 性能参数
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }

            // 建表
            createTablesIfNeeded();

            // 创建当前 session
            this.currentSessionId = createSession();
            this.seqCounter = 0;
            log.info("SessionStore 初始化完成: sessionId={}, db={}", currentSessionId, dbFile);

        } catch (SQLException e) {
            throw new RuntimeException("SessionStore 初始化失败: " + storageDir, e);
        }

        // 异步 flush 队列
        this.queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        int interval = resolveFlushIntervalMs();
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-session-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleWithFixedDelay(this::drainAndFlush, interval, interval, TimeUnit.MILLISECONDS);

        // JVM 退出兜底
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "paicli-session-shutdown"));
    }

    // ---- public API ----

    /**
     * @return 当前 session 的自增编号
     */
    public int getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * 将一条 MemoryEntry 加入异步写队列。
     * 由 MemoryManager.addXxx() 调用，不阻塞调用线程。
     */
    public void append(MemoryEntry entry) {
        if (queue == null || closed.get()) {
            return; // 已禁用或已关闭
        }
        String role = deriveRole(entry);
        String metadataJson = serializeMetadata(entry);
        PersistTask task = new PersistTask(entry, role, metadataJson);
        if (!queue.offer(task)) {
            log.warn("Session 写队列已满({})，丢弃条目: {}", QUEUE_CAPACITY, entry.getId());
        }
    }

    /**
     * FTS5 全文检索历史会话。
     *
     * @param query     搜索关键词
     * @param limit     最多返回条数
     * @param sessionId 限定 session，null 表示全量
     * @return 匹配的 SessionEntry 列表，按 BM25 相关性排序
     */
    public List<SessionEntry> search(String query, int limit, Integer sessionId) {
        if (conn == null || closed.get()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int clampedLimit = Math.max(1, Math.min(limit, 50));

        // jieba 分词后拼成空格分隔，使 FTS5 unicode61 能匹配中文词组
        String ftsQuery = tokenizeForFts(query);
        if (ftsQuery.isEmpty()) {
            return List.of();
        }

        String sql = """
                SELECT e.id, e.session_id, s.created_at AS session_created_at,
                       e.seq, e.role, e.content, e.type, e.metadata, e.token_count
                FROM session_entry_fts f
                JOIN session_entry e ON f.rowid = e.id
                JOIN session s ON e.session_id = s.id
                WHERE session_entry_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """;

        if (sessionId != null) {
            sql = """
                    SELECT e.id, e.session_id, s.created_at AS session_created_at,
                           e.seq, e.role, e.content, e.type, e.metadata, e.token_count
                    FROM session_entry_fts f
                    JOIN session_entry e ON f.rowid = e.id
                    JOIN session s ON e.session_id = s.id
                    WHERE session_entry_fts MATCH ?
                      AND e.session_id = ?
                    ORDER BY rank
                    LIMIT ?
                    """;
        }

        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, ftsQuery);
            if (sessionId != null) {
                ps.setInt(2, sessionId);
                ps.setInt(3, clampedLimit);
            } else {
                ps.setInt(2, clampedLimit);
            }

            ResultSet rs = ps.executeQuery();
            List<SessionEntry> results = new ArrayList<>();
            while (rs.next()) {
                results.add(SessionEntry.fromResultSet(rs));
            }
            return results;
        } catch (SQLException e) {
            log.warn("Session FTS5 搜索失败: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 关闭 SessionStore：flush 剩余数据、标记 session 为 closed、释放资源。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // 已关闭，幂等
        }
        log.info("SessionStore 关闭中...");

        // 停止 flusher 并等待最后一轮
        if (flusher != null) {
            flusher.shutdown();
            try {
                if (!flusher.awaitTermination(3, TimeUnit.SECONDS)) {
                    flusher.shutdownNow();
                }
            } catch (InterruptedException e) {
                flusher.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 最后一次 flush
        drainAndFlush();

        // 标记 session closed
        if (conn != null) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE session SET status='closed' WHERE id=" + currentSessionId);
            } catch (SQLException e) {
                log.debug("关闭 session status 标记失败: {}", e.getMessage());
            }
            try {
                conn.close();
            } catch (SQLException e) {
                log.debug("关闭 session 数据库连接失败: {}", e.getMessage());
            }
        }

        log.info("SessionStore 已关闭, sessionId={}", currentSessionId);
    }

    // ---- 内部实现 ----

    private void createTablesIfNeeded() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS session (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                        project    TEXT,
                        model      TEXT,
                        status     TEXT NOT NULL DEFAULT 'active'
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS session_entry (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id  INTEGER NOT NULL REFERENCES session(id),
                        seq         INTEGER NOT NULL,
                        role        TEXT NOT NULL,
                        content     TEXT NOT NULL,
                        type        TEXT NOT NULL DEFAULT 'CONVERSATION',
                        metadata    TEXT,
                        token_count INTEGER NOT NULL DEFAULT 0,
                        created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_entry_session_seq
                        ON session_entry(session_id, seq)
                    """);
            // FTS5 虚拟表
            stmt.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS session_entry_fts
                        USING fts5(content, metadata, tokenize='unicode61')
                    """);
        }
    }

    private int createSession() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO session (project) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, System.getProperty("user.dir"));
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                return keys.getInt(1);
            }
            throw new SQLException("无法获取 session 自增 id");
        }
    }

    private void drainAndFlush() {
        if (conn == null) {
            return;
        }
        List<PersistTask> batch = new ArrayList<>(MAX_BATCH_SIZE);
        queue.drainTo(batch, MAX_BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        try {
            conn.setAutoCommit(false);

            PreparedStatement insertEntry = conn.prepareStatement(
                    "INSERT INTO session_entry (session_id, seq, role, content, type, metadata, token_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            PreparedStatement insertFts = conn.prepareStatement(
                    "INSERT INTO session_entry_fts(rowid, content, metadata) VALUES (?, ?, ?)");

            for (PersistTask task : batch) {
                MemoryEntry entry = task.entry;
                int seq = ++seqCounter;

                insertEntry.setInt(1, currentSessionId);
                insertEntry.setInt(2, seq);
                insertEntry.setString(3, task.role);
                insertEntry.setString(4, entry.getContent());
                insertEntry.setString(5, entry.getType().name());
                insertEntry.setString(6, task.metadataJson);
                insertEntry.setInt(7, entry.getTokenCount());
                insertEntry.executeUpdate();

                // FTS5 rowid 必须等于 session_entry.id
                ResultSet keys = insertEntry.getGeneratedKeys();
                if (keys.next()) {
                    long rowId = keys.getLong(1);
                    // FTS5 索引用 jieba 分词后的内容，解决 unicode61 不分中文词的问题
                    String tokenized = tokenizeForFts(entry.getContent());
                    insertFts.setLong(1, rowId);
                    insertFts.setString(2, tokenized.isEmpty() ? entry.getContent() : tokenized);
                    insertFts.setString(3, task.metadataJson);
                    insertFts.executeUpdate();
                }
            }

            conn.commit();
            conn.setAutoCommit(true);
            log.debug("SessionStore flush {} 条", batch.size());
        } catch (SQLException e) {
            log.warn("SessionStore flush 失败: {}", e.getMessage());
            try {
                conn.rollback();
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    // ---- 辅助方法 ----

    private static String deriveRole(MemoryEntry entry) {
        String id = entry.getId();
        if (id.startsWith("user-")) return "user";
        if (id.startsWith("assistant-")) return "assistant";
        if (id.startsWith("tool-")) return "tool";
        return "unknown";
    }

    private static String serializeMetadata(MemoryEntry entry) {
        try {
            return JSON.writeValueAsString(entry.getMetadata());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static File resolveStorageDir() {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(DIR_ENV);
        }
        if (dir != null && !dir.isBlank()) {
            return new File(dir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli"), "sessions");
    }

    private static int resolveFlushIntervalMs() {
        String val = System.getProperty(FLUSH_INTERVAL_PROPERTY);
        if (val == null || val.isBlank()) {
            val = System.getenv(FLUSH_INTERVAL_ENV);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Math.max(100, Integer.parseInt(val.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_FLUSH_INTERVAL_MS;
    }

    /**
     * 使用 jieba 对文本分词，返回空格分隔的 token 串，
     * 供 FTS5 unicode61 正确索引中英文混合文本。
     * 保留所有 token（包括单字），不进行 MemoryQueryTokenizer 的单字符过滤。
     */
    static String tokenizeForFts(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> words = com.paicli.util.JiebaSegmenterFactory.createSilently()
                .sentenceProcess(text.trim());
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            String trimmed = word.trim();
            // 过滤纯标点/空白 token，其余全部保留
            if (trimmed.isEmpty() || trimmed.codePoints().allMatch(Character::isWhitespace)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private static boolean isEnabled() {
        String val = System.getProperty(ENABLED_PROPERTY);
        if (val == null || val.isBlank()) {
            val = System.getenv(ENABLED_ENV);
        }
        if (val != null && !val.isBlank()) {
            return !"false".equalsIgnoreCase(val.trim());
        }
        return true;
    }

    // ---- 内部类型 ----

    private record PersistTask(MemoryEntry entry, String role, String metadataJson) {}
}
