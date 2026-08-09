package com.bilicache;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);
        text.setPadding(48, 48, 48, 48);
        text.setTextSize(16);
        text.setText("Bili Cache\n\n"
                + "LSPosed 模块：让 B 站 8.61.0-9.6.0（52 个版本）\n"
                + "直接扫描识别旧版离线缓存。\n\n"
                + "启用步骤：\n"
                + "1. 在 LSPosed 管理器里勾选本模块；\n"
                + "2. 作用域勾选 tv.danmaku.bili；\n"
                + "3. 重启；\n"
                + "4. 把旧缓存目录放进\n"
                + "   Android/data/tv.danmaku.bili/download/ 下，\n"
                + "   重启 B 站即可自动识别。");
        setContentView(text);
    }
}
