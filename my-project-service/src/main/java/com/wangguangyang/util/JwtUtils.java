package com.wangguangyang.util;

import com.wangguangyang.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 *
 * 是什么：封装 JWT 的「生成」和「解析」两个核心操作。
 * 干什么：
 *   - generateToken：登录成功后，把用户信息封装进 token 返回给前端。
 *   - parseToken：拦截器拿到前端传来的 token，解析出用户信息。
 * 为什么：JWT 是无状态的登录凭证，服务端不存 session，全靠这个 token 携带用户身份。
 *
 * 说明：
 *   - 密钥和有效期从 application.yml 的 jwt.secret / jwt.expire 读，不硬编码。
 *   - 用的库是 JJWT 0.12.x，API 是新版(Jwts.builder().subject() / Jwts.parser().verifyWith())。
 */
@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire}")
    private long expire;

    /**
     * 把密钥字符串转成 HMAC-SHA256 需要的 SecretKey 对象
     */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token
     *
     * subject 存用户 id（唯一标识），另外把学号、姓名作为自定义 claim 塞进去，
     * 这样拦截器解析后能直接拿到这些信息，不用再查一次数据库。
     */
    public String generateToken(User user) {
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))       // 主题：用户 id
                .claim("studentNo", user.getStudentNo())      // 自定义字段：学号
                .claim("name", user.getName())                // 自定义字段：姓名
                .issuedAt(new Date())                         // 签发时间
                .expiration(new Date(System.currentTimeMillis() + expire))  // 过期时间
                .signWith(getKey())                           // 用密钥签名(HS256)
                .compact();
    }

    /**
     * 解析 token，返回 Claims（里面装着生成时塞进去的所有信息）
     *
     * 解析失败(篡改/过期/密钥不对)会抛异常，由调用方(拦截器)捕获处理。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())                          // 用同一个密钥验签
                .build()
                .parseSignedClaims(token)                      // 解析并校验签名
                .getPayload();                                 // 取出 Claims
    }
}
