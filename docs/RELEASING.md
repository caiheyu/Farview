# 签名与发布

仓库：<https://github.com/caiheyu/Farview>。本项目不会自动创建密钥、提交、推送或发布 Release。

1. 在 `app/build.gradle.kts` 增加 `versionCode`，更新 `versionName`，完成测试。
2. Android Studio → Build → Generate Signed App Bundle / APK → APK → release。首次创建自己的正式 keystore，保存在仓库外；之后始终使用同一签名。
3. 用 SDK 的 aapt 和 apksigner 检查正式 APK。运行发布准备脚本，将路径改为实际正式 APK；`BuildToolsDirectory` 指向本机 SDK。

```powershell
$env:JAVA_HOME = 'C:\develop\jdk\zulu-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-release.ps1 `
  -ApkPath '.\app\release\app-release.apk' `
  -BuildToolsDirectory 'C:\develop\android\Sdk\build-tools\36.0.0' `
  -ReleaseNotes '遥映首版'
```

脚本校验包名、版本、SDK、文件大小和单证书签名，拒绝 debuggable APK 与标准 Android debug 证书，生成：

```text
app/build/outputs/github-release/farview.apk
app/build/outputs/github-release/update.json
```

4. 将已测试代码提交并推送，在同一提交建立正式 Release 标签，例如 v0.1.0。将这两个附件同时上传到同一个公开、非预发布 Release。
5. 用旧版正式签名安装测试新版：检查、下载、校验、未知来源授权、系统安装，以及覆盖升级后的相册内容。不要用 debug APK 验证正式签名更新；签名不同无法覆盖安装。

应用通过 GitHub Releases API 确定最新正式版本，随后读取该标签的附件。清单字段与计划一致：schemaVersion、versionName、versionCode、minSdk、apkSha256、signerCertificateSha256、publishedAt、releaseNotes。版本比较使用 versionCode，不按名称字符串比较。

脚本仅准备本地附件，不读取 keystore、不签名、不上传；正式密钥与密码不可加入 Git。首版交付的 debug APK 只用于安装测试。
