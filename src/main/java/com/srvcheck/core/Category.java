package com.srvcheck.core;

/** 检查分类及其在总分中的权重 */
public enum Category {
    SYSTEM("系统健康", 30),
    SECURITY("安全配置", 40),
    DATA("数据安全", 20),
    WEB("网站与证书", 10);

    public final String label;
    public final int maxScore;

    Category(String label, int maxScore) {
        this.label = label;
        this.maxScore = maxScore;
    }
}
