package com.wangguangyang.service;

import com.wangguangyang.dto.PhoneLoginDTO;
import com.wangguangyang.dto.RegisterDTO;
import com.wangguangyang.dto.StudentLoginDTO;
import com.wangguangyang.vo.LoginVO;

public interface UserService {

    /** 生成验证码 */
    void generateCode(String phone);

    /** 学号密码登录，成功返回 token + 用户信息 */
    LoginVO studentLogin(StudentLoginDTO dto);

    /** 手机号验证码登录，成功返回 token + 用户信息 */
    LoginVO phoneLogin(PhoneLoginDTO dto);

    /** 注册 */
    void register(RegisterDTO dto);
}
