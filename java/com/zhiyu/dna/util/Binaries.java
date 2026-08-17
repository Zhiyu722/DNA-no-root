package com.zhiyu.dna.util;

import android.content.Context;
import android.os.Environment;

import com.zhiyu.dna.engine.ToolPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 内置原生工具管理: 首次运行时把 assets/bin 下的二进制解压到应用私有目录并赋予执行权限。
 */
public final class Binaries {

    private static final String[] BINARIES = {
            "debugfs", "mke2fs", "brotli", "lz4", "payload-dumper-go",
    };

    private static final String[] LIBS = {
            "libblkid.so", "libuuid.so", "libandroid-posix-semaphore.so",
            "libbrotlienc.so", "libbrotlidec.so", "libbrotlicommon.so",
    };

    private Binaries() {}

    /** 解压二进制到 filesDir/bin, 返回 ToolPaths; 若解压过则直接复用。 */
    public static synchronized ToolPaths ensure(Context ctx, ProgressLog log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "bin");
        File libDir = new File(dir, "lib");
        dir.mkdirs();
        libDir.mkdirs();
        ToolPaths paths = new ToolPaths(
                new File(dir, "debugfs"),
                new File(dir, "mke2fs"),
                new File(dir, "brotli"),
                new File(dir, "lz4"),
                new File(dir, "payload-dumper-go"),
                libDir);

        boolean needExtract = false;
        for (String b : BINARIES) {
            File f = new File(dir, b);
            if (!f.exists() || f.length() == 0) needExtract = true;
        }
        if (needExtract || !new File(dir, "mke2fs").exists()) {
            if (log != null) log.log("首次运行: 释放内置引擎工具 ...");
            for (String b : BINARIES) {
                extract(ctx, "bin/" + b, new File(dir, b), log);
            }
            for (String l : LIBS) {
                extract(ctx, "bin/lib/" + l, new File(libDir, l), log);
            }
        }
        if (log != null) log.log("引擎工具就绪 ✓");
        return paths;
    }

    private static void extract(Context ctx, String asset, File out, ProgressLog log) throws Exception {
        if (out.exists() && out.length() > 0) return;
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        out.setExecutable(true, false);
        out.setReadable(true, false);
        if (log != null) log.log("  释放 " + out.getName());
    }

    /** 默认输出目录 /sdcard/DNA/out */
    public static File defaultOutDir(Context ctx) {
        File sdcard = Environment.getExternalStorageDirectory();
        File dna = new File(sdcard, "DNA");
        File out = new File(dna, "out");
        return out;
    }

    public interface ProgressLog {
        void log(String line);
    }
}
