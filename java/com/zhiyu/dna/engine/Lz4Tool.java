package com.zhiyu.dna.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** lz4 解压 —— 调用内置 lz4 CLI(支持 legacy 与 frame 格式自动识别)。 */
public final class Lz4Tool {

    private Lz4Tool() {}

    public static void decompress(File lz4In, File out, ToolPaths tools, Progress p) throws IOException {
        p.log("lz4 解压 " + lz4In.getName() + " ...");
        List<String> cmd = Exec.cmd(tools.lz4.getAbsolutePath(),
                "-d", "-f", lz4In.getAbsolutePath(), out.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("lz4 解压失败 (exit " + code + ")");
    }

    /** 判断是否 lz4(legacy 0x184C2102 / frame 0x184D2204) */
    public static boolean isLz4(byte[] b) {
        if (b.length < 4) return false;
        return (b[0] & 0xFF) == 0x02 && (b[1] & 0xFF) == 0x21 && (b[2] & 0xFF) == 0x4C && (b[3] & 0xFF) == 0x18
                || (b[0] & 0xFF) == 0x04 && (b[1] & 0xFF) == 0x22 && (b[2] & 0xFF) == 0x4D && (b[3] & 0xFF) == 0x18;
    }
}
