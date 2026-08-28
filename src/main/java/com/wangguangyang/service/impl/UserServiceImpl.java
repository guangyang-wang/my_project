package com.wangguangyang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangguangyang.common.BusinessException;
import com.wangguangyang.config.RabbitConfig;
import com.wangguangyang.dto.PhoneLoginDTO;
import com.wangguangyang.dto.RegisterDTO;
import com.wangguangyang.dto.StudentLoginDTO;
import com.wangguangyang.entity.User;
import com.wangguangyang.mapper.UserMapper;
import com.wangguangyang.service.UserService;
import com.wangguangyang.util.JwtUtils;
import com.wangguangyang.vo.LoginUser;
import com.wangguangyang.vo.LoginVO;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;


    @Override
    public void generateCode(String phone) {
        // 1. 校验手机号：1 开头，第二位 3~9，共 11 位数字
        String phoneRegex = "^1[3-9]\\d{9}$";
        if (phone == null || !phone.matches(phoneRegex)) {
            throw new BusinessException("手机号格式不正确");
        }

        // 2. 生成 6 位验证码（100000 ~ 999999）
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));

        // 3. 存入 Redis，5 分钟过期
        redisTemplate.opsForValue().set("code:" + phone, code, 5, TimeUnit.MINUTES);


        // 4. 把「手机号:验证码」发到 RabbitMQ 队列，异步发短信（解耦，接口立刻返回，不等短信真正发出）
        rabbitTemplate.convertAndSend(
                RabbitConfig.SMS_EXCHANGE,      // 交换机名 sms.exchange
                RabbitConfig.SMS_ROUTING_KEY,   // 路由键 sms
                phone + ":" + code,             // 消息体，形如 "13812345678:123456"
                message -> {                    // 发送前设置消息属性
                    // 消息 5 分钟过期，和 Redis 里验证码过期时间一致，防止消费者挂掉导致消息堆积
                    message.getMessageProperties().setExpiration("300000");
                    // 消息持久化：RabbitMQ 重启后消息不丢（要和队列 durable=true 一起用才完整）
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                new CorrelationData(phone)      // 消息唯一标识，用于 confirm 回调时区分是哪条消息
        );
    }


    @Override
    public LoginVO studentLogin(StudentLoginDTO dto) {
        // 1. 根据学号查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getStudentNo, dto.getStudentNo())
        );

        // 2. 用户不存在
        if (user == null) {
            throw new BusinessException("学号不存在");
        }

        // 3. 比对密码：BCrypt 的 matches(明文, 密文)，不是 equals（数据库存的是密文）
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("密码错误");
        }

        // 4. 生成 token，封装返回
        return buildLoginVO(user);
    }


    @Override
    public LoginVO phoneLogin(PhoneLoginDTO dto) {
        // 1. 从 Redis 查验证码（generateCode 时存的是 "code:" + phone）
        String redisCode = redisTemplate.opsForValue().get("code:" + dto.getPhone());

        // 2. 验证码不存在或已过期
        if (redisCode == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }

        // 3. 比对验证码
        if (!redisCode.equals(dto.getCode())) {
            throw new BusinessException("验证码错误");
        }

        // 4. 根据手机号查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone())
        );

        // 5. 用户不存在（该手机号没注册）
        if (user == null) {
            throw new BusinessException("该手机号未注册");
        }

        // 6. 验证码用后即删，防止同一个验证码被重复使用
        redisTemplate.delete("code:" + dto.getPhone());

        // 7. 生成 token，封装返回
        return buildLoginVO(user);
    }


    @Override
    public void register(RegisterDTO dto) {
        // 1. 校验学号格式：4位年份 + 30 + 4位任意数字（共10位），如 2024302803
        String studentNoRegex = "^\\d{4}30\\d{4}$";
        if (!StringUtils.hasText(dto.getStudentNo()) || !dto.getStudentNo().matches(studentNoRegex)) {
            throw new BusinessException("学号格式不正确，应为4位年份+30+4位数字，如2024302803");
        }
        String phoneNoRegex = "^1[3-9]\\d{9}$";

        if(!StringUtils.hasText(dto.getPhone())||!dto.getPhone().matches(phoneNoRegex)){
            throw new BusinessException("电话号码格式不对");
        }

        // 2. 校验必填字段（姓名、密码、身份证、性别、学院、专业、班级、入学年份）
        if (!StringUtils.hasText(dto.getName())) throw new BusinessException("姓名不能为空");
        if (!StringUtils.hasText(dto.getPassword())) throw new BusinessException("密码不能为空");
        if (!StringUtils.hasText(dto.getIdCard())) throw new BusinessException("身份证不能为空");
        if (dto.getGender() == null) throw new BusinessException("性别不能为空");
        if (!StringUtils.hasText(dto.getCollege())) throw new BusinessException("学院不能为空");
        if (!StringUtils.hasText(dto.getMajor())) throw new BusinessException("专业不能为空");
        if (!StringUtils.hasText(dto.getClassName())) throw new BusinessException("班级不能为空");
        if (dto.getEnrollmentYear() == null) throw new BusinessException("入学年份不能为空");

        // 3. 校验学号唯一（不能重复注册）
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStudentNo, dto.getStudentNo())
        );
        if (count != null && count > 0) {
            throw new BusinessException("该学号已注册");
        }

        // 4. 封装 User 对象（密码用 BCrypt 加密，绝不存明文）
        User user = new User();
        user.setStudentNo(dto.getStudentNo());
        user.setName(dto.getName());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setIdCard(dto.getIdCard());
        user.setGender(dto.getGender());
        user.setCollege(dto.getCollege());
        user.setMajor(dto.getMajor());
        user.setClassName(dto.getClassName());
        user.setEnrollmentYear(dto.getEnrollmentYear());
        user.setStatus(0);   // 默认状态：0=在校
        user.setPhone(dto.getPhone());

        // 5. 插入数据库
        userMapper.insert(user);
    }


    /**
     * 封装登录成功返回体：生成 token + 用户基本信息
     */
    private LoginVO buildLoginVO(User user) {
        String token = jwtUtils.generateToken(user);
        LoginUser loginUser = new LoginUser(user.getId(), user.getStudentNo(), user.getName());
        return new LoginVO(token, loginUser);
    }
}
