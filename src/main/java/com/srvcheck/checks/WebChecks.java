package com.srvcheck.checks;

import com.srvcheck.core.BaseCheck;
import com.srvcheck.core.Category;
import com.srvcheck.core.Check;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Severity;
import com.srvcheck.util.Probe;

import java.util.ArrayList;
import java.util.List;

/**
 * 网站与证书类检查（满分 10）。
 * 需要通过 --domain 参数提供域名；未提供时整体跳过。
 */
public final class WebChecks {
    private WebChecks() {}

    public static List<Check> all(String domain) {
        if (domain == null) {
            return List.of(new WebSkip("web_site_up", "网站可访问性"),
                    new WebSkip("ssl_expiry", "SSL 证书有效期"),
                    new WebSkip("tls_version", "TLS 加密版本"),
                    new WebSkip("http_headers", "HTTP 安全响应头"));
        }
        return List.of(new SiteUp(domain), new SslExpiry(domain),
                new TlsVersion(domain), new HttpHeaders(domain));
    }

    /** 未提供域名时的占位结果 */
    static class WebSkip extends BaseCheck {
        WebSkip(String key, String title) { super(key, title, Category.WEB, 1); }

        @Override protected CheckResult doRun() {
            return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未提供网站域名（--domain），跳过网站类检查。", "");
        }
    }

    // ---------- 网站可访问性 ----------
    static class SiteUp extends BaseCheck {
        private final String domain;
        SiteUp(String domain) { super("web_site_up", "网站可访问性", Category.WEB, 3); this.domain = domain; }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd(
                    "curl -s -o /dev/null -w '%{http_code}' -m 10 -L https://" + domain + " || curl -s -o /dev/null -w '%{http_code}' -m 10 -L http://" + domain);
            String code = out.stdout().trim();
            if (code.matches("(200|301|302|303|307|308)"))
                return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                        "网站可正常访问（HTTP " + code + "）。", "http_code=" + code);
            return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "网站访问异常（HTTP " + (code.isEmpty() ? "无响应" : code) + "），客户可能正在流失。",
                    "http_code=" + code,
                    "⚠️ 建议：排查网站服务状态，费用约 ¥300-800。");
        }
    }

    // ---------- SSL 证书有效期 ----------
    static class SslExpiry extends BaseCheck {
        private final String domain;
        SslExpiry(String domain) { super("ssl_expiry", "SSL 证书有效期", Category.WEB, 4); this.domain = domain; }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd(
                    "echo | openssl s_client -connect " + domain + ":443 -servername " + domain + " 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null");
            if (!out.stdout().contains("="))
                return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                        "无法获取证书信息（可能未启用 HTTPS）。", out.stdout());
            String date = out.stdout().split("=", 2)[1].trim();
            long days = daysUntil(date);
            if (days < 3) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "SSL 证书即将过期或已过期（剩 " + Math.max(days, 0) + " 天），浏览器会提示'不安全'，客户不敢下单。",
                    "notAfter=" + date,
                    "🔴 后果：证书过期后网站无法正常访问。续签证书费用约 ¥100-300。");
            if (days < 14) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "SSL 证书剩 " + days + " 天过期，建议尽快续签。",
                    "notAfter=" + date,
                    "⚠️ 建议：续签证书或配置自动续期，费用约 ¥100-300。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "SSL 证书有效（剩 " + days + " 天）。", "notAfter=" + date);
        }

        private long daysUntil(String opensslDate) {
            try {
                Probe.CmdOut s = Probe.cmd("date -d \"" + opensslDate + "\" +%s");
                Probe.CmdOut n = Probe.cmd("date +%s");
                return (Long.parseLong(s.stdout().trim()) - Long.parseLong(n.stdout().trim())) / 86400;
            } catch (Exception e) {
                return -999;
            }
        }
    }

    // ---------- TLS 版本 ----------
    static class TlsVersion extends BaseCheck {
        private final String domain;
        TlsVersion(String domain) { super("tls_version", "TLS 加密版本", Category.WEB, 2); this.domain = domain; }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd(
                    "echo | openssl s_client -connect " + domain + ":443 -servername " + domain + " 2>/dev/null | grep -m1 'Protocol'");
            String v = out.stdout().replace(":", "").replace("Protocol", "").trim();
            if (v.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法获取 TLS 版本。", "");
            if (v.contains("TLSv1") && !v.contains("TLSv1.2") && !v.contains("TLSv1.3"))
                return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                        "网站仍在使用已淘汰的旧版加密（" + v + "），可被中间人窃听。",
                        "protocol=" + v,
                        "🔴 后果：旧协议存在已知漏洞。升级 Nginx TLS 配置费用约 ¥200-500。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "TLS 版本安全（" + v + "）。", "protocol=" + v);
        }
    }

    // ---------- HTTP 安全响应头 ----------
    static class HttpHeaders extends BaseCheck {
        private final String domain;
        HttpHeaders(String domain) { super("http_headers", "HTTP 安全响应头", Category.WEB, 1); this.domain = domain; }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd("curl -sI -m 10 https://" + domain + " 2>/dev/null");
            List<String> missing = new ArrayList<>();
            if (!out.stdout().toLowerCase().contains("strict-transport-security")) missing.add("HSTS");
            if (!out.stdout().toLowerCase().contains("x-frame-options")) missing.add("X-Frame-Options");
            if (missing.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "关键安全响应头已配置。", out.stdout());
            return CheckResult.of(key(), title(), category(), Severity.INFO, weight(),
                    "缺少安全响应头：" + missing + "（低风险，建议补齐）。",
                    "缺失: " + missing,
                    "ℹ️ 建议：Nginx 增加安全响应头，费用约 ¥100-300。");
        }
    }
}
