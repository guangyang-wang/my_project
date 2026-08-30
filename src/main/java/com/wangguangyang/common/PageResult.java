package com.wangguangyang.common;

import lombok.Data;

import java.util.List;

/**
 * 通用分页返回对象
 *
 * 是什么：把「一页数据 + 总条数」包成统一结构返回给前端。
 * 干什么：分页查询都要告诉前端两件事——当前页有哪些数据、一共有多少条（算总页数用）。
 * 为什么：不直接返回 List，是因为前端分页组件需要 total 才能算「共几页、能不能翻下一页」；
 *         抽成泛型类后，课程、用户等任何分页查询都能复用，不用每个都单独定义。
 */
@Data
public class PageResult<T> {

    /** 总记录数 */
    private long total;

    /** 当前页的数据列表 */
    private List<T> records;
}
