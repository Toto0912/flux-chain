package com.admin.common.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

@Component
public class UdpEchoKeyUtil {
    private static final String CONTEXT = "fluxchain-udp-echo-v2";
    private static final String ALGORITHM = "HmacSHA256";
    private static String SECRET_KEY;

    @Value("${jwt-secret}")
    private String secretKey;

    @PostConstruct
    public void init() {
        SECRET_KEY = this.secretKey;
    }

    public static String getEchoKey() {
        return deriveKey(SECRET_KEY);
    }

    public static String deriveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT密钥未配置，无法派生 UDP 回显密钥");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(CONTEXT.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256不可用", e);
        }
    }
}
