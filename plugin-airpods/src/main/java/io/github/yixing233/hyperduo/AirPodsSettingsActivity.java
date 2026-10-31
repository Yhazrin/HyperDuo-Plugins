package io.github.yixing233.hyperduo;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Standalone launcher and optional host-library entry; all settings live in this APK. */
public final class AirPodsSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.setFillViewport(true);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        TextView title=text("AirPods",26);
        content.addView(title);
        content.addView(text("连接素材与蓝牙设置静态图 · AirPods Pro 2（2420）",15),row());
        Switch enabled=new Switch(this);
        enabled.setText("启用 AirPods 素材");
        enabled.setTextSize(18);
        enabled.setChecked(AirPodsPluginConfig.preferences(this)
                .getBoolean(AirPodsPluginConfig.ENABLED,false));
        content.addView(enabled,row());
        TextView status=text(enabled.isChecked()?"已开启，下次连接会话生效。":"已关闭，使用系统原有素材。",15);
        content.addView(status,row());
        enabled.setOnCheckedChangeListener((button,checked)->{
            AirPodsPluginConfig.preferences(this).edit().putBoolean(AirPodsPluginConfig.ENABLED,checked).apply();
            status.setText(checked?"已开启，下次连接会话生效。":"已关闭，使用系统原有素材。");
        });
        content.addView(text("在原 LSPosed 管理器启用本插件，并勾选“小米蓝牙”和“设置”。主程序不再加载 AirPods Hook。",15),row());
        content.addView(text("配置和素材随本插件独立保存，无需安装 HyperDuo 主程序。开关不改变配对记录或蓝牙连接。",15),row());
        Button back=new Button(this);
        back.setText("返回");
        back.setOnClickListener(view->finish());
        content.addView(back,row());
        setContentView(scroll);
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams result=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.topMargin=dp(20);
        return result;
    }
    private TextView text(String value,int size) {
        TextView view=new TextView(this);
        view.setText(value);view.setTextSize(size);
        return view;
    }
}
