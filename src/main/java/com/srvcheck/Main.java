package com.srvcheck;

import com.srvcheck.core.BaseCheck;
import com.srvcheck.core.Check;
import com.srvcheck.core.CheckRegistry;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Report;
import com.srvcheck.core.Severity;
import com.srvcheck.report.HtmlReportWriter;
import com.srvcheck.report.JsonReportWriter;
import com.srvcheck.util.Probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 服务器体检仪 CLI 入口。
 *
 * 用法：
 *   java -jar srvcheck.jar                          # 默认输出 srvcheck-report.html
 *   java -jar srvcheck.jar --html report.html       # 指定 HTML 报告路径
 *   java -jar srvcheck.jar --json report.json       # 同时输出 JSON
 *   java -jar srvcheck.jar --domain example.com     # 启用网站与证书检查
 *   java -jar srvcheck.jar --contact "微信: xxx"     # 报告尾部的服务入口
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // 统一 UTF-8 输出，避免非 UTF-8 控制台下的乱码
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        String htmlOut = "srvcheck-report.html";
        String jsonOut = null;
        String domain = null;
        String contact = "";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--html" -> htmlOut = args[++i];
                case "--json" -> jsonOut = args[++i];
                case "--domain" -> domain = args[++i];
                case "--contact" -> contact = args[++i];
                case "--help", "-h" -> { printHelp(); return; }
                default -> { System.err.println("未知参数: " + args[i]); printHelp(); System.exit(2); }
            }
        }

        String hostname = Probe.cmd("hostname").stdout().trim();
        if (hostname.isEmpty()) {
            try { hostname = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) { hostname = "unknown-host"; }
        }

        System.out.println("🩺 服务器体检仪 v0.1.0");
        System.out.println("   目标主机: " + hostname + (domain != null ? "（网站: " + domain + "）" : ""));
        System.out.println("   模式: 只读探测，不修改任何系统配置\n");

        Report report = new Report(hostname, contact);
        int done = 0;
        var checks = CheckRegistry.all(domain);
        for (Check c : checks) {
            CheckResult r = c.run();
            report.add(r);
            done++;
            System.out.printf("  [%2d/%d] %s %s%n", done, checks.size(), icon(r.severity()), r.title());
        }

        // ===== 终端摘要 =====
        System.out.println("\n" + "=".repeat(56));
        System.out.printf("体检总分: %.1f / 100%n", report.totalScore());
        System.out.println("总评: " + report.verdict());
        var risks = report.results().stream()
                .filter(r -> r.severity().isRisk())
                .sorted(Comparator.comparing(r -> r.severity() == Severity.CRIT ? 0 : 1))
                .toList();
        if (!risks.isEmpty()) {
            System.out.println("\n需要关注的问题:");
            for (CheckResult r : risks) {
                System.out.println("  " + icon(r.severity()) + " " + r.title() + " —— " + r.message());
                if (r.hint() != null) System.out.println("      " + r.hint());
            }
        }

        // ===== 写报告 =====
        Files.writeString(Path.of(htmlOut), HtmlReportWriter.render(report));
        System.out.println("\n📄 HTML 报告: " + Path.of(htmlOut).toAbsolutePath());
        if (jsonOut != null) {
            Files.writeString(Path.of(jsonOut), JsonReportWriter.render(report));
            System.out.println("📄 JSON 报告: " + Path.of(jsonOut).toAbsolutePath());
        }
        System.out.println("提示: 报告仅保存在本机，可直接微信发送给负责人。");
    }

    private static String icon(Severity s) {
        return switch (s) {
            case PASS -> "✅";
            case INFO -> "ℹ️ ";
            case WARN -> "⚠️ ";
            case CRIT -> "🔴";
            case SKIP -> "➖";
        };
    }

    private static void printHelp() {
        System.out.println("""
                服务器体检仪 —— 一条命令体检你的云服务器

                用法: java -jar srvcheck.jar [选项]
                  --html <文件>    HTML 报告输出路径（默认 srvcheck-report.html）
                  --json <文件>    额外输出 JSON 报告
                  --domain <域名>  启用网站与 SSL 证书检查
                  --contact <文案> 报告尾部的联系方式（服务入口）
                  -h               显示帮助

                本工具只读探测，不修改任何系统配置，不上传任何数据。""");
    }
}
