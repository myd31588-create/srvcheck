package com.srvcheck.checks;

import com.srvcheck.core.BaseCheck;
import com.srvcheck.core.Category;
import com.srvcheck.core.Check;
import com.srvcheck.core.CheckResult;
import com.srvcheck.core.Severity;
import com.srvcheck.util.Probe;

import java.util.ArrayList;
import java.util.List;

/** 数据安全类检查（满分 20）——这是老板最痛的部分 */
public final class DataChecks {
    private DataChecks() {}

    public static List<Check> all() {
        return List.of(new MysqlExpose(), new RedisExpose(), new RedisAuth(),
                new BackupCron(), new BackupFreshness());
    }

    /** 从 ss -tlnp 输出判断某端口是否监听公网；返回 null=服务未运行 */
    private static String publicOrLocal(int port) {
        Probe.CmdOut out = Probe.cmd("ss -tlnp 2>/dev/null | grep ':" + port + " '");
        if (out.stdout().isBlank()) return null;
        for (String line : out.stdout().split("\n")) {
            String addr = line.trim().split("\\s+")[3];
            if (addr.startsWith("127.0.0.1") || addr.startsWith("[::1]")) return "local";
        }
        return "public";
    }

    // ---------- MySQL 公网暴露 ----------
    static class MysqlExpose extends BaseCheck {
        MysqlExpose() { super("mysql_expose", "MySQL 是否暴露公网", Category.DATA, 6); }

        @Override protected CheckResult doRun() {
            String v = publicOrLocal(3306);
            if (v == null) return CheckResult.of(key(), title(), category(), Severity.INFO, weight(),
                    "未检测到 MySQL 在运行。", "ss :3306 无监听");
            if ("public".equals(v)) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "MySQL 数据库监听在公网地址上，全网都可尝试连接你的数据库！",
                    "ss -tlnp 显示 3306 监听 0.0.0.0/::",
                    "🔴 后果：一旦口令被爆破，客户数据将被拖走或加密勒索。改为仅本机监听+防火墙限制，费用约 ¥500-1500。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "MySQL 仅监听本机，外部无法直连。", "ss -tlnp 显示 3306 监听 127.0.0.1");
        }
    }

    // ---------- Redis 公网暴露 ----------
    static class RedisExpose extends BaseCheck {
        RedisExpose() { super("redis_expose", "Redis 是否暴露公网", Category.DATA, 6); }

        @Override protected CheckResult doRun() {
            String v = publicOrLocal(6379);
            if (v == null) return CheckResult.of(key(), title(), category(), Severity.INFO, weight(),
                    "未检测到 Redis 在运行。", "ss :6379 无监听");
            if ("public".equals(v)) return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                    "Redis 监听在公网地址上——这是挖矿团伙最爱利用的漏洞之一。",
                    "ss -tlnp 显示 6379 监听 0.0.0.0/::",
                    "🔴 后果：未设密码的公网 Redis 可在几分钟内被写入计划任务植入挖矿木马。加固费用约 ¥500-1500。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "Redis 仅监听本机，外部无法直连。", "ss -tlnp 显示 6379 监听 127.0.0.1");
        }
    }

    // ---------- Redis 弱口令 ----------
    static class RedisAuth extends BaseCheck {
        RedisAuth() { super("redis_auth", "Redis 是否设置密码", Category.DATA, 5); }

        @Override protected CheckResult doRun() {
            if (publicOrLocal(6379) == null) return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "未检测到 Redis 在运行，跳过。", "");
            Probe.CmdOut ping = Probe.cmd("redis-cli ping 2>/dev/null");
            String out = ping.stdout().trim();
            if (out.equalsIgnoreCase("PONG"))
                return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                        "Redis 无需密码即可执行任意命令！",
                        "redis-cli ping => PONG（未认证）",
                        "🔴 后果：攻击者可直接读写甚至清空你的缓存数据。设置 requirepass 费用约 ¥200-500。");
            if (out.toUpperCase().contains("NOAUTH") || out.toUpperCase().contains("ERR"))
                return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                        "Redis 已设置密码认证。", "redis-cli ping => " + out);
            return CheckResult.of(key(), title(), category(), Severity.SKIP, weight(),
                    "无法连接 Redis 检测（可能需要权限）。", out);
        }
    }

    // ---------- 是否有备份任务 ----------
    static class BackupCron extends BaseCheck {
        BackupCron() { super("backup_cron", "数据库备份任务", Category.DATA, 6); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd(
                    "cat /etc/crontab /etc/cron.d/* /var/spool/cron/* 2>/dev/null | grep -iE 'mysqldump|pg_dump|mongodump|backup|xtrabackup' | head -5");
            if (out.stdout().isBlank())
                return CheckResult.of(key(), title(), category(), Severity.CRIT, weight(),
                        "没有发现任何自动备份任务！数据一旦误删或被勒索，将永久无法找回。",
                        "crontab 与 /etc/cron.d 中未发现备份相关任务",
                        "🔴 后果：没有备份 = 数据裸奔。搭建自动备份费用约 ¥500-1500，强烈建议立即处理。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "已发现自动备份任务。", out.stdout());
        }
    }

    // ---------- 备份文件新鲜度 ----------
    static class BackupFreshness extends BaseCheck {
        BackupFreshness() { super("backup_fresh", "近期备份文件", Category.DATA, 4); }

        @Override protected CheckResult doRun() {
            Probe.CmdOut out = Probe.cmd(
                    "find /var/backups /backup /data/backup /root /home -maxdepth 2 " +
                    "\\( -name '*.sql' -o -name '*.sql.gz' -o -name '*.tar.gz' -o -name '*.dump' \\) -mtime -7 2>/dev/null | head -5");
            if (out.stdout().isBlank())
                return CheckResult.of(key(), title(), category(), Severity.WARN, weight(),
                        "最近 7 天没有发现新的备份文件，备份可能形同虚设。",
                        "常用备份目录中无 7 天内生成的备份文件",
                        "⚠️ 建议：核查备份任务是否真的在执行并异地保存，费用约 ¥300-800。");
            return CheckResult.of(key(), title(), category(), Severity.PASS, weight(),
                    "最近 7 天有新备份文件生成。", out.stdout());
        }
    }
}
