package com.srvcheck.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 只读探测工具：执行命令 / 读取文件，失败一律安全降级 */
public final class Probe {
    private Probe() {}

    public record CmdOut(int exitCode, String stdout) {
        public boolean ok() { return exitCode == 0; }
    }

    /** 执行 shell 命令（仅只读探测命令），超时 10s，异常返回 exitCode=-1 */
    public static CmdOut cmd(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().reduce((a, b) -> a + "\n" + b).orElse("");
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return new CmdOut(-1, out); }
            return new CmdOut(p.exitValue(), out);
        } catch (Exception e) {
            return new CmdOut(-1, "");
        }
    }

    /** 读取文本文件，失败返回 null */
    public static String file(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }
}
