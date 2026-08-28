-- =====================================================================
-- time_slot 时间片维度表初始化脚本
-- 生成 18周 × 7天 × 6节 = 756 条时间片数据
-- 执行方式：整个文件在 MySQL 客户端 / Navicat / IDEA 里直接运行即可
-- 说明：用 INSERT IGNORE 可重复执行，已存在的数据会自动跳过（不会报唯一键冲突）
-- =====================================================================

USE `my_project`;

INSERT IGNORE INTO `time_slot` (`week`, `weekday`, `section`)
SELECT w.n, d.n, s.n
FROM (
    SELECT 1 n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6
    UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION SELECT 11 UNION SELECT 12
    UNION SELECT 13 UNION SELECT 14 UNION SELECT 15 UNION SELECT 16 UNION SELECT 17 UNION SELECT 18
) w
CROSS JOIN (
    SELECT 1 n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7
) d
CROSS JOIN (
    SELECT 1 n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6
) s
ORDER BY w.n, d.n, s.n;

-- 验证：应返回 756
SELECT COUNT(*) AS total FROM `time_slot`;
