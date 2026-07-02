# NeteaseCloudMusicForMe

一个基于 **Kotlin + Jetpack Compose** 的第三方网易云音乐 Android 客户端，支持发现、搜索、歌单、登录、播放、歌词、喜欢歌曲同步，以及官方音源不可用时的备用音源播放。

## 当前重点

- Android 端主路径已经改为 Kotlin 网络层直接访问所需接口，不依赖本地 Node 代理才能运行。
- 播放优先使用网易云官方音源，官方地址不可读或加载失败时再尝试备用音源。
- 登录支持网页登录 Cookie 同步和二维码登录，播放请求会携带 Cookie、Referer、Origin 和网易云桌面 UA。
- 已加入 JVM 单元测试、Android Lint 配置和 GitHub Actions CI，避免只靠人工回归。

## 功能

### Android 客户端 (`app/`)

- 发现页：推荐内容、歌单、排行榜、新歌入口。
- 搜索：支持歌曲、歌单等内容搜索。
- 播放器：播放、暂停、上一首、下一首、进度拖动、封面和歌词展示。
- 播放模式：顺序播放、单曲循环、随机播放。
- 音质选择：标准、较高、极高、无损。
- 官方优先：优先使用网易云官方播放地址。
- 备用音源：官方音源不可用或加载超时时，尝试切换可用备用源。
- 登录：支持网页登录 Cookie 同步和二维码登录。
- 喜欢歌曲：在播放器内喜欢/取消喜欢，并同步真实账号状态。
- 后台播放：Media3 ExoPlayer、前台播放服务和系统通知栏控制。

### Legacy Node 工具 (`tools/legacy-node/`)

`tools/legacy-node/server.js` 和 `tools/legacy-node/unblock.js` 是早期本地 API 代理和备用音源实验工具。它们不属于当前 Android 主运行路径，仅保留给手动对照、抓接口或回归旧实验使用。

如需启动旧工具：

```powershell
npm install
npm start
```

默认监听端口为 `3000`。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- ViewModel + Repository
- Retrofit / OkHttp
- Media3 ExoPlayer
- Gradle / Android Gradle Plugin / Kotlin
- JUnit 4

## 构建

先确保本机安装 JDK 17。Android Studio 用户可以直接打开项目根目录并同步 Gradle。

命令行构建：

```powershell
.\gradlew.bat --no-daemon assembleDebug
```

如果本机没有全局 JDK，可临时设置自己的 JDK 17 路径，例如：

```powershell
$env:JAVA_HOME='<your-jdk-17-path>'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 质量门禁

本地验证建议至少执行：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

当前单元测试覆盖的第一批高风险纯逻辑：

- Repository 策略：Cookie 合并、备用音质映射、不可播放提示优先级。
- 备用音源匹配：歌名归一化、艺人/时长匹配、误匹配拒绝。
- 播放队列窗口：空队列、循环窗口、当前歌曲不在队列时的回退。
- 我的音乐状态：喜欢歌单计数、缓存歌单增删同步。

CI 配置在 `.github/workflows/android.yml`，会在 push 和 pull request 时运行：

```text
testDebugUnitTest
lintDebug
assembleDebug
```

Lint 规则入口为 `lint.xml`。

## 项目结构

```text
app/
  src/main/java/com/ncm/app/
    data/
      api/          网络接口
      model/        数据模型
      repository/   数据仓库、登录与音源处理
    playback/       播放器、播放服务、通知栏
    ui/
      navigation/   导航
      screens/      各页面
      theme/        主题
    viewmodel/      页面与播放状态
  src/test/          JVM 单元测试
tools/legacy-node/  早期本地代理和备用音源实验工具
design/             设计素材
```

## 说明

本项目用于学习和个人使用。播放能力依赖网易云接口和可用音源状态，部分歌曲可能因为版权、会员、地区或接口限制无法播放。

## 许可

MIT
