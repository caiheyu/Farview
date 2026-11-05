# 遥映 Farview

一台安卓手机拍摄，另一台在同一热点内实时查看。原生 Kotlin / Jetpack Compose 应用，最低 Android 8.0（API 26），包名 `com.github.caiheyu.farview`。

## 使用

1. 任选一台手机开启个人热点，另一台连接该热点。不需要移动数据或互联网。
2. 两台手机打开遥映，分别选择「拍摄端」「观看端」，允许相机权限。
3. 用观看端扫描拍摄端的临时二维码，连接后即可查看和控制。
4. 观看端手动开始、停止录像。无声 MP4 保存到观看手机的 `DCIM/Farview`，可从系统相册打开。
5. 主动结束后需要重新扫码；意外断线会自动重连，录像不会自动续录。

实际镜头、补光和变焦范围来自 Camera2 能力查询。自动画质以 720p 起步，稳定且有余量时尝试 1080p；也可手动锁定。录像中切换镜头或分辨率会自动分段。手动旋转仅改变观看预览，录像保留拍摄传感器方向标记。

「黑屏继续拍摄」隐藏预览并降低亮度。真正熄屏请按手机电源键；前台相机服务和持续通知负责维持拍摄。vivo / iQOO 的后台管理仍可能暂停相机，需在目标机型上验证；没有新画面时观看端会明确遮罩提示。

## 构建

使用 Android Studio，安装 Android SDK 36.1，Gradle JVM 选择 JDK 21。项目自带 Gradle Wrapper。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试安装包：`app/build/outputs/apk/debug/app-debug.apk`。调试 APK 用于双机验证；公开更新使用同一正式密钥签名的 release APK。

Windows 环境变量若有不配对的引号，Gradle 的测试进程可能无法启动。可使用进程内设置，不改变系统环境：

```powershell
$env:Path = $env:Path.Replace('"', '')
.\gradlew.bat "-Djava.library.path=$env:JAVA_HOME\bin" :app:testDebugUnitTest --no-daemon
```

## 实现与验证

- [技术方案](docs/ARCHITECTURE.md)：配对、TLS、本地视频、相机控制和录像生命周期。
- [真机验收](docs/TESTING.md)：vivo X200s / iQOO Neo7 SE 的双向角色、熄屏、离线、录像和断线测试。
- [版本发布](docs/RELEASING.md)：正式签名、更新清单和 GitHub Release。

核心拍摄流程不连接互联网，不申请麦克风权限，不保存配对设备。只有用户手动打开项目、检查或下载更新时访问 GitHub。仓库地址为 <https://github.com/caiheyu/Farview>。
