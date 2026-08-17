package com.zhiyu.dna;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zhiyu.dna.engine.DnaEngine;
import com.zhiyu.dna.engine.Progress;
import com.zhiyu.dna.engine.ToolPaths;
import com.zhiyu.dna.ui.GlassScene;
import com.zhiyu.dna.ui.DotIndicator;
import com.zhiyu.dna.ui.GlassButton;
import com.zhiyu.dna.ui.GlassCard;
import com.zhiyu.dna.ui.GlassPager;
import com.zhiyu.dna.ui.GlassSwitch;
import com.zhiyu.dna.ui.GlassSegmented;
import com.zhiyu.dna.ui.LogView;
import com.zhiyu.dna.util.Binaries;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int REQ_INPUT_FILE = 1001;
    static final int REQ_OUTPUT_DIR = 1002;
    static final int REQ_ALL_FILES = 1003;

    private GlassPager pager;
    private DotIndicator dots;
    private GlassSegmented topTabs;
    private TextView permHint;
    private GlassButton permBtn;

    // 解包页状态
    private EditText inputPathEt;
    private EditText outPathEt;
    private TextView typeLabel;
    private GlassSwitch autoPartsSw;
    private LogView unpackLog;

    // 打包页状态
    private EditText packSrcEt;
    private EditText packOutEt;
    private EditText packLabelEt;
    private GlassSegmented formatSeg;
    private LogView packLog;

    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private ToolPaths tools;
    private boolean engineReady;
    private TextView engineStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        buildUi();
    }

    // ================= UI 组装 =================

    private GlassScene scene;
    private LinearLayout topArea;

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        scene = new GlassScene(this);
        FrameLayout root = scene;
        root.setBackgroundColor(0xFFE8EEF7);
        scene.getBackgroundView().setFrameCallback(scene::requestCapture);

        // ===== 顶部区域(品牌标题 + 玻璃顶栏, 整体随滑动视差移动) =====
        LinearLayout topArea = new LinearLayout(this);
        topArea.setOrientation(LinearLayout.VERTICAL);
        topArea.setGravity(Gravity.CENTER_HORIZONTAL);
        topArea.setPadding((int) dp(18), 0, (int) dp(18), 0);

        // 品牌标题: DNA(渐变) + 副标题
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        TextView brand = new TextView(this);
        brand.setText("DNA");
        brand.setTextSize(21);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setTextColor(0xFF169AFF);
        brand.setShadowLayer(dp(8), 0, dp(2), 0x55169AFF);
        brandRow.addView(brand);
        TextView brandSub = new TextView(this);
        brandSub.setText("  固件解包助手");
        brandSub.setTextSize(13);
        brandSub.setTextColor(0xFF5A6B7A);
        brandRow.addView(brandSub);
        topArea.addView(brandRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(30)));

        // 玻璃顶栏: 宽度自适应(左右留 18dp)
        topTabs = new GlassSegmented(this, new String[]{"解包", "打包", "关于"});
        topTabs.setOnSelectedListener(index -> pager.setCurrentPage(index, true));
        topTabs.attachScene(scene);
        topArea.addView(topTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(48)));
        this.topArea = topArea;

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = (int) (dp(8) + statusBarHeight());
        root.addView(topArea, topLp);

        // 分页器
        pager = new GlassPager(this);
        pager.addPage(buildUnpackPage());
        pager.addPage(buildPackPage());
        pager.addPage(buildAboutPage());
        FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        pagerLp.topMargin = (int) (dp(96) + statusBarHeight());
        pagerLp.bottomMargin = (int) dp(40);
        root.addView(pager, pagerLp);

        pager.setOnPageChangedListener((index, pos) -> {
            boolean dragging = pager.isDragging();
            topTabs.setPosition(pos, dragging);   // 胶囊 1:1 跟手, 松手弹簧回位
            dots.setPosition(pos);                // 指示点连续
            if (dragging) {
                // 拖动: 顶栏整体跟随手指(按页面宽度的 30% 移动, 强跟手感)
                float shift = -pos * pager.getWidth() * 0.30f;
                topTabs.setTranslationX(shift * 0.55f);
                if (topArea != null) topArea.setTranslationX(shift);
            } else {
                // 松手: 顶栏弹性归位(液态过冲)
                topTabs.animate().translationX(0).setDuration(340)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();
                if (topArea != null) topArea.animate().translationX(0).setDuration(340)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();
            }
        });

        // 底部指示器
        dots = new DotIndicator(this);
        dots.setCount(3);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, (int) dp(12), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotLp.bottomMargin = (int) dp(12);
        root.addView(dots, dotLp);

        setContentView(root);

        // 存储权限检查
        checkStoragePermission();
        // 初始化引擎
        ensureEngine(null);
        // 自检(输出到 logcat, 便于验证): /sdcard 可写性
        worker.execute(() -> {
            try {
                File out = Binaries.defaultOutDir(this);
                out.mkdirs();
                File probe = new File(out, ".probe");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(probe);
                fos.write(1);
                fos.close();
                probe.delete();
                android.util.Log.i("DNA", "SDCARD_OK " + out.getAbsolutePath());
            } catch (Exception e) {
                android.util.Log.e("DNA", "SDCARD_FAIL " + e.getMessage());
            }
        });
    }

    private float statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------------- 解包页 ----------------

    private View buildUnpackPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("解包镜像"));

        // 输入文件卡片
        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        c1.addView(fieldLabel("镜像文件"));
        inputPathEt = glassEdit();
        inputPathEt.setHint("点右侧按钮选择, 或直接输入路径(含 /sdcard/...)");
        c1.addView(inputPathEt);
        GlassButton browseBtn = new GlassButton(this, "浏览", GlassButton.STYLE_GLASS);
        browseBtn.setOnClickListener(v -> pickInputFile());
        c1.addView(browseBtn);
        typeLabel = new TextView(this);
        typeLabel.setText("未选择文件");
        typeLabel.setTextColor(0xFF696E73);
        typeLabel.setTextSize(13);
        c1.addView(typeLabel);
        col.addView(card1, cardLp());

        // 输出目录卡片
        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("解包输出目录"));
        outPathEt = glassEdit();
        outPathEt.setText(Binaries.defaultOutDir(this).getAbsolutePath());
        c2.addView(outPathEt);
        GlassButton dirBtn = new GlassButton(this, "选择目录", GlassButton.STYLE_GLASS);
        dirBtn.setOnClickListener(v -> pickOutputDir());
        c2.addView(dirBtn);
        col.addView(card2, cardLp());

        // 选项卡片
        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText("自动解包 payload/super 分区(耗时更长)");
        tv.setTextColor(0xFF1C242C);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        autoPartsSw = new GlassSwitch(this);
        autoPartsSw.setChecked(true);
        row.addView(autoPartsSw);
        c3.addView(row);
        col.addView(card3, cardLp());

        GlassButton runBtn = new GlassButton(this, "开始解包", GlassButton.STYLE_PRIMARY);
        runBtn.setOnClickListener(v -> startUnpack());
        col.addView(runBtn, btnLp());

        unpackLog = new LogView(this);
        unpackLog.setVisibility(View.GONE);
        col.addView(unpackLog, logLp());

        return scrollWrap(col);
    }

    // ---------------- 打包页 ----------------

    private View buildPackPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("打包镜像"));

        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        c1.addView(fieldLabel("源目录(解包产物 / 要打包的文件夹)"));
        packSrcEt = glassEdit();
        packSrcEt.setHint("选择或输入目录路径");
        c1.addView(packSrcEt);
        GlassButton b1 = new GlassButton(this, "选择目录", GlassButton.STYLE_GLASS);
        b1.setOnClickListener(v -> pickPackSrc());
        c1.addView(b1);
        col.addView(card1, cardLp());

        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("输出文件"));
        packOutEt = glassEdit();
        packOutEt.setHint("例如 /sdcard/DNA/out/system.img");
        c2.addView(packOutEt);
        GlassButton b2 = new GlassButton(this, "浏览保存位置", GlassButton.STYLE_GLASS);
        b2.setOnClickListener(v -> pickPackOut());
        c2.addView(b2);
        c2.addView(fieldLabel("ext4 卷标(可选)"));
        packLabelEt = glassEdit();
        packLabelEt.setHint("system / vendor ...");
        c2.addView(packLabelEt);
        col.addView(card2, cardLp());

        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        c3.addView(fieldLabel("输出格式"));
        formatSeg = new GlassSegmented(this, new String[]{"ext4 镜像", "sparse 镜像", "boot 镜像"});
        c3.addView(formatSeg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(44)));
        col.addView(card3, cardLp());

        GlassButton runBtn = new GlassButton(this, "开始打包", GlassButton.STYLE_PRIMARY);
        runBtn.setOnClickListener(v -> startPack());
        col.addView(runBtn, btnLp());

        packLog = new LogView(this);
        packLog.setVisibility(View.GONE);
        col.addView(packLog, logLp());

        return scrollWrap(col);
    }

    // ---------------- 关于页 ----------------

    private View buildAboutPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("关于"));

        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        TextView title = new TextView(this);
        title.setText("DNA 固件解包助手");
        title.setTextColor(0xFF1C242C);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        c1.addView(title);
        TextView ver = new TextView(this);
        ver.setText("版本 1.0.0  ·  包名 com.zhiyu.dna");
        ver.setTextColor(0xFF696E73);
        ver.setTextSize(12);
        ver.setGravity(Gravity.CENTER);
        c1.addView(ver);
        TextView author = new TextView(this);
        author.setText("作者: Zhiyu · cuoxianxu");
        author.setTextColor(0xFF169AFF);
        author.setTextSize(13);
        author.setGravity(Gravity.CENTER);
        author.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c1.addView(author);
        engineStatus = new TextView(this);
        engineStatus.setText("引擎: 初始化中 ...");
        engineStatus.setTextColor(0xFF169AFF);
        engineStatus.setTextSize(13);
        engineStatus.setGravity(Gravity.CENTER);
        c1.addView(engineStatus);
        GlassButton initBtn = new GlassButton(this, "初始化 / 修复引擎", GlassButton.STYLE_GLASS);
        initBtn.setOnClickListener(v -> ensureEngine(unpackLog != null ? unpackLog : packLog));
        c1.addView(initBtn);
        col.addView(card1, cardLp());

        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("原理(移植自 D.N.A3)"));
        c2.addView(infoLine("· 魔数识别: 不靠后缀, 直接读文件头判断 zip / sparse / ext4 / super / payload / boot 等格式"));
        c2.addView(infoLine("· sparse→raw: 解析 Android sparse 块表(RAW/FILL/DONTCARE)展开为完整镜像"));
        c2.addView(infoLine("· super.img: 解析 liblp geometry + metadata 表, 按 extent 切出各分区"));
        c2.addView(infoLine("· ext4: 内置 debugfs 提取文件树, 不依赖 root"));
        c2.addView(infoLine("· new.dat: 解析 transfer list, 按块区间写回镜像; .br 用 brotli 解压"));
        c2.addView(infoLine("· payload.bin: 内置 payload-dumper-go 提取分区"));
        c2.addView(infoLine("· boot.img: 解析头部 + cpio/gzip 解包 ramdisk"));
        col.addView(card2, cardLp());

        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        c3.addView(fieldLabel("支持格式"));
        c3.addView(chipsRow(new String[]{"*.img", "*.sparse", "super.img", "payload.bin",
                "*.new.dat", "*.dat.br", "*.zip", "*.br", "boot.img", "*.gz"}));
        col.addView(card3, cardLp());

        GlassCard card4 = new GlassCard(this);
        card4.attachScene(scene);
        LinearLayout c4 = cardColumn(card4);
        c4.addView(infoLine("无需 root: 解包只需读取镜像文件, 只有\"从手机物理分区导出镜像\"才需要 root, 本应用不包含该功能。"));
        c4.addView(infoLine("输出默认保存到 /sdcard/DNA/out, 路径可自定义。"));
        c4.addView(infoLine("来源: github.com/ColdWindScholar/D.N.A3 (MIT)"));
        col.addView(card4, cardLp());

        return scrollWrap(col);
    }

    // ================= 页面构建工具 =================

    private LinearLayout pageColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) dp(20), (int) dp(10), (int) dp(20), (int) dp(20));
        return col;
    }

    private LinearLayout cardColumn(GlassCard card) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) dp(18), (int) dp(16), (int) dp(18), (int) dp(16));
        card.addView(c);
        return c;
    }

    private View scrollWrap(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private FrameLayout.LayoutParams cardLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private FrameLayout.LayoutParams btnLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(54));
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private FrameLayout.LayoutParams logLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(220));
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private View sectionTitle(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 渐变强调条
        View bar = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF64C6FF, 0xFF169AFF, 0xFF9C6BFF});
        gd.setCornerRadius(dp(4));
        bar.setBackground(gd);
        bar.setElevation(dp(2));
        row.addView(bar, new LinearLayout.LayoutParams((int) dp(7), (int) dp(26)));
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF1C242C);
        tv.setTextSize(23);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setShadowLayer(dp(3), 0, dp(1), 0x33000000);
        tv.setPadding((int) dp(10), 0, 0, 0);
        row.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(14);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView fieldLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF696E73);
        tv.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(4);
        lp.bottomMargin = (int) dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView infoLine(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF3A4149);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(2), 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout chipsRow(String[] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (String it : items) {
            TextView chip = new TextView(this);
            chip.setText(it);
            chip.setTextColor(0xFF169AFF);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) dp(12), (int) dp(6), (int) dp(12), (int) dp(6));
            chip.setBackground(roundedRect(0x22FFFFFF, 0x66169AFF, dp(16)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) dp(8);
            lp.bottomMargin = (int) dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private android.graphics.drawable.Drawable roundedRect(int fill, int stroke, float radius) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke((int) dp(1), stroke);
        return gd;
    }

    private EditText glassEdit() {
        EditText et = new EditText(this);
        et.setTextColor(0xFF1C242C);
        et.setHintTextColor(0xFFA0A6AD);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        et.setBackground(roundedRect(0x99FFFFFF, 0x77FFFFFF, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    // ================= 存储权限 =================

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            // 直接引导到「所有文件访问」设置
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALL_FILES);
            } catch (Exception e) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
                } catch (Exception e2) {
                    // 无法打开设置页, 继续使用 SAF
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 30) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    // ================= 文件选择 =================

    private void pickInputFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try {
            startActivityForResult(i, REQ_INPUT_FILE);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    private void pickOutputDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, REQ_OUTPUT_DIR);
        } catch (Exception e) {
            toast("无法打开目录选择器");
        }
    }

    private void pickPackSrc() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, REQ_OUTPUT_DIR + 10);
        } catch (Exception e) {
            toast("无法打开目录选择器");
        }
    }

    private void pickPackOut() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "output.img");
        try {
            startActivityForResult(i, REQ_OUTPUT_DIR + 20);
        } catch (Exception e) {
            toast("无法打开保存选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_INPUT_FILE && data.getData() != null) {
                String path = resolvePath(data.getData());
                inputPathEt.setText(path);
                updateTypeLabel(path);
            } else if (requestCode == REQ_OUTPUT_DIR && data.getData() != null) {
                String path = resolveTreePath(data.getData());
                if (path != null) outPathEt.setText(path);
            } else if (requestCode == REQ_OUTPUT_DIR + 10 && data.getData() != null) {
                String path = resolveTreePath(data.getData());
                if (path != null) packSrcEt.setText(path);
            } else if (requestCode == REQ_OUTPUT_DIR + 20 && data.getData() != null) {
                String path = resolvePath(data.getData());
                if (path != null) packOutEt.setText(path);
            }
        } catch (Exception e) {
            toast("解析路径失败: " + e.getMessage());
        }
    }

    /** 把 content:// 解析为真实路径(尽力而为); 解析不到则原样保留 uri。 */
    private String resolvePath(Uri uri) {
        String path = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) path = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (path != null && new File(path).exists()) return path;
        return uri.toString();
    }

    private String resolveTreePath(Uri treeUri) {
        try (android.database.Cursor c = getContentResolver().query(treeUri,
                new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) {
                    String p = c.getString(idx);
                    if (p != null && new File(p).isDirectory()) return p;
                }
            }
        } catch (Exception ignored) {
        }
        String docId = treeUri.getLastPathSegment();
        if (docId != null && docId.startsWith("primary:")) {
            return Environment.getExternalStorageDirectory() + "/" + docId.substring(8);
        }
        return null;
    }

    private void updateTypeLabel(String path) {
        File f = new File(path);
        if (!f.exists()) {
            typeLabel.setText("文件不存在");
            typeLabel.setTextColor(0xFFF44336);
            return;
        }
        com.zhiyu.dna.engine.ImgType.Type t = com.zhiyu.dna.engine.ImgType.detect(f);
        typeLabel.setText("识别类型: " + com.zhiyu.dna.engine.ImgType.label(t)
                + "  ·  " + (f.length() / 1048576) + " MB");
        typeLabel.setTextColor(0xFF169AFF);
    }

    // ================= 引擎 =================

    private void ensureEngine(LogView log) {
        engineStatus = engineStatus != null ? engineStatus : new TextView(this);
        worker.execute(() -> {
            try {
                ToolPaths p = Binaries.ensure(this, s -> post(() -> {
                    if (log != null) log.append(s);
                    if (engineStatus != null) engineStatus.setText("引擎: " + s);
                }));
                tools = p;
                engineReady = true;
                post(() -> {
                    if (engineStatus != null) engineStatus.setText("引擎: 就绪 ✓ (" + tools.debugfs.getName() + ")");
                });
            } catch (Exception e) {
                engineReady = false;
                post(() -> {
                    if (engineStatus != null) engineStatus.setText("引擎: 初始化失败 - " + e.getMessage());
                });
            }
        });
    }

    private void startUnpack() {
        String input = inputPathEt.getText().toString().trim();
        String out = outPathEt.getText().toString().trim();
        if (input.isEmpty()) { toast("请先选择镜像文件"); return; }
        if (out.isEmpty()) { toast("请设置输出目录"); return; }
        unpackLog.setVisibility(View.VISIBLE);
        unpackLog.clear();
        final ToolPaths t = tools;
        worker.execute(() -> {
            try {
                if (t == null || !engineReady) {
                    ToolPaths p = Binaries.ensure(this, null);
                    tools = p;
                }
                final ToolPaths tt = tools;
                DnaEngine.unpack(new File(input), new File(out), autoPartsSw.isChecked(), tt,
                        uiProgress(unpackLog, "解包"));
            } catch (Exception e) {
                post(() -> unpackLog.append("[失败] " + e.getMessage()));
            }
        });
    }

    private void startPack() {
        String src = packSrcEt.getText().toString().trim();
        String out = packOutEt.getText().toString().trim();
        String label = packLabelEt.getText().toString().trim();
        if (src.isEmpty()) { toast("请选择源目录"); return; }
        if (out.isEmpty()) { toast("请设置输出文件"); return; }
        packLog.setVisibility(View.VISIBLE);
        packLog.clear();
        worker.execute(() -> {
            try {
                if (tools == null) tools = Binaries.ensure(this, null);
                int fmt = formatSeg.getSelectedIndex();
                Progress p = uiProgress(packLog, "打包");
                if (fmt == 0) {
                    DnaEngine.packExt4(new File(src), new File(out), label, tools, p);
                } else if (fmt == 1) {
                    DnaEngine.packSparse(new File(src), new File(out), p);
                } else {
                    DnaEngine.packBoot(new File(src), new File(out), tools, p);
                }
            } catch (Exception e) {
                post(() -> packLog.append("[失败] " + e.getMessage()));
            }
        });
    }

    private Progress uiProgress(LogView log, String tag) {
        return new Progress() {
            @Override
            public void log(String line) {
                post(() -> log.append(line));
            }

            @Override
            public void progress(int percent) {
                post(() -> log.setProgress(percent));
            }

            @Override
            public void done(boolean ok, String message) {
                post(() -> log.append(ok ? "✅ " + message : "❌ " + message));
            }
        };
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }
}
