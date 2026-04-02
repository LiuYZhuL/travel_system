package com.travel.travel_system.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
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

    /**
     * -- GETTER --
     *  获取令牌前缀
     */
    @Getter
    @Value("${jwt.token-prefix}")
    private String tokenPrefix;

    @Getter
    @Value("${jwt.expiration}")
    private Long expiration;
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }


    /**
     * 生成JWT令牌
     */
    public String generateToken(String openId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("openId", openId);
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration * 1000);

        String token = Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(getSigningKey())
                .compact();
        return token;
    }

    /**
     * 从令牌中获取claims
     */
    public Claims getClaimsFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims;
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 验证令牌是否有效
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
     * 从令牌中获取openId
     */
    public String getOpenIdFromToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return (String) claims.get("openId");
        } catch (Exception e) {
            throw e;
        }
    }


}