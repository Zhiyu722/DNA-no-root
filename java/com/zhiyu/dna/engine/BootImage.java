package com.zhiyu.dna.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * boot / recovery / vendor_boot 镜像解包与打包 —— 移植 mkbootimg/unpack_bootimg 逻辑。
 *
 * v0:  header 1632B, page_size @36, kernel/ramdisk/second 依次 page 对齐
 * v1:  + recovery_dtbo
 * v2:  + dtb
 * v3:  固定 page 4096, 无地址字段, header 1580B
 * v4:  + signature
 */
public final class BootImage {

    public static final byte[] MAGIC = "ANDROID!".getBytes();

    public static class BootConfig {
        public int version = 0;
        public int pageSize = 2048;
        public long kernelAddr = 0x10008000L;
        public long ramdiskAddr = 0x11000000L;
        public long secondAddr = 0x10F00000L;
        public long tagsAddr = 0x10000100L;
        public long dtbAddr = 0x10000000L;
        public long osVersion = 0;
        public String name = "";
        public String cmdline = "";
        public String extraCmdline = "";
        public int headerSize = 0; // 实际结构体大小
    }

    private BootImage() {}

    /** 解析 boot 头, 返回配置与各部件大小/偏移。 */
    public static class BootParts {
        public BootConfig cfg = new BootConfig();
        public long kernelOff, kernelSize;
        public long ramdiskOff, ramdiskSize;
        public long secondOff, secondSize;
        public long dtboOff, dtboSize;
        public long dtbOff, dtbSize;
        public long sigOff, sigSize;
    }

    public static BootParts parse(File img) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(img, "r")) {
            byte[] head = new byte[64];
            raf.readFully(head);
            for (int i = 0; i < 8; i++) if (head[i] != MAGIC[i]) throw new IOException("不是 boot 镜像");
            int version = Io.i32(head, 40);
            BootParts parts = new BootParts();
            BootConfig cfg = parts.cfg;
            cfg.version = version;
            long fileLen = raf.length();

            if (version <= 2) {
                cfg.pageSize = Io.i32(head, 36);
                if (cfg.pageSize < 2048) cfg.pageSize = 2048;
                parts.kernelSize = Io.u32(head, 8);
                cfg.kernelAddr = Io.u32(head, 12);
                parts.ramdiskSize = Io.u32(head, 16);
                cfg.ramdiskAddr = Io.u32(head, 20);
                parts.secondSize = Io.u32(head, 24);
                cfg.secondAddr = Io.u32(head, 28);
                cfg.tagsAddr = Io.u32(head, 32);
                cfg.osVersion = Io.u32(head, 44);
                byte[] name = new byte[16], cmd = new byte[512], extra = new byte[1024];
                raf.seek(48); raf.readFully(name);
                raf.seek(64); raf.readFully(cmd);
                raf.seek(608); raf.readFully(extra);
                cfg.name = cstr(name);
                cfg.cmdline = cstr(cmd);
                cfg.extraCmdline = cstr(extra);
                cfg.headerSize = 1632;
                if (version >= 1) {
                    raf.seek(1632);
                    byte[] b = new byte[32];
                    raf.readFully(b);
                    parts.dtboSize = Io.u32(b, 0);
                    // recovery_dtbo_offset @1636 (u64)
                    if (version >= 2) {
                        parts.dtbSize = Io.u32(b, 16);
                        cfg.dtbAddr = Io.u64(b, 20);
                    }
                }
                long page = cfg.pageSize;
                long pos = page;
                parts.kernelOff = pos; pos = align(pos + parts.kernelSize, page);
                parts.ramdiskOff = pos; pos = align(pos + parts.ramdiskSize, page);
                parts.secondOff = pos; pos = align(pos + parts.secondSize, page);
                if (version >= 1 && parts.dtboSize > 0) { parts.dtboOff = pos; pos = align(pos + parts.dtboSize, page); }
                if (version >= 2 && parts.dtbSize > 0) { parts.dtbOff = pos; pos = align(pos + parts.dtbSize, page); }
            } else {
                // v3 / v4
                cfg.pageSize = 4096;
                parts.kernelSize = Io.u32(head, 8);
                parts.ramdiskSize = Io.u32(head, 12);
                cfg.osVersion = Io.u32(head, 16);
                cfg.headerSize = Io.i32(head, 20);
                if (cfg.headerSize == 0) cfg.headerSize = 1580;
                byte[] cmd = new byte[512];
                raf.seek(48); raf.readFully(cmd);
                cfg.cmdline = cstr(cmd);
                long page = cfg.pageSize;
                long pos = page;
                parts.kernelOff = pos; pos = align(pos + parts.kernelSize, page);
                parts.ramdiskOff = pos; pos = align(pos + parts.ramdiskSize, page);
                if (version >= 4) {
                    // signature 位于 ramdisk 之后
                    parts.sigOff = pos;
                    if (pos < fileLen) {
                        parts.sigSize = fileLen - pos;
                    }
                }
            }
            return parts;
        }
    }

    /**
     * 解包 boot 镜像: 输出 kernel / ramdisk(解 gzip+cpio 到 ramdisk/ 目录) / second / dtb / dtbo + boot.json 配置。
     */
    public static void unpack(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建输出目录");
        BootParts parts = parse(img);
        BootConfig cfg = parts.cfg;
        p.log("boot header v" + cfg.version + ", page=" + cfg.pageSize + ", cmdline=" + cfg.cmdline);
        p.log("  kernel=" + (parts.kernelSize / 1024) + " KB"
                + "  ramdisk=" + (parts.ramdiskSize / 1024) + " KB"
                + (parts.secondSize > 0 ? "  second=" + (parts.secondSize / 1024) + " KB" : "")
                + (parts.dtboSize > 0 ? "  dtbo=" + (parts.dtboSize / 1024) + " KB" : "")
                + (parts.dtbSize > 0 ? "  dtb=" + (parts.dtbSize / 1024) + " KB" : "")
                + (parts.sigSize > 0 ? "  signature=" + (parts.sigSize / 1024) + " KB" : ""));

        try (RandomAccessFile raf = new RandomAccessFile(img, "r")) {
            if (parts.kernelSize > 0) writeRange(raf, outDir, "kernel", parts.kernelOff, parts.kernelSize);
            if (parts.secondSize > 0) writeRange(raf, outDir, "second", parts.secondOff, parts.secondSize);
            if (parts.dtboSize > 0) writeRange(raf, outDir, "recovery_dtbo", parts.dtboOff, parts.dtboSize);
            if (parts.dtbSize > 0) writeRange(raf, outDir, "dtb", parts.dtbOff, parts.dtbSize);
            if (parts.sigSize > 0) writeRange(raf, outDir, "signature", parts.sigOff, parts.sigSize);

            if (parts.ramdiskSize > 0) {
                byte[] blob = readRange(raf, parts.ramdiskOff, parts.ramdiskSize);
                File ramdiskDir = new File(outDir, "ramdisk");
                if (isGzip(blob)) {
                    byte[] cpio = gunzip(blob);
                    p.log("ramdisk: gzip → cpio, 解包 " + cpio.length + " 字节");
                    ramdiskDir.mkdirs();
                    Cpio.extract(cpio, ramdiskDir, p);
                    Io.writeFile(new File(outDir, "ramdisk.cpio.gz"), blob);
                } else if (Lz4Tool.isLz4(blob)) {
                    // lz4 压缩 ramdisk(新设备常见): 用内置 lz4 解压
                    File lz4Tmp = new File(outDir, "ramdisk.lz4");
                    Io.writeFile(lz4Tmp, blob);
                    File cpioOut = new File(outDir, "ramdisk.cpio");
                    Lz4Tool.decompress(lz4Tmp, cpioOut, tools, p);
                    p.log("ramdisk: lz4 → cpio, 解包中...");
                    ramdiskDir.mkdirs();
                    byte[] cpio = java.nio.file.Files.readAllBytes(cpioOut.toPath());
                    Cpio.extract(cpio, ramdiskDir, p);
                    lz4Tmp.delete();
                    cpioOut.delete();
                } else {
                    // 其它压缩 —— 保留原始 blob, 提示用户
                    p.log("ramdisk: 未知压缩格式, 保留原始文件");
                    Io.writeFile(new File(outDir, "ramdisk.cpio.raw"), blob);
                }
            }
        }

        // 保存配置, 供打包重建
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(cfg.version).append(",\n");
        sb.append("  \"page_size\": ").append(cfg.pageSize).append(",\n");
        sb.append("  \"kernel_addr\": \"").append(String.format("0x%08x", cfg.kernelAddr)).append("\",\n");
        sb.append("  \"ramdisk_addr\": \"").append(String.format("0x%08x", cfg.ramdiskAddr)).append("\",\n");
        sb.append("  \"second_addr\": \"").append(String.format("0x%08x", cfg.secondAddr)).append("\",\n");
        sb.append("  \"tags_addr\": \"").append(String.format("0x%08x", cfg.tagsAddr)).append("\",\n");
        sb.append("  \"dtb_addr\": \"").append(String.format("0x%08x", cfg.dtbAddr)).append("\",\n");
        sb.append("  \"os_version\": ").append(cfg.osVersion).append(",\n");
        sb.append("  \"name\": \"").append(escapeJson(cfg.name)).append("\",\n");
        sb.append("  \"cmdline\": \"").append(escapeJson(cfg.cmdline)).append("\",\n");
        sb.append("  \"extra_cmdline\": \"").append(escapeJson(cfg.extraCmdline)).append("\"\n");
        sb.append("}\n");
        Io.writeFile(new File(outDir, "boot.json"), sb.toString().getBytes("UTF-8"));
        p.log("已保存 boot.json 配置");
    }

    /**
     * 打包 boot 镜像。bootDir 需含 kernel, 可选 ramdisk/(目录) 或 ramdisk.cpio.gz, second, dtb, recovery_dtbo, boot.json。
     */
    public static void pack(File bootDir, File outImg, Progress p) throws IOException {
        BootConfig cfg = new BootConfig();
        File cfgFile = new File(bootDir, "boot.json");
        if (cfgFile.exists()) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(cfgFile.toPath()), "UTF-8");
                cfg = parseJson(json);
            } catch (Exception e) {
                p.log("boot.json 解析失败, 使用默认配置: " + e.getMessage());
            }
        }

        byte[] kernel = readIfExists(new File(bootDir, "kernel"), null);
        if (kernel == null) throw new IOException("缺少 kernel 文件, 无法打包 boot");

        byte[] second = readIfExists(new File(bootDir, "second"), new byte[0]);
        byte[] dtbo = readIfExists(new File(bootDir, "recovery_dtbo"), new byte[0]);
        byte[] dtb = readIfExists(new File(bootDir, "dtb"), new byte[0]);

        byte[] ramdisk;
        File rdDir = new File(bootDir, "ramdisk");
        File rdGz = new File(bootDir, "ramdisk.cpio.gz");
        File rdLz4 = new File(bootDir, "ramdisk.cpio.lz4");
        if (rdGz.exists()) {
            ramdisk = java.nio.file.Files.readAllBytes(rdGz.toPath());
            p.log("使用已有 ramdisk.cpio.gz");
        } else if (rdLz4.exists()) {
            ramdisk = java.nio.file.Files.readAllBytes(rdLz4.toPath());
            p.log("使用已有 ramdisk.cpio.lz4");
        } else if (rdDir.exists() && rdDir.isDirectory()) {
            p.log("打包 ramdisk 目录 → cpio → gzip ...");
            byte[] cpio = Cpio.pack(rdDir, p);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(cpio); }
            ramdisk = bos.toByteArray();
        } else {
            ramdisk = new byte[0];
        }

        int page = cfg.pageSize >= 2048 ? cfg.pageSize : 2048;
        int version = cfg.version;
        if (version > 4) version = 4;
        if (version < 0) version = 0;
        // 按实际部件自动调整版本: 有 dtb 至少 v2, 有 dtbo 至少 v1; 声称的高版本缺部件则降级
        if (version >= 2 && dtb.length == 0) version = 1;
        if (version >= 1 && dtbo.length == 0 && dtb.length == 0) version = 0;
        if (version < 2 && dtb.length > 0) version = 2;
        if (version < 1 && dtbo.length > 0) version = 1;

        byte[] hdr = new byte[page];
        System.arraycopy(MAGIC, 0, hdr, 0, 8);
        Io.putU32(hdr, 8, kernel.length);
        Io.putU32(hdr, 16, ramdisk.length);
        Io.putU32(hdr, 24, second.length);
        if (version <= 2) {
            Io.putU32(hdr, 12, cfg.kernelAddr);
            Io.putU32(hdr, 20, cfg.ramdiskAddr);
            Io.putU32(hdr, 28, cfg.secondAddr);
            Io.putU32(hdr, 32, cfg.tagsAddr);
            Io.putU32(hdr, 36, page);
            Io.putU32(hdr, 40, version);
            Io.putU32(hdr, 44, cfg.osVersion);
            putStr(hdr, 48, 16, cfg.name);
            putStr(hdr, 64, 512, cfg.cmdline);
            putStr(hdr, 608, 1024, cfg.extraCmdline);
            if (version >= 1) {
                Io.putU32(hdr, 1632, dtbo.length);
                Io.putU64(hdr, 1636, 0);
                Io.putU32(hdr, 1644, version == 2 ? 1664 : 1660);
                if (version >= 2) {
                    Io.putU32(hdr, 1648, dtb.length);
                    Io.putU64(hdr, 1652, cfg.dtbAddr);
                }
            }
        } else {
            Io.putU32(hdr, 8, kernel.length);
            Io.putU32(hdr, 12, ramdisk.length);
            Io.putU32(hdr, 16, cfg.osVersion);
            Io.putU32(hdr, 20, version == 4 ? 1584 : 1580);
            Io.putU32(hdr, 40, version);
            putStr(hdr, 48, 512, cfg.cmdline);
        }

        try (FileOutputStream fos = new FileOutputStream(outImg)) {
            fos.write(hdr);
            writeAligned(fos, kernel, page);
            writeAligned(fos, ramdisk, page);
            writeAligned(fos, second, page);
            if (version >= 1 && dtbo.length > 0) writeAligned(fos, dtbo, page);
            if (version >= 2 && dtb.length > 0) writeAligned(fos, dtb, page);
        }
        p.log("已生成 " + outImg.getName() + " (boot v" + version + ", " + outImg.length() / 1024 + " KB)");
    }

    // ---------- helpers ----------

    private static byte[] readIfExists(File f, byte[] def) throws IOException {
        if (f.exists() && f.isFile()) return java.nio.file.Files.readAllBytes(f.toPath());
        return def;
    }

    private static boolean isGzip(byte[] b) {
        return b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = gz.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void writeRange(RandomAccessFile raf, File dir, String name, long off, long size)
            throws IOException {
        raf.seek(off);
        byte[] buf = new byte[(int) size];
        raf.readFully(buf);
        Io.writeFile(new File(dir, name), buf);
    }

    private static byte[] readRange(RandomAccessFile raf, long off, long size) throws IOException {
        raf.seek(off);
        byte[] buf = new byte[(int) size];
        raf.readFully(buf);
        return buf;
    }

    private static long align(long v, long page) {
        return (v + page - 1) / page * page;
    }

    private static void writeAligned(FileOutputStream fos, byte[] data, int page) throws IOException {
        if (data.length > 0) {
            fos.write(data);
            int pad = (int) (align(data.length, page) - data.length);
            byte[] z = new byte[pad];
            fos.write(z);
        }
    }

    private static void putStr(byte[] hdr, int off, int len, String s) {
        byte[] b = s.getBytes();
        for (int i = 0; i < len; i++) {
            hdr[off + i] = i < b.length ? b[i] : 0;
        }
    }

    private static String cstr(byte[] b) {
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static BootConfig parseJson(String json) {
        BootConfig cfg = new BootConfig();
        String[] lines = json.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.contains(":")) continue;
            String k = line.substring(0, line.indexOf(':')).trim().replace("\"", "");
            String v = line.substring(line.indexOf(':') + 1).trim();
            v = v.replace(",", "").replace("\"", "");
            try {
                switch (k) {
                    case "version": cfg.version = Integer.parseInt(v); break;
                    case "page_size": cfg.pageSize = Integer.parseInt(v); break;
                    case "kernel_addr": cfg.kernelAddr = Long.decode(v); break;
                    case "ramdisk_addr": cfg.ramdiskAddr = Long.decode(v); break;
                    case "second_addr": cfg.secondAddr = Long.decode(v); break;
                    case "tags_addr": cfg.tagsAddr = Long.decode(v); break;
                    case "dtb_addr": cfg.dtbAddr = Long.decode(v); break;
                    case "os_version": cfg.osVersion = Long.parseLong(v); break;
                    case "name": cfg.name = v; break;
                    case "cmdline": cfg.cmdline = v; break;
                    case "extra_cmdline": cfg.extraCmdline = v; break;
                }
            } catch (Exception ignored) {}
        }
        return cfg;
    }
}
