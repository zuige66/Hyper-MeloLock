package io.github.melolock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Four-page, dependency-free settings UI inspired by HyperIsland's card layout. */
public final class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(74, 91, 220);
    private static final int TEXT = Color.rgb(30, 34, 48);
    private static final int MUTED = Color.rgb(105, 111, 130);
    private final List<View> pages = new ArrayList<>();
    private LinearLayout pageHost;
    private Button toggle;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 252));
        root.setPadding(dp(18), dp(18), dp(18), dp(10));
        TextView title = text("Hyper MeloLock", 28, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("Hyper MeloLock  ·  OS3", 14, MUTED), marginParams(-1, -2, 0, 4, 0, 12));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        String[] names = {"首页", "音乐应用", "外观", "关于"};
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            Button tab = button(names[i], 14);
            tab.setOnClickListener(v -> showPage(page));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        root.addView(tabs, marginParams(-1, -2, 0, 0, 0, 10));
        FrameLayout host = new FrameLayout(this);
        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        host.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        pages.add(homePage()); pages.add(musicPage()); pages.add(appearancePage()); pages.add(aboutPage());
        showPage(0);
    }

    private View homePage() {
        LinearLayout content = page();
        LinearLayout hero = card();
        hero.addView(text("MODULE STATUS", 12, MUTED));
        TextView status = text(Config.enabled(this) ? "已启用" : "准备就绪", 30, TEXT);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        hero.addView(status, marginParams(-1, -2, 0, 6, 0, 0));
        hero.addView(text("锁屏时显示正在播放的音乐，并保留系统解锁与紧急操作。", 15, MUTED));
        toggle = button(Config.enabled(this) ? "关闭MeloLock" : "开启MeloLock", 16);
        toggle.setOnClickListener(v -> { if (!Config.setEnabled(this, !Config.enabled(this))) Toast.makeText(this, "保存开关失败", Toast.LENGTH_SHORT).show(); refresh(); });
        hero.addView(toggle, marginParams(-1, dp(48), 0, 18, 0, 0));
        content.addView(hero);
        LinearLayout info = card();
        addSection(info, "当前适配", "Redmi Note 9 Pro · HyperOS 3 · SystemUI 精确版本门禁");
        addSection(info, "工作方式", "通过 Android MediaSession 获取封面与播放控制，不修改音乐 App 数据。");
        addSection(info, "安全提示", "更新 APK 后请重新检查 Vector 总开关和 SystemUI 作用域。");
        content.addView(info, marginParams(-1, -2, 0, 12, 0, 0));
        return scroll(content);
    }

    private View musicPage() {
        LinearLayout content = page();
        LinearLayout intro = card();
        intro.addView(text("选择要接管的音乐应用", 20, TEXT));
        intro.addView(text("只有勾选的播放器会出现在锁屏沉浸界面。未选择任何应用时默认允许所有媒体会话。", 14, MUTED), marginParams(-1, -2, 0, 8, 0, 0));
        content.addView(intro);
        LinearLayout list = card();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), PackageManager.GET_META_DATA);
        Set<String> selected = Config.allowedPackages(this);
        if (services.isEmpty()) {
            list.addView(text("暂未发现声明媒体服务的应用。先安装播放器并打开一次，再返回此页。", 14, MUTED));
        } else {
            for (ResolveInfo info : services) {
                String packageName = info.serviceInfo.packageName;
                CheckBox check = new CheckBox(this);
                check.setText(info.loadLabel(pm)); check.setTextSize(16); check.setTextColor(TEXT);
                check.setCompoundDrawablesWithIntrinsicBounds(info.loadIcon(pm), null, null, null); check.setCompoundDrawablePadding(dp(12)); check.setPadding(0, dp(8), 0, dp(8));
                check.setChecked(selected.isEmpty() || selected.contains(packageName)); check.setTag(packageName);
                check.setOnCheckedChangeListener((button, checked) -> saveMusicSelection(list));
                list.addView(check, new LinearLayout.LayoutParams(-1, dp(58)));
            }
        }
        content.addView(list, marginParams(-1, -2, 0, 12, 0, 0));
        content.addView(text("提示：应用是否提供标准 MediaSession 由播放器本身决定。", 13, MUTED));
        return scroll(content);
    }

    private void saveMusicSelection(ViewGroup list) {
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < list.getChildCount(); i++) { View child = list.getChildAt(i); if (child instanceof CheckBox && ((CheckBox) child).isChecked()) selected.add((String) child.getTag()); }
        if (!Config.setAllowedPackages(this, selected)) Toast.makeText(this, "保存音乐应用失败", Toast.LENGTH_SHORT).show();
    }

    private View appearancePage() {
        LinearLayout content = page();
        LinearLayout radiusCard = card();
        radiusCard.addView(text("封面圆角", 19, TEXT));
        TextView radiusLabel = text(Config.cornerRadiusDp(this) + " dp", 14, MUTED);
        radiusCard.addView(radiusLabel, marginParams(-1, -2, 0, 5, 0, 0));
        SeekBar radius = new SeekBar(this); radius.setMax(48); radius.setProgress(Config.cornerRadiusDp(this));
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b, int v, boolean u) { radiusLabel.setText(v + " dp"); } public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) { Config.setCornerRadiusDp(MainActivity.this, b.getProgress()); } });
        radiusCard.addView(radius); content.addView(radiusCard);

        LinearLayout styleCard = card();
        styleCard.addView(text("背景样式", 19, TEXT));
        styleCard.addView(text("改变锁屏背景的模糊与遮罩风格", 14, MUTED), marginParams(-1, -2, 0, 6, 0, 4));
        LinearLayout styleRow = new LinearLayout(this);
        String[] styles = {"深色玻璃", "浅色玻璃", "纯色沉浸"};
        for (int i = 0; i < styles.length; i++) { final int value = i; Button b = button(styles[i], 13); b.setOnClickListener(v -> Config.setOverlayStyle(this, value)); styleRow.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1)); }
        styleCard.addView(styleRow); content.addView(styleCard, marginParams(-1, -2, 0, 12, 0, 0));

        LinearLayout colorCard = card(); colorCard.addView(text("遮罩颜色与强度", 19, TEXT));
        LinearLayout colors = new LinearLayout(this); int[] palette = {0xFF111827, 0xFF253B80, 0xFF5B2C83, 0xFF14532D, 0xFF000000};
        for (int color : palette) { Button chip = new Button(this); chip.setBackground(round(color, 22)); chip.setOnClickListener(v -> Config.setOverlayColor(this, color)); colors.addView(chip, marginParams(dp(42), dp(42), 0, 0, 8, 0)); }
        colorCard.addView(colors);
        TextView alphaLabel = text("遮罩强度：" + Config.overlayAlpha(this), 14, MUTED); colorCard.addView(alphaLabel, marginParams(-1, -2, 0, 6, 0, 0));
        SeekBar alpha = new SeekBar(this); alpha.setMax(255); alpha.setProgress(Config.overlayAlpha(this));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b, int v, boolean u) { alphaLabel.setText("遮罩强度：" + v); } public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) { Config.setOverlayAlpha(MainActivity.this, b.getProgress()); } });
        colorCard.addView(alpha); content.addView(colorCard); return scroll(content);
    }

    private View aboutPage() {
        LinearLayout content = page(); LinearLayout hero = card(); hero.setGravity(Gravity.CENTER);
        TextView logo = text("HMSC", 34, BLUE); logo.setTypeface(null, android.graphics.Typeface.BOLD); hero.addView(logo);
        hero.addView(text("Hyper MeloLock", 19, TEXT), marginParams(-1, -2, 0, 5, 0, 0)); hero.addView(text("为 HyperOS 打造的MeloLock模块", 14, MUTED)); content.addView(hero);
        LinearLayout developer = card(); addSection(developer, "开发者", "Hyper MeloLock Team"); addSection(developer, "基于", "HyperIsland 的页面设计思路；模块逻辑保持本项目独立实现。");
        Button github = button("打开 GitHub 项目", 15); github.setOnClickListener(v -> open("https://github.com/1812z/HyperIsland")); developer.addView(github, marginParams(-1, dp(46), 0, 12, 0, 0)); content.addView(developer, marginParams(-1, -2, 0, 12, 0, 0));
        content.addView(text("开源许可证：AGPL-3.0（本项目）\nHyperIsland：MIT License", 13, MUTED)); return scroll(content);
    }

    private void refresh() { if (toggle != null) toggle.setText(Config.enabled(this) ? "关闭MeloLock" : "开启MeloLock"); }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private void showPage(int index) { pageHost.removeAllViews(); pageHost.addView(pages.get(index), new LinearLayout.LayoutParams(-1, -1)); }
    private LinearLayout page() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private ScrollView scroll(View child) { ScrollView view = new ScrollView(this); view.setFillViewport(true); view.addView(child); return view; }
    private LinearLayout card() { LinearLayout box = page(); box.setPadding(dp(18), dp(16), dp(18), dp(16)); box.setBackground(round(Color.WHITE, 24)); return box; }
    private void addSection(LinearLayout parent, String heading, String body) { parent.addView(text(heading, 13, BLUE)); parent.addView(text(body, 15, TEXT), marginParams(-1, -2, 0, 12, 0, 0)); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String value, int size) { Button b = new Button(this); b.setText(value); b.setTextSize(size); b.setTextColor(TEXT); b.setAllCaps(false); b.setBackground(round(Color.WHITE, 18)); return b; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) {} }
}
