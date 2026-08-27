package com.wangguangyang.interceptor;

import com.wangguangyang.common.UserContext;
import com.wangguangyang.util.JwtUtils;
import com.wangguangyang.vo.LoginUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 登录拦截器
 *
 * 是什么：Spring MVC 的拦截器，在 Controller 方法执行前校验登录状态。
 * 干什么：从请求头 Authorization 取 token → 解析 → 校验通过就放行并把用户存 ThreadLocal。
 * 为什么：登录校验不能写在每个 Controller 里，用拦截器统一做，一次配置全局生效。
 *
 * 说明：
 *   - preHandle 返回 true = 放行，false = 拦截（配合 response.setStatus(401)）。
 *   - 哪些路径拦/不拦，在 WebConfig 里配置。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 从请求头取 token（约定格式：Authorization: Bearer xxx）
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);   // 去掉 "Bearer " 前缀
        }

        // 2. 没带 token → 拦截
        if (token == null || token.isEmpty()) {
            response.setStatus(401);
            return false;
        }

        // 3. 解析 token，失败(过期/篡改/密钥错)会抛异常 → 拦截
        try {
            Claims claims = jwtUtils.parseToken(token);

            // 4. 解析成功，把用户信息装进 ThreadLocal，供 Controller 使用
            LoginUser loginUser = new LoginUser(
                    Long.valueOf(claims.getSubject()),          // subject 存的是用户 id
                    claims.get("studentNo", String.class),
                    claims.get("name", String.class)
            );
            UserContext.set(loginUser);

            // 5. 放行
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后清理 ThreadLocal，防止线程复用时串数据、内存泄漏
        UserContext.remove();
    }
}
