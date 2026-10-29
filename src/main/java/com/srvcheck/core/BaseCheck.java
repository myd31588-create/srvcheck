package com.srvcheck.core;

/**
 * 检查项基类：统一处理非 Linux 环境降级与运行时异常兜底。
 * 原则：体检仪只读探测，任何失败都不能崩溃，也不能在目标机器上留下痕迹。
 */
public abstract class BaseCheck implements Check {
    private final String key;
    private final String title;
    private final Category category;
    private final int weight;

    protected BaseCheck(String key, String title, Category category, int weight) {
        this.key = key;
        this.title = title;
        this.category = category;
        this.weight = weight;
    }

    @Override public String key() { return key; }
    @Override public String title() { return title; }
    @Override public Category category() { return category; }
    @Override public int weight() { return weight; }

    @Override
    public final CheckResult run() {
        if (!com.srvcheck.util.Probe.isLinux()) {
            return CheckResult.of(key, title, category, Severity.SKIP, weight,
                    "当前环境非 Linux，此项在 Linux 服务器上运行时才会检测。",
                    "os=" + System.getProperty("os.name"));
        }
        try {
            return doRun();
        } catch (Exception e) {
            return CheckResult.of(key, title, category, Severity.SKIP, weight,
                    "该项未能完成检测，不影响其他项。", "exception=" + e.getClass().getSimpleName());
        }
    }

    protected abstract CheckResult doRun();
}
