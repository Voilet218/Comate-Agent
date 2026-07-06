package com.paicli.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SessionStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void shouldCreateTables() throws Exception {
        store = new SessionStore(tempDir.toFile());

        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("sessions.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            // 验证表存在
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
                List<String> tables = new java.util.ArrayList<>();
                while (rs.next()) tables.add(rs.getString("name"));
                assertTrue(tables.contains("session"), "缺 session 表: " + tables);
                assertTrue(tables.contains("session_entry"), "缺 session_entry 表: " + tables);
                // FTS5 虚拟表以 table 类型出现（至少 sqlite 3.49 确认）
                assertTrue(tables.contains("session_entry_fts"), "缺 session_entry_fts 表: " + tables);
            }
            // 验证索引
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index'");
                List<String> indexes = new java.util.ArrayList<>();
                while (rs.next()) indexes.add(rs.getString("name"));
                assertTrue(indexes.contains("idx_entry_session_seq"),
                        "缺 idx_entry_session_seq: " + indexes);
            }
        }
    }

    @Test
    void shouldHavePositiveSessionId() {
        store = new SessionStore(tempDir.toFile());
        assertTrue(store.getCurrentSessionId() > 0, "session id 应为正数");
    }

    @Test
    void shouldAppendAndFlush() throws Exception {
        store = new SessionStore(tempDir.toFile());

        // 写入 3 条
        store.append(fakeEntry("user-001", "你好，帮我写代码"));
        store.append(fakeEntry("assistant-001", "好的，请提供需求"));
        store.append(fakeEntry("tool-001", "[read_file] 文件内容..."));

        // 等异步 flush
        Thread.sleep(1500);

        // 验证数据库中有 3 条记录，seq 顺序正确
        try (Connection c = connect()) {
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM session_entry WHERE session_id=" + store.getCurrentSessionId());
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
            // 验证 seq 递增
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT seq, role, content FROM session_entry "
                                + "WHERE session_id=" + store.getCurrentSessionId()
                                + " ORDER BY seq");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("seq"));
                assertEquals("user", rs.getString("role"));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("seq"));
                assertEquals("assistant", rs.getString("role"));
                assertTrue(rs.next());
                assertEquals(3, rs.getInt("seq"));
                assertEquals("tool", rs.getString("role"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void shouldAutoIncrementSessionId() throws Exception {
        store = new SessionStore(tempDir.toFile());
        int id1 = store.getCurrentSessionId();
        store.close();

        store = new SessionStore(tempDir.toFile());
        int id2 = store.getCurrentSessionId();

        assertEquals(id1 + 1, id2, "关闭再打开 session id 应自增");
    }

    @Test
    void shouldSetStatusClosedOnClose() throws Exception {
        store = new SessionStore(tempDir.toFile());
        int sid = store.getCurrentSessionId();
        store.close();
        store = null; // 防止 AfterEach 重复 close

        try (Connection c = connect()) {
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT status FROM session WHERE id=" + sid);
                assertTrue(rs.next());
                assertEquals("closed", rs.getString("status"));
            }
        }
    }

    @Test
    void shouldSearchChineseContent() throws Exception {
        store = new SessionStore(tempDir.toFile());
        store.append(fakeEntry("user-001", "用户要求修改登录页面样式"));
        store.append(fakeEntry("assistant-001", "已修改 Login.vue 的背景色"));
        store.append(fakeEntry("user-002", "再帮我加一个注册页面"));
        Thread.sleep(1500);

        // jieba 会把 "登录页面" 拆成 ["登录", "页面"]，所以 FTS5 搜索这两个词都能命中
        List<SessionEntry> results = store.search("登录", 10, null);
        assertFalse(results.isEmpty(), "应搜索到登录相关条目");
        assertTrue(results.get(0).content().contains("登录"), "命中条目内容应含登录");
    }

    @Test
    void shouldSearchWithEnglishKeywords() throws Exception {
        store = new SessionStore(tempDir.toFile());
        store.append(fakeEntry("user-001", "Please refactor the UserService class"));
        store.append(fakeEntry("assistant-001", "I've refactored UserService to use dependency injection"));
        store.append(fakeEntry("user-002", "What about the tests?"));
        Thread.sleep(1500);

        List<SessionEntry> results = store.search("UserService", 10, null);
        assertFalse(results.isEmpty(), "应搜索到 UserService 相关条目");
    }

    @Test
    void shouldReturnEmptyOnNoMatch() throws Exception {
        store = new SessionStore(tempDir.toFile());
        store.append(fakeEntry("user-001", "普通对话内容"));
        Thread.sleep(1500);

        List<SessionEntry> results = store.search("不存在的关键词abcdefg", 10, null);
        assertTrue(results.isEmpty(), "无匹配时应返回空列表");
    }

    @Test
    void shouldReturnEmptyOnBlankQuery() {
        store = new SessionStore(tempDir.toFile());
        List<SessionEntry> results = store.search("", 10, null);
        assertTrue(results.isEmpty(), "空查询应返回空列表");
    }

    @Test
    void shouldFilterBySessionId() throws Exception {
        // Session 1
        store = new SessionStore(tempDir.toFile());
        int sid1 = store.getCurrentSessionId();
        store.append(fakeEntry("user-001", "第一个会话的对话内容"));
        Thread.sleep(1000);
        store.close();

        // Session 2
        store = new SessionStore(tempDir.toFile());
        int sid2 = store.getCurrentSessionId();
        store.append(fakeEntry("user-002", "第二个会话的不同内容"));
        Thread.sleep(1000);

        // 全量搜索 "内容" 两边都有
        List<SessionEntry> all = store.search("内容", 10, null);
        assertEquals(2, all.size(), "跨 session 搜索应返回 2 条");

        // 按 session 过滤
        List<SessionEntry> filtered1 = store.search("内容", 10, sid1);
        assertEquals(1, filtered1.size());
        assertEquals(sid1, filtered1.get(0).sessionId());

        List<SessionEntry> filtered2 = store.search("内容", 10, sid2);
        assertEquals(1, filtered2.size());
        assertEquals(sid2, filtered2.get(0).sessionId());
    }

    @Test
    void shouldStoreMetadataAsJson() throws Exception {
        store = new SessionStore(tempDir.toFile());
        MemoryEntry entry = new MemoryEntry(
                "user-001", "测试内容", MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user", "project", "/repo/test"), 10);
        store.append(entry);
        Thread.sleep(1000);

        try (Connection c = connect()) {
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT metadata FROM session_entry WHERE session_id=" + store.getCurrentSessionId());
                assertTrue(rs.next());
                String meta = rs.getString("metadata");
                assertTrue(meta.contains("\"source\""), "metadata 应为 JSON 格式");
                assertTrue(meta.contains("user"), "应含 source 字段");
            }
        }
    }

    @Test
    void shouldFlushBatchOfFewHundred() throws Exception {
        store = new SessionStore(tempDir.toFile());
        int count = 100;
        for (int i = 0; i < count; i++) {
            store.append(fakeEntry("user-" + i, "批量消息内容 " + i));
        }
        Thread.sleep(3000);

        try (Connection c = connect()) {
            try (Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM session_entry WHERE session_id=" + store.getCurrentSessionId());
                assertTrue(rs.next());
                assertEquals(count, rs.getInt(1));
            }
        }
    }

    @Test
    void shouldBeClosedIdempotent() {
        store = new SessionStore(tempDir.toFile());
        store.close();
        // 第二次 close 不应抛异常
        assertDoesNotThrow(() -> store.close());
    }

    // ---- 辅助 ----

    private static MemoryEntry fakeEntry(String id, String content) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.CONVERSATION, null, 5);
    }

    private Connection connect() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("sessions.db").toString().replace("\\", "/");
        return DriverManager.getConnection(jdbcUrl);
    }
}
