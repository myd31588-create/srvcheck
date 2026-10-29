package com.srvcheck.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 汇总所有检查结果并计算评分 */
public class Report {
    private final String hostname;
    private final LocalDateTime time;
    private final List<CheckResult> results = new ArrayList<>();
    private final String contact;

    public Report(String hostname, String contact) {
        this.hostname = hostname;
        this.contact = contact;
        this.time = LocalDateTime.now();
    }

    public void add(CheckResult r) { results.add(r); }

    public List<CheckResult> results() { return results; }

    public String hostname() { return hostname; }

    public String contact() { return contact; }

    public String timeText() {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 分类得分：分类内按权重归一化后换算为该分类满分制的分数；SKIP 项不计入分母 */
    public Map<Category, Double> categoryScores() {
        Map<Category, Double> scores = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            double earned = 0, total = 0;
            for (CheckResult r : results) {
                if (r.category() != c || r.severity() == Severity.SKIP) continue;
                total += r.weight();
                earned += r.weight() * r.severity().scoreFactor();
            }
            scores.put(c, total == 0 ? 0.0 : Math.round(earned / total * c.maxScore * 10) / 10.0);
        }
        return scores;
    }

    /** 总分（四类满分求和 100，保留 1 位小数） */
    public double totalScore() {
        return Math.round(categoryScores().values().stream().mapToDouble(Double::doubleValue).sum() * 10) / 10.0;
    }

    public long count(Severity s) { return results.stream().filter(r -> r.severity() == s).count(); }

    /** 一句话总评 */
    public String verdict() {
        boolean allSkipped = results.stream().allMatch(r -> r.severity() == Severity.SKIP);
        if (allSkipped) {
            return "本机不是 Linux 服务器，所有检查项未执行。请将体检仪放到目标 Linux 服务器上运行。";
        }
        long crit = count(Severity.CRIT);
        long warn = count(Severity.WARN);
        double score = totalScore();
        if (crit > 0) {
            return String.format("您的服务器存在 %d 个高危问题，可能造成数据泄露、勒索或业务中断，建议尽快处理。", crit);
        } else if (warn > 0) {
            return String.format("服务器整体可用，但存在 %d 个建议整改项，处理后稳定性与安全性会明显提升。", warn);
        } else if (score >= 90) {
            return "服务器健康状况良好，继续保持。";
        } else {
            return "服务器基本健康，有少量可优化项。";
        }
    }
}
