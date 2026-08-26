# DNA 解包助手 (DNA-no-root)

免 root 的 Android 固件镜像解包/打包工具。核心逻辑移植自 [ColdWindScholar/D.N.A3](https://github.com/ColdWindScholar/D.N.A3)(MIT) 与 [hanfume/TIK5](https://github.com/hanfume/TIK5),UI 为液态玻璃(Liquid Glass)风格,带弹簧物理滑动效果。

- 包名:`com.zhiyu.dna`
- 作者:Zhiyu · cuoxianxu
- 无需 root:解包只是读文件;只有"从手机物理分区导出镜像"才需要 root,本应用不包含该功能

## 功能

| 方向 | 格式 |
| ---- | ---- |
| 解包 | ext2/3/4 `*.img`、Android sparse 镜像、`super.img`(liblp 动态分区)、`payload.bin`(OTA)、`boot.img`(含 ramdisk)、`erofs`、`*.new.dat`(+`.dat.br`、分段 `.dat.1~N`)、`*.zip`(自动识别内部 payload/.dat/.img)、`*.br`、`*.gz`、`*.lz4` |
| 打包 | 目录 → ext4 镜像(含 fs_config/SELinux 上下文)、raw → sparse 镜像、解包目录 → boot.img(通过 magiskboot) |

解包路径可自定义(默认 `/sdcard/DNA/out`),支持 Android 11+「所有文件访问」与 SAF 文件选择器。

## 原理(移植自 D.N.A3 + TIK5)

1. **魔数识别**(`gettype.py` → `ImgType.java`):不靠扩展名,直接读文件头判断 zip / sparse / ext4 / super / payload / boot 等格式。
2. **sparse → raw**(`sparse_img.py` → `SparseImage.java`):解析 28 字节文件头 + RAW/FILL/DONTCARE 块表,展开为完整镜像。
3. **super.img**(`lpunpack.py` → `SuperUnpacker.java`):纯结构体解析 liblp 的 geometry(偏移 4096)+ metadata 表(分区/扩展/组/块设备),按 extent 的扇区偏移切出各分区。
4. **ext4**:内置 `debugfs`(`rdump`)提取文件树;打包用 `mke2fs -d` + `e2fsdroid` 应用 fs_config 与 SELinux 上下文(确保打包后可引导)。
5. **new.dat**(`sdat2img.py` → `Sdat2Img.java`):解析 transfer list 的 `new` 命令,按块区间从数据文件顺序读回写镜像;`.br` 用内置 brotli 解压。
6. **payload.bin**:内置 `payload-dumper-go` 提取分区。
7. **boot.img**:通过 `magiskboot` 解包/打包(支持 v0~v4 头部、AVB 签名、多压缩格式),替代旧的手动 Java 解析。
8. **erofs**:通过 `extract.erofs`(来自 TIK5)提取 erofs 文件系统。
9. **super 打包**:通过 `lpmake` 合成动态分区镜像。

## 内置工具(来自 TIK5)

| 工具 | 用途 |
|------|------|
| `magiskboot` | boot 镜像解包/打包(正确支持 AVB 签名) |
| `e2fsdroid` | ext4 镜像应用 fs_config 与 SELinux 上下文 |
| `lpmake` | 动态分区 super 镜像合成 |
| `extract.erofs` | erofs 文件系统提取 |
| `make_ext4fs` | 兼容旧设备的 ext4 打包 |
| `debugfs` / `mke2fs` | ext4 提取与打包 |
| `payload-dumper-go` | payload.bin 分区提取 |

## 滑动/玻璃效果

移植自 [Liquidglass.js](https://github.com/nicholasgasior/liquid-glass)(用户提供的参考实现):

- **欠阻尼弹簧**(K=300, ζ≈0.55):翻页回弹带液态过冲
- **最小二乘速度估算**(100ms 窗口):甩动判定更跟手
- **视差 + 缩放**:相邻页有深度感
- **玻璃渲染**:背景低分辨率捕获 + 折射采样(卡片内背景微放大)+ 上缘镜面高光 + 柔和阴影
- 顶部 tab 胶囊弹簧跟手,按钮按压弹性形变

## 构建(在 Termux / aarch64 上)

依赖:

```bash
pkg install -y aapt2 openjdk-17 d8 apksigner e2fsprogs brotli git
```

- `android.jar`(platform-34):放到 `../build/tools/platform-34/android-34/android.jar`(相对本目录),或修改 `build.sh` 里的 `ANDROID_JAR`。
- `assets/bin/` 下的原生二进制(aarch64,termux 编译)直接随包分发;`payload-dumper-go` 取自 [ssut/payload-dumper-go](https://github.com/ssut/payload-dumper-go) 的 `linux_arm64` 静态构建;TIK5 工具取自 [hanfume/TIK5](https://github.com/hanfume/TIK5)。

构建:

```bash
bash build.sh            # 正式签名 APK
DEBUG_BUILD=1 bash build.sh   # 可调试版本
```

产物:`build/apk/DNA-解包助手-v1.0.apk`

## 免责声明

本工具仅用于学习与合法的固件解包/定制。使用本工具修改系统镜像造成的一切后果由使用者自行承担。