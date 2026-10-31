// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone configuration; resource operations only follow an explicit tap. */
public final class GallerySettingsActivity extends Activity {
    private static final int EXPORT_RESOURCES = 701;
    private TextView resourceStatus;
    private static final String DEPLOYMENT = "部署步骤（电脑端，需要 adb、Python 3 和已授权的 Root）\n公开源码：https://github.com/Yhazrin/HyperDuo-Plugins\n"
        + "1. 将导出的 ZIP 解压到公开插件源码仓库根目录。\n"
        + "2. 先在原生小米组件中心下载／授权本插件支持的相册组件。\n"
        + "3. 在仓库根目录执行：\n"
        + "python3 plugin-gallery/tools/stage-gallery-resources.py --apply --serial SERIAL\n"
        + "将 SERIAL 替换为 adb devices 中的手机序列号。工具默认只打印计划，--apply 才写资源；保留原授权包、组件记录和照片。\n"
        + "现有部署工具要求对应的固定 ROM／组件版本；桌面需要使用可容纳 4×6 的布局。缺少条件会停止，不会因导出资源而视为可用。\n"
        + "4. 在 LSPosed 为本插件启用“小米智能助理”作用域。\n";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        text(content, "照片组件", 24);
        addSwitch(content, "相册 4×6 半页组件", GalleryConfig.SPAN);
        addSwitch(content, "使用相册原生编辑器", GalleryConfig.EDIT);
        text(content, "本插件可独立安装和设置，不要求安装 HyperDuo。需要 LSPosed 与“小米智能助理”作用域。宿主最低版本 171 只用于统一插件管理。", 16);
        resourceStatus = text(content, "资源状态：尚未检测。安装 APK 不等于已部署相册资源；已部署设备继续使用原路径。", 16);
        Button check = new Button(this);
        check.setText("检查已部署资源（Root，只读）");
        check.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("检查资源")
            .setMessage("将请求 Root，只读取原 PA/Home 路径的就绪标记和组件文件是否存在。不部署、不重载、不修改布局或照片。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始检查", (dialog, which) -> checkResources(check)).show());
        content.addView(check);
        Button export = new Button(this);
        export.setText("导出公开占位模板部署包");
        export.setOnClickListener(view -> {
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "HyperDuo-Gallery-resources.zip");
            startActivityForResult(create, EXPORT_RESOURCES);
        });
        content.addView(export);
        text(content, DEPLOYMENT, 14);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void checkResources(Button check) {
        check.setEnabled(false);
        resourceStatus.setText("资源状态：正在执行只读检测…");
        new Thread(() -> {
            String result;
            try {
                String command = "set -e\n"
                    + "test \"$(id -u)\" = 0\n"
                    + "pa=/data/user/0/com.miui.personalassistant/files\n"
                    + "home=/data/user_de/0/com.miui.home/files/maml/res/0\n"
                    + "test -f \"$pa/duo-gallery-halfpage-v1/ready.json\"\n"
                    + "test \"$(cat \"$pa/duo-gallery-halfpage-v3/protocol-ready\")\" = DUO_GALLERY_NATIVE_23_V3\n"
                    + "for pair in e96919c6-105a-4b6e-986e-9eb910e1fa15:40117 10538035-e37d-4edd-88db-1420c9ccaa04:40116 1648abf9-db10-4941-8083-9a5e68241c75:40116; do\n"
                    + "id=${pair%:*}; ver=${pair#*:}\n"
                    + "test -f \"$pa/duo-gallery-halfpage-v3/resources/$id/$ver/$id/widget_4x6\"\n"
                    + "test -f \"$home/$id/$ver/$id.duo4x6.zip\"\n"
                    + "done\necho RESOURCE_FILES_PRESENT\n";
                Process process = new ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                result = exit == 0 ? "资源文件与就绪标记已存在。组件 SHA／ROM 条件和 LSPosed 作用域由插件实际运行时检查。"
                    : "资源检查未通过（exit=" + exit + "）。可能缺少部署文件或 Root 未授权。\n" + output;
            } catch (Exception error) { result = "资源检测失败：" + error.getMessage(); }
            String message = result;
            runOnUiThread(() -> { resourceStatus.setText(message); check.setEnabled(true); });
        }, "gallery-resource-check").start();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_RESOURCES || result != RESULT_OK || data == null) return;
        Uri destination = data.getData();
        new Thread(() -> {
            String message;
            try (OutputStream output = getContentResolver().openOutputStream(destination);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                exportAssets(zip, "gallery-halfpage");
                zip.putNextEntry(new ZipEntry("GALLERY-DEPLOY.txt"));
                zip.write(DEPLOYMENT.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                message = "资源包已导出：" + destination + "\n按页面中的电脑端命令部署；本次只写入你选择的 ZIP，没有改 PA/Home 资源。";
            } catch (Exception error) { message = "导出失败：" + error.getMessage(); }
            String status = message;
            runOnUiThread(() -> resourceStatus.setText(status));
        }, "gallery-resource-export").start();
    }

    private void exportAssets(ZipOutputStream zip, String path) throws Exception {
        String[] children = getAssets().list(path);
        if (children.length != 0) {
            for (String child : children) exportAssets(zip, path + "/" + child);
            return;
        }
        zip.putNextEntry(new ZipEntry("plugin-gallery/src/main/assets/" + path));
        try (InputStream input = getAssets().open(path)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }

    private TextView text(LinearLayout content, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); content.addView(text);
        return text;
    }
    private void addSwitch(LinearLayout content, String label, String key) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setChecked(GalleryConfig.prefs(this).getBoolean(key, true));
        control.setOnCheckedChangeListener((button, enabled) ->
            GalleryConfig.prefs(this).edit().putBoolean(key, enabled).apply());
        content.addView(control);
    }
}
