package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 课程 Mapper 接口
 *
 * 是什么：MyBatis-Plus 的数据访问接口，对应 course 表的 CRUD 操作。
 * 干什么：继承 BaseMapper<Course> 后，自动获得 insert / deleteById / updateById / selectById / selectList 等
 *         通用方法，不用写任何 SQL。
 * 为什么：和 UserMapper 一样，@Mapper 让 MyBatis 扫描注册这个接口。
 *
 * 说明：标准 CRUD 已由 BaseMapper 提供；需要绕开逻辑删除过滤的自定义查询（如编号唯一校验）加到这里，
 *       以后「抢课落库」用到的条件更新扣库存（incrSelectedCount）等自定义 SQL 也放这里。
 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * 统计指定课程编号的物理记录数（不过滤逻辑删除）
     *
     * 是什么：直接用原生 SQL 数 course 表里 course_no = ? 的行数。
     * 干什么：给「课程编号唯一校验」用，把已经逻辑删除的课程也一并算进去。
     * 为什么：@TableLogic 会让 selectCount 自动拼上 WHERE deleted=0，导致查不到已删除的课程；
     *         但唯一索引 uk_course_no 建在物理列上，已删除的课程依然占着编号。若校验漏掉它们，
     *         insert 会撞唯一索引抛 DuplicateKeyException，最后被全局异常处理器当成「系统繁忙」，
     *         所以这里必须绕过逻辑删除过滤、直接数物理行。
     *
     * @param courseNo  课程编号
     * @param excludeId 要排除的课程 id（修改课程时排除自身；新增课程时传 null）
     * @return 该编号对应的物理记录数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM course WHERE course_no = #{courseNo}" +
            "<if test='excludeId != null'> AND id != #{excludeId}</if>" +
            "</script>")
    Long countByCourseNoIgnoreDeleted(@Param("courseNo") String courseNo,
                                      @Param("excludeId") Long excludeId);

    /**
     * 条件更新：已选人数 +1（防超卖兜底）
     *
     * 是什么：抢课异步落库时，把课程已选人数加 1。
     * 干什么：消费者收到抢课消息后调用，把 MySQL 里的 selected_count 扣减 1。
     * 为什么用 WHERE selected_count < capacity 条件：
     *   - Redis 预扣是「第一道闸」，但消息重复/Redis 数据脏时可能已经超卖；
     *   - 这条 SQL 靠条件保证「已选人数永远不可能超过容量」，是数据库层的最终兜底；
     *   - 返回影响行数：1 表示扣减成功，0 表示已经满了（超卖），调用方据此回补 Redis。
     */
    @Update("UPDATE course SET selected_count = selected_count + 1 " +
            "WHERE id = #{id} AND selected_count < capacity")
    int incrSelectedCount(@Param("id") Long id);
}
