package com.wangguangyang.config;

import com.wangguangyang.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 *
 * 是什么：Spring MVC 的配置入口。
 * 干什么：把 JwtInterceptor 注册进去，并配置「哪些路径拦截、哪些路径放行」。
 * 为什么：拦截器写好后不会自动生效，必须在 addInterceptors 里注册并指定路径。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")                 // 拦截所有请求
                .excludePathPatterns(                   // 放行这些不需要登录的路径
                        "/studentlogin",                // 学号密码登录
                        "/phonelogin",                  // 手机号验证码登录
                        "/register",                    // 注册
                        "/user/code",                   // 获取验证码
                        "/doc.html",                    // knife4j 接口文档
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/error"                        // Spring 错误页
                );
    }
}
