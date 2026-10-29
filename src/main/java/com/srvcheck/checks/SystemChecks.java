package com.srvcheck.checks;

import com.srvcheck.core.BaseCheck;
import com.srvcheck.core.Category;
import com.srvcheck.core.Check;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Severity;
import com.srvcheck.util.Probe;

import java.util.ArrayList;
import java.util.List;

/** 系统健康类检查（满分 30） */
public final class SystemChecks {
    private SystemChecks() {}

    public static List<Check> all() {
        return List.of(new DiskUsage(), new MemAvailable(), new CpuLoad(),
                new Swap(), new OomRecent(), new Zombie());
    }

    // ---------- 磁盘水位 ----------
    static class DiskUsage extends BaseCheck {
        DiskUsage() { super("disk_usage", "磁盘空间水位", Category.SYSTEM, 6); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd("df -P | awk 'NR>1 && $1 !~ /^(tmpfs|devtmpfs|overlay|squashfs)$/ {print $5, $6}'");
            int max = 0; String mount = "?";
            for (String line : out.stdout().split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2 || !p[0].endsWith("%")) continue;
                int pct = Integer.parseInt(p[0].replace("%", ""));
                if (pct > max) { max = pct; mount = p[1]; }
            }
            if (max == 0) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未能读取磁盘信息。", out.stdout());
            if (max > 90) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "磁盘 " + mount + " 已用 " + max + "%，即将写满！写满后系统和服务会直接崩溃。",
                    "df -P 显示最高使用率 " + max + "%（挂载点 " + mount + "）",
                    "🔴 后果：磁盘写满会导致数据库损坏、日志无法写入、业务停摆。清理或扩容费用约 ¥300-1000。");
            if (max > 80) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "磁盘 " + mount + " 已用 " + max + "%，建议尽快清理。",
                    "df -P 显示最高使用率 " + max + "%（挂载点 " + mount + "）",
                    "⚠️ 建议：清理日志/临时文件或扩容，费用约 ¥200-500。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "磁盘空间充足（最高使用率 " + max + "%）。", out.stdout());
        }
    }

    // ---------- 内存可用量 ----------
    static class MemAvailable extends BaseCheck {
        MemAvailable() { super("mem_available", "内存可用量", Category.SYSTEM, 6); }

        @Override protected CheckResult doRun() {
            String content = Probe.file("/proc/meminfo");
            if (content == null) return skipFile();
            long total = kb(content, "MemTotal"), avail = kb(content, "MemAvailable");
            if (total <= 0) return skipFile();
            double freeRatio = avail * 100.0 / total;
            if (freeRatio < 10) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    String.format("内存仅剩 %.0f%% 可用，随时可能触发 OOM 被强制杀进程。", freeRatio),
                    String.format("MemTotal=%dMB MemAvailable=%dMB", total / 1024, avail / 1024),
                    "🔴 后果：内存耗尽时系统会强杀进程，数据库和应用可能随机崩溃。升级内存或优化服务费用约 ¥500-2000。");
            if (freeRatio < 20) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    String.format("内存可用量偏低（%.0f%%），建议排查占用大户。", freeRatio),
                    String.format("MemTotal=%dMB MemAvailable=%dMB", total / 1024, avail / 1024),
                    "⚠️ 建议：排查内存占用或升级配置，费用约 ¥300-1000。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    String.format("内存充足（可用 %.0f%%）。", freeRatio),
                    String.format("MemTotal=%dMB MemAvailable=%dMB", total / 1024, avail / 1024));
        }

        private CheckResult skipFile() {
            return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(), "未能读取 /proc/meminfo。", "");
        }

        private long kb(String content, String field) {
            for (String line : content.split("\n")) {
                if (line.startsWith(field)) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "").trim());
                }
            }
            return -1;
        }
    }

    // ---------- CPU 负载 ----------
    static class CpuLoad extends BaseCheck {
        CpuLoad() { super("cpu_load", "CPU 负载", Category.SYSTEM, 5); }

        @Override protected CheckResult doRun() {
            String loadavg = Probe.file("/proc/loadavg");
            if (loadavg == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未能读取负载信息。", "");
            double load = Double.parseDouble(loadavg.split("\\s+")[0]);
            int cores = Runtime.getRuntime().availableProcessors();
            double ratio = load / Math.max(cores, 1);
            if (ratio > 3) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    String.format("CPU 严重过载（负载 %.1f / %d 核），服务响应会明显变慢甚至无响应。", load, cores),
                    "loadavg=" + loadavg.trim() + " cores=" + cores,
                    "🔴 后果：持续过载会导致网站卡死、订单丢失。优化或升级费用约 ¥500-2000。");
            if (ratio > 1.5) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    String.format("CPU 偏高（负载 %.1f / %d 核）。", load, cores),
                    "loadavg=" + loadavg.trim() + " cores=" + cores,
                    "⚠️ 建议：排查高占用进程，费用约 ¥200-800。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    String.format("CPU 负载正常（%.1f / %d 核）。", load, cores), "loadavg=" + loadavg.trim());
        }
    }

    // ---------- Swap ----------
    static class Swap extends BaseCheck {
        Swap() { super("swap", "Swap 交换分区", Category.SYSTEM, 4); }

        @Override protected CheckResult doRun() {
            String content = Probe.file("/proc/meminfo");
            if (content == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未能读取内存信息。", "");
            long total = 0, free = 0;
            for (String line : content.split("\n")) {
                if (line.startsWith("SwapTotal")) total = Long.parseLong(line.replaceAll("[^0-9]", ""));
                if (line.startsWith("SwapFree")) free = Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
            if (total == 0) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "未配置 Swap，内存突发紧张时进程会被直接杀死。",
                    "SwapTotal=0",
                    "⚠️ 建议：配置 Swap 作为内存缓冲，费用约 ¥100-300。");
            double usedRatio = (total - free) * 100.0 / total;
            if (usedRatio > 80) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    String.format("Swap 已使用 %.0f%%，说明内存长期紧张。", usedRatio),
                    String.format("SwapTotal=%dMB SwapUsed=%dMB", total / 1024, (total - free) / 1024),
                    "⚠️ 建议：排查内存占用，费用约 ¥300-1000。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "Swap 配置正常。", String.format("SwapTotal=%dMB SwapUsed=%dMB", total / 1024, (total - free) / 1024));
        }
    }

    // ---------- 近 7 天 OOM 记录 ----------
    static class OomRecent extends BaseCheck {
        OomRecent() { super("oom_recent", "近 7 天内存溢出记录", Category.SYSTEM, 4); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut j = Probe.cmd("journalctl -k --since \"-7 days\" 2>/dev/null | grep -ci 'out of memory\\|oom-kill'");
            int count = -1;
            if (j.ok()) {
                try { count = Integer.parseInt(j.stdout().trim()); } catch (Exception ignore) { }
            }
            if (count < 0) {
                Probe.CmdOut d = Probe.cmd("dmesg -T 2>/dev/null | grep -ci 'out of memory\\|oom-kill'");
                try { count = Integer.parseInt(d.stdout().trim()); } catch (Exception ignore) { count = -1; }
            }
            if (count < 0) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取内核日志（可能需要 root）。", "");
            if (count > 0) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "近 7 天发生过 " + count + " 次内存溢出，系统曾强制杀死进程。",
                    "oom 记录数=" + count,
                    "⚠️ 建议：排查内存占用或升级配置，费用约 ¥300-1000。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "近 7 天无内存溢出记录。", "oom 记录数=0");
        }
    }

    // ---------- 僵尸进程 ----------
    static class Zombie extends BaseCheck {
        Zombie() { super("zombie", "僵尸进程", Category.SYSTEM, 3); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd("ps -eo stat | grep -c '^Z' || true");
            int n = -1;
            try { n = Integer.parseInt(out.stdout().trim()); } catch (Exception ignore) { }
            if (n < 0) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法读取进程列表。", "");
            if (n > 10) return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                    "存在 " + n + " 个僵尸进程，说明有程序没有正确回收子进程。",
                    "僵尸进程数=" + n,
                    "⚠️ 建议：定位并修复对应程序，费用约 ¥300-800。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "无异常僵尸进程。", "僵尸进程数=" + n);
        }
    }
}
