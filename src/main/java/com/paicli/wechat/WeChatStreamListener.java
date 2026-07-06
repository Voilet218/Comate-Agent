package com.paicli.wechat;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式输出监听器 - 将 Claude 的推理过程和内容逐步推送到微信
 */
public class WeChatStreamListener implements LlmClient.StreamListener {
    private static final Logger log = LoggerFactory.getLogger(WeChatStreamListener.class);

    private final WeChatBridge bridge;
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final AtomicBoolean contentStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService flushTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wechat-stream-flush");
        t.setDaemon(true);
        return t;
    });

    private volatile String lastContextToken;
    private volatile String lastFromUser;

    public WeChatStreamListener(WeChatBridge bridge) {
        this.bridge = bridge;
        flushTimer.scheduleAtFixedRate(this::flushThinking, 800, 800, TimeUnit.MILLISECONDS);
    }

    public void setContextToken(String contextToken) {
        this.lastContextToken = contextToken;
    }

    public void setFromUser(String fromUser) {
        this.lastFromUser = fromUser;
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        synchronized (thinkingBuffer) {
            thinkingBuffer.append(delta);
            if (delta.contains("\n")) {
                flushThinking();
            }
        }
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;

        if (contentStarted.compareAndSet(false, true)) {
            flushThinking();
        }

        synchronized (contentBuffer) {
            contentBuffer.append(delta);
            String current = contentBuffer.toString();
            int boundary = findSentenceBoundary(current);
            if (boundary > 0) {
                String segment = current.substring(0, boundary);
                pushContent(segment);
                contentBuffer.delete(0, boundary);
            }
        }
    }

    public void flush() {
        flushThinking();
        flushContent();
        flushTimer.shutdownNow();
    }

    private void flushThinking() {
        String text;
        synchronized (thinkingBuffer) {
            if (thinkingBuffer.length() == 0) return;
            text = thinkingBuffer.toString();
            thinkingBuffer.setLength(0);
        }
        if (!text.isBlank()) {
            bridge.sendMessage(new WeChatMessage(null, "💭 " + text.trim(), lastFromUser, lastContextToken, "text", null));
        }
    }

    private void flushContent() {
        String text;
        synchronized (contentBuffer) {
            if (contentBuffer.length() == 0) return;
            text = contentBuffer.toString();
            contentBuffer.setLength(0);
        }
        if (!text.isBlank()) {
            pushContent(text.trim());
        }
    }

    private void pushContent(String text) {
        if (text == null || text.isBlank()) return;
        int maxLen = 4000;
        String prefix = contentStarted.get() ? "💬 " : "";
        if (text.length() <= maxLen) {
            bridge.sendMessage(new WeChatMessage(null, prefix + text, lastFromUser, lastContextToken, "text", null));
        } else {
            for (int i = 0; i < text.length(); i += maxLen) {
                int end = Math.min(i + maxLen, text.length());
                String segment = text.substring(i, end);
                bridge.sendMessage(new WeChatMessage(null, (i == 0 ? "💬 " : "↳ ") + segment, lastFromUser, lastContextToken, "text", null));
            }
        }
    }

    private static int findSentenceBoundary(String text) {
        if (text == null || text.length() < 15) return -1;
        int start = text.length() * 3 / 5;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        if (text.length() > 2000) return 1500;
        return -1;
    }
}
