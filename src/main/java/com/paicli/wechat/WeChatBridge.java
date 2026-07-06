package com.paicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * iLink Bot API 桥接 - 微信消息收发核心
 */
public class WeChatBridge implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WeChatBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int QUEUE_CAPACITY = 100;

    private final OkHttpClient http;
    private final OkHttpClient pollHttp;
    private final WeChatConfig config;
    private final BlockingQueue<WeChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;
    private Thread sendThread;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wechat-retry");
        t.setDaemon(true);
        return t;
    });

    private volatile WeChatHitlHandler hitlHandler;

    public WeChatBridge(WeChatConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
        this.pollHttp = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(65))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setHitlHandler(WeChatHitlHandler hitlHandler) {
        this.hitlHandler = hitlHandler;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        pollThread = new Thread(this::pollLoop, "wechat-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        sendThread = new Thread(this::sendLoop, "wechat-send");
        sendThread.setDaemon(true);
        sendThread.start();
        log.info("微信消息桥接已启动");
    }

    @Override
    public void close() {
        running.set(false);
        retryScheduler.shutdownNow();
        if (pollThread != null) pollThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        log.info("微信消息桥接已关闭");
    }

    public boolean isRunning() { return running.get(); }

    public WeChatMessage pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return messageQueue.poll(timeout, unit);
    }

    public int queueSize() { return messageQueue.size(); }

    public void sendMessage(String content) {
        sendMessage(WeChatMessage.toSend(content));
    }

    public void sendMessage(WeChatMessage msg) {
        if (!running.get()) {
            log.warn("微信桥接未运行，丢弃消息: {}", preview(msg.content()));
            return;
        }
        pendingSends.add(msg);
    }

    // ===== 长轮询 =====

    private final BlockingQueue<WeChatMessage> pendingSends = new LinkedBlockingQueue<>();
    private volatile String getUpdatesBuf = "";

    private void pollLoop() {
        while (running.get()) {
            try {
                JsonNode response = callGetUpdates();
                if (response == null) break;

                String newBuf = response.path("get_updates_buf").asText("");
                if (!newBuf.isBlank()) getUpdatesBuf = newBuf;

                JsonNode msgs = response.path("msgs");
                if (msgs.isArray()) {
                    for (JsonNode msg : msgs) {
                        WeChatMessage parsed = parseMessage(msg);
                        if (parsed == null) continue;
                        routeMessage(parsed);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("微信消息轮询异常: {}", e.getMessage());
                if (running.get()) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void routeMessage(WeChatMessage msg) {
        if (msg == null || !msg.hasContent()) return;

        WeChatHitlHandler handler = this.hitlHandler;
        if (handler != null && handler.hasPendingApproval()) {
            handler.onReply(msg.content());
            return;
        }

        if (!messageQueue.offer(msg)) {
            log.warn("消息队列已满，丢弃消息: {}", msg.msgId());
        }
    }

    // ===== 发送循环 =====

    private void sendLoop() {
        while (running.get()) {
            try {
                WeChatMessage msg = pendingSends.take();
                doSend(msg, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void doSend(WeChatMessage msg, int retryCount) {
        try {
            String payload = buildSendPayload(msg);
            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/ilink/bot/sendmessage")
                    .post(RequestBody.create(payload, JSON))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.botToken())
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("X-WECHAT-UIN", randomWechatUin())
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (response.isSuccessful()) return;

                log.warn("微信消息发送失败: HTTP {}", response.code());
                if (response.code() == 429 && retryCount < 3) {
                    long delay = (long) Math.pow(2, retryCount + 1) * 1000;
                    int frc = retryCount + 1;
                    retryScheduler.schedule(() -> doSend(msg, frc), delay, TimeUnit.MILLISECONDS);
                }
            }
        } catch (IOException e) {
            log.warn("微信消息发送异常: {}", e.getMessage());
            if (retryCount < 3) {
                long delay = (long) Math.pow(2, retryCount + 1) * 1000;
                int frc = retryCount + 1;
                retryScheduler.schedule(() -> doSend(msg, frc), delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ===== iLink API 调用 =====

    private JsonNode callGetUpdates() throws IOException, InterruptedException {
        String payload = "{\"get_updates_buf\":\"" + escapeJson(getUpdatesBuf) + "\","
                + "\"base_info\":{\"channel_version\":\"1.0.0\"}}";
        Request request = new Request.Builder()
                .url(config.baseUrl() + "/ilink/bot/getupdates")
                .post(RequestBody.create(payload, JSON))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.botToken())
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", randomWechatUin())
                .build();

        try (Response response = pollHttp.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401) {
                    config.clearAccount();
                    running.set(false);
                }
                return null;
            }
            if (response.body() == null) return null;
            String bodyStr = response.body().string();

            if (bodyStr.contains("errcode")) {
                JsonNode errJson = MAPPER.readTree(bodyStr);
                int errcode = errJson.path("errcode").asInt(0);
                if (errcode == -14) {
                    getUpdatesBuf = "";
                    return MAPPER.readTree(bodyStr);
                }
                if (errcode != 0) return null;
            }
            return MAPPER.readTree(bodyStr);
        }
    }

    // ===== 消息模型 =====

    private WeChatMessage parseMessage(JsonNode node) {
        if (node == null) return null;

        String fromUser = node.path("from_user_id").asText("");
        String contextToken = node.path("context_token").asText("");
        String msgId = node.path("msg_id").asText("");
        if (msgId.isBlank()) msgId = String.valueOf(System.nanoTime());

        String content = "";
        JsonNode itemList = node.path("item_list");
        if (itemList.isArray()) {
            for (JsonNode item : itemList) {
                JsonNode textItem = item.path("text_item");
                if (!textItem.isMissingNode()) {
                    content = textItem.path("text").asText("");
                    break;
                }
            }
        }

        if (content.isBlank()) return null;
        return new WeChatMessage(msgId, content, fromUser, contextToken, "text", null);
    }

    private String buildSendPayload(WeChatMessage msg) {
        String clientId = "comate-" + System.currentTimeMillis() + "-" + Integer.toHexString(RANDOM.nextInt());
        return "{\"msg\":{"
                + "\"from_user_id\":\"" + escapeJson(config.accountId()) + "\","
                + "\"to_user_id\":\"" + escapeJson(msg.fromUser()) + "\","
                + "\"client_id\":\"" + escapeJson(clientId) + "\","
                + "\"message_type\":2,"
                + "\"message_state\":2,"
                + "\"context_token\":\"" + escapeJson(msg.contextToken()) + "\","
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"" + escapeJson(msg.content()) + "\"}}]"
                + "}}";
    }

    // ===== 工具方法 =====

    private static String randomWechatUin() {
        int uint32 = RANDOM.nextInt() & 0xFFFFFFFF;
        return Base64.getEncoder().encodeToString(String.valueOf(uint32).getBytes());
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String preview(String text) {
        if (text == null) return "null";
        if (text.length() <= 60) return text;
        return text.substring(0, 57) + "...";
    }
}
