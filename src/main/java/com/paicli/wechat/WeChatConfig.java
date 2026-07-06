package com.paicli.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 微信配置管理 - 凭证持久化 + 配置加载
 */
public class WeChatConfig {
    private static final Logger log = LoggerFactory.getLogger(WeChatConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path BASE_DIR = Path.of(
            System.getProperty("user.home"), ".paicli", "wechat");
    private static final Path ACCOUNT_FILE = BASE_DIR.resolve("accounts").resolve("default.json");
    static final Path QRCODE_FILE = BASE_DIR.resolve("qrcode.png");

    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    private String baseUrl;
    private String accountId;     // ilink_bot_id, e.g. "xxx@im.bot"
    private Account account;

    public WeChatConfig() {
        this.baseUrl = resolveBaseUrl();
        Account loaded = loadAccount();
        if (loaded != null) {
            this.account = loaded;
            this.accountId = loaded.accountId();
        }
    }

    public boolean hasValidToken() {
        return account != null && account.botToken != null
                && !account.botToken.isBlank() && !account.isExpired();
    }

    public String botToken() {
        return account != null ? account.botToken : null;
    }

    public String accountId() {
        return accountId;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void updateBaseUrl(String newBaseUrl) {
        if (newBaseUrl != null && !newBaseUrl.isBlank() && !newBaseUrl.equals(this.baseUrl)) {
            System.out.println("  → 更新 API 地址: " + this.baseUrl + " -> " + newBaseUrl);
            this.baseUrl = newBaseUrl;
        }
    }

    public void saveAccount(String botToken, long expiresIn, String accountId) {
        try {
            Files.createDirectories(ACCOUNT_FILE.getParent());
            this.account = new Account(botToken, accountId,
                    System.currentTimeMillis() + expiresIn * 1000L);
            this.accountId = accountId;
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(ACCOUNT_FILE.toFile(), account);
            log.info("微信账号凭证已保存到 {}", ACCOUNT_FILE);
        } catch (IOException e) {
            log.warn("保存微信账号凭证失败: {}", e.getMessage());
        }
    }

    public void clearAccount() {
        this.account = null;
        this.accountId = null;
        try {
            Files.deleteIfExists(ACCOUNT_FILE);
        } catch (IOException ignored) {
        }
    }

    private static String resolveBaseUrl() {
        String env = System.getenv("PAICLI_WECHAT_API_BASE");
        if (env != null && !env.isBlank()) return env;
        String prop = System.getProperty("paicli.wechat.api.base");
        if (prop != null && !prop.isBlank()) return prop;
        return DEFAULT_BASE_URL;
    }

    private Account loadAccount() {
        if (!Files.isRegularFile(ACCOUNT_FILE)) return null;
        try {
            return MAPPER.readValue(ACCOUNT_FILE.toFile(), Account.class);
        } catch (IOException e) {
            log.warn("读取微信账号凭证失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unused")
    private record Account(String botToken, String accountId, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
