-- =====================================================================
-- 选课相关建表语句：course（课程信息表）/ time_slot（时间片维度表）/ course_time（课程-时间片关联表）/ enrollment（选课记录表）
-- 数据库：my_project（对应 application.yml 的 jdbc:mysql://localhost:3306/my_project）
-- 执行方式：整个文件在 MySQL 客户端 / Navicat / IDEA 里直接运行即可
-- 说明：
--   1. user 表已存在（登录/注册功能已建），本文件不重复建，enrollment 只做逻辑关联不建外键；
--   2. 全部用 CREATE TABLE IF NOT EXISTS，可重复执行不报错（但不会更新已有表结构）；
--   3. 顺序：先建 course 和 time_slot（维度表），再建关联表 course_time / enrollment。
-- =====================================================================

USE `my_project`;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 课程信息表 course
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `course` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `course_no`         VARCHAR(32)  NOT NULL COMMENT '课程编号（业务唯一，如 CS101），选课/展示用',
    `course_name`       VARCHAR(128) NOT NULL COMMENT '课程名称',
    `category`          TINYINT      NOT NULL DEFAULT 1 COMMENT '课程类别：1=必修 2=选修 3=公选 4=实践',
    `credit`            DECIMAL(3,1) NOT NULL COMMENT '学分（如 3.0、2.5，支持半学分）',
    `hours`             INT          NOT NULL COMMENT '总学时',
    `teacher_name`      VARCHAR(64)  NOT NULL COMMENT '任课教师姓名（冗余展示；正规做法是 teacher_id 关联教师表）',
    `college`           VARCHAR(64)  NOT NULL COMMENT '开课院系/学院',
    `major`             VARCHAR(64)  DEFAULT NULL COMMENT '开课专业（NULL=不限专业）',
    `campus`            VARCHAR(64)  DEFAULT NULL COMMENT '校区（可空）',
    `exam_type`         VARCHAR(32)  DEFAULT NULL COMMENT '考试形式：考试/考查/论文/机考',
    `language`          VARCHAR(32)  DEFAULT NULL COMMENT '授课语言：中文/英文/双语',
    `term`              VARCHAR(32)  NOT NULL COMMENT '开课学期，如 2026-2027-1',
    `capacity`          INT          NOT NULL COMMENT '选课人数上限（= 总库存，抢课核心字段）',
    `selected_count`    INT          NOT NULL DEFAULT 0 COMMENT '已选人数（当前库存，异步落库时 +1）',
    `select_start_time` DATETIME     DEFAULT NULL COMMENT '选课开始时间（时间窗）',
    `select_end_time`   DATETIME     DEFAULT NULL COMMENT '选课结束时间（时间窗）',
    `restrict_grade`    VARCHAR(255) DEFAULT NULL COMMENT '限选年级（逗号分隔，如 2024,2025；NULL=不限）',
    `restrict_major`    VARCHAR(255) DEFAULT NULL COMMENT '限选专业（逗号分隔；NULL=不限）',
    `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=未开放 1=可选 2=已满 3=已结束 4=下架',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删 1=已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_no` (`course_no`),
    KEY `idx_term_status` (`term`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程信息表';

-- ---------------------------------------------------------------------
-- 2. 时间片维度表 time_slot（预置 18周 × 7天 × 6节 = 756 条，全局复用）
--    为什么这样设计：大学的排课时间是「离散且固定」的（每周几第几节），
--    把每个时间片抽象成一条独立记录，课程通过关联表「占用」某些时间片，
--    时间冲突的比对就从「区间重叠计算」简化成「查是否有相同时间片」。
--    顺带消灭了 week_type（单双周）：单周只占奇数周的时间片，双周只占偶数周。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `time_slot` (
    `id`      BIGINT  NOT NULL AUTO_INCREMENT COMMENT '时间片ID（全局唯一，多门课复用）',
    `week`    TINYINT NOT NULL COMMENT '第几周：1~18',
    `weekday` TINYINT NOT NULL COMMENT '星期几：1=周一 … 7=周日',
    `section` TINYINT NOT NULL COMMENT '第几节：1~6',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_week_weekday_section` (`week`, `weekday`, `section`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='时间片维度表（18周×7天×6节=756条）';

-- 说明：756 条时间片数据的插入语句见 sql/seed_time_slot.sql（交叉连接生成，可重复执行）。

-- ---------------------------------------------------------------------
-- 3. 课程-时间片关联表 course_time（多对多：一门课占用多个时间片）
--    为什么需要这张表：time_slot 去掉了 course_id 后，课程和它占用哪些时间片的
--    对应关系必须有地方存，就落在这张关联表里。
--    classroom 放这里（而非 time_slot）：同一个时间片可能被多门课占用，
--    但教室是「某门课 + 某个时间片」这个组合的属性。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `course_time` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `course_id`    BIGINT      NOT NULL COMMENT '课程ID（关联 course.id）',
    `time_slot_id` BIGINT      NOT NULL COMMENT '时间片ID（关联 time_slot.id）',
    `classroom`    VARCHAR(64) DEFAULT NULL COMMENT '上课教室（属于"课程+时间片"组合）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_slot` (`course_id`, `time_slot_id`),
    UNIQUE KEY `uk_time_classroom` (`time_slot_id`, `classroom`),
    KEY `idx_time_slot_id` (`time_slot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程-时间片关联表（多对多）';

-- ---------------------------------------------------------------------
-- 4. 选课记录表 enrollment
--    ★ uk_student_course(student_id, course_id) 是「一人一课」的最终兜底，
--      必须建：无论并发多乱、MQ 重试多少次，数据库里都不会出现重复选课记录。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `enrollment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`  BIGINT       NOT NULL COMMENT '学生ID（逻辑关联 user.id，不建外键）',
    `student_no`  VARCHAR(32)  NOT NULL COMMENT '学号（冗余，便于查询）',
    `course_id`   BIGINT       NOT NULL COMMENT '课程ID（逻辑关联 course.id）',
    `course_no`   VARCHAR(32)  NOT NULL COMMENT '课程编号（冗余）',
    `course_name` VARCHAR(128) NOT NULL COMMENT '课程名称（快照，选课时固化）',
    `credit`      DECIMAL(3,1) NOT NULL COMMENT '学分（快照，选课时的学分）',
    `term`        VARCHAR(32)  NOT NULL COMMENT '学期',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=已选 1=已退课 2=已完成 3=已取消(抢课失败回滚)',
    `select_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '选课时间',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删 1=已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_course` (`student_id`, `course_id`),  -- ★ 一人一课的唯一约束（最终兜底）
    KEY `idx_student_term` (`student_id`, `term`),
    KEY `idx_course_id` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选课记录表';
