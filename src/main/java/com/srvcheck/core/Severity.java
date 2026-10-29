package com.srvcheck.core;

/** 检查项风险等级 */
public enum Severity {
    PASS("✅ 正常"),
    INFO("ℹ️ 提示"),
    WARN("⚠️ 建议整改"),
    CRIT("🔴 高危"),
    SKIP("➖ 未检测");

    public final String label;

    Severity(String label) { this.label = label; }

    /** 该等级下的得分系数 */
    public double scoreFactor() {
        return switch (this) {
            case PASS, INFO, SKIP -> 1.0;
            case WARN -> 0.5;
            case CRIT -> 0.0;
        };
    }

    public boolean isRisk() { return this == WARN || this == CRIT; }
}
