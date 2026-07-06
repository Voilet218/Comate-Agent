package com.paicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 微信扫码登录流程
 *
 * API 协议（GET 请求，query 参数）：
 *   1. GET /ilink/bot/get_bot_qrcode?bot_type=3
 *      → {"qrcode":"令牌","qrcode_img_content":"https://...","ret":0}
 *   2. GET /ilink/bot/get_qrcode_status?qrcode={令牌}
 *      → {"bot_token":"...","ilink_bot_id":"...","baseurl":"...","ret":0}
 */
public class WeChatLogin {
    private static final Logger log = LoggerFactory.getLogger(WeChatLogin.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient http;
    private final WeChatConfig config;

    public WeChatLogin(OkHttpClient http, WeChatConfig config) {
        this.http = http;
        this.config = config;
    }

    public boolean login() throws IOException, InterruptedException {
        System.out.println("\n📱 正在获取微信登录二维码...");

        // Step 1: 获取二维码
        String qrUrl = config.baseUrl() + "/ilink/bot/get_bot_qrcode?bot_type=3";
        System.out.println("  → GET " + qrUrl);
        JsonNode qrResponse = doGet(qrUrl);
        System.out.println("  → 响应: " + qrResponse);

        String qrcode = qrResponse.path("qrcode").asText();
        int ret = qrResponse.path("ret").asInt(-1);

        if (qrcode.isBlank() || ret != 0) {
            System.err.println("❌ 获取微信二维码失败: " + qrResponse);
            return false;
        }

        // Step 2: 生成二维码图片
        String qrContent = qrResponse.path("qrcode_img_content").asText();
        if (qrContent.isBlank()) {
            qrContent = qrcode;
        }
        generateQrCodeImage(qrContent);

        try {
            java.awt.Desktop.getDesktop().open(WeChatConfig.QRCODE_FILE.toFile());
            System.out.println("📱 已自动打开二维码图片，请用微信扫描");
        } catch (Exception ignored) {
            System.out.println("📱 二维码已保存到: " + WeChatConfig.QRCODE_FILE.toAbsolutePath());
        }
        System.out.println("   （等待扫码中，最长 120 秒...）\n");

        // Step 3: 轮询扫码状态
        String statusUrl = config.baseUrl() + "/ilink/bot/get_qrcode_status?qrcode=" + qrcode;
        long deadline = System.currentTimeMillis() + 120_000;
        long pollInterval = 2000;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollInterval);

            try {
                JsonNode statusResponse = doGet(statusUrl);
                int statusRet = statusResponse.path("ret").asInt(-1);

                if (statusRet == 0) {
                    String botToken = statusResponse.path("bot_token").asText();
                    String baseUrl = statusResponse.path("baseurl").asText("");
                    long expiresIn = statusResponse.path("expires_in").asLong(7 * 24 * 3600);

                    if (botToken.isBlank()) {
                        System.err.println("❌ 登录确认后未获取到 bot_token");
                        System.err.println("   完整响应: " + statusResponse);
                        return false;
                    }
                    String ilinkBotId = statusResponse.path("ilink_bot_id").asText("");
                    System.out.println("  → bot_token=" + botToken.substring(0, Math.min(16, botToken.length())) + "...");
                    System.out.println("  → ilink_bot_id=" + ilinkBotId);
                    System.out.println("  → baseurl=" + (baseUrl.isBlank() ? "(默认)" : baseUrl));
                    System.out.println("  → 过期时间=" + expiresIn + " 秒");
                    if (!baseUrl.isBlank() && !baseUrl.equals(config.baseUrl())) {
                        config.updateBaseUrl(baseUrl);
                    }
                    config.saveAccount(botToken, expiresIn, ilinkBotId);
                    System.out.println("✅ 微信登录成功！");
                    return true;
                }

                switch (statusRet) {
                    case 1 -> { /* 等待扫码 */ }
                    case 2 -> {
                        System.out.println("✅ 已扫码，请在手机上确认登录...");
                        pollInterval = 3000;
                    }
                    case 3 -> {
                        System.out.println("✅ 已确认，正在获取凭证...");
                        pollInterval = 1000;
                    }
                    default -> {
                        String statusMsg = statusResponse.path("status").asText("");
                        System.out.println("  → 状态: ret=" + statusRet + " status=" + statusMsg);
                        if ("expired".equals(statusMsg)) {
                            System.err.println("❌ 二维码已过期");
                            return false;
                        }
                        if ("cancel".equals(statusMsg)) {
                            System.out.println("⏹️ 用户已取消登录");
                            return false;
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("  → 轮询异常: " + e.getMessage() + "，重试中...");
                pollInterval = Math.min(pollInterval + 1000, 5000);
            }
        }

        System.err.println("⏰ 扫码超时，请重新运行");
        return false;
    }

    private void generateQrCodeImage(String content) throws IOException {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            var bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400);
            Files.createDirectories(WeChatConfig.QRCODE_FILE.getParent());
            MatrixToImageWriter.writeToPath(bitMatrix, "PNG", WeChatConfig.QRCODE_FILE);
        } catch (Exception e) {
            throw new IOException("生成二维码失败: " + e.getMessage(), e);
        }
    }

    private JsonNode doGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("iLink-App-ClientVersion", "8.0.70")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return MAPPER.readTree(response.body().string());
        }
    }
}
