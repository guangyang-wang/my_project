package com.wangguangyang.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 工具类（网关裁剪版）
 *
 * 是什么：从原单体复制的 JwtUtils，但只保留了「解析/验签」部分。
 * 干什么：网关拿前端传来的 token 做校验，解析出用户信息。
 * 为什么裁剪掉 generateToken：
 *   - 生成 token 是 user-service（登录）的职责，网关不生成 token；
 *   - generateToken 依赖 User 实体，删掉它网关就不用连 User 实体一起复制，保持网关轻量、不碰数据库。
 * 为什么密钥必须和签发方一致：JWT 是对称签名（HS256），签和验用同一个密钥，不一致全部验签失败(401)。
 */
@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    /** 把密钥字符串转成 HMAC-SHA256 需要的 SecretKey 对象 */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 token，返回 Claims（装着 subject=用户id、studentNo、name）
     * 解析失败（篡改/过期/密钥不对）抛异常，由调用方（鉴权过滤器）捕获返回 401。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())          // 用同一个密钥验签
                .build()
                .parseSignedClaims(token)      // 解析并校验签名
                .getPayload();                 // 取出 Claims
    }
}
