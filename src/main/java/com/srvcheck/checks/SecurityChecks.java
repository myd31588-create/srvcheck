package com.srvcheck.checks;

import com.srvcheck.core.BaseCheck;
import com.srvcheck.core.Category;
import com.srvcheck.core.Check;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Severity;
import com.srvcheck.util.Probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 安全配置类检查（满分 40） */
public final class SecurityChecks {
    private SecurityChecks() {}

    public static List<Check> all() {
        return List.of(new SshRootLogin(), new SshPasswordAuth(), new SshPort(), new Firewall(),
                new Fail2ban(), new OpenPorts(), new UidZero(), new EmptyPassword());
    }

    /** 读取 sshd 生效配置：优先 sshd -T，失败则解析 /etc/ssh/sshd_config */
    private static String sshdConfig(String field) {
        Probe.CmdOut t = Probe.cmd("sshd -T 2>/dev/null | grep -i '^" + field + " '");
        if (t.ok() && !t.stdout().isBlank()) return t.stdout().trim().toLowerCase();
        String conf = Probe.file("/etc/ssh/sshd_config");
        if (conf != null) {
            for (String line : conf.split("\n")) {
                String l = line.trim();
                if (l.toLowerCase().startsWith(field.toLowerCase()) && l.contains(" ")
                        && !l.startsWith("#")) {
                    return l.toLowerCase();
                }
            }
        }
        return null;
    }

    // ---------- SSH root 直接登录 ----------
    static class SshRootLogin extends BaseCheck {
        SshRootLogin() { super("ssh_root_login", "SSH 是否允许 root 直接登录", Category.SECURITY, 6); }

        @Override protected CheckResult doRun() {
            String v = sshdConfig("permitrootlogin");
            if (v == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取 SSH 配置（可能未安装或需要 root）。", "");
            if (v.endsWith("yes")) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "SSH 允许 root 直接登录，全网黑客每天都在爆破这个入口。",
                    v,
                    "⚠️ 建议：禁止 root 直接登录并改用密钥登录，费用约 ¥300-600。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "SSH 已禁止 root 直接登录。", v);
        }
    }

    // ---------- SSH 密码登录 ----------
    static class SshPasswordAuth extends BaseCheck {
        SshPasswordAuth() { super("ssh_password_auth", "SSH 是否允许密码登录", Category.SECURITY, 6); }

        @Override protected CheckResult doRun() {
            String v = sshdConfig("passwordauthentication");
            if (v == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取 SSH 配置。", "");
            if (v.endsWith("yes")) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "SSH 允许密码登录，弱密码会被自动化脚本爆破。",
                    v,
                    "⚠️ 建议：改用密钥登录并关闭密码认证，费用约 ¥300-600。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "SSH 已关闭密码登录（密钥认证）。", v);
        }
    }

    // ---------- SSH 默认端口 ----------
    static class SshPort extends BaseCheck {
        SshPort() { super("ssh_port", "SSH 端口是否为默认 22", Category.SECURITY, 3); }

        @Override protected CheckResult doRun() {
            String v = sshdConfig("port");
            if (v == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取 SSH 配置。", "");
            if (v.trim().endsWith("22")) return CheckResult.of(key(), title(), category(), Severity.INFO, weight(),
                    "SSH 使用默认端口 22，会被大量自动扫描器骚扰（改端口属于锦上添花）。",
                    v);
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "SSH 已使用非默认端口。", v);
        }
    }

    // ---------- 防火墙 ----------
    static class Firewall extends BaseCheck {
        Firewall() { super("firewall", "防火墙是否启用", Category.SECURITY, 7); }

        @Override protected CheckResult doRun() {
            boolean ufw = Probe.cmd("ufw status 2>/dev/null | grep -q 'Status: active'").ok();
            boolean fwall = Probe.cmd("firewall-cmd --state 2>/dev/null | grep -q running").ok();
            Probe.CmdOut ipt = Probe.cmd("iptables -L -n 2>/dev/null | grep -cE '^ACCEPT|^DROP|^REJECT'");
            int rules = -1;
            try { rules = Integer.parseInt(ipt.stdout().trim()); } catch (Exception ignore) { }
            if (ufw || fwall || rules > 5) {
                return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                        "防火墙已启用。",
                        (ufw ? "ufw=active " : "") + (fwall ? "firewalld=running " : "") + "iptables规则数=" + rules);
            }
            return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "未检测到任何启用的防火墙，所有端口都对公网敞开。",
                    "ufw/firewalld 未启用，iptables 规则数=" + rules,
                    "🔴 后果：数据库、缓存等服务直接暴露在公网，随时可能被入侵或勒索。配置防火墙费用约 ¥300-800。");
        }
    }

    // ---------- fail2ban ----------
    static class Fail2ban extends BaseCheck {
        Fail2ban() { super("fail2ban", "SSH 爆破防护（fail2ban）", Category.SECURITY, 4); }

        @Override protected CheckResult doRun() {
            boolean active = Probe.cmd("systemctl is-active fail2ban 2>/dev/null | grep -q active").ok();
            if (active) return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "fail2ban 已启用，爆破 IP 会被自动封禁。", "systemctl is-active fail2ban => active");
            return CheckResult.of(key(), title(), category(), Severity.INFO, weight(),
                    "未启用 fail2ban（有防火墙兜底时风险可控，建议加装）。",
                    "systemctl is-active fail2ban => inactive",
                    "ℹ️ 建议：加装 fail2ban 自动封禁爆破 IP，费用约 ¥200-400。");
        }
    }

    // ---------- 公网开放端口 ----------
    static class OpenPorts extends BaseCheck {
        private static final Map<String, String> RISKY = Map.of(
                "3306", "MySQL 数据库", "6379", "Redis 缓存", "5432", "PostgreSQL 数据库",
                "27017", "MongoDB 数据库", "9200", "Elasticsearch", "11211", "Memcached",
                "1433", "SQL Server", "8086", "InfluxDB");

        OpenPorts() { super("open_ports", "公网开放端口清单", Category.SECURITY, 8); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd("ss -tuln 2>/dev/null");
            if (!out.ok() && out.stdout().isBlank())
                return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(), "无法读取端口列表。", "");
            List<String> risky = new ArrayList<>();
            List<String> all = new ArrayList<>();
            for (String line : out.stdout().split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) continue;
                String addr = p[4];
                String port = addr.substring(addr.lastIndexOf(':') + 1);
                if (!addr.startsWith("0.0.0.0") && !addr.startsWith("[::]") && !addr.startsWith("*:")) continue;
                all.add(port);
                String svc = RISKY.get(port);
                if (svc != null) risky.add(port + "(" + svc + ")");
            }
            if (all.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未解析到监听端口。", out.stdout());
            if (!risky.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "以下敏感服务直接暴露在公网：" + String.join("、", risky) + "。这是被入侵的头号原因。",
                    "公网监听端口: " + String.join(", ", all),
                    "🔴 后果：数据库/缓存暴露公网可被直接连接、拖库、勒索。立即加固费用约 ¥500-1500。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "未发现敏感服务暴露公网（公网监听端口: " + String.join(", ", all) + "）。",
                    out.stdout());
        }
    }

    // ---------- UID 0 异常账户 ----------
    static class UidZero extends BaseCheck {
        UidZero() { super("uid_zero", "root 级权限账户数量", Category.SECURITY, 6); }

        @Override protected CheckResult doRun() {
            String content = Probe.file("/etc/passwd");
            if (content == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取账户列表。", "");
            List<String> roots = new ArrayList<>();
            for (String line : content.split("\n")) {
                String[] p = line.split(":");
                if (p.length > 2 && "0".equals(p[2])) roots.add(p[0]);
            }
            List<String> suspicious = roots.stream().filter(n -> !n.equals("root")).toList();
            if (!suspicious.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "发现 root 级权限的异常账户：" + suspicious + "，极可能已被入侵！",
                    "UID=0 账户: " + roots,
                    "🔴 后果：黑客留有和 root 同级的后门账户。应急响应与清除费用约 ¥1000-3000。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "root 级权限账户仅 root 本身。", "UID=0 账户: " + roots);
        }
    }

    // ---------- 空密码账户 ----------
    static class EmptyPassword extends BaseCheck {
        EmptyPassword() { super("empty_password", "空密码账户", Category.SECURITY, 4); }

        @Override protected CheckResult doRun() {
            String content = Probe.file("/etc/shadow");
            if (content == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取影子文件（需要 root 权限运行可检测此项）。", "");
            List<String> empties = new ArrayList<>();
            for (String line : content.split("\n")) {
                String[] p = line.split(":");
                if (p.length > 1 && p[1].isEmpty()) empties.add(p[0]);
            }
            if (!empties.isEmpty()) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "发现空密码账户：" + empties + "，任何人都能直接登录！",
                    "空密码账户: " + empties,
                    "🔴 后果：空密码等于敞开大门。立即处理费用约 ¥500-1000。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "无空密码账户。", "");
        }
    }
}
