package com.wangguangyang.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangguangyang.common.Result;
import com.wangguangyang.util.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权全局过滤器
 *
 * 是什么：网关里的「登录校验」，对应原单体里的 JwtInterceptor，只是换成了 Gateway 的 GlobalFilter。
 * 干什么：
 *   1. 白名单路径（登录/注册/接口文档）直接放行；
 *   2. 其余路径从 Authorization 头取 token → 验签；
 *   3. 验签失败返回 401；成功则把用户信息塞进 header（X-User-Id 等）透传给下游服务。
 * 为什么不能照搬原来的 HandlerInterceptor：
 *   - Gateway 是 WebFlux（响应式），没有 Servlet，HandlerInterceptor 在这里根本用不上；
 *   - GlobalFilter 是 Gateway 自己的过滤器接口，@Component 标注即全局生效。
 * 为什么是「透传」而不是「存 ThreadLocal」：
 *   - WebFlux 一个请求可能跨多个线程执行，ThreadLocal 会串数据/丢失；
 *   - 网关的职责是把身份传给下游，下游服务将来从 header 读即可，网关自己不存状态。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 白名单：这些路径不需要登录（和原单体 WebConfig 里的 excludePathPatterns 一致） */
    private static final List<String> WHITE_LIST = List.of(
            "/studentlogin",        // 学号密码登录
            "/phonelogin",          // 手机号验证码登录
            "/register",            // 注册
            "/user/code",           // 获取验证码
            "/doc.html",            // knife4j 接口文档
            "/webjars",             // 接口文档静态资源
            "/v3/api-docs",         // OpenAPI 文档
            "/swagger-resources",   // swagger 资源
            "/error"                // Spring 错误页
    );

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 白名单路径：不鉴权，直接放行转发
        if (isWhite(path)) {
            return chain.filter(exchange);
        }

        // 2. 取 token（约定格式 Authorization: Bearer xxx）
        String token = resolveToken(request);
        if (token == null || token.isEmpty()) {
            return unauthorized(exchange);
        }

        // 3. 验签，失败（过期/篡改/密钥错）返回 401
        try {
            Claims claims = jwtUtils.parseToken(token);
            String userId = claims.getSubject();                       // subject 存用户 id
            String studentNo = claims.get("studentNo", String.class);
            String name = claims.get("name", String.class);

            // 4. 把用户身份塞进 header 透传给下游（下游将来从这些 header 读，不再重复解析 JWT）
            ServerHttpRequest newRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-Student-No", studentNo == null ? "" : studentNo)
                    .header("X-Name", name == null ? "" : name)
                    .build();

            // 5. 用改过的请求继续走过滤器链
            return chain.filter(exchange.mutate().request(newRequest).build());
        } catch (Exception e) {
            return unauthorized(exchange);
        }
    }

    /** 判断路径是否在白名单内（用前缀匹配，兼容 /webjars/xxx 这种带子路径的） */
    private boolean isWhite(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    /** 从 Authorization 头取 token，去掉 "Bearer " 前缀 */
    private String resolveToken(ServerHttpRequest request) {
        String token = request.getHeaders().getFirst("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return token;
    }

    /** 返回 401 统一 JSON */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Result.error(401, "未登录或登录已过期"));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    /**
     * 过滤器执行顺序：返回负值保证它在路由转发（NettyRoutingFilter，order 接近 Integer.MAX_VALUE）之前执行，
     * 否则请求还没鉴权就被转发到下游了，鉴权形同虚设。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
