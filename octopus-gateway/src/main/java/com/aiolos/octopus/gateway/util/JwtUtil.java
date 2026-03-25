package com.aiolos.octopus.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtil {

    // 使用足够长的密钥（至少 256 位 / 32 字符）
    private static final String SECRET_STRING = "AiolosBadgerCiamSecretKeyForJwtAuthentication2026!";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes(StandardCharsets.UTF_8));

    // 默认过期时间，Access Token 2小时，Refresh Token 7天
    public static final long ACCESS_TOKEN_EXPIRE_MILLIS = 2 * 60 * 60 * 1000L;
    public static final long REFRESH_TOKEN_EXPIRE_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    /**
     * 生成 JWT Token
     *
     * @param subject      主题（通常是 userId）
     * @param claims       额外信息
     * @param expireMillis 过期时间（毫秒）
     * @param tokenType    Token类型：access 或 refresh
     * @return JWT 字符串
     */
    public static String generateToken(String subject, Map<String, Object> claims, Long expireMillis, String tokenType) {
        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + (expireMillis != null ? expireMillis : ACCESS_TOKEN_EXPIRE_MILLIS);
        Date now = new Date(nowMillis);
        Date exp = new Date(expMillis);

        var builder = Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(exp)
                .claim("type", tokenType != null ? tokenType : "access")
                .signWith(SECRET_KEY);

        if (claims != null && !claims.isEmpty()) {
            builder.addClaims(claims);
        }

        return builder.compact();
    }

    /**
     * 解析并验证 JWT Token
     *
     * @param token JWT 字符串
     * @return Claims 载荷
     * @throws JwtException 如果 Token 无效或已过期
     */
    public static Claims parseToken(String token) throws JwtException {
        if (StringUtils.isBlank(token)) {
            throw new IllegalArgumentException("Token cannot be blank");
        }

        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 获取 Token 中的主题 (subject)
     *
     * @param token JWT 字符串
     * @return 主题（userId）
     */
    public static String getSubjectFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }
}
