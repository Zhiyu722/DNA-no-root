package com.zhiyu.dna.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * boot / recovery 镜像解包与打包 —— 基于 magiskboot(来自 TIK5)。
 * magiskboot 正确处理所有版本(v0-v4)的头部、AVB 签名、多压缩格式。
 * 移除旧的纯 Java 解析(不完整/易出错)。
 */
public final class BootImage {

    private BootImage() {}

    /** 解包 boot 镜像: 调用 magiskboot unpack + 解压 ramdisk */
    public static void unpack(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建输出目录: " + outDir);

        p.log("调用 magiskboot unpack ...");
        List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                "unpack", "-h", img.getAbsolutePath());
        runInDir(cmd, img.getParentFile(), tools.libDir, p);

        // 移动输出到 outDir
        String[] outputs = {"kernel", "ramdisk.cpio", "ramdisk.cpio.lz4", "ramdisk.cpio.gz",
                "second", "dtb", "dtbo", "recovery_dtbo", "header",
                "kernel_dtb", "extra", "signature"};
        File imgDir = img.getParentFile();
        for (String name : outputs) {
            File f = new File(imgDir, name);
            if (f.exists() && f.length() > 0) {
                File dest = new File(outDir, name);
                if (!dest.exists()) f.renameTo(dest);
            }
        }

        // 解压 ramdisk
        File ramdiskDir = new File(outDir, "ramdisk");
        File ramdiskComp = null;
        for (String name : new String[]{"ramdisk.cpio", "ramdisk.cpio.lz4", "ramdisk.cpio.gz"}) {
            File f = new File(outDir, name);
            if (f.exists() && f.length() > 0) { ramdiskComp = f; break; }
        }
        if (ramdiskComp != null && ramdiskComp.length() > 0) {
            if (ramdiskComp.getName().equals("ramdisk.cpio")) {
                p.log("ramdisk: cpio 解包...");
                ramdiskDir.mkdirs();
                extractCpio(ramdiskComp, ramdiskDir, tools, p);
            } else {
                p.log("ramdisk: " + ramdiskComp.getName() + " → 解压中...");
                File decomp = new File(outDir, "ramdisk.cpio.decomp");
                List<String> decompCmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                        "decompress", ramdiskComp.getAbsolutePath(), decomp.getAbsolutePath());
                int rc = Exec.run(tools.libDir, p, decompCmd);
                if (rc == 0 && decomp.exists() && decomp.length() > 0) {
                    ramdiskDir.mkdirs();
                    extractCpio(decomp, ramdiskDir, tools, p);
                    decomp.delete();
                } else {
                    p.log("ramdisk: 解压失败, 保留原始文件");
                }
            }
        }
        p.log("boot 解包完成 ✓");
    }

    private static void extractCpio(File cpio, File dir, ToolPaths tools, Progress p) throws IOException {
        // 用 magiskboot cpio 提取
        List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                "cpio", cpio.getAbsolutePath(), "extract");
        runInDir(cmd, dir, tools.libDir, p);
    }

    /** 打包 boot 镜像: 从解包目录还原(magiskboot repack) */
    public static void pack(File bootDir, File outImg, ToolPaths tools, Progress p) throws IOException {
        if (!bootDir.isDirectory()) throw new IOException("boot 目录无效: " + bootDir);
        File kernel = new File(bootDir, "kernel");
        if (!kernel.exists()) throw new IOException("缺少 kernel 文件");

        // 还原 ramdisk 目录 → cpio
        File ramdiskDir = new File(bootDir, "ramdisk");
        File ramdiskCpio = new File(bootDir, "ramdisk.cpio");
        if (ramdiskDir.isDirectory() && !ramdiskCpio.exists()) {
            p.log("打包 ramdisk 目录 → cpio ...");
            // magiskboot cpio 打包: 从目录创建 cpio
            List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                    "cpio", new File(bootDir, "ramdisk.cpio.tmp").getAbsolutePath(), "add");
            // 简化: 用 cpio 命令
            List<String> cmds = Exec.cmd("cpio",
                    "-o", "-H", "newc", "-O", ramdiskCpio.getAbsolutePath());
            runInDir(cmds, ramdiskDir, tools.libDir, p);
            if (ramdiskCpio.exists()) p.log("ramdisk cpio 打包完成");
        }

        // 用 magiskboot repack 生成最终 boot
        p.log("调用 magiskboot repack ...");
        List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                "repack", "-n", bootDir.getAbsolutePath(), outImg.getAbsolutePath());
        int rc = Exec.run(tools.libDir, p, cmd);
        if (rc != 0) throw new IOException("magiskboot repack 失败 (exit " + rc + ")");

        File newBoot = new File(bootDir, "new-boot.img");
        if (newBoot.exists()) newBoot.renameTo(outImg);
        p.log("boot 打包完成 → " + outImg.getAbsolutePath());
    }

    private static void runInDir(List<String> cmd, File dir, File libDir, Progress p) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        if (libDir != null && libDir.isDirectory()) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? libDir.getAbsolutePath()
                            : libDir.getAbsolutePath() + ":" + existing);
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread pump = new Thread(() -> {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) {
                    if (p != null && !line.trim().isEmpty()) p.log("  " + line.trim());
                }
            } catch (Exception ignored) {}
        });
        pump.setDaemon(true); pump.start();
        try {
            int code = proc.waitFor(); pump.join(2000);
            if (code != 0) throw new IOException("命令失败 (exit " + code + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断");
        }
    }
}