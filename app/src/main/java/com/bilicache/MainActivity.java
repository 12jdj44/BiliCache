package com.bilicache;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(36), pad, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 头部
        TextView title = new TextView(this);
        title.setText("Bili Cache");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FB7299"));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("B 站离线缓存增强模块\n支持 8.61.0 - 9.6.0（52 个版本）");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.parseColor("#757575"));
        subtitle.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        root.addView(subtitle, subLp);

        SharedPreferences prefs = getSharedPreferences(BiliPrefs.PREFS_NAME, MODE_PRIVATE);

        // 功能卡片 1：识别旧版缓存
        final Switch swRecognize = new Switch(this);
        swRecognize.setChecked(prefs.getBoolean(BiliPrefs.KEY_RECOGNIZE_OLD_CACHE, true));
        root.addView(buildCard(
                "识别旧版缓存",
                "启动时全盘扫描下载目录，自动把旧版缓存登记进数据库，无需清数据/重置计数；同时禁用“无效缓存清理”",
                swRecognize));

        // 功能卡片 2：导出日志
        final Switch swExportLog = new Switch(this);
        swExportLog.setChecked(prefs.getBoolean(BiliPrefs.KEY_EXPORT_LOG, false));
        root.addView(buildCard(
                "导出日志",
                "开启后，模块日志会持续同步到公共 Download/BiliCache.log（由 B 站进程写入，需重启 B 站生效）；调试排障时开启",
                swExportLog));

        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs1, key) -> {
            boolean recognize = prefs1.getBoolean(BiliPrefs.KEY_RECOGNIZE_OLD_CACHE, true);
            boolean exportLog = prefs1.getBoolean(BiliPrefs.KEY_EXPORT_LOG, false);
            if (swRecognize.isChecked() != recognize) {
                swRecognize.setChecked(recognize);
            }
            if (swExportLog.isChecked() != exportLog) {
                swExportLog.setChecked(exportLog);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);

        swRecognize.setOnCheckedChangeListener((buttonView, isChecked) -> save(BiliPrefs.KEY_RECOGNIZE_OLD_CACHE, isChecked));
        swExportLog.setOnCheckedChangeListener((buttonView, isChecked) -> save(BiliPrefs.KEY_EXPORT_LOG, isChecked));

        // 页脚
        TextView footer = new TextView(this);
        footer.setText("提示：Hook 每次调用都会实时读取开关，修改后立即生效，无需重启。\n\n"
                + "日志位置：Download/BiliCache.log（导出开关开启时），\n"
                + "或 LSPosed 日志中过滤 [BiliCache] 标签。\n\n"
                + "在 B 站「我的 → 设置」页面最底部可以找到本模块入口，直接进入本设置页。");
        footer.setTextSize(12);
        footer.setTextColor(Color.parseColor("#9E9E9E"));
        footer.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(20);
        root.addView(footer, footerLp);

        return scroll;
    }

    private View buildCard(String titleText, String descText, Switch sw) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F6F7F9"));
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#212121"));
        row.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        card.addView(row);

        TextView desc = new TextView(this);
        desc.setText(descText);
        desc.setTextSize(13);
        desc.setTextColor(Color.parseColor("#616161"));
        desc.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(8);
        card.addView(desc, descLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(14);
        card.setLayoutParams(cardLp);
        return card;
    }

    private void save(String key, boolean value) {
        getSharedPreferences(BiliPrefs.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
        // 尽力让 LSPosed 的 XSharedPreferences 可以读到
        try {
            File dir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File xml = new File(dir, BiliPrefs.PREFS_NAME + ".xml");
            if (xml.exists()) {
                dir.setExecutable(true, false);
                xml.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
