package com.wangguangyang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 安全配置类（只引入 BCrypt 加密，不引入整个 Spring Security 框架）
 *
 * 是什么：提供 BCryptPasswordEncoder 这个加密工具 Bean。
 * 干什么：登录时用它比对密码 matches(明文, 密文)。
 * 为什么：
 *   - 密码不能存明文，存的是 BCrypt 加密后的密文。
 *   - 单独声明成 Bean，方便在 Service 里 @Autowired 注入复用（不用到处 new）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
