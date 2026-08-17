package com.zhiyu.dna.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** erofs 镜像解包 —— 调用内置 dump.erofs。 */
public final class ErofsTool {

    private ErofsTool() {}

    /** 解包 erofs 镜像到 outDir(fsck.erofs --extract 保留目录结构与权限)。 */
    public static void extract(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        File fsck = new File(tools.debugfs.getParentFile(), "fsck.erofs");
        if (!fsck.exists()) throw new IOException("缺少 fsck.erofs 工具");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建目录: " + outDir);
        p.log("调用 fsck.erofs 提取 erofs 文件系统 ...");
        List<String> cmd = Exec.cmd(fsck.getAbsolutePath(),
                "--extract=" + outDir.getAbsolutePath(),
                img.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("fsck.erofs 提取失败 (exit " + code + ")");
        p.log("erofs 提取完成 → " + outDir.getAbsolutePath());
    }
}
