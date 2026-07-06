package com.paicli.wechat;

/**
 * 微信消息模型 - 对应 iLink Bot API 的消息结构
 *
 * @param msgId        消息 ID（服务端分配）
 * @param content      消息文本内容
 * @param fromUser     发送者标识（登录者本人）
 * @param contextToken 会话上下文令牌，回复时必须带回
 * @param msgType      消息类型：text / image 等
 * @param imageData    图片消息的 base64 数据（可选）
 */
public record WeChatMessage(
        String msgId,
        String content,
        String fromUser,
        String contextToken,
        String msgType,
        String imageData
) {
    public boolean isText() {
        return "text".equals(msgType);
    }

    public boolean isImage() {
        return "image".equals(msgType) || "img".equals(msgType);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /** 出站消息构造器 */
    public static WeChatMessage toSend(String content, String contextToken) {
        return new WeChatMessage(null, content, null, contextToken, "text", null);
    }

    public static WeChatMessage toSend(String content) {
        return new WeChatMessage(null, content, null, null, "text", null);
    }
}
