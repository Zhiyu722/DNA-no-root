package com.zhiyu.dna.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行外部进程(内置原生工具)的工具类。
 * 设置 LD_LIBRARY_PATH 指向捆绑的动态库目录, 使 termux 编译的二进制在 App 内可运行。
 */
public final class Exec {

    private Exec() {}

    /** 执行命令, 实时回传 stdout/stderr 到 Progress.log, 返回退出码。 */
    public static int run(File libDir, Progress p, List<String> cmd) throws IOException {
        return run(libDir, p, cmd, null);
    }

    /** 执行命令, 可过滤不需要展示的输出行(如 chown 警告)。 */
    public static int run(File libDir, Progress p, List<String> cmd,
                          java.util.function.Predicate<String> lineFilter) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (libDir != null && libDir.isDirectory()) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? libDir.getAbsolutePath()
                            : libDir.getAbsolutePath() + ":" + existing);
        }
        // mke2fs 需要 mke2fs.conf(termux 的 /etc 在应用内不可达, 随包分发)
        if (!cmd.isEmpty()) {
            File exe = new File(cmd.get(0));
            File conf = new File(exe.getParentFile(), "mke2fs.conf");
            if (exe.getName().equals("mke2fs") && conf.exists()) {
                pb.environment().put("MKE2FS_CONFIG", conf.getAbsolutePath());
            }
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread pump = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (lineFilter != null && !lineFilter.test(t)) continue;
                    if (p != null) p.log("  " + t);
                }
            } catch (IOException ignored) {}
        });
        pump.setDaemon(true);
        pump.start();
        try {
            int code = proc.waitFor();
            pump.join(2000);
            return code;
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    public static int runQuiet(File libDir, List<String> cmd) throws IOException {
        return run(libDir, null, cmd);
    }

    public static List<String> cmd(String... parts) {
        List<String> l = new ArrayList<>();
        for (String s : parts) l.add(s);
        return l;
    }
}
