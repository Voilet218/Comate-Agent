package com.paicli.memory;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Session 存储条目的只读结果类，用于 search_session 工具返回值。
 * 不同于 MemoryEntry（内存级），SessionEntry 含 session_id/seq 等持久化字段。
 */
public record SessionEntry(
        int id,
        int sessionId,
        String sessionCreatedAt,
        int seq,
        String role,
        String content,
        String type,
        String metadata,
        int tokenCount) {

    /**
     * 从 SQL 结果集构造 SessionEntry。
     * 列名需与 search() 方法的 SELECT 别名一致。
     */
    public static SessionEntry fromResultSet(ResultSet rs) throws SQLException {
        return new SessionEntry(
                rs.getInt("id"),
                rs.getInt("session_id"),
                rs.getString("session_created_at"),
                rs.getInt("seq"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("type"),
                rs.getString("metadata"),
                rs.getInt("token_count"));
    }

    /**
     * 格式化摘要，用于工具返回值或日志。
     */
    public String toDisplayString() {
        int maxLen = 200;
        String snippet = content.length() > maxLen ? content.substring(0, maxLen) + "..." : content;
        return "[Session #%d / %s @%s] %s: %s".formatted(
                sessionId, role, sessionCreatedAt, type, snippet);
    }
}