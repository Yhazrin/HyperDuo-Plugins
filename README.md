# HyperDuo Plugins

三个可独立安装的插件，也可由 HyperDuo 171 起的「设置 → 插件库」统一管理。

| 插件 | 安装包 | 运行条件 |
|---|---|---|
| 相册卡片增强 | [Gallery](https://github.com/Yhazrin/HyperDuo-Plugins/releases) | 小米智能助理、LSPosed、组件资源部署 |
| AirPods | [AirPods](https://github.com/Yhazrin/HyperDuo-Plugins/releases) | 小米蓝牙/设置作用域；当前Pro2型号2420 |
| Standby | [Standby](https://github.com/Yhazrin/HyperDuo-Plugins/releases) | Android11起；按需授权照片、通知监听、屏保等 |

完整源码在对应 `plugin-*` 目录。每个APK有独立桌面入口、配置与版本；不用安装其他插件。

## 安装

1. 从 [Releases](https://github.com/Yhazrin/HyperDuo-Plugins/releases) 下载对应APK。首次为alpha预发布，采用当前开发签名。
2. 直接安装并打开插件；AirPods和相册还需在原LSPosed管理器启用各自作用域。
3. 使用HyperDuo管理时，在插件库选择「迁移原配置」：只复制旧配置与编辑数据，不删除本体原件。跨包照片授权、通知监听和屏保按系统提示重新授权。
4. Gallery新增安装不等于资源已部署。设置页可检测/导出资源，电脑端工具 `python3 plugin-gallery/tools/stage-gallery-resources.py --serial SERIAL --apply` 显式部署已授权的支持组件；只使用通用灰色占位图，不附带用户照片。

## 构建

JDK21、AndroidSDK35：

```sh
./gradlew :plugin-gallery:assembleDebug :plugin-airpods:assembleDebug :plugin-standby:assembleDebug
```

独立APK不依赖主程序的源码或运行进程。`:plugin-api`仅含版本、设置入口和同签名迁移协议，没有远程DEX或脚本加载。
统一管理要求与宿主同签名；从源码自行构建者应给宿主和插件使用同一签名。独立使用不要求安装宿主。

主程序系统适配仍由HyperDuo负责，插件不重复写其共享native几何位点。本次只做集中编译、签名和APK边界检查，尚未完成新APK的真机迁移/功能验收。

## 许可

参见 LICENSE、LICENSES 与源码文件头。资源来源和约束以各模块说明为准。
