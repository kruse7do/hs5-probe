# HS5 Android环境探测器

这是一个不依赖第三方库的轻量Android探测APK，面向红旗HS5 Android 9车机。它同时检查标准Android网络环境和原车T-BOX候选路径。

## 0.2.1探测范围

- 只读检查 `WifiManager`、`WifiP2pManager`、系统特性、权限和全部网络接口；
- 查询 `com.faw.hqzl3.hqvehicleservice.HQVehicleService` 是否存在、启用、导出及其权限；
- 用户点击后尝试绑定上述服务，记录Binder类名和接口描述符，随后自动解绑；
- 在车机的18080端口启动HTTP自检页，列出所有可供iPhone访问的IPv4地址；
- 保留标准 `LocalOnlyHotspot` 测试作为对照项，它失败不代表T-BOX方案失败。

本版本不会调用 `openAP()`。在Binder接口、参数、权限和关闭方法得到实车确认前，不猜测原厂事务编号。

## 不安装Android Studio，使用GitHub Actions编译

需要：

1. 一个GitHub账号；
2. 一个GitHub仓库（公开或私有均可）；
3. 能访问GitHub并下载构建产物；
4. 免费账号需要有可用的Actions额度；公开仓库通常不消耗标准托管Runner额度，私有仓库有每月配额；
5. 本项目不要放入MFi证书、私钥或其他敏感文件。

操作步骤：

1. 在GitHub新建空仓库；
2. 将本目录中的全部文件上传到仓库根目录，确保 `.github/workflows/build-apk.yml` 路径不变；
3. 打开仓库的 **Actions** 页面，选择 **Build HS5 Probe APK**；
4. 点击 **Run workflow**；推送到 `main` 或 `master` 分支时也会自动运行；
5. 构建完成后打开该次运行，在 **Artifacts** 下载 `HS5-Probe-debug-apk.zip`；
6. 解压得到 `app-debug.apk`，改名为 `HS5-Probe-0.1.0.apk` 也可以，不影响安装。

GitHub生成的是调试签名APK，可直接用于探测，不用于正式发布。首次运行先按“重新检测”。测试T-BOX路径时，先通过原车界面手动打开热点，让iPhone连接热点，然后按“启动/停止HTTP自检”，使用Safari访问屏幕列出的 `http://车机地址:18080/`。只有需要验证标准Android热点API时才授予定位权限并按对应的对照按钮。

## 报告位置

点击“保存报告”后，通常写入：

```text
/sdcard/Android/data/com.kruse7do.hs5probe/files/reports/
```

报告会直接显示在屏幕上，即使车机文件管理器无法进入上述目录，也可以拍照记录关键结果。重点关注：

- `WIFI_SERVICE class`
- `WifiManager unavailable`
- `WIFI_P2P_SERVICE class`
- `LocalOnlyHotspot API present`
- 主动测试后的 `ACTIVE RESULT`
- `Network interfaces`
- `FAW T-BOX candidate service`
- `T-BOX BIND RESULT`
- `T-BOX binder descriptor`
- `HTTP HIT`

## 本地编译（以后安装环境后）

项目要求JDK 17、Android SDK 35和Gradle 8.9。可以直接用Android Studio打开，也可以在命令行执行：

```text
gradle :app:assembleDebug
```
