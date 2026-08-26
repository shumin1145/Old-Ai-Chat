================================================================================
OAC 1.4 源码工程（已修复 TLS 握手 internal_error(80)）
================================================================================

这是一个可以直接编译的标准老式安卓工程（Eclipse / Ant 布局），不是反编译
产物、也不是混搭包。你可以用 Android Studio 当作“无 Gradle 的旧工程”导入，
或直接用下面的 build.ps1 / build.sh 一键出包。

--------------------------------------------------------------------------------
目录结构
--------------------------------------------------------------------------------
OAC_1.4_src/
├── AndroidManifest.xml        # 根清单：package=com.oac.nazhiyazi.op, minSdk=9
├── project.properties         # target=android-28
├── local.properties.template  # 改成 local.properties 后填 sdk.dir
├── build.ps1                  # Windows 一键构建（PowerShell）
├── build.sh                   # Linux / macOS / WSL 一键构建
├── src/com/oac/nazhiyazi/op/  # 全部 Java 源码（19 个 .java，含 R.java 由 aapt 生成）
│   ├── MainActivity.java
│   ├── SettingsActivity.java  # 含“模板AI”按钮（DeepSeek / Google 自动填表）
│   ├── OACApplication.java    # onCreate 里装 SpongyCastle Provider
│   ├── AIClient.java / AIRequest.java / ...
│   └── util/SslHelper.java     # TLS 握手修复核心（SC + SCJSSE 双注册 + SNI）
├── res/                       # 标准资源树（layout/values/drawable/...）
├── libs/                      # 6 个 jar：okhttp-2.7.5, okio-1.6.0,
│                              #   prov-1.58.0.0, core-1.58.0.0,
│                              #   bctls-jdk15on-1.58.0.0, pkix-1.54.0.0
└── gen/  (build 时由 aapt 生成 R.java，已内置一份以便无 SDK 也能 javac 校验)

--------------------------------------------------------------------------------
如何构建（需要 Android SDK）
--------------------------------------------------------------------------------
前置：安装 Android SDK，确保有 build-tools/30.0.3 和 platforms/android-28（或任意
      platform，脚本会自动回退找 android.jar）。无需 NDK，无 .so。

Windows:
  1) 复制 local.properties.template -> local.properties，设 sdk.dir=C:\AndroidSDK
  2) 右键 build.ps1 用 PowerShell 运行，或：  powershell -File build.ps1
  产物：bin\OAC_1.4.apk

Linux / macOS / WSL:
  1) 复制 local.properties.template -> local.properties，设 sdk.dir=/path/to/sdk
     或导出 export ANDROID_HOME=/path/to/sdk
  2)  bash build.sh
  产物：bin/OAC_1.4.apk

签名：脚本会用 keytool 自动生成 oac.keystore（alias=oacbeta，密码 oacbeta123），
      你也可以替换成自己的密钥。APK 同时启用 V1+V2 签名，兼容 Android 2.3。

--------------------------------------------------------------------------------
关键事实（验收点）
--------------------------------------------------------------------------------
- 兼容 Android 2.3：minSdkVersion=9，targetSdkVersion=28。
- 全架构兼容、无原生库：libs 里没有任何 .so，纯 Java（SpongyCastle 提供 TLS）。
- TLS 握手修复：SslHelper.installSpongyCastle() 同时注册 BouncyCastleProvider("SC")
  与 BouncyCastleJsseProvider("SCJSSE")，并对 SC socket 反射设置 SNI
  （BCSNIHostName），解决 Cloudflare 前置端点的 internal_error(80)。
- “模板AI”按钮：在“添加模型”窗口的“测试连接”下方，选 DeepSeek / Google 可自动
  回填名称 / 地址 / 模型ID / Key 并设为优化模式。
- 方法数 < 65536，单 classes.dex，无需 multidex。

--------------------------------------------------------------------------------
说明
--------------------------------------------------------------------------------
本工程为可编译源码交付物。之前误交付的 zip 是把手写 Java、反编译 smali、
平铺的资源 XML 混在一起的“大杂烩”，无法 import/编译——那份已作废，以此为准。
