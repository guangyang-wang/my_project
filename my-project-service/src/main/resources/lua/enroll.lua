-- 抢课 Lua 脚本：原子完成「库存 / 时间冲突 / 学分」三项判断 + 扣减
--
-- 是什么：一次抢课的核心判断逻辑，跑在 Redis 里，整个脚本是原子的。
-- 为什么必须用 Lua：判断（库存够不够 / 时间冲不冲突 / 学分超不超）和扣减（减库存 / 加学分 / 占时间片）
--   是多个 Redis 命令，如果拆开发，并发下两个请求会同时读到「够」，都扣，就超卖了。
--   Redis 单线程执行 Lua，脚本内所有命令按顺序一次性执行完，中间不会被别的请求插进来，天然原子。
--
-- KEYS[1] = stock:{courseId}         课程剩余名额（String，数字字符串）
-- KEYS[2] = stu:credit:{studentId}   学生已选学分 ×10（String，数字字符串，整数）
-- KEYS[3] = stu:times:{studentId}    学生已占用的时间片 id（Set）
-- KEYS[4] = course:times:{courseId}  这门课占用的时间片 id（Set）
-- ARGV[1] = 这门课学分 ×10（数字字符串）
-- ARGV[2] = 学分上限 ×10（= 300）
-- 返回值：1=成功  -1=售罄  -2=时间冲突  -3=学分超限

-- 1. 库存判断：名额不足直接返回 -1（售罄）
local stock = tonumber(redis.call('get', KEYS[1]))
if not stock or stock < 1 then
    return -1
end

-- 2. 时间冲突判断：课程时间片与学生已占时间片有没有交集
--    用 SMEMBERS 取出课程时间片，逐个 SISMEMBER 判断学生是否已占用；命中任一个即冲突。
local courseTimes = redis.call('smembers', KEYS[4])
for _, t in ipairs(courseTimes) do
    if redis.call('sismember', KEYS[3], t) == 1 then
        return -2
    end
end

-- 3. 学分上限判断：已选学分 + 这门课学分 > 上限则返回 -3
--    学分统一 ×10 存整数，避免浮点比较精度问题（3.0 + 2.5 这类小数）。
local credit = tonumber(redis.call('get', KEYS[2]) or '0')
if credit + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
    return -3
end

-- 4. 全部通过：扣库存、加学分、把课程时间片写进学生已占时间片集合
redis.call('decr', KEYS[1])
redis.call('incrby', KEYS[2], ARGV[1])
for _, t in ipairs(courseTimes) do
    redis.call('sadd', KEYS[3], t)
end
return 1
