package com.zhiyu.dna.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * cpio newc("070701") 归档读写 —— 用于 ramdisk 解包/打包。
 */
public final class Cpio {

    public static final int S_IFMT = 0170000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFIFO = 0010000;
    public static final int S_IFSOCK = 0140000;

    private Cpio() {}

    /**
     * 从 cpio 字节流解包到 outDir。
     * @return 条目数
     */
    public static int extract(byte[] data, File outDir, Progress p) throws IOException {
        int pos = 0;
        int count = 0;
        if (data.length < 6 || !new String(data, 0, 6, "ASCII").equals("070701")) {
            throw new IOException("不是 cpio newc 归档");
        }
        while (true) {
            if (pos + 110 > data.length) break;
            String magic = new String(data, pos, 6, "ASCII");
            if (!magic.equals("070701")) break;
            long ino = hex(data, pos + 6, 8);
            long mode = hex(data, pos + 14, 8);
            long uid = hex(data, pos + 22, 8);
            long gid = hex(data, pos + 30, 8);
            long nlink = hex(data, pos + 38, 8);
            long mtime = hex(data, pos + 46, 8);
            long filesize = hex(data, pos + 54, 8);
            long devmajor = hex(data, pos + 62, 8);
            long devminor = hex(data, pos + 70, 8);
            long rdevmajor = hex(data, pos + 78, 8);
            long rdevminor = hex(data, pos + 86, 8);
            long namesize = hex(data, pos + 94, 8);
            long check = hex(data, pos + 102, 8);
            pos += 110;
            if (namesize <= 0 || namesize > 65536) break;
            byte[] nameBytes = new byte[(int) namesize];
            if (pos + namesize > data.length) break;
            System.arraycopy(data, pos, nameBytes, 0, (int) namesize);
            pos += (int) namesize;
            pos = align4(pos);
            String name = new String(nameBytes, 0, (int) namesize - 1, "UTF-8"); // 去掉结尾 NUL
            if (name.equals("TRAILER!!!")) break;

            byte[] content = new byte[(int) filesize];
            if (pos + filesize > data.length) throw new IOException("cpio 数据截断: " + name);
            System.arraycopy(data, pos, content, 0, (int) filesize);
            pos += (int) filesize;
            pos = align4(pos);

            long type = mode & S_IFMT;
            int perm = (int) (mode & 07777);
            File target = new File(outDir, name);
            if (type == S_IFDIR) {
                target.mkdirs();
                try { target.setExecutable((perm & 0111) != 0, false); } catch (Exception ignored) {}
            } else if (type == S_IFLNK) {
                String link = new String(content, "UTF-8");
                if (target.exists()) target.delete();
                try { Files.createSymbolicLink(target.toPath(), new File(link).toPath()); }
                catch (Exception e) { Io.writeFile(target, content); }
            } else if (type == S_IFREG || type == 0) {
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                Io.writeFile(target, content);
                target.setReadable(true, false);
                target.setWritable(true, false);
                target.setExecutable((perm & 0111) != 0, false);
            } else {
                // 设备节点等 —— 无 root 时写占位文件
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                Io.writeFile(target, content);
            }
            count++;
            if (p != null && count % 200 == 0) p.log("  解包 ramdisk: " + count + " 个条目");
        }
        return count;
    }

    /** 从目录打包 cpio newc 归档(ramdisk 打包用)。 */
    public static byte[] pack(File root, Progress p) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
        writeDir(bos, root, "", p);
        // trailer
        writeHeader(bos, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, "TRAILER!!!");
        return bos.toByteArray();
    }

    private static void writeDir(OutputStream out, File dir, String prefix, Progress p) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = prefix + f.getName();
            boolean symlink = Files.isSymbolicLink(f.toPath());
            if (symlink) {
                String target = Files.readSymbolicLink(f.toPath()).toString();
                int mode = S_IFLNK | 0777;
                byte[] content = target.getBytes("UTF-8");
                writeHeader(out, 0, mode, 0, 0, 1, 0, content.length, 0, 0, 0, 0, 0, 0, 0, name);
                writeContent(out, content);
            } else if (f.isDirectory()) {
                int mode = S_IFDIR | permOf(f);
                writeHeader(out, 0, mode, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, name);
                writeDir(out, f, name + "/", p);
            } else {
                byte[] content = java.nio.file.Files.readAllBytes(f.toPath());
                int mode = S_IFREG | permOf(f);
                writeHeader(out, 0, mode, 0, 0, 1, 0, content.length, 0, 0, 0, 0, 0, 0, 0, name);
                writeContent(out, content);
            }
        }
    }

    private static int permOf(File f) {
        int perm = 0;
        if (f.canRead()) perm |= 0444;
        if (f.canWrite()) perm |= 0222;
        if (f.canExecute()) perm |= 0111;
        return perm;
    }

    private static void writeHeader(OutputStream out, long ino, long mode, long uid, long gid, long nlink,
                                    long mtime, long filesize, long devmajor, long devminor,
                                    long rdevmajor, long rdevminor, long namesize, long check,
                                    long _pad, String name) throws IOException {
        StringBuilder sb = new StringBuilder(110);
        sb.append("070701");
        sb.append(String.format("%08x", ino));
        sb.append(String.format("%08x", mode));
        sb.append(String.format("%08x", uid));
        sb.append(String.format("%08x", gid));
        sb.append(String.format("%08x", nlink));
        sb.append(String.format("%08x", mtime));
        sb.append(String.format("%08x", filesize));
        sb.append(String.format("%08x", devmajor));
        sb.append(String.format("%08x", devminor));
        sb.append(String.format("%08x", rdevmajor));
        sb.append(String.format("%08x", rdevminor));
        sb.append(String.format("%08x", name.length() + 1));
        sb.append(String.format("%08x", check));
        out.write(sb.toString().getBytes("ASCII"));
        out.write(name.getBytes("UTF-8"));
        out.write(0);
        // 对齐基准是 header(110) + namesize(含 NUL), 使数据从 4 字节边界开始
        int pad = (4 - ((110 + name.length() + 1) % 4)) % 4;
        for (int i = 0; i < pad; i++) out.write(0);
    }

    private static void writeContent(OutputStream out, byte[] content) throws IOException {
        out.write(content);
        pad4(out, content.length);
    }

    private static void pad4(OutputStream out, int size) throws IOException {
        int pad = (4 - (size % 4)) % 4;
        for (int i = 0; i < pad; i++) out.write(0);
    }

    private static int align4(int pos) {
        return (pos + 3) & ~3;
    }

    private static long hex(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            char c = (char) b[off + i];
            int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
            else return 0;
            v = (v << 4) | d;
        }
        return v;
    }
}
