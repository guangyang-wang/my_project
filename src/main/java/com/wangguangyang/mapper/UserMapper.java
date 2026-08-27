package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 学生用户 Mapper 接口
 *
 * 是什么：MyBatis-Plus 的数据访问接口，对应 user 表的 CRUD 操作。
 * 干什么：继承 BaseMapper<User> 后，自动获得 insert / deleteById / updateById / selectById / selectList 等
 *         通用方法，不用写任何 SQL 和 XML。
 * 为什么：BaseMapper 是 MyBatis-Plus 的核心，泛型指定实体类型，内部用反射 + 驼峰映射自动生成 SQL。
 *
 * 说明：
 *   - @Mapper：让 MyBatis 扫描并注册这个接口(也可在启动类用 @MapperScan 批量扫，二选一)。
 *   - 标准 CRUD 已由 BaseMapper 提供，这里先留空；
 *     以后要自定义查询(如"按学号查")再往这里加方法 + XML 或注解。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("select * from user where name=name")
    User getByNameUser(String name);

}
