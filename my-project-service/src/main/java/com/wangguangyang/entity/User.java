package com.wangguangyang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 学生用户实体类
 *
 * 是什么：对应数据库 user 表，是「表的一行 ↔ Java 对象」之间的映射载体。
 * 干什么：MyBatis-Plus 查出的每一行数据都会被装进这个对象，业务代码拿到的就是 User 对象而非散列字段。
 * 为什么：MyBatis-Plus 是「实体类 + Mapper(继承 BaseMapper)」两件套，实体类是映射的载体。
 *
 * 字段对应规则：
 *   - 命名：数据库下划线(student_no) → Java 驼峰(studentNo)，MyBatis-Plus 默认开启驼峰映射
 *   - 类型：BIGINT→Long、VARCHAR→String、TINYINT→Integer、DATETIME→LocalDateTime
 *
 * 注解说明：
 *   - @Data / @NoArgsConstructor / @AllArgsConstructor：Lombok 生成 getter/setter/构造器
 *   - @TableName("`user`")：表名映射。注意 user 是 MySQL 8 保留字，必须加反引号，否则生成的 SQL 会语法报错
 *   - @TableId(type = IdType.AUTO)：主键，数据库自增
 *   - @TableLogic：逻辑删除。加了它，deleteById 会自动变成 UPDATE deleted=1，
 *                   查询/更新会自动带上 WHERE deleted=0，不用手动写
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("`user`")
public class User {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学号 */
    private String studentNo;

    /** 姓名 */
    private String name;

    /** 性别：0=未知 1=男 2=女 */
    private Integer gender;

    /** 身份证号(18位，末尾可能是X) */
    private String idCard;

    /** 手机号 */
    private String phone;

    /** 邮箱(可空) */
    private String email;

    /** 密码(BCrypt密文，不存明文) */
    private String password;

    /** 院系 */
    private String college;

    /** 专业 */
    private String major;

    /** 班级 */
    private String className;

    /** 入学年份 */
    private Integer enrollmentYear;

    /** 状态：0=在校 1=毕业 2=休学 3=退学 */
    private Integer status;

    /** 创建人ID */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 修改人ID */
    private Long updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0=未删 1=已删（@TableLogic 自动处理） */
    @TableLogic
    private Integer deleted;
}
