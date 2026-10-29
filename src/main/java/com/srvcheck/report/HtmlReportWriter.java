package com.srvcheck.report;

import com.srvcheck.core.Category;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Report;
import com.srvcheck.core.Severity;

import java.util.List;
import java.util.Map;

/**
 * 单文件 HTML 报告：内联 CSS，无外部依赖，微信可直接发送、离线可打开。
 * 排版原则：老板 10 秒看懂总评，技术人可展开看全部明细。
 */
public final class HtmlReportWriter {
    private HtmlReportWriter() {}

    public static String render(Report report) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        double total = report.totalScore();
        long crit = report.count(Severity.CRIT);
        long warn = report.count(Severity.WARN);
        String scoreColor = total >= 80 ? "#16a34a" : total >= 60 ? "#d97706" : "#dc2626";

        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>服务器体检报告 - ").append(esc(report.hostname())).append("</title>")
          .append(style()).append("</head><body><div class=\"wrap\">");

        // ===== 报告头 =====
        sb.append("<div class=\"header\"><div class=\"brand\">🩺 服务器体检仪</div>")
          .append("<div class=\"meta\">服务器：").append(esc(report.hostname()))
          .append("　|　体检时间：").append(report.timeText())
          .append("　|　只读探测，未修改任何系统配置</div></div>");

        // ===== 总评卡 =====
        sb.append("<div class=\"score-card\"><div class=\"score\" style=\"color:").append(scoreColor).append("\">")
          .append(String.format("%.1f", total)).append("<span class=\"unit\">/100</span></div>")
          .append("<div class=\"verdict\"><p class=\"v-main\">").append(esc(report.verdict())).append("</p>")
          .append("<p class=\"v-sub\">🔴 高危 <b>").append(crit).append("</b> 项　⚠️ 建议整改 <b>").append(warn)
          .append("</b> 项　✅ 正常 <b>").append(report.count(Severity.PASS)).append("</b> 项</p></div></div>");

        // ===== 分类得分条 =====
        Map<Category, Double> scores = report.categoryScores();
        sb.append("<div class=\"cats\">");
        for (Category c : Category.values()) {
            double s = scores.getOrDefault(c, 0.0);
            double pct = Math.max(2, s / c.maxScore * 100);
            String color = s >= c.maxScore * 0.8 ? "#16a34a" : s >= c.maxScore * 0.6 ? "#d97706" : "#dc2626";
            sb.append("<div class=\"cat\"><div class=\"cat-label\">").append(c.label)
              .append("</div><svg viewBox=\"0 0 200 14\" preserveAspectRatio=\"none\" class=\"bar\">")
              .append("<rect x=\"0\" y=\"0\" width=\"200\" height=\"14\" rx=\"7\" fill=\"#e5e7eb\"/>")
              .append("<rect x=\"0\" y=\"0\" width=\"").append(String.format("%.1f", pct * 2))
              .append("\" height=\"14\" rx=\"7\" fill=\"").append(color).append("\"/></svg>")
              .append("<div class=\"cat-score\">").append(String.format("%.0f", s)).append("/").append(c.maxScore).append("</div></div>");
        }
        sb.append("</div>");

        // ===== 高危/警告问题卡 =====
        List<CheckResult> risks = report.results().stream().filter(r -> r.severity().isRisk()).toList();
        if (!risks.isEmpty()) {
            sb.append("<h2>发现的问题（按严重程度排序）</h2><div class=\"risks\">");
            for (CheckResult r : risks) {
                boolean isCrit = r.severity() == Severity.CRIT;
                sb.append("<div class=\"risk ").append(isCrit ? "crit" : "warn").append("\">")
                  .append("<div class=\"risk-title\">").append(r.severity().label).append("　").append(esc(r.title()))
                  .append("</div><div class=\"risk-msg\">").append(esc(r.message())).append("</div>");
                if (r.hint() != null) sb.append("<div class=\"risk-hint\">").append(esc(r.hint())).append("</div>");
                sb.append("</div>");
            }
            sb.append("</div>");
        } else {
            sb.append("<div class=\"all-good\">🎉 未发现高危或警告项。</div>");
        }

        // ===== 完整明细（可折叠） =====
        sb.append("<h2>完整检查明细</h2>");
        for (Category c : Category.values()) {
            List<CheckResult> items = report.results().stream().filter(r -> r.category() == c).toList();
            if (items.isEmpty()) continue;
            sb.append("<details><summary>").append(c.label).append("（")
              .append(String.format("%.0f", scores.getOrDefault(c, 0.0))).append("/").append(c.maxScore).append("）</summary><table>");
            sb.append("<tr><th>检查项</th><th>结果</th><th>说明</th></tr>");
            for (CheckResult r : items) {
                sb.append("<tr><td>").append(esc(r.title()))
                  .append("</td><td class=\"sev ").append(r.severity().name()).append("\">")
                  .append(r.severity().label)
                  .append("</td><td>").append(esc(r.message()));
                if (r.detail() != null && !r.detail().isBlank())
                    sb.append("<div class=\"detail\">").append(esc(truncate(r.detail(), 300))).append("</div>");
                sb.append("</td></tr>");
            }
            sb.append("</table></details>");
        }

        // ===== 服务入口（获客钩子） =====
        sb.append("<div class=\"cta\"><div class=\"cta-title\">需要专业工程师帮您修复？</div>")
          .append("<div class=\"cta-body\">我们提供：服务器维修加固（按次）／月度托管体检（每月出一份本报告）<br>")
          .append(esc(report.contact() == null || report.contact().isBlank()
                  ? "联系方式：（在启动命令中通过 --contact 配置）" : report.contact()))
          .append("</div></div>");

        sb.append("<div class=\"footer\">本报告由服务器体检仪生成 · 仅做只读探测，未修改任何配置、未上传任何数据</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String style() {
        return """
                <style>
                *{box-sizing:border-box;margin:0;padding:0}
                body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f3f4f6;color:#111827;line-height:1.6}
                .wrap{max-width:760px;margin:0 auto;padding:16px}
                .header{padding:8px 4px}
                .brand{font-size:20px;font-weight:700}
                .meta{font-size:12px;color:#6b7280;margin-top:4px}
                .score-card{background:#fff;border-radius:14px;padding:24px;display:flex;align-items:center;gap:24px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}
                .score{font-size:56px;font-weight:800;line-height:1}
                .score .unit{font-size:18px;color:#9ca3af;font-weight:600}
                .verdict .v-main{font-size:16px;font-weight:600}
                .verdict .v-sub{font-size:13px;color:#6b7280;margin-top:6px}
                .cats{background:#fff;border-radius:14px;padding:16px 24px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}
                .cat{display:flex;align-items:center;gap:12px;margin:10px 0}
                .cat-label{width:88px;font-size:13px;font-weight:600}
                .bar{flex:1;height:14px}
                .cat-score{width:56px;text-align:right;font-size:13px;color:#374151}
                h2{font-size:16px;margin:20px 4px 10px}
                .risks .risk{background:#fff;border-radius:12px;padding:14px 18px;margin:10px 0;box-shadow:0 1px 3px rgba(0,0,0,.08);border-left:4px solid #d97706}
                .risk.crit{border-left-color:#dc2626}
                .risk-title{font-weight:700;font-size:14px}
                .risk-msg{font-size:13px;color:#374151;margin-top:4px}
                .risk-hint{font-size:13px;margin-top:6px;padding:8px 10px;background:#fef3c7;border-radius:8px}
                .risk.crit .risk-hint{background:#fee2e2}
                .all-good{background:#ecfdf5;border:1px solid #a7f3d0;border-radius:12px;padding:14px 18px;margin:10px 0;font-size:14px}
                details{background:#fff;border-radius:12px;margin:10px 0;padding:12px 18px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
                summary{cursor:pointer;font-weight:600;font-size:14px}
                table{width:100%;border-collapse:collapse;margin-top:10px;font-size:13px}
                th{text-align:left;color:#6b7280;padding:6px 8px;border-bottom:1px solid #e5e7eb}
                td{padding:8px;border-bottom:1px solid #f3f4f6;vertical-align:top}
                .sev{white-space:nowrap;font-weight:600}
                .sev.CRIT{color:#dc2626}.sev.WARN{color:#d97706}.sev.PASS{color:#16a34a}.sev.SKIP{color:#9ca3af}.sev.INFO{color:#2563eb}
                .detail{margin-top:4px;padding:6px 8px;background:#f9fafb;border-radius:6px;font-family:monospace;font-size:11px;color:#6b7280;word-break:break-all}
                .cta{background:#1d4ed8;color:#fff;border-radius:14px;padding:18px 24px;margin:20px 0}
                .cta-title{font-size:16px;font-weight:700}
                .cta-body{font-size:13px;margin-top:8px;opacity:.92}
                .footer{text-align:center;font-size:11px;color:#9ca3af;padding:16px 0}
                @media print{.wrap{max-width:none}}
                </style>""";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
