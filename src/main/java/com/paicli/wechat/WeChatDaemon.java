package com.paicli.wechat;

import com.paicli.agent.Agent;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信守护进程 - 编排所有 WeChat 相关组件
 */
public class WeChatDaemon implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WeChatDaemon.class);

    private final WeChatConfig config;
    private final WeChatBridge bridge;
    private final WeChatHitlHandler hitlHandler;
    private final WeChatStreamListener streamListener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Agent agent;
    private LlmClient llmClient;
    private Thread agentThread;

    private static final long IDLE_POLL_TIMEOUT_MS = 5000;

    public WeChatDaemon() {
        this.config = new WeChatConfig();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
        this.bridge = new WeChatBridge(config);
        this.hitlHandler = new WeChatHitlHandler(bridge);
        this.streamListener = new WeChatStreamListener(bridge);
        bridge.setHitlHandler(hitlHandler);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public boolean start() {
        if (!running.compareAndSet(false, true)) return false;

        try {
            if (!ensureLoggedIn()) {
                System.err.println("❌ 微信登录失败");
                running.set(false);
                return false;
            }
            if (llmClient == null) {
                System.err.println("❌ LLM Client 未设置");
                running.set(false);
                return false;
            }
            initAgent();
            bridge.start();

            agentThread = new Thread(this::agentLoop, "wechat-agent");
            agentThread.setDaemon(true);
            agentThread.start();

            log.info("微信互通守护进程已启动");
            return true;
        } catch (Exception e) {
            log.error("微信守护进程启动失败", e);
            running.set(false);
            return false;
        }
    }

    public boolean isRunning() { return running.get(); }

    public String getStatus() {
        if (!running.get()) return "📱 微信互通: 未启动（输入 /wechat 启动）";
        return "📱 微信互通: 运行中（队列: " + bridge.queueSize() + " 条待处理）";
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        streamListener.flush();
        bridge.close();
        if (agentThread != null) agentThread.interrupt();
        log.info("微信守护进程已关闭");
    }

    private boolean ensureLoggedIn() {
        if (config.hasValidToken()) return true;
        System.out.println("📱 需要微信扫码登录");
        try {
            OkHttpClient http = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(130))
                    .build();
            WeChatLogin login = new WeChatLogin(http, config);
            return login.login();
        } catch (Exception e) {
            System.err.println("❌ 微信登录异常: " + e.getMessage());
            log.error("微信登录失败", e);
            return false;
        }
    }

    private void initAgent() {
        HitlToolRegistry toolRegistry = new HitlToolRegistry(hitlHandler);
        toolRegistry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        agent = new Agent(llmClient, toolRegistry);
        agent.setExternalStreamListener(streamListener);
    }

    private void agentLoop() {
        while (running.get()) {
            try {
                WeChatMessage msg = bridge.pollMessage(IDLE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                if (!running.get()) break;

                String text = msg.content().trim();
                if (text.startsWith("/")) {
                    handleWeChatCommand(text, msg.contextToken());
                    continue;
                }

                streamListener.setContextToken(msg.contextToken());
                streamListener.setFromUser(msg.fromUser());

                log.info("处理微信消息: {}", preview(msg.content()));
                String result = agent.run(msg.content());

                if (result != null && !result.isEmpty()) {
                    bridge.sendMessage(WeChatMessage.toSend(result, msg.contextToken()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Agent 处理消息异常", e);
                bridge.sendMessage("❌ 处理出错: " + e.getMessage());
            }
        }
    }

    private void handleWeChatCommand(String text, String ctx) {
        String cmd = text.toLowerCase().trim();

        switch (cmd) {
            case "/help":
            case "/h":
                bridge.sendMessage(new WeChatMessage(null, """
                        PaiCLI 微信命令：
                        /help     查看帮助
                        /clear    清空当前会话
                        /status   查看运行状态
                        /stop     取消当前任务
                        /model    查看当前模型
                        """.trim(), null, ctx, "text", null));
                return;

            case "/clear":
                agent.clearHistory();
                bridge.sendMessage(new WeChatMessage(null, "🗑️ 当前微信会话历史已清空", null, ctx, "text", null));
                return;

            case "/status":
                bridge.sendMessage(new WeChatMessage(null,
                        "📊 状态\n"
                        + "Agent: " + (agent == null ? "未初始化" : "就绪") + "\n"
                        + "队列: " + bridge.queueSize() + " 条待处理\n"
                        + "模型: " + (llmClient == null ? "未知" : llmClient.getModelName()),
                        null, ctx, "text", null));
                return;

            case "/stop":
            case "/cancel":
                {
                    CancellationToken token = CancellationContext.current();
                    if (token != null) token.cancel();
                }
                bridge.sendMessage(new WeChatMessage(null, "⏹️ 已请求取消当前任务", null, ctx, "text", null));
                return;

            case "/model":
                String name = llmClient == null ? "未知" : llmClient.getModelName();
                String provider = llmClient == null ? "" : " (" + llmClient.getProviderName() + ")";
                bridge.sendMessage(new WeChatMessage(null, "🤖 当前模型: " + name + provider, null, ctx, "text", null));
                return;

            default:
                bridge.sendMessage(new WeChatMessage(null,
                        "❌ 未知命令: " + text + "\n发送 /help 查看可用命令", null, ctx, "text", null));
        }
    }

    private static String preview(String text) {
        if (text == null) return "null";
        if (text.length() <= 60) return text;
        return text.substring(0, 57) + "...";
    }
}
