# NeteaseCloudMusicForMe

一个基于 **Kotlin + Jetpack Compose** 的第三方网易云音乐 Android 客户端，支持发现、搜索、歌单、登录、播放、歌词、喜欢歌曲同步，以及官方音源不可用时的备用音源播放。

## 最近修复

- 修复二维码登录后 Cookie 保存不完整，导致大量官方歌曲返回 403、手机号网页登录却能正常播放的问题。
- 二维码登录成功后会合并二维码会话 Cookie、设备标识 Cookie 和最终 `MUSIC_U` Cookie，避免播放鉴权丢失。
- 官方音源仍优先使用；播放前会用轻量请求确认官方 CDN 可读，明确 403/404 后才切换备用音源。
- 播放器媒体请求统一携带 Cookie、Referer、Origin 和网易云桌面 UA，减少官方 CDN 鉴权失败。
- 调整播放页进度条、拖动圆点和歌词样式，降低视觉厚重感，避免歌词滚动时突然换行。
- 登录页支持自定义网页登录和二维码登录两种方式。

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

### 本地 Node 服务

仓库仍保留 `server.js` 和 `unblock.js`，用于本地 API 代理和备用音源实验。当前 Android 端主要通过 Kotlin 网络层直接请求所需接口。

## 技术栈

### Android

- Kotlin
- Jetpack Compose + Material 3
- ViewModel + Repository
- Retrofit / OkHttp
- Media3 ExoPlayer
- Gradle 8.4 / AGP 8.2.2 / Kotlin 2.0

### Node

- Node.js
- NeteaseCloudMusicApi
- 本地默认端口：`3000`

## 构建

使用 Android Studio 打开项目根目录并同步 Gradle，或在命令行执行：

```powershell
$env:JAVA_HOME='D:\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

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
server.js           本地 API 代理
unblock.js          备用音源实验模块
design/             设计素材
```

## 说明

本项目用于学习和个人使用。播放能力依赖网易云接口和可用音源状态，部分歌曲可能因为版权、会员、地区或接口限制无法播放。

## 许可

MIT
