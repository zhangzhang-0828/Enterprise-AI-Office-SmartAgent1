package com.yupi.yuaiagent.utils;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 工具类：生成、验证、解析 Token，以及密码哈希
 */
@Component
@Slf4j
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtUtils {

    /** 与数据库中密码比较时用的盐（生产环境请改为随机生成并持久化） */
    private static final String PASSWORD_SALT = "YuAiAgent_PwdSalt_2026";

    private String secret = "your-256-bit-secret-key-change-this-in-production-32chars!";

    private long expiresInMs = 7 * 24 * 60 * 60 * 1000L; // 默认 7 天

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ===================== Token 操作 =====================

    public String generateToken(String username, Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiresInMs);
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            log.warn("[JWT] Token 验证失败: {}", e.getMessage());
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).getPayload().getSubject();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token).getPayload();
        return claims.get("userId", Long.class);
    }

    private Jws<Claims> parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
    }

    // ===================== 密码哈希（SHA-256 + 盐）=====================

    /**
     * 对明文密码进行哈希（加盐 SHA-256）
     */
    public String encodePassword(String rawPassword) {
        return DigestUtil.sha256Hex(PASSWORD_SALT + rawPassword);
    }

    /**
     * 验证密码是否匹配
     */
    public boolean checkPassword(String rawPassword, String encodedPassword) {
        return encodePassword(rawPassword).equals(encodedPassword);
    }

    // === getters / setters ===
    public void setSecret(String secret) {
        this.secret = secret;
    }

    public void setExpiresInMs(long expiresInMs) {
        this.expiresInMs = expiresInMs;
    }

    public long getExpiresInMs() {
        return expiresInMs;
    }
}
