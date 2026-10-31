# HyperDuo 照片插件

独立 APK `local.hyperduo.gallery`，可从桌面直接打开设置；HyperDuo 171+ 可统一管理。公开源码：<https://github.com/Yhazrin/HyperDuo-Plugins>。

插件拥有 Personal Assistant 的照片半页 picker/preview/editor hooks。桌面通用坐标、宽度、圆角和 native MAML gates 归宿主，不复制进插件。LSPosed scope 仅 `com.miui.personalassistant`。

## 配置

`content://local.hyperduo.gallery.config` 的 `config` 只向 Assistant、自身和同签名宿主返回两个布尔值。`importLegacy` / `set` 限自身或同签名 `local.duodesktop`；迁移从 extras 的 config Bundle 读取两键，仅一次且不覆盖已修改值。独立设置不受宿主权限限制。

## 公开资源

APK 只携带通用 MAML 和灰色加载占位预览；不包含用户照片、肖像素材、本机路径、验收截图或本机恢复规则。已有设备的用户照片与相册数据库不迁移、不删除。

设置可检查已部署文件，或导出公开资源 ZIP。安装 APK 不等于资源部署完成。电脑安装 adb/Python 3，手机具有用户明确授予的 Root，在原生组件中心取得对应商品授权后，从源码仓库根运行：

```sh
python3 plugin-gallery/tools/stage-gallery-resources.py --serial SERIAL
python3 plugin-gallery/tools/stage-gallery-resources.py --serial SERIAL --apply
```

第一条只显示计划，第二条显式部署。固定 ROM 校验、原生授权包和可容纳4×6的桌面布局是运行条件。工具动态读取UID；发现同名不同内容资源即停止，不覆盖现有文件，不写照片数据库，不重载。
