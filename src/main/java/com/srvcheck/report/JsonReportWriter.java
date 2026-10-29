package com.srvcheck.report;

import com.srvcheck.core.Category;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Report;

import java.util.Map;

/** 简易 JSON 输出（零依赖），供自动化流水线对接 */
public final class JsonReportWriter {
    private JsonReportWriter() {}

    public static String render(Report report) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("{\n  \"hostname\": \"").append(esc(report.hostname())).append("\",\n");
        sb.append("  \"time\": \"").append(report.timeText()).append("\",\n");
        sb.append("  \"total_score\": ").append(report.totalScore()).append(",\n");
        sb.append("  \"crit_count\": ").append(report.count(com.srvcheck.core.Severity.CRIT)).append(",\n");
        sb.append("  \"warn_count\": ").append(report.count(com.srvcheck.core.Severity.WARN)).append(",\n");
        sb.append("  \"category_scores\": {");
        Map<Category, Double> scores = report.categoryScores();
        boolean first = true;
        for (Category c : Category.values()) {
            if (!first) sb.append(", ");
            sb.append('"').append(c.name()).append("\": ").append(scores.getOrDefault(c, 0.0));
            first = false;
        }
        sb.append("},\n  \"results\": [\n");
        boolean f = true;
        for (CheckResult r : report.results()) {
            if (!f) sb.append(",\n");
            sb.append("    {\"key\": \"").append(esc(r.key()))
              .append("\", \"category\": \"").append(r.category().name())
              .append("\", \"severity\": \"").append(r.severity().name())
              .append("\", \"message\": \"").append(esc(r.message()))
              .append("\"}");
            f = false;
        }
        sb.append("\n  ]\n}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
