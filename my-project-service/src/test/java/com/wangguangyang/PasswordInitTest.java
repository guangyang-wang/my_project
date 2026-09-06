package com.wangguangyang;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangguangyang.entity.User;
import com.wangguangyang.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码初始化测试（一次性）
 *
 * 干什么：把之前用 SQL 插进去的「明文密码 060520」加密成 BCrypt 密文。
 * 为什么：登录用的是 BCrypt matches(明文, 密文) 比对，数据库里存明文会对不上，必须先加密。
 *
 * 用法：跑一次这个测试方法即可（跑之前保证 MySQL 已启动、user 表已建好、测试数据已插入）。
 */
@SpringBootTest
class PasswordInitTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Test
    void encryptTestPassword() {
        // 1. 找到学号为 2024302803 的测试数据
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getStudentNo, "2024302803")
        );

        if (user == null) {
            System.out.println("没找到学号为 2024302803 的用户，请先确认测试数据已插入");
            return;
        }

        // 2. 把明文密码 060520 加密成 BCrypt 密文
        String encoded = passwordEncoder.encode("060520");
        user.setPassword(encoded);
        userMapper.updateById(user);

        // 3. 打印密文，方便确认
        System.out.println("==========================================");
        System.out.println("密码已加密，BCrypt 密文 = " + encoded);
        System.out.println("之后可用「学号 2024302803 + 密码 060520」做密码登录");
        System.out.println("==========================================");
    }
}
