package com.srvcheck.core;

import java.util.List;

/** 检查项注册表：集中组装全部检查，供 Main 调用 */
public final class CheckRegistry {
    private CheckRegistry() {}

    /** domain 非空时启用网站与证书类检查，否则该类检查整体 SKIP */
    public static List<Check> all(String domain) {
        return List.of(
                com.srvcheck.checks.SystemChecks.all(),
                com.srvcheck.checks.SecurityChecks.all(),
                com.srvcheck.checks.DataChecks.all(),
                com.srvcheck.checks.WebChecks.all(domain)
        ).stream().flatMap(List::stream).toList();
    }
}
