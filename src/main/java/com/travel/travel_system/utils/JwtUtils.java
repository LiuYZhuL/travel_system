package com.travel.travel_system.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Getter
    @Value("${jwt.token-prefix}")
    private String tokenPrefix;

    @Getter
    @Value("${jwt.expiration}")
    private Long expiration;

    @Getter
    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成访问令牌（短期有效）
     */
    public String generateAccessToken(String openId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("openId", openId);
        claims.put("type", "access");
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration * 1000);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成刷新令牌（长期有效）
     */
    public String generateRefreshToken(String openId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("openId", openId);
        claims.put("type", "refresh");
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + refreshExpiration * 1000);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 从令牌中获取 claims
     */
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证令牌签名及有效期
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从令牌中获取 openId
     */
    public String getOpenIdFromToken(String token) {
        return (String) getClaimsFromToken(token).get("openId");
    }

    /**
     * 获取令牌类型（"access" 或 "refresh"）
     */
    public String getTokenType(String token) {
        try {
            return (String) getClaimsFromToken(token).get("type");
        } catch (Exception e) {
            return null;
        }
    }
}
