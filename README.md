# NeteaseCloudMusicForMe

一个基于 **Kotlin + Jetpack Compose** 的第三方网易云音乐 Android 客户端，支持发现、搜索、歌单、登录、播放、歌词、喜欢歌曲同步，以及官方音源不可用时的备用音源播放。
简云音乐官网：https://music.deltabound.top/

## 下载

当前版本：**1.4.0**（Android 8.0 及以上）

## 1.4.0 更新

- 新增「每周推荐」歌单，根据上一完整自然周的有效播放记录生成相似歌曲推荐。
- 优化聆澜音频缓存：迁移到 Android 缓存目录，容量上限提升至 1 GB，并自动迁移旧版本缓存。
- 新增独立的 1 GB 封面磁盘缓存，统一列表、迷你播放器和播放页的封面加载与复用。
- 完善音频焦点与耳机断开处理，降低电话、语音和外部音频场景中的播放冲突。
- 优化搜索、卡密输入与页面切换时的软键盘收起体验。
- 修复每周推荐跨用户、跨周复用旧结果，以及歌曲详情返回乱序等问题。

## 当前重点

- Android 端主路径通过 Kotlin 网络层直接访问所需接口，不需要 Node.js 或本地代理服务。
- 播放优先使用网易云官方音源，官方地址不可读或加载失败时依次尝试聆澜音源和酷狗搜索兜底。
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
- 每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐，以「每周推荐」歌单形式展示在「我的」页歌单列表。

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

聆澜音源的服务地址默认使用 `https://source.shiqianjiang.cn/api/music`，也可以通过 `paidMusicApiUrl`、`PAID_MUSIC_API_URL` 或根目录唯一一份 `lx-music-source-paid-*.js` 中的 `API_URL` 覆盖。卡密不再编译进 APK，由用户在首次启动引导或设置页面中输入并经服务端验证后保存。

简云官方歌曲和图片默认从 `https://music.deltabound.top/` 加载；需要切换站点时，可通过 `jianyunContentBaseUrl`、`JIANYUN_CONTENT_BASE_URL` 或 `local.properties` 中的 `jianyunContentBaseUrl` 覆盖。

### 简云官方歌曲目录

将 [`server/jianyun-music.php`](server/jianyun-music.php) 上传到简云音乐官网根目录，并确认访问 `/jianyun-music.php` 能返回 JSON。该接口会自动扫描根目录下所有 `.mp3` 后缀文件（扩展名不区分大小写）；文件名（不含扩展名）就是歌曲名，歌手统一为「简云官方」，封面统一使用 `assets/app-icon.png`。之后新增歌曲只需把 MP3 上传到服务器根目录，App 会在约 60 秒内从搜索和歌手详情页读取到新作品。接口不可用时会回退到内置的《简云漫游》信息。

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
- 每周推荐：播放合格判定、播放记录去重与裁剪、推荐生成 single-flight、缓存命中零请求、退出登录清理顺序。

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
```

## 说明

本项目用于学习和个人使用。播放能力依赖网易云接口和可用音源状态，部分歌曲可能因为版权、会员、地区或接口限制无法播放。

## 许可

MIT
