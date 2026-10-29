package com.srvcheck.core;

/**
 * 单个检查项的执行结果。
 * @param key      检查项唯一标识
 * @param title    中文标题
 * @param category 所属分类
 * @param severity 风险等级
 * @param weight   该项在本分类内的相对权重（分类内归一化后换算为分数）
 * @param message  给老板看的一句话结论（大白话）
 * @param detail   给技术人看的明细（命令输出/配置值等）
 * @param hint     高危/警告项的后果与修复费用提示（翻译成钱）
 */
public record CheckResult(
        String key,
        String title,
        Category category,
        Severity severity,
        int weight,
        String message,
        String detail,
        String hint) {

    public static CheckResult of(String key, String title, Category category, Severity severity,
                                 int weight, String message, String detail) {
        return new CheckResult(key, title, category, severity, weight, message, detail, null);
    }

    /** 带"翻译成钱"提示的结果（用于高危/警告项） */
    public static CheckResult of(String key, String title, Category category, Severity severity,
                                 int weight, String message, String detail, String hint) {
        return new CheckResult(key, title, category, severity, weight, message, detail, hint);
    }
}
