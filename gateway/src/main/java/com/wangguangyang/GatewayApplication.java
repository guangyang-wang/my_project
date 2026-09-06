package com.wangguangyang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动类
 *
 * 是什么：gateway 模块的入口。
 * 干什么：启动网关服务（默认 8081 端口，在 application.yml 里配）。
 * 为什么：放在 com.wangguangyang 包下，@SpringBootApplication 会扫描本包及子包，
 *   这样 filter/util/common 下的 Bean（鉴权过滤器、JwtUtils 等）才会被加载。
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
