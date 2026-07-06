package com.paicli.wechat;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信内审批处理器 - 实现 HitlHandler，将审批请求发送到微信
 *
 * 核心机制：
 * - Agent 线程在调用 requestApproval() 时会被阻塞（CountDownLatch.await）
 * - 微信消息到达后，若 hasPendingApproval() == true，直接路由到此处理器的 onReply()
 * - 用户在微信回复 y/yes 或 n/no 即可完成审批
 * - 120 秒超时自动拒绝
 *
 * 设计约束：
 * - HitlHandler.requestApproval() 是同步阻塞的，Agent 等待期间不会再发起新的审批
 * - 因此"当前只有一个 pending 审批"的假设成立，不需要匹配 ID
 */
public class WeChatHitlHandler implements HitlHandler {
    private static final Logger log = LoggerFactory.getLogger(WeChatHitlHandler.class);
    private static final long APPROVAL_TIMEOUT_SECONDS = 120;

    private final WeChatBridge bridge;
    private final AtomicReference<PendingApproval> pendingRef = new AtomicReference<>();
    private volatile boolean enabled = true;

    public WeChatHitlHandler(WeChatBridge bridge) {
        this.bridge = bridge;
    }

    // ===== HitlHandler 接口实现 =====

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }

        String toolName = request.toolName();
        String args = summarizeArgs(request.arguments());

        PendingApproval pa = new PendingApproval();
        pendingRef.set(pa);

        // 构建审批消息发送到微信
        String approvalText = formatApprovalText(toolName, args);
        bridge.sendMessage(WeChatMessage.toSend("🤖 PaiCLI 需要审批\n\n" + approvalText));

        boolean approved;
        String reason = null;
        try {
            approved = pa.waitForReply(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!approved) {
                reason = "⏰ 审批超时（" + APPROVAL_TIMEOUT_SECONDS + "秒），已自动拒绝";
                bridge.sendMessage("⏰ 审批超时，已自动拒绝");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            approved = false;
            reason = "审批流程被中断";
        } finally {
            pendingRef.set(null);
        }

        if (approved) {
            log.info("HITL 审批通过: tool={}", toolName);
            return ApprovalResult.approve();
        }
        log.info("HITL 审批拒绝: tool={}, reason={}", toolName, reason);
        return ApprovalResult.reject(reason);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ===== 微信消息路由入口（由 WeChatBridge 调用）=====

    /**
     * 当前是否有待处理的审批
     */
    public boolean hasPendingApproval() {
        return pendingRef.get() != null;
    }

    /**
     * 用户通过微信回复审批结果
     */
    public void onReply(String text) {
        PendingApproval pa = pendingRef.get();
        if (pa == null) return;

        String trimmed = text.trim().toLowerCase();
        boolean approved = "y".equals(trimmed) || "yes".equals(trimmed)
                || "是".equals(trimmed) || "批准".equals(trimmed);
        boolean rejected = "n".equals(trimmed) || "no".equals(trimmed)
                || "否".equals(trimmed) || "拒绝".equals(trimmed);

        if (approved || rejected) {
            pa.complete(approved);
        } else {
            // 不理解的内容，提示用户
            bridge.sendMessage("❓ 请回复 y 批准 / n 拒绝（" + APPROVAL_TIMEOUT_SECONDS + "秒内）");
        }
    }

    // ===== 内部 =====

    private static String formatApprovalText(String toolName, String args) {
        String toolLabel = switch (toolName) {
            case "write_file" -> "📄 写文件";
            case "execute_command" -> "🔧 执行命令";
            case "create_project" -> "📁 创建项目";
            case "revert_turn" -> "↩️ 回退操作";
            default -> "🔧 工具: " + toolName;
        };

        return toolLabel + "\n"
                + "参数: " + args + "\n\n"
                + "回复 y 批准 / n 拒绝\n"
                + "（" + APPROVAL_TIMEOUT_SECONDS + "秒后自动拒绝）";
    }

    private static String summarizeArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return "(空)";
        // 截断过长参数
        return argumentsJson.length() > 200
                ? argumentsJson.substring(0, 197) + "..."
                : argumentsJson;
    }

    /**
     * 一次待处理的审批请求
     */
    private static class PendingApproval {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean approved = false;

        boolean waitForReply(long timeout, TimeUnit unit) throws InterruptedException {
            latch.await(timeout, unit);
            return approved;
        }

        void complete(boolean approved) {
            this.approved = approved;
            latch.countDown();
        }
    }
}
