# AirPods

独立APK：`local.hyperduo.airpods`。可直接安装并从桌面打开；不依赖HyperDuo或其他插件。

运行条件：小米蓝牙、设置。API 1，主程序统一管理从171版本起兼容。

独立配置/入口在本模块src/main；所有迁移只复制数据，Provider写入受同签名权限限制。

[下载](https://github.com/Yhazrin/HyperDuo-Plugins/releases)。本次为预发布，编译/签名/边界检查通过，真机迁移与功能验收待进行。


## 1.0.1 设置界面

设置页使用共享 `:plugin-ui` 的 MIUIX 组件，包含安全区、横屏挖孔避让、深浅色和 Yhazrin / HyperDuo 主项目链接。功能代码与配置仍由本插件独立保存。
HyperDuo 172 可通过签名限定的 `config/set` 接口读取与修改素材开关。
