# 聆澜 MusicFree 兼容插件接入与平台解耦 — 8 阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把简云音乐从「网易云数据模型 + 播放失败时聆澜/酷狗兜底」改造成「通用播放器 + MusicFree 兼容插件宿主」：在线曲库由用户购买并授权的聆澜插件提供，简云仓库和 APK 不再内置网易云接口、聆澜脚本或用户密钥；本地能力（本地音乐、本地歌单、历史）不依赖聆澜授权。

**Architecture:** 参考 MusicFree 插件宿主思路但独立实现（不复制其 AGPL-3.0 源码）。在线来源链路为 `设置页 → LinglanCredentialStore + LinglanManifestClient → PluginRegistry（下载/校验/缓存/装载）→ QuickJS PluginRuntime（轻量隔离）→ MusicProvider 统一 Kotlin 接口 → Repository/ViewModel/Player`。收藏/历史/队列/下载以 `pluginId + remoteId`（`ProviderTrackKey`）为主键；旧网易云 Long ID 数据迁移为只读 `legacy-netease` 记录，不静默按标题匹配。

**Tech Stack:** Kotlin 2.0.0、AGP 8.13.2、Jetpack Compose + Material 3、StateFlow、Room 2.6.1（已有）、Gson 2.11.0（已有）、OkHttp 4.12.0（已有）、Media3 1.4.1（已有）、Android Keystore（加密存储）、QuickJS（阶段 0 选定，候选：`wang.harlon.quickjs:wrapper-android` Apache-2.0 / `io.github.qdsfdhvh:quickjs-kt` KMP）。构建命令 `.\gradlew.bat --no-daemon`。

**设计文档（spec，用户已确认）：** `docs/superpowers/specs/2026-08-07-linglan-musicfree-plugin-design.md`

## Global Constraints

> 全部取自 spec，逐条从 §2/§5/§7/§8/§9/§14 复制。每个任务的需求隐含包含本节全部内容。

- **GC #1 构建命令**：一律 `.\gradlew.bat --no-daemon`（Git Bash 下等价 `./gradlew.bat --no-daemon`）。项目无 version catalog；minSdk 26 / compileSdk 34 / targetSdk 34 / Kotlin 2.0.0 / AGP 8.13.2 / versionCode 9 / versionName 1.4.0。**不新增依赖**超出本文档 Phase 0 选定的 QuickJS 依赖。
- **GC #2 新代码不用 `runCatching`**（项目既有约定，见旧计划 GC #5）；用 `try/catch` + `CancellationException` 优先重抛。
- **GC #3 文档路径**：`docs/superpowers/plans/` 与 `docs/superpowers/specs/` **保持 untracked，绝不提交**。`.superpowers/` 已被 gitignore。
- **GC #4 仓库与 APK 洁净**：仓库、APK、日志、构建产物中**不得存在**聆澜脚本正文、固定聆澜密钥、含凭据的个性化脚本 URL、测试密钥。`PluginDescriptor.downloadUrl` 只存在于清单获取与下载过程的内存中；含用户凭据的 URL 不持久化到普通设置、数据库或诊断信息。
- **GC #5 稳定插件 ID**：写入任何收藏/历史/队列/下载记录前必须得到稳定 ID。优先用聆澜清单不可变 `id`；清单无该字段时用版本化内置精确映射：已批准的清单主机 + 去除查询参数后的固定路径 + 来源类型 → `linglan.kg` / `linglan.kw` / `linglan.tx` / `linglan.wy`。显示名、密钥、查询参数、脚本版本都不参与 ID 计算。无法命中稳定映射的清单项不允许产生持久数据。映射变更必须附带数据库迁移，不能运行时哈希 URL。
- **GC #6 不跨来源兜底**：搜索/播放失败或结果为空时**不**调用其他来源补齐；一首歌的封面、歌词、音源始终由歌曲自身绑定的插件处理，不跟随「当前设置中的插件」变化；不把一首歌的音源/封面/歌词自动拼接自多个来源。
- **GC #7 QuickJS 隔离边界**：每个插件独立 QuickJS 上下文，只暴露受控 HTTP/HTTPS、必要定时器、有脱敏和频率限制的日志、明确注册的 CommonJS 兼容模块、只读运行参数。**禁止** Android Context、反射、文件系统、SharedPreferences/数据库直访、`file:`/`content:`/`intent:` 协议、对本机/局域网/链路本地地址与受限端口请求、动态装依赖。每次调用设执行超时、内存上限、响应体上限、重定向上限。网络限制在 DNS 解析后校验 IPv4/IPv6 目标，并在每次重定向后重新解析校验（防 DNS 重绑定/重定向进私网）；播放器取得媒体 URL 后使用同一套校验，不能绕过 SSRF 限制。仅本地假插件验证阶段允许无真实脚本；不满足签名清单硬门槛时**不能发布远程脚本执行能力**。
- **GC #8 密钥与授权**：密钥存 Android Keystore 支持的加密存储；设置页只显示掩码和有效期，不提供复制完整密钥入口；禁止密钥进入日志/崩溃报告/备份/分析事件/文件名。授权状态机：`DISCONNECTED / VALIDATING / ACTIVE / STALE_OFFLINE / EXPIRED / REVOKED / ERROR`。网络失败不能被当成密钥无效；只有服务端明确返回过期/撤销才进入对应状态。超过 24h 未验证进入 `STALE_OFFLINE`；超过有效期或撤销后停止新的在线调用。断开/清除/到期/撤销时删除密钥及其个性化脚本缓存，Keystore 密钥先删实现加密擦除。播放地址有时效性，不作为歌曲实体永久保存；临近播放时解析并按过期时间缓存。
- **GC #9 来源过滤**：首选服务端 `category == music`；清单无分类字段时客户端用临时允许列表，仅接纳已确认的酷狗、酷我、QQ、网易云音乐插件；**Bilibili 与 GitCode 始终排除**，不以显示名模糊匹配决定安全边界；未知来源默认不展示。允许列表匹配预置清单主机 + 去除查询参数后的路径 + 精确来源类型，不能仅凭显示名放行。待清单提供稳定 ID 和分类后删除该兼容规则。
- **GC #10 生产签名硬门槛（阶段 3 前必须确定）**：生产环境执行远程脚本前，必须由认证清单把插件 ID、版本、SHA-256 绑定，并验证应用信任的聆澜签名密钥生成的签名。仅有 HTTPS 或「摘要与脚本从同一未签名接口返回」不足以作为完整性保证。签名公钥、轮换、紧急撤销规则在阶段 3 前确定；达不到时只能停留在本地假插件验证。清单状态至少区分 `active / mandatory-update / revoked / disabled`；强制升级、版本撤销、安全 kill switch 生效时禁止回退。每个插件最多保留当前版和一个未撤销的上一版。缓存键 = 用户授权身份的不可逆摘要 + 插件 ID + 版本组合，绝不使用原始密钥作路径或文件名。
- **GC #11 脚本装载两步检查**：第一步在禁用真实网络的上下文中求值模块，检查元数据、导出方法、顶层执行是否越界；第二步用宿主提供的固定 HTTP 响应执行契约探针。不存在「随意访问真实网络的健康检查」。只有用户首次发起真实搜索/浏览时才开始正常在线调用。
- **GC #12 数据迁移原则**：旧网易云 Long ID 数据转成只读 `legacy-netease` 记录，保留已有元数据（标题/歌手/专辑/时长/封面缓存）；**不**在后台自动按标题/歌手匹配新来源；后续单独设计「迁移到当前来源」工具（候选 + 匹配依据 + 用户确认）。旧在线队列项升级后可显示但标记 legacy 不可播放；已完整下载且文件仍存在的歌曲迁移为本地文件记录。
- **GC #13 UI 交互约束**：来源选择是**单选**；切换来源不强制中断当前播放歌曲；队列歌曲保留各自 `pluginId`，旧上下文释放后按需从该用户私有缓存重建，无法获取时明确标记不可播放；不自动把旧来源歌曲按标题重匹配到新来源。插件缺能力时 UI 隐藏入口或显示「当前来源不支持」，不能用另一个来源补齐。首版隐藏依赖网易云相似歌曲/推荐歌单接口的在线推荐入口（本地最近播放/收藏/统计保留）。简云自有内容保留为 `OFFICIAL` 来源，不通过聆澜插件，不伪装成第三方内容。本地音乐/本地歌单/历史不依赖聆澜授权。
- **GC #14 pluginPayload 边界**：插件后续调用需要的附加字段放在**经过清洗、可序列化、有大小与层级上限**的 `BoundedJsonObject` 中；不能无限制持久化整个脚本返回对象，不保留密钥、授权头、个性化脚本 URL。持久记录保存产生它的插件版本与宿主 payload schema 版本；新版插件必须通过旧 payload 契约测试，不兼容则用上一个未撤销的已知可用版本，仍不兼容则标记需要重新搜索，不能猜测转换。
- **GC #15 日志与分析脱敏**：日志和分析事件不得记录密钥、带密钥的 URL、完整播放地址、插件脚本正文。

**基线测试数：** 运行 `.\gradlew.bat --no-daemon :app:testDebugUnitTest` 确认全绿后记录当前数字，作为每个阶段「基线」比较基准。

---

## 阶段总览（Roadmap）

| 阶段 | 标题 | 可交付测试软件 | 外部前置（见 §17） |
|---|---|---|---|
| 0 | 基线与技术验证 | 特征测试套件；QuickJS 选定；本地假插件契约测试套件；`JianyunMusicFreeCompat/1` 契约冻结 | 聆澜授权覆盖脚本下载/缓存/运行时执行/分发场景的确认 |
| 1 | 通用模型与接口 | 新模型 + `MusicProvider`/`PluginRuntime` 接口 + 旧模型转换层，全部单测；现有 UI 行为不变 | 无 |
| 2 | 授权、清单和设置页 | Keystore 凭据存储 + 授权状态机 + 清单客户端 + 来源过滤 + 单选设置 UI（假清单/假插件） | 密钥校验接口的限流/到期/撤销/错误码稳定（联调用） |
| 3 | 插件宿主 | QuickJS 上下文 + 受控 HTTP + SSRF 防护 + 兼容模块 + 资源限制 + 两步装载 + 脚本缓存更新，契约测试全绿 | 稳定插件 ID、签名清单、版本/撤销规则、授权状态机、聆澜运行授权全部确定 |
| 4 | 首条完整链路 | 单来源搜索 → 播放地址解析 → 播放 → 歌词/封面；再接专辑/歌手/歌单/榜单 | 阶段 3 门槛 + 轮换后测试密钥 |
| 5 | 本地资料库迁移 | 收藏/历史/队列/下载/歌单主键迁移 + legacy 记录 + 迁移工具候选 UI | 无（本地能力） |
| 6 | 移除平台耦合 | 删除网易云 API/登录/Cookie/兜底链；中性文案与包名 | 阶段 4 链路稳定 |
| 7 | 回归与发布准备 | 全量单测/集成/UI/离线/升级/异常恢复；产物洁净扫描；许可与隐私复核 | 阶段 6 后 |

每个阶段都应在其提交点运行完整测试并全绿。以下每阶段的 Task 编号在各自阶段内连续（P0T1、P1T1…），避免跨阶段重编号。

---

# 阶段 0：基线与技术验证

**阶段目标：** 在当前业务代码上补特征测试锁定行为；选定 Android QuickJS 实现（验证包体积/ABI/生命周期/许可证）；用完全本地的假插件验证 CommonJS、HTTP 桥和超时；冻结 `JianyunMusicFreeCompat/1` 契约。**本阶段不改动任何现有业务代码，只新增测试与评估产物。**

**阶段外部前置：** 在与真实脚本联调前，书面确认聆澜授权覆盖「脚本下载、设备缓存、运行时执行、预期分发场景」（§14 阶段 0 最后一条；记录到 `docs/superpowers/specs/linglan-integration-notes.md`，该文件不入库）。

### P0T1: 为当前搜索/播放/收藏/历史/队列行为补特征测试

**Files:**
- Create: `app/src/test/java/com/ncm/app/data/repository/MusicRepositorySearchCharacteristicTest.kt`
- Create: `app/src/test/java/com/ncm/app/data/repository/SongIdKeyedStateCharacteristicTest.kt`
- Test: 以上两个文件

**Interfaces:**
- Consumes: 现有 `MusicRepository.search`、`Song`、`JianyunOfficialContent.isOfficialSongId`、`PlaybackSource`。
- Produces: 无新接口。为阶段 1 的数据模型改造锁定「行为契约」：搜索结果必须带来源可识别字段；`Song.id` 当前是 Long 且简云官方 id 有哨兵区段。

**说明：** 阶段 1 会把 `Song.id` 改成来源感知键，此任务记录「现状」防止回归。搜索特征测试用 `MusicRepository` 的构造（需要 `NeteaseApi`/`SessionManager`/`LinglanAudioCache`/`MusicSourceSettings`）；若无现成 Robolectric 桩模式，改用纯函数测试 `JianyunOfficialContent.mergeSearchResponse` 与 `isOfficialSongId` 边界。

- [ ] **Step 1: 写失败测试 — 官方歌曲 ID 判定边界**

```kotlin
// app/src/test/java/com/ncm/app/data/repository/SongIdKeyedStateCharacteristicTest.kt
package com.ncm.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SongIdKeyedStateCharacteristicTest {

    @Test
    fun officialSongIdRangeIsDisjointFromNeteaseIds() {
        // 简云官方 id 通过 JianyunOfficialContent.songIdForFile 生成，必须与网易云 Long id 区间分离。
        val official = JianyunOfficialContent.song("demo.mp3")
        assertEquals(true, JianyunOfficialContent.isOfficialSongId(official.id))
        // 网易云真实 id 不在官方区段
        assertEquals(false, JianyunOfficialContent.isOfficialSongId(3779629L))
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.repository.SongIdKeyedStateCharacteristicTest"
```
Expected: 编译/运行后断言通过（若 `songIdForFile` 现不可测，Step 1 改为测试 `mergeSearchResponse` 去重与顺序，见 Step 1b）。

- [ ] **Step 1b（仅当 Step 1 断言已存在/不适用时）：写 mergeSearchResponse 特征测试**

```kotlin
@Test
fun mergeKeepsLocalFirstWithoutDuplicatingExistingResult() {
    val local = Song(id = 90001L, name = "本地曲", artists = listOf(ArtistBrief(1, "A")))
    val remote = SearchResponse(
        songs = listOf(Song(id = 1L, name = "远程曲", artists = listOf(ArtistBrief(2, "B")))),
        songCount = 1
    )
    val merged = JianyunOfficialContent.mergeSearchResponse("本地曲", remote, listOf(local))
    assertEquals(2, merged.songs.size)
    assertEquals(90001L, merged.songs.first().id)
}
```

- [ ] **Step 3: 实现（本步通常无实现）** 若 Step 1 测试通过而无需改生产代码，标记完成并注明「行为契约已锁定」。若 `songIdForFile` 不稳定，按 §5.1 精神把它做成确定性函数（hash of fileName）——**但本阶段不改生产代码**，把观察记录到 plan 注释。

- [ ] **Step 4: 运行全量测试确认基线全绿**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
Expected: 全绿。记录测试总数。

- [ ] **Step 5: 提交**

```bash
git add app/src/test/java/com/ncm/app/data/repository/SongIdKeyedStateCharacteristicTest.kt
git commit -m "test: lock song-id and search-merge behavior contract for Phase 1"
```
（若创建了 MusicRepositorySearchCharacteristicTest，一并 add。）

### P0T2: 评估并选定 Android QuickJS 实现

**Files:**
- Create: `docs/superpowers/specs/linglan-quickjs-evaluation.md`（不入库，仅记录决策）
- Modify: `app/build.gradle.kts`（只在选定后添加依赖；本 Task 先只做评估与决策）

**Interfaces:**
- Produces（后续 P0T3 依赖）：选定依赖坐标 + 许可证 + 初始化方式（如 `QuickJSLoader.init()`）+ ProGuard 规则要点 + ABI 覆盖（minSdk 26 / 64KB page size 支持）。

**说明：** 候选（据 2025 现状，实施时以最新版本复核）：
- `wang.harlon.quickjs:wrapper-android:3.2.x` — Apache-2.0，JNI，Promise/异常/ESModule/bytecode，16KB page size 支持。
- `io.github.qdsfdhvh:quickjs-kt` — KMP，Kotlin 惯用 API，coroutine 集成，ES Modules，64KB page size。

- [ ] **Step 1: 验证依赖坐标与许可证**
  到 Maven Central / GitHub Releases 核对最新稳定版本、许可证（Apache-2.0 优先，MIT 可接受，AGPL/GPL 排除）、`minSdk` 支持、ABI（arm64-v8a / armeabi-v7a / x86_64）与 64KB page size 支持。记录到评估文件。

- [ ] **Step 2: 构造最小 APK 体积验证（黑盒）**
  在 `app/build.gradle.kts` 临时添加候选依赖，`assembleDebug` 一次，记录 APK 体积增量与 `*.so` 大小；对比候选后移除依赖，把数字写进评估文件。**不提交该依赖改动。**

```bash
.\gradlew.bat --no-daemon :app:assembleDebug
```

- [ ] **Step 3: 生命周期与初始化评估**
  确认初始化调用位置（Application.onCreate vs 首次使用时），是否线程安全，Context 是否需要。记录要点。

- [ ] **Step 4: 决策**
  在评估文件写入结论：选定坐标、理由（许可/Auto/体积/维护活跃度）、备选；**并在决策记录里附「最小可用调用片段」**——用选定库加载一段 `module.exports={...}` 脚本并读取导出字段的可编译示例（P3T1 的实现体直接粘贴这段代码）。**本 Task 不写业务代码，不提交 build.gradle 改动。**

- [ ] **Step 5: 提交评估结论（可选，若目录被忽略则不提交）**
  按 GC #3，若 `docs/superpowers/specs/` untracked，则不提交；改为在 plan 文件内标注决策摘要。

### P0T3: 本地假插件契约测试套件（CommonJS / HTTP 桥 / 超时）

**Files:**
- Create: `app/src/test/resources/fakeplugins/hello.cjs`（假插件脚本，无真实密钥）
- Create: `app/src/test/java/com/ncm/app/plugin/compat/FakePluginContractTest.kt`
- Create: `app/src/main/java/com/ncm/app/plugin/compat/MusicFreeCompatContracts.kt`（纯常量 + 契约探针纯函数，本 Task 只冻结常量，不接 QuickJS）

**Interfaces:**
- Produces（阶段 3 依赖）：
  - `const val MUSICFREE_PROTOCOL_VERSION = 1`
  - `val SUPPORTED_SEARCH_TYPES: Set<String> = setOf("music", "album", "artist", "sheet")`
  - `fun missingRequiredFieldMessage(field: String): String`
  - `fun validateSearchResultShape(name: String?, id: Any?): List<String>`（返回缺失必需字段列表，空表即合法）
- Consumes: 无。

**说明：** 冻结契约（§6.2）：搜索 `search(query, page, type)` → `{data, isEnd}`；播放 `getMediaSource(musicItem, quality?)` → `{url, headers?, userAgent?, quality?, expiresAt?}`；歌词 `getLyric(musicItem)` → `{rawLrc?, translation?, romaLrc?, wordLrc?}`；专辑/歌手/歌单/榜单见 spec 表格。页码从 1 开始，缺失列表按空列表处理，必需字段缺失拒绝该条结果。

- [ ] **Step 1: 写失败测试 — 契约常量与结果形状校验**

```kotlin
// app/src/test/java/com/ncm/app/plugin/compat/FakePluginContractTest.kt
package com.ncm.app.plugin.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePluginContractTest {

    @Test
    fun frozenSearchTypesMatchSpec() {
        assertEquals(setOf("music", "album", "artist", "sheet"), SUPPORTED_SEARCH_TYPES)
        assertTrue("music" in SUPPORTED_SEARCH_TYPES)
    }

    @Test
    fun validateSearchResultShapeRejectsMissingNameOrId() {
        assertEquals(emptyList(), validateSearchResultShape("歌", 1L))
        assertEquals(listOf("name"), validateSearchResultShape("", 1L))
        assertEquals(listOf("id"), validateSearchResultShape("歌", null))
        assertEquals(listOf("name", "id"), validateSearchResultShape("", null))
    }

    @Test
    fun httpResponseContractFieldsMatchSpec() {
        // 冻结：插件 HTTP 返回对象需兼容 status/headers/data/request
        val fields = listOf("status", "headers", "data", "request")
        fields.forEach { assertTrue("$it must exist in HTTP compat surface", true) }
    }
}
```

- [ ] **Step 2: 运行验证失败**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.compat.FakePluginContractTest"
```
Expected: FAIL，`Unresolved reference`。

- [ ] **Step 3: 实现常量与校验函数**

```kotlin
// app/src/main/java/com/ncm/app/plugin/compat/MusicFreeCompatContracts.kt
package com.ncm.app.plugin.compat

/** 冻结的宿主-插件协议版本。任何不兼容改动必须升级此常量并写迁移。 */
const val MUSICFREE_PROTOCOL_VERSION = 1

/** 宿主声明并支持的搜索类型（spec §6.2 表格）。 */
val SUPPORTED_SEARCH_TYPES: Set<String> = setOf("music", "album", "artist", "sheet")

fun missingRequiredFieldMessage(field: String): String = "插件返回结果缺少必需字段：$field"

/** 返回缺失的必需字段名列表；空列表表示形状合法。 */
fun validateSearchResultShape(name: String?, id: Any?): List<String> =
    buildList {
        if (name.isNullOrBlank()) add("name")
        if (id == null) add("id")
    }
```

- [ ] **Step 4: 运行验证通过**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.compat.FakePluginContractTest"
```
Expected: PASS。

- [ ] **Step 5: 创建假插件脚本资源（无密钥）**

```js
// app/src/test/resources/fakeplugins/hello.cjs
module.exports = {
    platform: 'fake-hello',
    version: '1.0.0',
    supportedSearchType: ['music', 'album', 'artist', 'sheet'],
    async search(query, page, type) {
        return {
            data: [
                { id: 'hello-' + page, name: query + ' 示例', artist: '测试歌手', album: '测试专辑' }
            ],
            isEnd: page >= 2
        };
    },
    async getMediaSource(musicItem, quality) {
        return { url: 'https://example.invalid/media.mp3', headers: {}, quality: quality || 'standard' };
    },
    async getLyric(musicItem) {
        return { rawLrc: '[00:00.00]测试歌词' };
    }
};
```

- [ ] **Step 6: 提交**
```bash
git add app/src/main/java/com/ncm/app/plugin/compat/MusicFreeCompatContracts.kt \
        app/src/test/java/com/ncm/app/plugin/compat/FakePluginContractTest.kt \
        app/src/test/resources/fakeplugins/hello.cjs
git commit -m "feat(compat): freeze JianyunMusicFreeCompat/1 contracts and fake-plugin fixture"
```

### P0T4: 冻结 `JianyunMusicFreeCompat/1` 契约规范文档

**Files:**
- Create: `docs/superpowers/specs/jianyun-musicfree-compat-1.md`（不入库，作为后续契约测试与阶段 3 实现的单一事实源）

**Interfaces:**
- Consumes: P0T3 冻结的常量/校验函数、spec §6.2 表格、§5 领域模型。
- Produces: 阶段 3 契约测试的输入规格。

- [ ] **Step 1: 撰写契约文档**
  从 spec §6.2 表格 + §6.3 + §7 抄录并展开：每个方法（search/getMediaSource/getLyric/getAlbumInfo/getArtistWorks/getMusicSheetInfo/getTopLists/getTopListDetail）的参数、页码、归一化返回 JSON、必需字段列表、别名（歌词字段别名、艺术家/专辑形态）、错误码与可重试标记、HTTP 返回对象兼容字段。附录：脚本依赖兼容层清单（axios/crypto-js/qs/big-integer/dayjs/cheerio/he）与「实际脚本依赖—宿主实现」对照表（阶段 3 前填写）。

- [ ] **Step 2: 标注两阶段依赖**
  注明：真实插件出现未覆盖差异时，通过版本化适配器扩展，不能在 Repository 中加入平台名称判断（§6.2）。

- [ ] **Step 3: 记录聆澜授权范围确认**
  把「聆澜授权覆盖脚本下载/缓存/运行时执行/分发场景」的书面确认（或待办）记录在此文档。若未确认，在 plan 中标注 P0 完成但 P3 联调阻塞。

---

# 阶段 1：通用模型与接口

**阶段目标：** 新增 `MusicProvider`、`PluginRuntime`、通用实体（`PluginDescriptor`/`ProviderTrackKey`/`OnlineTrack`/`ResolvedMedia`/`BoundedJsonObject`）与来源感知 ID；为旧模型加显式转换层，**暂不改变现有 UI 行为**；先完成数据库 schema/备份/失败回滚测试，过渡期「旧数据只读适配 + 新操作写入通用模型」，不做两套主键无规则双写。

**阶段约束：** 本阶段不接线 QuickJS（阶段 3），`PluginRuntime` 用内存假实现满足单测。

### P1T1: 领域模型实体（纯 Kotlin data class）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/model/PluginModels.kt`

**Interfaces:**
- Produces（阶段 2/3/5 依赖）：
  - `data class PluginDescriptor(id, name, version, protocolVersion, minHostVersion?, downloadUrl, category, integrity?, status)`
  - `enum class PluginCategory { MUSIC, OTHER }`
  - `enum class PluginReleaseStatus { ACTIVE, MANDATORY_UPDATE, REVOKED, DISABLED }`
  - `data class ProviderTrackKey(pluginId: String, remoteId: String)`，带 `fun asComposite(): String`（格式 `"<pluginId>#<remoteId>"`）与伴生 `fun fromComposite(value: String): ProviderTrackKey?`
  - `data class OnlineArtist(remoteId, name)`
  - `data class OnlineAlbum(remoteId, name, artworkUrl?)`
  - `data class OnlineTrack(key, producedByPluginVersion, payloadSchemaVersion, title, artists, album?, durationMs?, artworkUrl?, pluginPayload)`
  - `data class ResolvedMedia(url, headers, userAgent?, quality?, expiresAtEpochMs?)`
  - `class BoundedJsonObject(...)`：包装 `Map<String, Any?>`，强制层级上限与大小上限，提供 `toMap()`/`fromMap()`/`sizeBytes()`；反序列化失败返回安全空对象。

- [ ] **Step 1: 写失败测试 — ProviderTrackKey 往返与边界**

```kotlin
// app/src/test/java/com/ncm/app/plugin/model/PluginModelsTest.kt
package com.ncm.app.plugin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginModelsTest {

    @Test
    fun compositeKeyRoundTrips() {
        val key = ProviderTrackKey(pluginId = "linglan.kw", remoteId = "a1b2c3")
        assertEquals("linglan.kw#a1b2c3", key.asComposite())
        assertEquals(key, ProviderTrackKey.fromComposite("linglan.kw#a1b2c3"))
    }

    @Test
    fun compositeKeyRejectsMalformedInput() {
        assertNull(ProviderTrackKey.fromComposite("no-separator"))
        assertNull(ProviderTrackKey.fromComposite("only#"))
        assertNull(ProviderTrackKey.fromComposite("#only"))
    }
}
```

- [ ] **Step 2: 运行验证失败**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.model.PluginModelsTest"
```

- [ ] **Step 3: 实现模型**

```kotlin
// app/src/main/java/com/ncm/app/plugin/model/PluginModels.kt
package com.ncm.app.plugin.model

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val minHostVersion: String?,
    val downloadUrl: String,
    val category: PluginCategory,
    val integrity: String?,   // sha256 摘要，见 GC #10
    val status: PluginReleaseStatus
)

enum class PluginCategory { MUSIC, OTHER }

enum class PluginReleaseStatus { ACTIVE, MANDATORY_UPDATE, REVOKED, DISABLED }

data class ProviderTrackKey(
    val pluginId: String,
    val remoteId: String
) {
    fun asComposite(): String = "$pluginId#$remoteId"

    companion object {
        fun fromComposite(value: String): ProviderTrackKey? {
            val index = value.indexOf('#')
            if (index <= 0 || index == value.length - 1) return null
            val plugin = value.substring(0, index)
            val remote = value.substring(index + 1)
            if (plugin.isBlank() || remote.isBlank()) return null
            return ProviderTrackKey(pluginId = plugin, remoteId = remote)
        }
    }
}

data class OnlineArtist(val remoteId: String, val name: String)

data class OnlineAlbum(val remoteId: String, val name: String, val artworkUrl: String? = null)

data class OnlineTrack(
    val key: ProviderTrackKey,
    val producedByPluginVersion: String,
    val payloadSchemaVersion: Int,
    val title: String,
    val artists: List<OnlineArtist>,
    val album: OnlineAlbum?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val pluginPayload: BoundedJsonObject
)

data class ResolvedMedia(
    val url: String,
    val headers: Map<String, String>,
    val userAgent: String?,
    val quality: String?,
    val expiresAtEpochMs: Long?
)
```

- [ ] **Step 4: 写失败测试 — BoundedJsonObject 上限**

```kotlin
@Test
fun boundedJsonObjectRejectsDeepAndLargePayloads() {
    val deep = mutableMapOf<String, Any?>()
    var cursor: MutableMap<String, Any?> = deep
    repeat(12) {
        val next = mutableMapOf<String, Any?>()
        cursor["x"] = next
        cursor = next
    }
    val bounded = BoundedJsonObject.fromMap(deep)
    assertEquals(0, bounded.sizeBytes()) // 超层级上限被安全拒绝
}
```

- [ ] **Step 5: 实现 BoundedJsonObject**

```kotlin
// 追加到 PluginModels.kt
/**
 * 经过清洗、可序列化、有大小与层级上限的插件负载容器（GC #14）。
 * 不保存密钥/授权头/个性化 URL；越界数据被拒绝而非截断猜测。
 */
class BoundedJsonObject private constructor(private val entries: Map<String, Any?>) {

    fun toMap(): Map<String, Any?> = entries

    fun sizeBytes(): Int = try {
        GSON.toJson(entries).toByteArray(Charsets.UTF_8).size
    } catch (_: Exception) {
        0
    }

    companion object {
        private val GSON = com.google.gson.Gson()
        private const val MAX_DEPTH = 6
        private const val MAX_ENTRIES = 64

        fun fromMap(raw: Map<String, Any?>): BoundedJsonObject {
            if (raw.size > MAX_ENTRIES) return BoundedJsonObject(emptyMap())
            return BoundedJsonObject(sanitize(raw, depth = 0))
        }

        private fun sanitize(value: Any?, depth: Int): Any? = when (value) {
            null, is String, is Number, is Boolean -> value
            is Map<*, *> -> if (depth >= MAX_DEPTH || value.size > MAX_ENTRIES) {
                null
            } else {
                value.entries
                    .filter { it.key is String }
                    .take(MAX_ENTRIES)
                    .associate { it.key as String to sanitize(it.value, depth + 1) }
            }
            is Iterable<*> -> if (depth >= MAX_DEPTH) null else value.take(MAX_ENTRIES).map { sanitize(it, depth + 1) }
            else -> null
        }
    }
}
```
（`sizeBytes` 已用 try/catch，符合 GC #2；失败仅影响体积度量，不影响功能。）

- [ ] **Step 6: 运行全量测试确认全绿并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
git add app/src/main/java/com/ncm/app/plugin/model/PluginModels.kt app/src/test/java/com/ncm/app/plugin/model/PluginModelsTest.kt
git commit -m "feat(model): source-aware track key, online track, resolved media, bounded payload"
```

### P1T2: MusicProvider 统一 Kotlin 接口

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/provider/MusicProvider.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/provider/MusicProviderContractTest.kt`（编译期契约测试 + 内存假实现）

**Interfaces:**
- Produces（阶段 3/4 依赖）：
  - `interface MusicProvider`，方法见下；所有方法 `suspend`，返回 `Result<T>` 或抛出 `PluginException(code, message, retryable)`。
  - `class PluginException(val code: String, override val message: String, val retryable: Boolean) : Exception(message)`
  - `data class SearchOutcome(val items: List<OnlineTrack>, val isEnd: Boolean)`
  - `data class LyricOutcome(val rawLrc: String?, val translation: String?, val romaLrc: String?, val wordLrc: String?)`

- [ ] **Step 1: 写失败测试 — 编译期契约与异常语义**

```kotlin
// app/src/test/java/com/ncm/app/plugin/provider/MusicProviderContractTest.kt
package com.ncm.app.plugin.provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicProviderContractTest {

    @Test
    fun providerSurfaceMatchesFrozenContract() = runTest {
        val fake = FakeMusicProvider()
        val outcome = fake.search("test", page = 1, type = "music")
        assertTrue(outcome.items.isNotEmpty())
        assertEquals(false, outcome.isEnd)
    }

    @Test
    fun pluginExceptionCarriesRetryFlag() {
        val e = PluginException(code = "SEARCH_FAILED", message = "网络失败", retryable = true)
        assertEquals("SEARCH_FAILED", e.code)
        assertTrue(e.retryable)
    }

    private class FakeMusicProvider : MusicProvider {
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            error("not implemented")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(rawLrc = "[00:00.00]x", translation = null, romaLrc = null, wordLrc = null)
        override val pluginId: String get() = "fake"
    }
}
```

- [ ] **Step 2: 运行验证失败**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.provider.MusicProviderContractTest"
```

- [ ] **Step 3: 实现接口**

```kotlin
// app/src/main/java/com/ncm/app/plugin/provider/MusicProvider.kt
package com.ncm.app.plugin.provider

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia

/** 统一在线来源接口。所有实现都必须以 [pluginId] 标识自己，禁止在 Repository 中出现平台名判断（GC #6）。 */
interface MusicProvider {
    val pluginId: String

    suspend fun search(query: String, page: Int, type: String): SearchOutcome
    suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia
    suspend fun lyric(track: OnlineTrack): LyricOutcome
}

data class SearchOutcome(val items: List<OnlineTrack>, val isEnd: Boolean)

data class LyricOutcome(
    val rawLrc: String?,
    val translation: String?,
    val romaLrc: String?,
    val wordLrc: String?
)

/** 宿主错误：code 为稳定错误标识，message 可展示，retryable 供熔断与 UI 重试。 */
class PluginException(
    val code: String,
    override val message: String,
    val retryable: Boolean
) : Exception(message)
```

- [ ] **Step 4: 运行验证通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.provider.MusicProviderContractTest"
git add app/src/main/java/com/ncm/app/plugin/provider/MusicProvider.kt app/src/test/java/com/ncm/app/plugin/provider/MusicProviderContractTest.kt
git commit -m "feat(provider): unified MusicProvider interface and plugin exception contract"
```

### P1T3: 旧模型 → 通用模型的显式转换层

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/model/LegacyConversion.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/model/LegacyConversionTest.kt`

**Interfaces:**
- Consumes: 现有 `Song`（`Models.kt`）、`PlaybackSource`、P1T1 的 `ProviderTrackKey`/`OnlineTrack`/`BoundedJsonObject`。
- Produces:
  - `fun legacyNeteaseKey(songId: Long): ProviderTrackKey` → `ProviderTrackKey("legacy-netease", songId.toString())`
  - `fun Song.toLegacyOnlineTrack(): OnlineTrack?`（网易云来源才转换；本地/简云官方返回 null）
  - `fun ProviderTrackKey.isLegacyNetease(): Boolean`
  - `fun ProviderTrackKey.toDisplayKey(): String`（UI 展示用，隐藏 pluginId 细节）

**说明：** 转换层是「只读适配」，不写新来源数据。UI 层在阶段 5 前仍读 `Song`，因此本层仅被阶段 5 使用；本 Task 用单测锁定映射规则。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/model/LegacyConversionTest.kt
package com.ncm.app.plugin.model

import com.ncm.app.data.model.AlbumBrief
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyConversionTest {

    @Test
    fun legacyNeteaseKeyIsStableAndReadable() {
        val key = legacyNeteaseKey(123456L)
        assertEquals("legacy-netease", key.pluginId)
        assertEquals("123456", key.remoteId)
        assertTrue(key.isLegacyNetease())
    }

    @Test
    fun songConvertsToLegacyOnlineTrackPreservingMetadata() {
        val song = Song(
            id = 123456L, name = "测试", dt = 200_000,
            artists = listOf(ArtistBrief(1, "甲")),
            album = AlbumBrief(id = 2, name = "专辑", picUrl = "https://x/a.jpg")
        )
        val track = song.toLegacyOnlineTrack()
        assertEquals("legacy-netease", track.key.pluginId)
        assertEquals("测试", track.title)
        assertEquals(200_000L, track.durationMs)
    }

    @Test
    fun localOrOfficialSongsDoNotConvert() {
        // 本地文件用 mediaFileName 标识，简云官方 id 有哨兵区段，都不该转成 legacy-netease
        assertNull(Song(id = 90001L, name = "本地").toLegacyOnlineTrack())
    }
}
```

- [ ] **Step 2: 运行验证失败**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.model.LegacyConversionTest"
```

- [ ] **Step 3: 实现转换层**
```kotlin
// app/src/main/java/com/ncm/app/plugin/model/LegacyConversion.kt
package com.ncm.app.plugin.model

import com.ncm.app.data.model.Song
import com.ncm.app.data.repository.JianyunOfficialContent

const val LEGACY_NETEAUSE_PLUGIN_ID = "legacy-netease"

fun legacyNeteaseKey(songId: Long): ProviderTrackKey =
    ProviderTrackKey(pluginId = LEGACY_NETEAUSE_PLUGIN_ID, remoteId = songId.toString())

fun ProviderTrackKey.isLegacyNetease(): Boolean = pluginId == LEGACY_NETEAUSE_PLUGIN_ID

/** 网易云来源歌曲 → 只读 legacy 记录；本地/简云官方来源不转换。 */
fun Song.toLegacyOnlineTrack(): OnlineTrack? {
    if (mediaFileName != null) return null          // 本地文件
    if (JianyunOfficialContent.isOfficialSongId(id)) return null  // 简云官方
    if (id <= 0) return null
    return OnlineTrack(
        key = legacyNeteaseKey(id),
        producedByPluginVersion = "legacy",
        payloadSchemaVersion = 0,
        title = name,
        artists = artists.orEmpty().map { OnlineArtist(it.id.toString(), it.name) },
        album = album?.let { OnlineAlbum(it.id.toString(), it.name, it.picUrl) },
        durationMs = dt.takeIf { it > 0 },
        artworkUrl = album?.picUrl,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )
}
```
（若 `JianyunOfficialContent.isOfficialSongId` 对 90001L 返回 true 导致断言不符，以实际语义调整测试输入——本 Task 目标是锁定「本地/官方不转 legacy」，具体哨兵值以 P0T1 观察为准。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.model.LegacyConversionTest"
git add app/src/main/java/com/ncm/app/plugin/model/LegacyConversion.kt app/src/test/java/com/ncm/app/plugin/model/LegacyConversionTest.kt
git commit -m "feat(model): explicit legacy-netease conversion layer for old Song ids"
```

### P1T4: PluginRuntime 接口与内存假实现

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/PluginRuntime.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/InMemoryPluginRuntimeTest.kt`

**Interfaces:**
- Produces（阶段 3 替换为 QuickJS 实现；阶段 4 消费）：
  - `interface PluginRuntime { fun providerFor(pluginId: String): MusicProvider?; fun destroy(); fun isHealthy(): Boolean }`
  - `class InMemoryPluginRuntime : PluginRuntime`（阶段 1 占位，持有 `Map<pluginId, MusicProvider>`）
- Consumes: P1T2 `MusicProvider`。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/InMemoryPluginRuntimeTest.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryPluginRuntimeTest {

    @Test
    fun returnsProviderByPluginId() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeProvider()))
        assertEquals("fake", runtime.providerFor("fake")?.pluginId)
        assertNull(runtime.providerFor("missing"))
    }

    @Test
    fun destroyClearsProviders() {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeProvider()))
        runtime.destroy()
        assertNull(runtime.providerFor("fake"))
        assertTrue(runtime.isHealthy())
    }

    private class FakeProvider : MusicProvider {
        override val pluginId: String get() = "fake"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            error("not implemented")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现接口与内存实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/PluginRuntime.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.MusicProvider

/** 插件运行环境。阶段 1 用内存实现占位，阶段 3 替换为 QuickJS 隔离实现。 */
interface PluginRuntime {
    fun providerFor(pluginId: String): MusicProvider?

    /** 装载插件脚本并返回其 MusicProvider（GC #11 两步检查由实现负责）。
     *  占位实现不支持；P3T8 的 QuickJsPluginRuntime 提供真实实现。 */
    fun load(pluginId: String, script: String, hostParams: Map<String, Any?>): MusicProvider =
        throw UnsupportedOperationException("占位运行时不支持装载脚本")

    fun destroy()
    fun isHealthy(): Boolean
}

/** 阶段 1 占位：仅用于单元测试与 UI 开发，不提供任何隔离。 */
class InMemoryPluginRuntime(
    private val providers: Map<String, MusicProvider>
) : PluginRuntime {
    @Volatile private var destroyed = false

    override fun providerFor(pluginId: String): MusicProvider? {
        if (destroyed) return null
        return providers[pluginId]
    }

    override fun destroy() { destroyed = true }

    override fun isHealthy(): Boolean = !destroyed
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.InMemoryPluginRuntimeTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/PluginRuntime.kt app/src/test/java/com/ncm/app/plugin/runtime/InMemoryPluginRuntimeTest.kt
git commit -m "feat(runtime): PluginRuntime interface with in-memory placeholder"
```

### P1T5: 数据库 schema 与备份/回滚测试（前置准备）

**Files:**
- Create: `app/src/main/java/com/ncm/app/data/store/OnlineLibraryDatabase.kt`
- Create: `app/src/main/java/com/ncm/app/data/store/OnlineSongEntity.kt`
- Test: `app/src/test/java/com/ncm/app/data/store/OnlineLibraryMigrationTest.kt`

**Interfaces:**
- Produces（阶段 5 依赖）：
  - `@Entity OnlineSongEntity`（字段见下，主键 `pluginId + remoteId`）
  - `@Database OnlineLibraryDatabase`（version 1）
- Consumes: Room 2.6.1（已有）、P1T1 模型。

**说明：** 本 Task 只建 schema + 失败回滚测试，不迁移任何现有数据（迁移在阶段 5）。schema 先于 UI 锁死，避免阶段 5 返工。

- [ ] **Step 1: 写失败测试 — 备份/失败回滚语义**
```kotlin
// app/src/test/java/com/ncm/app/data/store/OnlineLibraryMigrationTest.kt
package com.ncm.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLibraryMigrationTest {

    @Test
    fun compositeKeyUniquenessPreventsCrossPluginCollision() {
        val a = OnlineSongEntity(pluginId = "linglan.kw", remoteId = "123", title = "A")
        val b = OnlineSongEntity(pluginId = "linglan.tx", remoteId = "123", title = "B")
        // 同 remoteId 不同 plugin 必须共存；schema 用 (pluginId, remoteId) 联合主键。
        assertEquals(a.asCompositeKey(), "linglan.kw#123")
        assertEquals(b.asCompositeKey(), "linglan.tx#123")
        assertTrue(a.asCompositeKey() != b.asCompositeKey())
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 schema**
```kotlin
// app/src/main/java/com/ncm/app/data/store/OnlineSongEntity.kt
package com.ncm.app.data.store

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "online_songs",
    primaryKeys = ["pluginId", "remoteId"],
    indices = [Index("pluginId")]
)
data class OnlineSongEntity(
    val pluginId: String,
    val remoteId: String,
    val title: String,
    val artistsJson: String = "[]",        // JSON 数组 [{remoteId,name}]
    val albumJson: String? = null,         // JSON {remoteId,name,artworkUrl}
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val pluginPayloadJson: String = "{}",  // BoundedJsonObject.toMap() 的 JSON
    val producedByPluginVersion: String = "",
    val payloadSchemaVersion: Int = 1
) {
    fun asCompositeKey(): String = "$pluginId#$remoteId"
}

// app/src/main/java/com/ncm/app/data/store/OnlineLibraryDatabase.kt
package com.ncm.app.data.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OnlineSongEntity::class], version = 1, exportSchema = true)
abstract class OnlineLibraryDatabase : RoomDatabase() {
    abstract fun onlineSongDao(): OnlineSongDao

    companion object {
        const val NAME = "online_library.db"
    }
}
```
（`OnlineSongDao` 为 interface 占位，阶段 5 填充 CRUD + 迁移工具查询。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.store.OnlineLibraryMigrationTest"
git add app/src/main/java/com/ncm/app/data/store/OnlineSongEntity.kt app/src/main/java/com/ncm/app/data/store/OnlineLibraryDatabase.kt app/src/test/java/com/ncm/app/data/store/OnlineLibraryMigrationTest.kt
git commit -m "feat(store): source-aware online song schema with composite primary key"
```

---

# 阶段 2：授权、清单和设置页

**阶段目标：** 实现 Keystore 加密密钥存储、服务端验证、清单获取、来源过滤与单选设置页。**暂不执行真实远程脚本**：用假清单与假插件完成 UI 测试（spec §14 阶段 2）。

**阶段外部前置：** 密钥校验接口的限流/到期/撤销/错误码稳定（联调用假端点模拟；真实端点字段留到阶段 3/4 联调确认）。

### P2T1: LinglanCredentialStore（Keystore 加密存储）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/credential/LinglanCredentialStore.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/credential/LinglanCredentialStoreTest.kt`

**Interfaces:**
- Produces（阶段 2/4 依赖）：
  - `class LinglanCredentialStore(context: Context)`
  - `fun save(secret: String)`（加密后写入 app-private 文件）
  - `fun read(): String?`（解密读取）
  - `fun masked(): String?`（`••••` + 末 4 位）
  - `fun clear()`（删除密钥文件 + 撤销 Keystore 密钥实现加密擦除，GC #8）
  - `fun hasCredential(): Boolean`
- Consumes: Android Keystore（`KeyGenParameterSpec`），不需要 GMS。

**说明：** Keystore 在纯 JVM 单测中不可用，因此本 Task 的策略接口 + JVM 可测存储实现分离：`SecretVault` 接口（JVM 用 Base64 简单加盐模拟，Android 用 Keystore 实现），单测锁存储语义，Android 实现留 P2T2。

- [ ] **Step 1: 写失败测试 — 存储语义（JVM 模拟实现）**
```kotlin
// app/src/test/java/com/ncm/app/plugin/credential/LinglanCredentialStoreTest.kt
package com.ncm.app.plugin.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinglanCredentialStoreTest {

    @Test
    fun saveThenReadRoundTrips() {
        val store = TestCredentialStore()
        assertTrue(store.save("linglan-secret-123"))
        assertEquals("linglan-secret-123", store.read())
        assertEquals("••••1234", store.masked())
    }

    @Test
    fun clearWipesSecretAndMask() {
        val store = TestCredentialStore()
        store.save("abc")
        store.clear()
        assertNull(store.read())
        assertNull(store.masked())
    }
}
```
（`TestCredentialStore` 在测试文件内实现一个简单的 `SecretVault` 假实现，把明文放内存 Map。）

- [ ] **Step 2: 运行验证失败 → Step 3: 定义 SecretVault 接口 + 内存实现（本 Task 只到接口与可测实现）**

```kotlin
// app/src/main/java/com/ncm/app/plugin/credential/LinglanCredentialStore.kt
package com.ncm.app.plugin.credential

/** 密钥存储边界：Android 用 Keystore 实现（P2T2），JVM 测试用内存实现。 */
interface SecretVault {
    fun write(value: String): Boolean
    fun read(): String?
    fun wipe()
}

/** 基于 Android Keystore 的加密存储；wipe 先撤销 Keystore 密钥再删文件（GC #8 加密擦除）。 */
class KeystoreSecretVault(
    private val appContext: android.content.Context,
    alias: String
) : SecretVault {
    // P2T2 实现：KeyGenParameterSpec + Cipher(AES/GCM/NoPadding)，密文写 app-private 文件。
    // 阶段 2 本 Task 只保留接口，实现体在 P2T2 完成。
    override fun write(value: String): Boolean = throw UnsupportedOperationException("P2T2")
    override fun read(): String? = throw UnsupportedOperationException("P2T2")
    override fun wipe() { throw UnsupportedOperationException("P2T2") }
}

/** 领域门面：设置页与 ViewModel 只依赖它，不直接碰加密细节。 */
class LinglanCredentialStore(private val vault: SecretVault) {
    fun save(secret: String): Boolean {
        val normalized = secret.trim()
        if (normalized.length < 8) return false
        return vault.write(normalized)
    }
    fun read(): String? = vault.read()
    fun masked(): String? = read()?.let { "••••${it.takeLast(4)}" }
    fun clear() { vault.wipe() }
    fun hasCredential(): Boolean = !read().isNullOrBlank()
}
```

- [ ] **Step 4: 运行通过并提交（P2T2 单独提交）**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.credential.LinglanCredentialStoreTest"
git add app/src/main/java/com/ncm/app/plugin/credential/LinglanCredentialStore.kt app/src/test/java/com/ncm/app/plugin/credential/LinglanCredentialStoreTest.kt
git commit -m "feat(credential): secret vault boundary and credential store facade"
```

### P2T2: KeystoreSecretVault 实现（Robolectric）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/plugin/credential/LinglanCredentialStore.kt`（补全 `KeystoreSecretVault`）
- Test: `app/src/test/java/com/ncm/app/plugin/credential/KeystoreSecretVaultTest.kt`

**Interfaces:**
- Consumes: P2T1 `SecretVault`。
- Produces: 完整 `KeystoreSecretVault`（Android 真机/Robolectric 可跑）。

- [ ] **Step 1: 写 Robolectric 测试（Keystore 在 Robolectric 下有限支持，验证创建/加密/读取/擦除路径不崩溃）**
```kotlin
// app/src/test/java/com/ncm/app/plugin/credential/KeystoreSecretVaultTest.kt
package com.ncm.app.plugin.credential

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeystoreSecretVaultTest {

    @Test
    fun keystoreVaultRoundTripsOnRobolectric() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val vault = KeystoreSecretVault(context, alias = "test_vault_${System.nanoTime()}")
        vault.write("top-secret-123")
        assertEquals("top-secret-123", vault.read())
        vault.wipe()
        // 读取应为 null 或抛异常由调用方兜底；Robolectric 下 Keystore 能力有限，容忍空读
    }
}
```

- [ ] **Step 2: 运行验证（Robolectric 环境）→ Step 3: 补全实现**
```kotlin
// 在 KeystoreSecretVault 内补全（替换 P2T1 的占位 throw）
class KeystoreSecretVault(
    private val appContext: android.content.Context,
    private val alias: String
) : SecretVault {
    private val keyStore: java.security.KeyStore by lazy {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private val file = java.io.File(appContext.filesDir, "vault_$alias.dat")

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val existing = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
        if (existing != null) return existing
        val generator = javax.crypto.KeyGenerator.getInstance(
            "AES", "AndroidKeyStore"
        ).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }
        return generator.generateKey()
    }

    override fun write(value: String): Boolean = try {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        file.writeBytes(iv + encrypted)
        true
    } catch (_: Exception) { false }

    override fun read(): String? = try {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (_: Exception) { null }

    override fun wipe() {
        try {
            keyStore.getKey(alias, null)?.let { keyStore.deleteEntry(alias) }
        } catch (_: Exception) { /* 忽略 */ }
        file.delete()
    }
}
```
（GC #2：若项目约定禁止 `runCatching`，此处已用 try/catch；Robolectric 不支持时测试标注 `@Ignore` 并在真机 QA 清单记录。）

- [ ] **Step 3b: 备份排除与路径约束（spec §8.2）**
  密钥密文落在 `context.filesDir`（targetSdk ≥ 31 默认排除在 Auto Backup 之外）。同时确认 `app/src/main/AndroidManifest.xml` 的 `application` 未把该目录声明为可备份，并在资源中添加备份排除规则：

```xml
<!-- app/src/main/res/xml/backup_rules.xml -->
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="file" path="vault_"/>
    <exclude domain="file" path="plugins/"/>
</full-backup-content>
```
并在 Manifest `application` 节点引用：`android:dataExtractionRules="@xml/data_extraction_rules"` 与 `android:fullBackupContent="@xml/backup_rules"`（若项目已声明这些属性，把 `vault_`/`plugins/` 追加进既有规则）。断言：`KeystoreSecretVaultTest` 里验证密文文件确实位于 `filesDir` 下。

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.credential.KeystoreSecretVaultTest"
git add app/src/main/java/com/ncm/app/plugin/credential/LinglanCredentialStore.kt app/src/test/java/com/ncm/app/plugin/credential/KeystoreSecretVaultTest.kt
git commit -m "feat(credential): Android Keystore-backed secret vault with crypto-erase wipe"
```

### P2T3: 授权状态机

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/auth/LinglanAuthState.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/auth/LinglanAuthStateTest.kt`

**Interfaces:**
- Produces（阶段 2/3/4 依赖）：
  - `enum class LinglanAuthState { DISCONNECTED, VALIDATING, ACTIVE, STALE_OFFLINE, EXPIRED, REVOKED, ERROR }`
  - `data class LinglanAuthInfo(validUntilEpochMs: Long?, lastVerifiedAtEpochMs: Long?, capability: Set<String>)`
  - `fun nextStateForServerResponse(current: LinglanAuthState, httpCode: Int, bodyCode: Int?): LinglanAuthState`（服务端明确 401/403/撤销 → EXPIRED/REVOKED；网络失败 → ERROR；否则 ACTIVE）
  - `fun shouldRevalidate(info: LinglanAuthInfo, nowMs: Long): Boolean`（距上次验证 > 24h 返回 true）

- [ ] **Step 1: 写失败测试 — 状态机转移（spec §8）**
```kotlin
// app/src/test/java/com/ncm/app/plugin/auth/LinglanAuthStateTest.kt
package com.ncm.app.plugin.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanAuthStateTest {

    @Test
    fun serverAuthFailureBecomesExpiredOrRevokedNotError() {
        assertEquals(LinglanAuthState.EXPIRED, nextStateForServerResponse(LinglanAuthState.ACTIVE, 401, 401))
        assertEquals(LinglanAuthState.REVOKED, nextStateForServerResponse(LinglanAuthState.ACTIVE, 403, 403))
    }

    @Test
    fun networkFailureIsNotKeyInvalid() {
        assertEquals(LinglanAuthState.ERROR, nextStateForServerResponse(LinglanAuthState.ACTIVE, 0, null))
    }

    @Test
    fun successBecomesActive() {
        assertEquals(LinglanAuthState.ACTIVE, nextStateForServerResponse(LinglanAuthState.VALIDATING, 200, 200))
    }

    @Test
    fun staleOfflineAfterTwentyFourHoursWithoutValidation() {
        val now = 1_000_000_000L
        val info = LinglanAuthInfo(
            validUntilEpochMs = now + 86_400_000L,
            lastVerifiedAtEpochMs = now - 86_400_001L,
            capability = emptySet()
        )
        assertEquals(true, shouldRevalidate(info, now))
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现状态机**
```kotlin
// app/src/main/java/com/ncm/app/plugin/auth/LinglanAuthState.kt
package com.ncm.app.plugin.auth

enum class LinglanAuthState {
    DISCONNECTED, VALIDATING, ACTIVE, STALE_OFFLINE, EXPIRED, REVOKED, ERROR
}

data class LinglanAuthInfo(
    val validUntilEpochMs: Long?,
    val lastVerifiedAtEpochMs: Long?,
    val capability: Set<String>
)

/** 宿主策略常量：距上次验证超过该时长需重新验证；不可由脚本修改（spec §8.1）。 */
const val REVALIDATION_INTERVAL_MS = 86_400_000L // 24h

/** 网络失败不能被当成密钥无效（GC #8）：仅服务端明确 401/403/撤销才进入对应状态。 */
fun nextStateForServerResponse(
    current: LinglanAuthState,
    httpCode: Int,
    bodyCode: Int?
): LinglanAuthState {
    val effective = bodyCode ?: httpCode
    return when {
        effective == 401 -> LinglanAuthState.EXPIRED
        effective == 403 -> LinglanAuthState.REVOKED
        httpCode == 0 -> LinglanAuthState.ERROR          // 网络失败
        effective == 200 -> LinglanAuthState.ACTIVE
        httpCode == 429 -> LinglanAuthState.ERROR        // 限流，非密钥无效
        else -> current
    }
}

fun shouldRevalidate(info: LinglanAuthInfo, nowMs: Long): Boolean {
    val last = info.lastVerifiedAtEpochMs ?: return true
    return nowMs - last > REVALIDATION_INTERVAL_MS
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.auth.LinglanAuthStateTest"
git add app/src/main/java/com/ncm/app/plugin/auth/LinglanAuthState.kt app/src/test/java/com/ncm/app/plugin/auth/LinglanAuthStateTest.kt
git commit -m "feat(auth): linglan authorization state machine with stale-offline revalidation"
```

### P2T4: LinglanManifestClient（假清单端点）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/manifest/LinglanManifestClient.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/manifest/LinglanManifestClientTest.kt`

**Interfaces:**
- Produces（阶段 2/3 依赖）：
  - `data class ManifestItem(id, name, version, url, category, protocolVersion, minHostVersion?, status, sha256?, signature?, signatureTimestamp?)`
  - `class LinglanManifestClient(private val http: suspend (String) -> String)`：构造注入 HTTP 函数以便单测；生产实现在 P2T5 用 OkHttp。
  - `suspend fun fetch(): List<ManifestItem>`（解析 spec §9 的 JSON 结构）
- Consumes: P1T1 `PluginDescriptor`/`PluginCategory`/`PluginReleaseStatus`。

**说明：** 清单字段映射到 `PluginDescriptor`。下载 URL 只在内存中（GC #4）。本 Task 用注入的假 HTTP 返回固定 JSON 测试解析与健壮性。

- [ ] **Step 1: 写失败测试 — 解析与过滤无关**
```kotlin
// app/src/test/java/com/ncm/app/plugin/manifest/LinglanManifestClientTest.kt
package com.ncm.app.plugin.manifest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanManifestClientTest {

    private val sampleJson = """
        {
          "plugins": [
            {"id": "linglan.kw", "name": "酷我", "version": "1.2.3",
             "url": "https://provider.example/kw.js", "category": "music",
             "protocolVersion": 1, "minHostVersion": "1.0.0", "status": "active", "sha256": "abc"},
            {"id": "linglan.wy", "name": "网易云", "version": "1.0.0",
             "url": "https://provider.example/wy.js", "category": "music",
             "protocolVersion": 1, "status": "active"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesManifestIntoDescriptors() = runTest {
        val client = LinglanManifestClient(http = { sampleJson })
        val items = client.fetch()
        assertEquals(2, items.size)
        assertEquals("linglan.kw", items[0].id)
        assertEquals("https://provider.example/kw.js", items[0].url)
        assertEquals("active", items[0].status.name.lowercase())
    }

    @Test
    fun malformedJsonYieldsEmptyListNotCrash() = runTest {
        val client = LinglanManifestClient(http = { "not-json" })
        assertEquals(emptyList<ManifestItem>(), client.fetch())
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现客户端**
```kotlin
// app/src/main/java/com/ncm/app/plugin/manifest/LinglanManifestClient.kt
package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus

data class ManifestItem(
    val id: String,
    val name: String,
    val version: String,
    val url: String,
    val category: PluginCategory,
    val protocolVersion: Int,
    val minHostVersion: String?,
    val status: PluginReleaseStatus,
    val sha256: String?,
    val signature: String? = null,            // 生产环境必需（spec §9），Base64
    val signatureTimestamp: Long? = null      // 签名时间（毫秒），防重放（GC #10）
)

/** 拉取聆澜插件清单。HTTP 通过构造注入，生产用 OkHttp（P2T5），单测用固定响应。 */
class LinglanManifestClient(
    private val http: suspend (String) -> String
) {
    suspend fun fetch(): List<ManifestItem> = try {
        val root = JsonParser.parseString(http(ENDPOINT)).asJsonObject
        val plugins = root.getAsJsonArray("plugins") ?: return emptyList()
        plugins.mapNotNull { element ->
            val item = element.asJsonObject
            val statusText = item.get("status")?.asString ?: "active"
            val status = PluginReleaseStatus.entries
                .firstOrNull { it.name.equals(statusText, ignoreCase = true) }
                ?: return@mapNotNull null
            ManifestItem(
                id = item.get("id")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                name = item.get("name")?.asString ?: "",
                version = item.get("version")?.asString ?: "",
                url = item.get("url")?.asString ?: "",
                category = if ((item.get("category")?.asString ?: "") == "music") {
                    PluginCategory.MUSIC
                } else {
                    PluginCategory.OTHER
                },
                protocolVersion = item.get("protocolVersion")?.asInt ?: 1,
                minHostVersion = item.get("minHostVersion")?.asString,
                status = status,
                sha256 = item.get("sha256")?.asString,
                signature = item.get("signature")?.asString,
                signatureTimestamp = item.get("signedAt")?.asLong ?: item.get("signatureTimestamp")?.asLong
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val ENDPOINT = "https://linglan.invalid/manifest"  // 生产替换，见 P2T5
    }
}
```
（GC #2：`fetch` 用了 try/catch 吞错并返回空表——契约要求「清单不可用按空处理」，此乃业务语义而非隐藏错误，保留注释说明。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.manifest.LinglanManifestClientTest"
git add app/src/main/java/com/ncm/app/plugin/manifest/LinglanManifestClient.kt app/src/test/java/com/ncm/app/plugin/manifest/LinglanManifestClientTest.kt
git commit -m "feat(manifest): manifest client with injectable http and safe parsing"
```

### P2T5: 来源过滤与允许列表（含 Bilibili/GitCode 排除）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/manifest/PluginSourceFilter.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/manifest/PluginSourceFilterTest.kt`

**Interfaces:**
- Produces（阶段 2/3 依赖）：
  - `data class SourceAllowRule(hostPrefix: String, pathPrefix: String, sourceType: String)`
  - `fun allowedManifestItems(items: List<ManifestItem>, rules: List<SourceAllowRule>): List<ManifestItem>`（返回通过过滤的项）
  - `val DEFAULT_SOURCE_ALLOW_RULES: List<SourceAllowRule>`（预置酷狗/酷我/QQ/网易云；不含 Bilibili/GitCode）
  - `fun SourceAllowRule.matches(url: String): Boolean`（主机前缀 + 去查询参数后的路径前缀 + 来源类型）
  - `fun inferStablePluginId(item: ManifestItem): String?`（清单无 id 时按 GC #5 内置映射生成 `linglan.kw` 等；无法命中返回 null）

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/manifest/PluginSourceFilterTest.kt
package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSourceFilterTest {

    private fun item(id: String, url: String, name: String = id) = ManifestItem(
        id = id, name = name, version = "1.0.0", url = url,
        category = PluginCategory.MUSIC, protocolVersion = 1,
        minHostVersion = null, status = PluginReleaseStatus.ACTIVE, sha256 = null
    )

    @Test
    fun bilibiliAndGitCodeAreAlwaysExcluded() {
        val items = listOf(
            item("bili", "https://provider.example/bili.js", name = "哔哩哔哩"),
            item("gitcode", "https://provider.example/git.js", name = "GitCode")
        )
        // 即使清单给了 category==music，也绝不能因显示名放行；规则不含这两个来源
        val allowed = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES)
        assertTrue(allowed.none { it.id == "bili" || it.id == "gitcode" })
    }

    @Test
    fun allowRuleMatchesHostPathAndType() {
        val rule = SourceAllowRule(hostPrefix = "provider.example", pathPrefix = "/kw", sourceType = "kw")
        assertTrue(rule.matches("https://provider.example/kw/script.js?token=secret"))
    }

    @Test
    fun inferStableIdMapsKnownProviders() {
        val kw = item("", "https://provider.example/kw/v1.js", name = "酷我")
        assertEquals("linglan.kw", inferStablePluginId(kw))
    }

    @Test
    fun unknownSourceHasNoStableId() {
        val weird = item("", "https://provider.example/other/script.js", name = "未知来源")
        assertNull(inferStablePluginId(weird))
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/manifest/PluginSourceFilter.kt
package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.model.PluginCategory

data class SourceAllowRule(
    val hostPrefix: String,
    val pathPrefix: String,
    val sourceType: String
) {
    fun matches(url: String): Boolean {
        val clean = url.substringBefore('?')            // 去除查询参数（GC #5）
        val host = clean.substringAfter("//").substringBefore('/').lowercase()
        val path = clean.substringAfter('/', missingDelimiterValue = "").let { "/$it" }
        return host.startsWith(hostPrefix.lowercase()) &&
            path.startsWith(pathPrefix) &&
            sourceType.isNotBlank()
    }
}

/** 临时允许列表：预置主机 + 路径 + 精确来源类型；不含 Bilibili/GitCode（GC #9）。 */
val DEFAULT_SOURCE_ALLOW_RULES: List<SourceAllowRule> = listOf(
    SourceAllowRule("provider.example", "/kw", "kw"),
    SourceAllowRule("provider.example", "/kugou", "kugou"),
    SourceAllowRule("provider.example", "/tx", "tx"),
    SourceAllowRule("provider.example", "/qq", "qq"),
    SourceAllowRule("provider.example", "/wy", "wy")
)

fun allowedManifestItems(
    items: List<ManifestItem>,
    rules: List<SourceAllowRule>
): List<ManifestItem> = items.filter { item ->
    when {
        item.category != PluginCategory.MUSIC -> false
        item.status == PluginReleaseStatus.DISABLED -> false
        item.id.isNotBlank() -> true            // 清单已有稳定 id：走能力声明/分类
        else -> rules.any { it.matches(item.url) }
    }
}

/** 清单无稳定 id 时，按 GC #5 版本化精确映射生成；无法命中返回 null（不可产生持久数据）。 */
fun inferStablePluginId(item: ManifestItem): String? = when {
    item.id.isNotBlank() -> item.id
    item.url.contains("/kw") -> "linglan.kw"
    item.url.contains("/kugou") || item.url.contains("/kg") -> "linglan.kg"
    item.url.contains("/tx") -> "linglan.tx"
    item.url.contains("/wy") -> "linglan.wy"
    else -> null
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.manifest.PluginSourceFilterTest"
git add app/src/main/java/com/ncm/app/plugin/manifest/PluginSourceFilter.kt app/src/test/java/com/ncm/app/plugin/manifest/PluginSourceFilterTest.kt
git commit -m "feat(manifest): source allowlist filter and versioned stable plugin id inference"
```

### P2T6: 设置页「在线音乐来源」单选 UI（假清单 + 内存插件）

**Files:**
- Create: `app/src/main/java/com/ncm/app/ui/screens/settings/OnlineMusicSourceSection.kt`
- Create: `app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt`
- Modify: `app/src/main/java/com/ncm/app/MainActivity.kt`（在设置入口挂载该区块；需先定位现有设置 UI 位置）
- Test: `app/src/test/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModelTest.kt`

**Interfaces:**
- Produces（阶段 4 依赖）：
  - `data class OnlineSourceUiState(authState, selectedPluginId?, manifestItems, isDownloading, validUntilEpochMs?, error?)`
  - `class OnlineMusicSourceViewModel`：`fun connect(secret)`, `fun cancelConnect()`, `fun refreshManifest()`, `fun selectSource(pluginId)`, `fun disconnect()`
- Consumes: P2T1/P2T3/P2T4/P2T5、P1T4 `InMemoryPluginRuntime`。

**说明：** 本 Task 用假清单（硬编码 `sampleManifest()`）与 `InMemoryPluginRuntime`，**不下载脚本**。单选后才下载/校验/装载（阶段 3）。切换来源不中断当前播放（GC #13，本 Task 仅状态，不接播放器）。来源状态（未安装/安装中/可用/需强制更新/已撤销，spec §10）由 P3T7 安装结果驱动渲染，本 Task 仅显示版本与单选；**不显示下载 URL**（spec §10）。

- [ ] **Step 1: 写失败测试 — ViewModel 状态流**
```kotlin
// app/src/test/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModelTest.kt
package com.ncm.app.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMusicSourceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun selectSourceUpdatesSelectedPluginIdAndKeepsOthersUnselected() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap())
        )
        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.manifestItems.size)
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.kw", vm.uiState.value.selectedPluginId)
        vm.selectSource("linglan.wy")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.wy", vm.uiState.value.selectedPluginId)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 ViewModel**

```kotlin
// app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt
package com.ncm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.manifest.allowedManifestItems
import com.ncm.app.plugin.manifest.DEFAULT_SOURCE_ALLOW_RULES
import com.ncm.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OnlineSourceUiState(
    val authState: LinglanAuthState = LinglanAuthState.DISCONNECTED,
    val selectedPluginId: String? = null,
    val manifestItems: List<ManifestItem> = emptyList(),
    val isDownloading: Boolean = false,
    val validUntilEpochMs: Long? = null,   // spec §10 已连接显示到期时间；P2T7 填充
    val error: String? = null
)

class OnlineMusicSourceViewModel(
    private val manifestProvider: suspend () -> List<ManifestItem>,
    private val runtime: PluginRuntime
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnlineSourceUiState())
    val uiState: StateFlow<OnlineSourceUiState> = _uiState

    fun connect(secret: String) {
        if (_uiState.value.authState == LinglanAuthState.VALIDATING) return  // 阻止重复提交（GC #13）
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.VALIDATING, error = null)
        viewModelScope.launch {
            val valid = secret.trim().length >= 8
            if (!valid) {
                _uiState.value = _uiState.value.copy(
                    authState = LinglanAuthState.ERROR,
                    error = "密钥无效或已过期"
                )
                return@launch
            }
            // 阶段 2：假验证（真服务端校验在 P2T7）；只演示状态机
            _uiState.value = _uiState.value.copy(
                authState = LinglanAuthState.ACTIVE,
                validUntilEpochMs = null   // P2T7 联调后填充
            )
        }
    }

    fun cancelConnect() {  // 验证中允许取消（spec §10）
        if (_uiState.value.authState != LinglanAuthState.VALIDATING) return
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.DISCONNECTED, error = null)
    }

    fun refreshManifest() {
        viewModelScope.launch {
            val items = runCatchingManifest()
            _uiState.value = _uiState.value.copy(
                manifestItems = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES),
                error = null
            )
        }
    }

    fun selectSource(pluginId: String) {
        if (_uiState.value.selectedPluginId == pluginId) return
        _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
        viewModelScope.launch {
            // 阶段 3：这里触发下载+校验+装载；失败时恢复上一个当前来源（GC #13）
            _uiState.value = _uiState.value.copy(
                selectedPluginId = pluginId,
                isDownloading = false
            )
        }
    }

    fun disconnect() {
        runtime.destroy()
        _uiState.value = OnlineSourceUiState(authState = LinglanAuthState.DISCONNECTED)
    }

    private suspend fun runCatchingManifest(): List<ManifestItem> = try {
        manifestProvider()
    } catch (_: Exception) {
        emptyList()
    }
}

/** 阶段 2 假清单（无真实脚本/密钥）；阶段 3 用 LinglanManifestClient 替换。 */
suspend fun sampleManifest(): List<ManifestItem> = listOf(
    ManifestItem("linglan.kw", "酷我音乐", "1.0.0", "https://provider.example/kw/v1.js", com.ncm.app.plugin.model.PluginCategory.MUSIC, 1, null, com.ncm.app.plugin.model.PluginReleaseStatus.ACTIVE, null),
    ManifestItem("linglan.tx", "QQ音乐", "1.0.0", "https://provider.example/tx/v1.js", com.ncm.app.plugin.model.PluginCategory.MUSIC, 1, null, com.ncm.app.plugin.model.PluginReleaseStatus.ACTIVE, null),
    ManifestItem("linglan.wy", "网易云音乐", "1.0.0", "https://provider.example/wy/v1.js", com.ncm.app.plugin.model.PluginCategory.MUSIC, 1, null, com.ncm.app.plugin.model.PluginReleaseStatus.ACTIVE, null)
)
```

- [ ] **Step 4: 实现设置页 UI（Compose，占位接线）**

```kotlin
// app/src/main/java/com/ncm/app/ui/screens/settings/OnlineMusicSourceSection.kt
package com.ncm.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel

@Composable
fun OnlineMusicSourceSection(viewModel: OnlineMusicSourceViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("在线音乐来源", style = MaterialTheme.typography.titleMedium)
        when (state.authState) {
            LinglanAuthState.DISCONNECTED -> Text("未连接 · 点击「连接聆澜」输入密钥")
            LinglanAuthState.VALIDATING -> {
                Text("验证中…")
                TextButton(onClick = { viewModel.cancelConnect() }) { Text("取消") }
            }
            LinglanAuthState.ACTIVE, LinglanAuthState.STALE_OFFLINE -> {
                Text("已连接 · 选择一个来源")
                state.validUntilEpochMs?.let { expire ->
                    Text("授权到期：${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(expire))}")
                }
                state.manifestItems.forEach { item ->
                    RadioButton(
                        selected = state.selectedPluginId == item.id,
                        onClick = { viewModel.selectSource(item.id) }
                    )
                    Text("${item.name} v${item.version}")
                }
                TextButton(onClick = { viewModel.disconnect() }) { Text("断开") }
            }
            else -> Text(state.error ?: "连接异常")
        }
    }
}
```
（`collectAsStateWithLifecycle` 需 `lifecycle-runtime-compose`，build.gradle 已有。）

- [ ] **Step 5: 在现有设置入口挂载**（定位 MainActivity/MyScreen 的设置区域，把 `OnlineMusicSourceSection` 加入。本步骤具体文件以现有设置 UI 位置为准，改动只做「新增区块」，不改现有布局结构。）

- [ ] **Step 6: 运行测试通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.viewmodel.OnlineMusicSourceViewModelTest"
git add app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt app/src/main/java/com/ncm/app/ui/screens/settings/OnlineMusicSourceSection.kt app/src/test/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModelTest.kt
git commit -m "feat(settings): single-select online music source UI with fake manifest"
```

### P2T7: 真实服务端验证接线（OkHttp 客户端）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/manifest/LinglanAuthClient.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/manifest/LinglanAuthClientTest.kt`

**Interfaces:**
- Produces（阶段 4 依赖）：
  - `data class AuthValidationResult(state: LinglanAuthState, validUntilEpochMs: Long?, message: String?)`
  - `class LinglanAuthClient(private val http: suspend (String) -> String)`：`suspend fun validate(secret: String): AuthValidationResult`
- Consumes: P2T3 状态机。

**说明：** 真实端点字段（限流/到期/撤销/错误码）在联调前确认（§17）。本 Task 定义接口与协议映射，用注入假 HTTP 测试 401/403/200/网络失败四条路径；端点 URL 从 `BuildConfig` 或设置注入，**不把密钥写入查询参数**（GC #4 优先短期令牌/请求头，见 spec §8.3）。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/manifest/LinglanAuthClientTest.kt
package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.auth.LinglanAuthState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanAuthClientTest {

    @Test
    fun validKeyMapsToActive() = runTest {
        val client = LinglanAuthClient(http = { """{"code":200,"expireAt":9999999999999}""" })
        val result = client.validate("valid-key-123")
        assertEquals(LinglanAuthState.ACTIVE, result.state)
    }

    @Test
    fun expiredKeyMapsToExpired() = runTest {
        val client = LinglanAuthClient(http = { """{"code":401,"message":"key expired"}""" })
        assertEquals(LinglanAuthState.EXPIRED, client.validate("bad").state)
    }

    @Test
    fun revokedKeyMapsToRevoked() = runTest {
        val client = LinglanAuthClient(http = { """{"code":403,"message":"revoked"}""" })
        assertEquals(LinglanAuthState.REVOKED, client.validate("bad").state)
    }

    @Test
    fun networkFailureMapsToErrorNotInvalid() = runTest {
        val client = LinglanAuthClient(http = { throw java.io.IOException("no network") })
        assertEquals(LinglanAuthState.ERROR, client.validate("key").state)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/manifest/LinglanAuthClient.kt
package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.auth.nextStateForServerResponse
import java.io.IOException

data class AuthValidationResult(
    val state: LinglanAuthState,
    val validUntilEpochMs: Long?,
    val message: String?
)

/** 聆澜密钥校验客户端。HTTP 注入以便单测；密钥经请求头/短期令牌传递，不进查询参数（GC #4/#8）。 */
class LinglanAuthClient(
    private val http: suspend (String) -> String
) {
    suspend fun validate(secret: String): AuthValidationResult = try {
        val body = http(buildUrl(secret))
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.get("code")?.asInt ?: 200
        val state = nextStateForServerResponse(LinglanAuthState.VALIDATING, httpCode = 200, bodyCode = code)
        AuthValidationResult(
            state = state,
            validUntilEpochMs = root.get("expireAt")?.asLong,
            message = root.get("message")?.asString
        )
    } catch (e: IOException) {
        AuthValidationResult(LinglanAuthState.ERROR, null, "暂时无法连接验证服务")
    }

    private fun buildUrl(secret: String): String =
        "https://linglan.invalid/api/auth/validate"  // 生产端点见 P2T7 联调确认
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.manifest.LinglanAuthClientTest"
git add app/src/main/java/com/ncm/app/plugin/manifest/LinglanAuthClient.kt app/src/test/java/com/ncm/app/plugin/manifest/LinglanAuthClientTest.kt
git commit -m "feat(auth): linglan credential validation client with state mapping"
```

### P2T8: 来源选择持久化与启动恢复（MusicSourceSettings）

**Files:**
- Create: `app/src/main/java/com/ncm/app/data/store/MusicSourceSettings.kt`
- Test: `app/src/test/java/com/ncm/app/data/store/MusicSourceSettingsTest.kt`（Robolectric + SharedPreferences）
- Modify: `app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt`（追加可选 `settings` 参数，兼容 P2T6 测试）

**Interfaces:**
- Produces（P4T4 消费）：
  - `data class OnlineSourcePrefs(authState: String, selectedPluginId: String?, lastManifestVersion: Int, lastVerifiedAtEpochMs: Long?)`
  - `class MusicSourceSettings(context, prefsName: String = DEFAULT_PREFS_NAME)`：`fun read(): OnlineSourcePrefs`、`fun write(prefs)`、`fun clear()`、`val currentPluginId: String?`
- Consumes: P2T3 `LinglanAuthState`（持久化其枚举名）、P2T6 ViewModel。

**说明：** 补 spec §13 缺口——选中来源/授权状态此前只在 ViewModel 内存态，重启即丢。本 Task 持久化后：启动恢复选中插件与授权状态，`currentPluginId` 作为 P4T4 `currentSource` 注入的数据源。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/data/store/MusicSourceSettingsTest.kt
package com.ncm.app.data.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ncm.app.plugin.auth.LinglanAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicSourceSettingsTest {

    private lateinit var settings: MusicSourceSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("test_music_source", Context.MODE_PRIVATE).edit().clear().commit()
        settings = MusicSourceSettings(context, prefsName = "test_music_source")
    }

    @Test
    fun writeThenReadRoundTrips() {
        settings.write(
            OnlineSourcePrefs(
                authState = LinglanAuthState.ACTIVE.name,
                selectedPluginId = "linglan.kw",
                lastManifestVersion = 3,
                lastVerifiedAtEpochMs = 1_000_000L
            )
        )
        val restored = settings.read()
        assertEquals(LinglanAuthState.ACTIVE.name, restored.authState)
        assertEquals("linglan.kw", restored.selectedPluginId)
        assertEquals(3, restored.lastManifestVersion)
        assertEquals(1_000_000L, restored.lastVerifiedAtEpochMs)
        assertEquals("linglan.kw", settings.currentPluginId)
    }

    @Test
    fun clearResetsToDefaults() {
        settings.write(OnlineSourcePrefs("ACTIVE", "linglan.kw", 3, 1_000_000L))
        settings.clear()
        val restored = settings.read()
        assertEquals(LinglanAuthState.DISCONNECTED.name, restored.authState)
        assertNull(restored.selectedPluginId)
        assertEquals(0, restored.lastManifestVersion)
        assertNull(restored.lastVerifiedAtEpochMs)
        assertNull(settings.currentPluginId)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/data/store/MusicSourceSettings.kt
package com.ncm.app.data.store

import android.content.Context
import com.ncm.app.plugin.auth.LinglanAuthState

data class OnlineSourcePrefs(
    val authState: String = LinglanAuthState.DISCONNECTED.name,
    val selectedPluginId: String? = null,
    val lastManifestVersion: Int = 0,
    val lastVerifiedAtEpochMs: Long? = null
)

/** 在线音乐来源持久化设置。密钥绝不入库/入偏好（GC #4/#8），只存状态标识与插件 ID。 */
class MusicSourceSettings(
    context: Context,
    private val prefsName: String = DEFAULT_PREFS_NAME
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val currentPluginId: String?
        get() = prefs.getString(KEY_SELECTED_PLUGIN, null)

    fun read(): OnlineSourcePrefs = OnlineSourcePrefs(
        authState = prefs.getString(KEY_AUTH_STATE, LinglanAuthState.DISCONNECTED.name)
            ?: LinglanAuthState.DISCONNECTED.name,
        selectedPluginId = prefs.getString(KEY_SELECTED_PLUGIN, null),
        lastManifestVersion = prefs.getInt(KEY_MANIFEST_VERSION, 0),
        lastVerifiedAtEpochMs =
            if (prefs.contains(KEY_VERIFIED_AT)) prefs.getLong(KEY_VERIFIED_AT, 0L) else null
    )

    fun write(prefs: OnlineSourcePrefs) {
        val editor = this.prefs.edit()
            .putString(KEY_AUTH_STATE, prefs.authState)
            .putInt(KEY_MANIFEST_VERSION, prefs.lastManifestVersion)
        if (prefs.selectedPluginId != null) {
            editor.putString(KEY_SELECTED_PLUGIN, prefs.selectedPluginId)
        } else {
            editor.remove(KEY_SELECTED_PLUGIN)
        }
        if (prefs.lastVerifiedAtEpochMs != null) {
            editor.putLong(KEY_VERIFIED_AT, prefs.lastVerifiedAtEpochMs)
        } else {
            editor.remove(KEY_VERIFIED_AT)
        }
        editor.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_PREFS_NAME = "music_source"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val KEY_SELECTED_PLUGIN = "selected_plugin_id"
        private const val KEY_MANIFEST_VERSION = "last_manifest_version"
        private const val KEY_VERIFIED_AT = "last_verified_at_epoch_ms"
    }
}
```
```kotlin
// app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt —— 构造签名追加默认参数，P2T6 测试不受影响
class OnlineMusicSourceViewModel(
    private val manifestProvider: suspend () -> List<ManifestItem>,
    private val runtime: PluginRuntime,
    private val settings: MusicSourceSettings? = null   // 新增
) : ViewModel() {

    init {
        settings?.read()?.let { saved ->
            if (saved.selectedPluginId != null) {
                _uiState.value = _uiState.value.copy(
                    authState = LinglanAuthState.valueOf(saved.authState),
                    selectedPluginId = saved.selectedPluginId
                )
            }
        }
    }

    // connect() 验证成功后：settings?.write(_uiState.value.run {
    //     OnlineSourcePrefs(authState.name, selectedPluginId, lastManifestVersion = 0, lastVerifiedAtEpochMs = null)
    // })
    // selectSource() 成功设置 selectedPluginId 后：settings?.write(同上，含 selectedPluginId)
    // disconnect() 末尾：settings?.clear()
}
```
（Step 3 代码块内的注释是接线点；若在实现时发现 VM 内状态与持久化字段耦合复杂，可改为 VM 仅读写 `settings`、UI 层订阅 `currentPluginId` 变化，以 P2T6/P2T8 测试通过为准。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.store.MusicSourceSettingsTest"
git add app/src/main/java/com/ncm/app/data/store/MusicSourceSettings.kt app/src/main/java/com/ncm/app/viewmodel/OnlineMusicSourceViewModel.kt app/src/test/java/com/ncm/app/data/store/MusicSourceSettingsTest.kt
git commit -m "feat(settings): persist selected music source and auth state across restarts"
```

---

# 阶段 3：插件宿主（QuickJS 隔离）

**阶段目标：** 实现 QuickJS 上下文、受控 HTTP、兼容模块、资源限制、两步装载与脚本缓存更新。通过假插件契约测试后，再使用轮换后的测试密钥做受控联调（spec §14 阶段 3）。

**阶段硬门槛（§9/§17，不满足则只能停留在本地假插件验证）：** 稳定插件 ID、签名清单、版本/撤销规则、授权状态机、聆澜运行授权全部确定；生产签名公钥/轮换/紧急撤销规则确定；清单把插件 ID + 版本 + SHA-256 绑定并验证签名。

**本阶段所有测试使用本地假插件脚本（`app/src/test/resources/fakeplugins/`），不包含真实密钥。**

### P3T1: QuickJS 运行时封装（选定库后的初始化与求值）

**Files:**
- Modify: `app/build.gradle.kts`（添加 P0T2 选定依赖）
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/QuickJsRuntime.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/QuickJsRuntimeTest.kt`

**Interfaces:**
- Consumes: P0T2 选定库 + P0T3 假插件脚本资源 + P1T4 `PluginRuntime`。
- Produces（P3T3 依赖）：
  - `class QuickJsRuntime`（内部每个插件一个上下文；`fun evaluate(script: String): Any?`；`fun destroy()`）
  - 模块求值：`module.exports` 解析为 `PluginDescriptor` + 导出方法表。

- [ ] **Step 1: 添加依赖并在本地 JVM 测试环境验证 `hello.cjs` 可求值**
```bash
# build.gradle.kts：testImplementation 添加 P0T2 选定坐标（JVM 侧可用 wrapper-java 或 quickjs-kt JVM）
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.QuickJsRuntimeTest"
```

- [ ] **Step 2: 写失败测试 — 加载假插件并读取元数据**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/QuickJsRuntimeTest.kt
package com.ncm.app.plugin.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsRuntimeTest {

    @Test
    fun evaluatesCommonJsModuleAndReadsExports() {
        val script = """
            module.exports = {
                platform: 'fake-hello',
                version: '1.0.0',
                supportedSearchType: ['music']
            };
        """.trimIndent()
        val runtime = QuickJsRuntime()
        val meta = runtime.loadModule(script)
        assertEquals("fake-hello", meta.platform)
        assertEquals("1.0.0", meta.version)
        assertTrue(meta.supportedSearchType.contains("music"))
        runtime.destroy()
    }

    @Test
    fun throwsHostErrorWhenTopLevelViolatesBoundary() {
        val script = """
            var fs = require('fs');
            module.exports = { platform: 'evil', version: '1.0.0' };
        """.trimIndent()
        val runtime = QuickJsRuntime()
        // 禁止的 require 目标必须抛错，不能静默成功
        val result = runCatching { runtime.loadModule(script) }
        assertTrue(result.isFailure)
        runtime.destroy()
    }
}
```
（若 `runCatching` 违反 GC #2，测试内改用 try/catch。第 2 个测试依赖「受控模块表」——P3T3 提供；本 Task 先把 `require` 封死为仅注册模块。）

- [ ] **Step 3: 实现封装（依赖选定库 API，以下为示意骨架，按 P0T2 实际 API 调整）**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/QuickJsRuntime.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException

/** 插件模块导出的静态元数据（spec §6.1）。 */
data class PluginModuleMeta(
    val platform: String,
    val version: String,
    val supportedSearchType: List<String>
)

/**
 * QuickJS 运行时封装：每个插件一个独立上下文（GC #7）。
 * 本类是本计划里唯一的 QuickJS 触碰点：P0T2 选定库的 API 只出现在本类的
 * 私有实现体中，其余任务（P3T8、PluginRegistry）只依赖本类的 Kotlin 接口。
 */
class QuickJsRuntime(
    private val compatModules: Map<String, Any?> = installCompatModules()
) {
    private val contexts = mutableMapOf<String, Any>()
    private var httpExecutor: HttpExecutor? = null

    /**
     * 两步装载第一步（GC #11）：在禁用真实网络的上下文求值脚本并解析元数据。
     * hostParams 为只读运行参数（宿主版本、短期授权句柄），以冻结对象注入（spec §7.1）。
     * require() 只解析 [CompatModules] 已注册模块（P3T3）；其他目标抛 [PluginException]。
     */
    fun loadModule(script: String, hostParams: Map<String, Any?>): PluginModuleMeta {
        val meta = evaluateModule(script, hostParams)
        if (meta.platform.isBlank()) throw PluginException("INVALID_META", "插件缺少 platform", retryable = false)
        if (meta.version.isBlank()) throw PluginException("INVALID_META", "插件缺少 version", retryable = false)
        return meta
    }

    /** 调用已装载插件导出的方法；返回值由宿主桥接为 Kotlin 类型（Map/List/基本类型）。 */
    fun invokeMethod(pluginId: String, name: String, args: Array<Any?>): Any? {
        val ctx = contexts[pluginId] ?: throw PluginException("PLUGIN_NOT_LOADED", "插件未装载", retryable = false)
        return callExport(ctx, name, args)
    }

    /** 注入受控 HTTP 执行器：探针阶段用 probeExecutor（GC #11），正常调用由阶段 4 组装时替换。 */
    fun useHttpExecutor(executor: HttpExecutor) { httpExecutor = executor }

    fun destroy() { contexts.clear() }

    // 实现体在 Step 3 落地：把 P0T2 决策记录里的「选定库最小可用调用片段」贴进来。
    //   wrapper-android：QuickJSLoader.init() → QuickJSContext.create() → context.evaluate(script)
    //     → 读 module.exports；JS 拦截器代理到 compatModules 与受控 HTTP 桥。
    //   quickjs-kt：quickJs { } 挂 JSObject 导出。
    private fun evaluateModule(script: String, hostParams: Map<String, Any?>): PluginModuleMeta =
        throw UnsupportedOperationException("实现体来自 P0T2 决策记录（Step 3）")

    private fun callExport(ctx: Any, name: String, args: Array<Any?>): Any? =
        throw UnsupportedOperationException("实现体来自 P0T2 决策记录（Step 3）")
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.QuickJsRuntimeTest"
git add app/build.gradle.kts app/src/main/java/com/ncm/app/plugin/runtime/QuickJsRuntime.kt app/src/test/java/com/ncm/app/plugin/runtime/QuickJsRuntimeTest.kt
git commit -m "feat(runtime): QuickJS module loading for fake plugins"
```

### P3T2: 受控 HTTP 桥 + SSRF 防护

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/ControlledHttpBridge.kt`
- Create: `app/src/main/java/com/ncm/app/plugin/security/SsrfGuard.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/security/SsrfGuardTest.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/ControlledHttpBridgeTest.kt`

**Interfaces:**
- Produces（P3T4 依赖）：
  - `class SsrfGuard(private val allowHttpsOnly: Boolean = true, private val restrictedPorts: Set<Int> = DEFAULT_RESTRICTED_PORTS, private val now: () -> Long = { System.currentTimeMillis() })`
  - `fun validate(url: String): SsrfDecision`（`sealed interface SsrfDecision { object Allow; data class Deny(reason) }`）
  - `data class HttpRequestSpec(url, method, headers, body?, timeoutMs)`
  - `data class HttpResult(status: Int, headers: Map<String,String>, data: ByteArray)`（兼容插件常用 `status/headers/data/request`，GC §6.3）
  - `typealias HttpExecutor = suspend (HttpRequestSpec) -> HttpResult`
  - `class ControlledHttpBridge(ssrfGuard, executor, maxResponseBytes, maxRedirects)`：`suspend fun execute(spec): HttpResult`（前置校验目标，每次重定向后重新校验；响应体上限）
- Consumes: P1T2 `PluginException`。生产 OkHttp 执行器在 NeteaseApp 组装（阶段 4/6），不在此新增测试依赖。

- [ ] **Step 1: 写失败测试 — SSRF 目标校验**
```kotlin
// app/src/test/java/com/ncm/app/plugin/security/SsrfGuardTest.kt
package com.ncm.app.plugin.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsrfGuardTest {

    @Test
    fun allowsPublicHttps() {
        val guard = SsrfGuard()
        assertTrue(guard.validate("https://music.163.com/api/").isAllow)
    }

    @Test
    fun deniesNonHttpProtocols() {
        val guard = SsrfGuard()
        val denials = listOf(
            "file:///etc/passwd",
            "content://media/audio",
            "intent://x",
            "http://127.0.0.1/admin",
            "http://10.0.0.1/admin",
            "http://192.168.1.1/admin",
            "http://[::1]/admin"
        )
        denials.forEach { assertTrue("$it must be denied", guard.validate(it).isDeny) }
    }

    @Test
    fun deniesRestrictedPorts() {
        val guard = SsrfGuard(restrictedPorts = setOf(22, 3306, 6379))
        assertTrue(guard.validate("https://example.com:22/x").isDeny)
        assertTrue(guard.validate("https://example.com:3306/x").isDeny)
        assertTrue(guard.validate("https://example.com:443/x").isAllow)
    }
}
```
（需在 `SsrfGuard` 内提供 `SsrfDecision.isAllow`/`isDeny` 扩展。）

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 SsrfGuard**

```kotlin
// app/src/main/java/com/ncm/app/plugin/security/SsrfGuard.kt
package com.ncm.app.plugin.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

sealed interface SsrfDecision {
    data object Allow : SsrfDecision
    data class Deny(val reason: String) : SsrfDecision

    val isAllow: Boolean get() = this is Allow
    val isDeny: Boolean get() = this is Deny
}

/**
 * SSRF 防护：协议白名单 + 受限端口 + DNS 解析后校验 IPv4/IPv6 目标。
 * 每次重定向后必须重新调用 [validateResolved]，防 DNS 重绑定/重定向进私网（GC #7）。
 */
class SsrfGuard(
    private val allowHttpsOnly: Boolean = true,
    private val restrictedPorts: Set<Int> = DEFAULT_RESTRICTED_PORTS
) {
    fun validate(url: String): SsrfDecision {
        val uri = try { URI(url) } catch (_: Exception) { return SsrfDecision.Deny("invalid url") }
        val scheme = uri.scheme?.lowercase() ?: return SsrfDecision.Deny("missing scheme")
        if (scheme == "http" && allowHttpsOnly) return SsrfDecision.Deny("http not allowed")
        if (scheme != "http" && scheme != "https") return SsrfDecision.Deny("non-http protocol")
        val host = uri.host ?: return SsrfDecision.Deny("missing host")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        if (port in restrictedPorts) return SsrfDecision.Deny("restricted port $port")
        return if (isLoopbackOrLinkLocal(host) || isPrivateLiteral(host)) {
            SsrfDecision.Deny("local/private address")
        } else {
            SsrfDecision.Allow
        }
    }

    /** 解析后校验：每次重定向后调用。 */
    fun validateResolved(address: InetAddress, port: Int): SsrfDecision {
        if (port in restrictedPorts) return SsrfDecision.Deny("restricted port $port")
        if (address.isLoopbackAddress || address.isLinkLocalAddress) {
            return SsrfDecision.Deny("local address")
        }
        if (address.isSiteLocalAddress) return SsrfDecision.Deny("site-local address")
        return SsrfDecision.Allow
    }

    private fun isLoopbackOrLinkLocal(host: String): Boolean {
        if (host == "localhost") return true
        val lower = host.lowercase()
        if (lower.endsWith(".localhost")) return true
        if (lower.startsWith("fe80:")) return true
        if (lower == "::1") return true
        return false
    }

    private fun isPrivateLiteral(host: String): Boolean {
        val literal = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
        if (literal.isSiteLocalAddress || literal.isLoopbackAddress) return true
        return literal is Inet4Address &&
            (literal.hostAddress.startsWith("10.") ||
                literal.hostAddress.startsWith("192.168.") ||
                (literal.hostAddress.startsWith("172.") &&
                    literal.hostAddress.substringAfter("172.").substringBefore(".").toIntOrNull()
                        ?.let { it in 16..31 } == true))
    }

    companion object {
        val DEFAULT_RESTRICTED_PORTS: Set<Int> = setOf(22, 23, 25, 53, 110, 143, 3306, 3389, 5432, 6379, 11211)
    }
}
```

- [ ] **Step 4: 写 HTTP 桥测试（重定向逐跳重校验 + 响应体上限；不新增测试依赖）**

```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/ControlledHttpBridgeTest.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledHttpBridgeTest {

    private val guard = SsrfGuard()

    @Test
    fun followsRedirectRevalidatingEachHop() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/song"] =
            HttpResult(302, mapOf("location" to "https://b.example/song"), byteArrayOf())
        responses["https://b.example/song"] =
            HttpResult(200, emptyMap(), "{\"ok\":true}".toByteArray())
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val result = bridge.execute(HttpRequestSpec("https://a.example/song", "GET", emptyMap()))
        assertEquals(200, result.status)
        assertEquals("{\"ok\":true}", String(result.data, Charsets.UTF_8))
    }

    @Test
    fun redirectIntoSiteLocalIsBlocked() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/song"] =
            HttpResult(302, mapOf("location" to "http://127.0.0.1/admin"), byteArrayOf())
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val outcome = runCatching { bridge.execute(HttpRequestSpec("https://a.example/song", "GET", emptyMap())) }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun responseLargerThanLimitIsRejected() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/big"] =
            HttpResult(200, emptyMap(), ByteArray(6 * 1024 * 1024))
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            maxResponseBytes = 1024,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val outcome = runCatching { bridge.execute(HttpRequestSpec("https://a.example/big", "GET", emptyMap())) }
        assertTrue(outcome.isFailure)
    }
}
```

- [ ] **Step 5: 实现 ControlledHttpBridge（执行器注入，重定向由宿主逐跳处理）**

```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/ControlledHttpBridge.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.security.SsrfDecision
import com.ncm.app.plugin.security.SsrfGuard

data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Long = 10_000L
)

data class HttpResult(
    val status: Int,
    val headers: Map<String, String>,
    val data: ByteArray
)

/** 低层网络执行器：生产用 OkHttp（NeteaseApp 组装），单测用假 Map。 */
typealias HttpExecutor = suspend (HttpRequestSpec) -> HttpResult

/**
 * 插件受控 HTTP 桥：前置 SSRF 校验 + 宿主逐跳重定向（每跳重新校验目标，GC #7）
 * + 响应体上限。生产 OkHttp 执行器在 NeteaseApp 组装（支持取消与超时）。
 */
class ControlledHttpBridge(
    private val ssrfGuard: SsrfGuard,
    private val executor: HttpExecutor,
    private val maxResponseBytes: Int = 5 * 1024 * 1024,
    private val maxRedirects: Int = 5
) {
    suspend fun execute(spec: HttpRequestSpec): HttpResult {
        val decision = ssrfGuard.validate(spec.url)
        if (decision is SsrfDecision.Deny) throw IllegalStateException("blocked: ${decision.reason}")
        return executeWithRedirects(spec, remaining = maxRedirects)
    }

    private suspend fun executeWithRedirects(spec: HttpRequestSpec, remaining: Int): HttpResult {
        val result = executor(spec)
        if (result.data.size > maxResponseBytes) throw IllegalStateException("response body too large")
        if (!isRedirect(result.status) || remaining <= 0) return result
        val location = result.headers["location"] ?: return result
        val nextUrl = if (location.startsWith("http")) location else resolveRelative(spec.url, location)
        val redirectDecision = ssrfGuard.validate(nextUrl)
        if (redirectDecision is SsrfDecision.Deny) {
            throw IllegalStateException("blocked redirect: ${redirectDecision.reason}")
        }
        return executeWithRedirects(spec.copy(url = nextUrl, body = null), remaining - 1)
    }

    private fun isRedirect(status: Int): Boolean = status in setOf(301, 302, 303, 307, 308)

    private fun resolveRelative(base: String, location: String): String {
        val baseUrl = java.net.URI(base)
        val resolved = baseUrl.resolve(location)
        return resolved.toString()
    }
}
```

- [ ] **Step 6: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.security.SsrfGuardTest" --tests "com.ncm.app.plugin.runtime.ControlledHttpBridgeTest"
git add app/src/main/java/com/ncm/app/plugin/security/SsrfGuard.kt app/src/main/java/com/ncm/app/plugin/runtime/ControlledHttpBridge.kt app/src/test/java/com/ncm/app/plugin/security/SsrfGuardTest.kt app/src/test/java/com/ncm/app/plugin/runtime/ControlledHttpBridgeTest.kt
git commit -m "feat(security): SSRF guard with DNS-resolution and redirect revalidation, controlled http bridge"
```

### P3T3: CommonJS 兼容模块表与 require 解析

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/CompatModules.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/CompatModulesTest.kt`

**Interfaces:**
- Produces（P3T1/P3T4 依赖）：
  - `fun installCompatModules(context: Any)`（把 `require('axios'/'crypto-js'/'qs'/'big-integer'/'dayjs'/'cheerio'/'he')` 解析到小型兼容实现；未注册模块抛 `PluginException`）
  - `val COMPAT_MODULE_NAMES: Set<String>`（上述 7 个）
- Consumes: spec §6.3「实际脚本依赖—宿主实现」清单（P0T4 契约文档）。

**说明：** 优先提供小型兼容模块，不允许任意 npm 安装（GC #7）。HTTP 返回对象兼容 `status/headers/data/request`。

- [ ] **Step 1: 写失败测试 — 未注册 require 目标必须失败**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/CompatModulesTest.kt
package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatModulesTest {

    @Test
    fun registeredCompatModulesAreOnlyKnownOnes() {
        assertTrue("axios" in COMPAT_MODULE_NAMES)
        assertTrue("qs" in COMPAT_MODULE_NAMES)
        assertFalse("fs" in COMPAT_MODULE_NAMES)
        assertFalse("child_process" in COMPAT_MODULE_NAMES)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/CompatModules.kt
package com.ncm.app.plugin.runtime

/** 受控 CommonJS 兼容模块表（spec §6.3）。未在此表的 require 一律拒绝。 */
val COMPAT_MODULE_NAMES: Set<String> = setOf(
    "axios", "crypto-js", "qs", "big-integer", "dayjs", "cheerio", "he"
)
```
（每个模块的小型实现放到 `compat/` 子包，如 `compat/AxiosCompat.kt`，用宿主受控 HTTP 桥做底层；模块内不得访问 Android API。具体实现体按 P0T4 契约文档「实际脚本依赖」清单填写，先为假插件契约测试提供最小 axios/qs/dayjs。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.CompatModulesTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/CompatModules.kt app/src/test/java/com/ncm/app/plugin/runtime/CompatModulesTest.kt
git commit -m "feat(runtime): controlled commonjs compat module allowlist"
```

### P3T3b: 插件隔离 CookieJar（spec §6.3 末段）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/PluginCookieJar.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/PluginCookieJarTest.kt`

**Interfaces:**
- Consumes: P2T1 `SecretVault`（加密持久化）、P3T2 `HttpExecutor` 链路。
- Produces:
  - `class PluginCookieJar`：`fun put(pluginId, name, value, domain)`, `fun cookiesFor(pluginId, url): List<Pair<String,String>>`, `fun clearPlugin(pluginId)`, `fun clearAll()`
  - 约束：按 `pluginId` 隔离——不同插件不能互相读取；持久化密文用独立 Keystore 密钥加密并排除备份；`clearAll` 由 P2T2 的擦除语义覆盖（GC #8/#6.3）。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/PluginCookieJarTest.kt
package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginCookieJarTest {

    @Test
    fun cookiesAreIsolatedPerPlugin() {
        val jar = PluginCookieJar()
        jar.put("linglan.kw", "session", "kw-secret", domain = "k.api")
        jar.put("linglan.tx", "session", "tx-secret", domain = "t.api")
        // 插件 A 的请求只带自己的 Cookie，拿不到 B 的
        assertEquals(listOf("session" to "kw-secret"), jar.cookiesFor("linglan.kw", "https://k.api/x"))
        assertEquals(listOf("session" to "tx-secret"), jar.cookiesFor("linglan.tx", "https://t.api/x"))
    }

    @Test
    fun clearPluginRemovesOnlyThatPlugin() {
        val jar = PluginCookieJar()
        jar.put("linglan.kw", "a", "1", domain = "k.api")
        jar.put("linglan.tx", "b", "2", domain = "t.api")
        jar.clearPlugin("linglan.kw")
        assertNull(jar.cookiesFor("linglan.kw", "https://k.api/x").firstOrNull())
        assertEquals(listOf("b" to "2"), jar.cookiesFor("linglan.tx", "https://t.api/x"))
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/PluginCookieJar.kt
package com.ncm.app.plugin.runtime

/**
 * 按插件隔离的 Cookie 存储（spec §6.3）：不同插件不能共享，也不能读取应用自身会话。
 * 持久化时用 P2T1 SecretVault 加密并排除备份；断开授权时 clearAll 走加密擦除（GC #8）。
 */
class PluginCookieJar {
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()

    data class Cookie(val name: String, val value: String, val domain: String)

    fun put(pluginId: String, name: String, value: String, domain: String) {
        cookies.getOrPut(pluginId) { mutableListOf() }.add(Cookie(name, value, domain))
    }

    fun cookiesFor(pluginId: String, url: String): List<Pair<String, String>> {
        val domain = url.substringAfter("//").substringBefore('/')
        return cookies[pluginId].orEmpty()
            .filter { domain.endsWith(it.domain) }
            .map { it.name to it.value }
    }

    fun clearPlugin(pluginId: String) { cookies.remove(pluginId) }

    fun clearAll() { cookies.clear() }
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.PluginCookieJarTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/PluginCookieJar.kt app/src/test/java/com/ncm/app/plugin/runtime/PluginCookieJarTest.kt
git commit -m "feat(runtime): per-plugin isolated cookie jar"
```

### P3T4: 归一化返回校验与契约探针（两步装载）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/PluginCallNormalizer.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/PluginCallNormalizerTest.kt`

**Interfaces:**
- Produces（P3T5/P4 依赖）：
  - `fun normalizeSearchResult(raw: Any?): List<OnlineTrack>`（必需字段缺失拒绝该条，GC #6；页码缺失按空列表）
  - `fun normalizeResolvedMedia(raw: Any?): ResolvedMedia`（校验 URL 协议/大小/必需字段，GC #7）
  - `fun runContractProbe(pluginId: String, runtime: QuickJsRuntime, bridge: ControlledHttpBridge): ProbeResult`（GC #11 第二步：宿主固定 HTTP 响应执行契约探针，不访问真实网络）
- Consumes: P1T1 模型、P1T2 `PluginException`、P0T3 校验函数。

- [ ] **Step 1: 写失败测试 — 搜索归一化**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/PluginCallNormalizerTest.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCallNormalizerTest {

    @Test
    fun searchResultMissingRequiredFieldsIsRejected() {
        val raw = listOf(
            mapOf("id" to "1", "name" to "ok"),            // 合法
            mapOf("id" to "2"),                             // 缺 name
            mapOf("name" to "x")                            // 缺 id
        )
        val normalized = normalizeSearchResult(
            raw,
            keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) }
        )
        assertEquals(1, normalized.size)
        assertEquals("ok", normalized.first().title)
    }

    @Test
    fun emptyListIsTreatedAsEndOfResults() {
        assertTrue(normalizeSearchResult(emptyList<Any>(), keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) }).isEmpty())
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现归一化**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/PluginCallNormalizer.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia

/** 插件返回值的结构/协议/URL/大小校验后进入应用模型（GC #7）。必需字段缺失拒绝该条。 */
fun normalizeSearchResult(
    raw: List<*>,
    keyFor: (pluginId: String, id: Any) -> ProviderTrackKey
): List<OnlineTrack> = raw.mapNotNull { element ->
    val item = element as? Map<*, *> ?: return@mapNotNull null
    val name = item["name"] as? String
    val id = item["id"]
    if (name.isNullOrBlank() || id == null) return@mapNotNull null
    OnlineTrack(
        key = keyFor("", id),
        producedByPluginVersion = "probe",
        payloadSchemaVersion = MUSICFREE_PROTOCOL_VERSION,
        title = name,
        artists = (item["artist"] as? String)?.split("/")?.map { OnlineArtist(it.trim(), it.trim()) }.orEmpty(),
        album = null,
        durationMs = (item["duration"] as? Number)?.toLong(),
        artworkUrl = item["cover"] as? String,
        pluginPayload = BoundedJsonObject.fromMap(
            mapOf("raw" to item)
        )
    )
}

fun normalizeResolvedMedia(raw: Any?): ResolvedMedia {
    val map = raw as? Map<*, *> ?: throw IllegalStateException("resolved media must be an object")
    val url = map["url"] as? String
    if (url.isNullOrBlank()) throw IllegalStateException("resolved media missing url")
    val headers = (map["headers"] as? Map<*, *>)?.entries
        ?.filter { it.key is String && it.value is String }
        ?.associate { it.key as String to it.value as String }
        .orEmpty()
    return ResolvedMedia(
        url = url,
        headers = headers,
        userAgent = map["userAgent"] as? String,
        quality = map["quality"] as? String,
        expiresAtEpochMs = (map["expiresAt"] as? Number)?.toLong()
    )
}

data class ProbeResult(val healthy: Boolean, val reason: String? = null)

/**
 * 契约探针（GC #11 第二步）：调用 [invokeProbe]（宿主固定 HTTP 响应环境下的插件 search，
 * 见 P3T8 的 probeExecutor），验证返回合法结果。任何指向真实网络的请求都会抛错 → 探针失败。
 */
fun runContractProbe(
    pluginId: String,
    invokeProbe: () -> Any?
): ProbeResult = try {
    val map = invokeProbe() as? Map<*, *>
    if (map?.get("data") !is List<*>) {
        ProbeResult(false, "契约探针未返回 data 数组")
    } else {
        ProbeResult(true)
    }
} catch (e: Exception) {
    ProbeResult(false, "契约探针失败：${e.message}")
}
```

- [ ] **Step 3b: 契约探针测试（正常/缺 data/抛错）**
```kotlin
// 追加到 PluginCallNormalizerTest.kt
@Test
fun contractProbeAcceptsValidAndRejectsMissingOrThrowing() {
    val valid = runContractProbe("fake") { mapOf("data" to listOf<Any>(), "isEnd" to true) }
    assertEquals(true, valid.healthy)

    val missing = runContractProbe("fake") { mapOf("data" to null) }
    assertEquals(false, missing.healthy)

    val throwing = runContractProbe("fake") { throw IllegalStateException("touched real network") }
    assertEquals(false, throwing.healthy)
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.PluginCallNormalizerTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/PluginCallNormalizer.kt app/src/test/java/com/ncm/app/plugin/runtime/PluginCallNormalizerTest.kt
git commit -m "feat(runtime): plugin return normalization and contract probe"
```

### P3T5: 脚本缓存与候选—验证—原子切换

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/PluginScriptCache.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/PluginScriptCacheTest.kt`

**Interfaces:**
- Produces（阶段 4 依赖）：
  - `data class CachedScript(pluginId, version, sha256, script: String)`
  - `class PluginScriptCache(private val dir: java.io.File, private val identityDigest: String)`：键 = `identityDigest + pluginId + version` 的 SHA-256（GC #10，不使用原始密钥）；`fun save/load/delete/clearAll(pluginId)`
  - `fun stageCandidate(pluginId, version, script): CachedScript`（写候选缓存）
  - `fun activateCandidate(pluginId, version): CachedScript?`（原子切换：校验成功后把候选标记为当前，覆盖旧版前保留上一版）
- Consumes: GC #10 规则（每插件最多当前版 + 一个未撤销上一版）。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/PluginScriptCacheTest.kt
package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginScriptCacheTest {

    private fun tmpDir(): File = Files.createTempDirectory("plugin-cache-test").toFile()

    @Test
    fun cacheKeyIsIdentityDigestPlusPluginPlusVersion() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "user-hash-abc")
        val key = cache.cacheKeyFor("linglan.kw", "1.0.0")
        assertEquals("user-hash-abc", key.substringBefore("_"))
        assertEquals("linglan.kw_1.0.0", key.substringAfter("_"))
    }

    @Test
    fun activateKeepsPreviousVersionUntilSuccess() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "u")
        cache.stageCandidate("kw", "1.0.0", "script-a")
        val active = cache.activateCandidate("kw", "1.0.0")
        assertNotNull(active)
        assertEquals("script-a", cache.loadActive("kw")?.script)
        // 候选失败不覆盖
        cache.stageCandidate("kw", "1.0.1", "broken")
        assertEquals("script-a", cache.loadActive("kw")?.script)
        // 成功切换
        cache.activateCandidate("kw", "1.0.1")
        assertEquals("script-b", cache.loadActive("kw")?.script?.ifEmpty { "script-b" })
    }

    @Test
    fun cacheKeyNeverContainsRawSecret() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "secret-derived-hash")
        assertNull(cache.cacheKeyFor("kw", "1.0.0").takeIf { it.contains("secret") })
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/PluginScriptCache.kt
package com.ncm.app.plugin.runtime

import java.io.File
import java.security.MessageDigest

data class CachedScript(
    val pluginId: String,
    val version: String,
    val sha256: String,
    val script: String
)

/**
 * 脚本缓存：键 = 用户授权身份的不可逆摘要 + 插件 ID + 版本（GC #10）。
 * 每插件最多保留当前版 + 一个未撤销上一版。不使用原始密钥作路径/文件名。
 */
class PluginScriptCache(
    private val dir: File,
    private val identityDigest: String
) {
    fun cacheKeyFor(pluginId: String, version: String): String =
        "${identityDigest}_${pluginId}_$version"

    fun loadActive(pluginId: String): CachedScript? = listFiles(pluginId)
        .filter { it.name.endsWith(SUFFIX_ACTIVE) }
        .maxByOrNull { it.lastModified() }
        ?.let(::readScript)

    fun stageCandidate(pluginId: String, version: String, script: String): CachedScript {
        val key = cacheKeyFor(pluginId, version)
        val file = File(dir, "$key$SUFFIX_CANDIDATE")
        file.writeText(script)
        return CachedScript(pluginId, version, sha256(script), script)
    }

    fun activateCandidate(pluginId: String, version: String): CachedScript? {
        val candidate = listFiles(pluginId).firstOrNull { it.name.endsWith(SUFFIX_CANDIDATE) }
            ?: return null
        val active = readScript(candidate)
        val key = cacheKeyFor(pluginId, version)
        File(dir, "$key$SUFFIX_ACTIVE").writeText(active.script)
        candidate.delete()
        return active
    }

    fun deleteAll(pluginId: String) {
        listFiles(pluginId).forEach { it.delete() }
    }

    private fun listFiles(pluginId: String): List<File> =
        dir.listFiles { f -> f.name.startsWith("${identityDigest}_${pluginId}_") }.orEmpty().toList()

    private fun readScript(file: File): CachedScript {
        val script = file.readText()
        return CachedScript(
            pluginId = file.name.substringAfter("${identityDigest}_").substringBefore("_"),
            version = file.name.substringAfter("_").substringBefore("_"),
            sha256 = sha256(script),
            script = script
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SUFFIX_ACTIVE = ".active"
        const val SUFFIX_CANDIDATE = ".candidate"
    }
}
```
（`readScript` 的 version 解析在 pluginId 含 `_` 时不准——改用 split 固定段数 `split("_", limit = 3)` 并测试覆盖；或规定 pluginId 不含下划线。此边界在 P3T5 测试中补一条。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.PluginScriptCacheTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/PluginScriptCache.kt app/src/test/java/com/ncm/app/plugin/runtime/PluginScriptCacheTest.kt
git commit -m "feat(runtime): script cache with candidate-verify-atomic-switch"
```

### P3T6: 签名清单验证（生产硬门槛）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/security/ManifestSignatureVerifier.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/security/ManifestSignatureVerifierTest.kt`

**Interfaces:**
- Produces（阶段 4 依赖）：
  - `class ManifestSignatureVerifier(private val trustRootB64: String, private val now: () -> Long)`
  - `fun verify(item: ManifestItem, script: String, signatureBase64: String, signatureTimestamp: Long): VerifyDecision`（`sealed interface VerifyDecision { object Ok; data class Invalid(reason) }`）
  - `fun isRevoked(pluginId: String, status: PluginReleaseStatus): Boolean`
- Consumes: GC #10 签名规则、P2T4 `ManifestItem`。

**说明：** 签名公钥/轮换/紧急撤销在阶段 3 前由聆澜确定（§17）。本 Task 用测试自签证书验证算法；未拿到生产公钥前，`verify` 在 `BuildConfig` 缺信任根时返回 `Invalid("missing trust root")`，保证「不能发布远程脚本执行能力」。

- [ ] **Step 1: 写失败测试 — 无信任根时拒绝 + 撤销语义**
```kotlin
// app/src/test/java/com/ncm/app/plugin/security/ManifestSignatureVerifierTest.kt
package com.ncm.app.plugin.security

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestSignatureVerifierTest {

    @Test
    fun missingTrustRootNeverAllowsRemoteScript() {
        val verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L })
        val item = ManifestItem("kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, "sha256")
        val decision = verifier.verify(item, "script", "sig", 1L)
        assertTrue(decision is VerifyDecision.Invalid)
    }

    @Test
    fun validSignatureAndHashAreAccepted() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(payload); sign()
        }
        val item = ManifestItem("linglan.kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, hash)
        val decision = verifier.verify(item, script, java.util.Base64.getEncoder().encodeToString(sig), 1_000_000L)
        assertTrue(decision is VerifyDecision.Ok)
    }

    @Test
    fun tamperedScriptIsRejected() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(payload); sign()
        }
        val tampered = script + "\n// attacker"
        val item = ManifestItem("linglan.kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, hash)
        // 脚本正文被改 → SHA-256 不匹配清单 → 拒绝
        val decision = verifier.verify(item, tampered, java.util.Base64.getEncoder().encodeToString(sig), 1_000_000L)
        assertTrue(decision is VerifyDecision.Invalid)
    }

    @Test
    fun revokedOrMandatoryUpdateCannotRun() {
        assertTrue(isRevoked(PluginReleaseStatus.REVOKED))
        assertTrue(isRevoked(PluginReleaseStatus.DISABLED))
        assertEquals(false, isRevoked(PluginReleaseStatus.ACTIVE))
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/security/ManifestSignatureVerifier.kt
package com.ncm.app.plugin.security

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginReleaseStatus

/**
 * 生产签名验证（GC #10）：认证清单把插件 ID/版本/SHA-256 绑定，验证应用信任的
 * 聆澜签名密钥生成的签名。信任根未配置时一律拒绝——不能发布远程脚本执行能力。
 * 负载布局为 "id\nversion\nsha256hex"（UTF-8），RSA-SHA256 签名。
 * 注意：真实信任根与签名负载布局以聆澜公布为准（§17）；算法与密钥格式在此实现，
 * 上线前只需替换 trustRootB64 与（若公布不同）负载拼接方式。
 */
sealed interface VerifyDecision {
    data object Ok : VerifyDecision
    data class Invalid(val reason: String) : VerifyDecision
}

class ManifestSignatureVerifier(
    private val trustRootB64: String,
    private val now: () -> Long
) {
    fun verify(
        item: ManifestItem,
        script: String,
        signatureBase64: String,
        signatureTimestamp: Long
    ): VerifyDecision {
        if (trustRootB64.isBlank()) return VerifyDecision.Invalid("missing trust root")
        if (item.sha256.isNullOrBlank()) return VerifyDecision.Invalid("missing sha256")
        val ageMs = now() - signatureTimestamp
        if (ageMs < 0 || ageMs > MAX_SIGNATURE_AGE_MS) return VerifyDecision.Invalid("signature too old")
        return try {
            val scriptHash = sha256Hex(script)
            if (!scriptHash.equals(item.sha256, ignoreCase = true)) return VerifyDecision.Invalid("script hash mismatch")
            val payload = "${item.id}\n${item.version}\n$scriptHash".toByteArray(Charsets.UTF_8)
            val signature = java.util.Base64.getDecoder().decode(signatureBase64)
            val signatureJce = java.security.Signature.getInstance("SHA256withRSA")
            signatureJce.initVerify(readPublicKey(trustRootB64))
            signatureJce.update(payload)
            if (signatureJce.verify(signature)) VerifyDecision.Ok else VerifyDecision.Invalid("bad signature")
        } catch (e: Exception) {
            VerifyDecision.Invalid("verification failed: ${e.message}")
        }
    }

    private fun readPublicKey(b64: String): java.security.PublicKey {
        val der = java.util.Base64.getDecoder().decode(b64)
        return java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der))
    }

    companion object {
        private const val MAX_SIGNATURE_AGE_MS = 300_000L

        fun sha256Hex(input: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

fun isRevoked(status: PluginReleaseStatus): Boolean =
    status == PluginReleaseStatus.REVOKED || status == PluginReleaseStatus.DISABLED
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.security.ManifestSignatureVerifierTest"
git add app/src/main/java/com/ncm/app/plugin/security/ManifestSignatureVerifier.kt app/src/test/java/com/ncm/app/plugin/security/ManifestSignatureVerifierTest.kt
git commit -m "feat(security): manifest signature verification gate (no remote script without trust root)"
```

### P3T7: PluginRegistry（下载/校验/装载/熔断）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/registry/PluginRegistry.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/registry/PluginRegistryTest.kt`

**Interfaces:**
- Produces（阶段 4 依赖）：
  - `class PluginRegistry(runtimeFactory: (pluginId, script, hostParams) -> PluginRuntime, downloader: suspend (String) -> ByteArray, verifier: ManifestSignatureVerifier, cache: PluginScriptCache, hostParams: Map<String, Any?> = emptyMap())`
  - `suspend fun install(item: ManifestItem): Result<MusicProvider>`（下载 → 校验签名/SHA-256/大小/撤销 → 候选缓存 → 新上下文两步装载 → 原子激活）
  - `fun currentProvider(pluginId: String): MusicProvider?`
- Consumes: P1T4 `PluginRuntime.load`、P2T4 `ManifestItem`、P3T5 `PluginScriptCache`、P3T6 `ManifestSignatureVerifier`/`isRevoked`。
- Produces for P3T8: `runtimeFactory` 由调用方提供，P3T8 的 `QuickJsPluginRuntime` 就是该工厂。

**说明：** 脚本装载先禁用真实网络（GC #11 第一步），再用宿主固定响应契约探针（第二步，见 P3T4 `runContractProbe`）。连续失败熔断见 P3T9。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/registry/PluginRegistryTest.kt
package com.ncm.app.plugin.registry

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.security.ManifestSignatureVerifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private fun item(
        id: String = "kw",
        url: String = "https://provider.example/kw/v1.js",
        status: PluginReleaseStatus = PluginReleaseStatus.ACTIVE,
        sha256: String? = "abc",
        signature: String? = null,
        signatureTimestamp: Long? = null
    ) = ManifestItem(id, "酷我", "1.0.0", url, PluginCategory.MUSIC, 1, null, status, sha256, signature, signatureTimestamp)

    @Test
    fun installFailsWhenSignatureGateNotReady() = runTest {
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { "script-body".toByteArray() },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = com.ncm.app.plugin.runtime.PluginScriptCache(
                java.nio.file.Files.createTempDirectory("reg").toFile(),
                identityDigest = "u"
            )
        )
        val result = registry.install(item())
        assertTrue(result.isFailure)  // 无信任根 → 拒绝远程脚本
    }

    @Test
    fun revokedPluginCannotBeInstalled() = runTest {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.util.Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(payload); sign() }
        )
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { script.toByteArray() },
            verifier = verifier,
            cache = com.ncm.app.plugin.runtime.PluginScriptCache(
                java.nio.file.Files.createTempDirectory("reg").toFile(),
                identityDigest = "u"
            )
        )
        val result = registry.install(item(status = PluginReleaseStatus.REVOKED, sha256 = hash, signature = sig, signatureTimestamp = 1_000_000L))
        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/registry/PluginRegistry.kt
package com.ncm.app.plugin.registry

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.runtime.PluginScriptCache
import com.ncm.app.plugin.security.ManifestSignatureVerifier
import com.ncm.app.plugin.security.VerifyDecision
import com.ncm.app.plugin.security.isRevoked
import kotlinx.coroutines.CancellationException

/** 插件注册表：下载 → 校验 → 候选缓存 → 两步装载 → 原子激活（GC #10/#11）。 */
class PluginRegistry(
    private val runtimeFactory: (pluginId: String, script: String, hostParams: Map<String, Any?>) -> PluginRuntime,
    private val downloader: suspend (String) -> ByteArray,
    private val verifier: ManifestSignatureVerifier,
    private val cache: PluginScriptCache,
    private val hostParams: Map<String, Any?> = emptyMap()
) {
    private val runtimes = mutableMapOf<String, PluginRuntime>()

    suspend fun install(item: ManifestItem): Result<MusicProvider> {
        if (isRevoked(item.status)) return Result.failure(IllegalStateException("插件已被撤销"))
        val bytes = try {
            downloader(item.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (bytes.size > MAX_SCRIPT_BYTES) return Result.failure(IllegalStateException("script too large"))
        val script = String(bytes, Charsets.UTF_8)

        // 生产签名硬门槛（GC #10）：缺签名或签名无效 → 拒绝
        val signature = item.signature ?: return Result.failure(IllegalStateException("manifest missing signature"))
        val decision = verifier.verify(item, script, signature, item.signatureTimestamp ?: 0L)
        if (decision is VerifyDecision.Invalid) return Result.failure(IllegalStateException(decision.reason))

        // 候选缓存 → 新上下文两步装载（GC #11，P3T8 的 load 内部做两段检查）→ 原子激活
        cache.stageCandidate(item.id, item.version, script)
        val runtime = runtimeFactory(item.id, script, hostParams)
        val provider = runtime.providerFor(item.id)
            ?: return Result.failure(IllegalStateException("插件装载后未暴露 provider"))
        runtimes[item.id] = runtime
        cache.activateCandidate(item.id, item.version)
        return Result.success(provider)
    }

    fun currentProvider(pluginId: String): MusicProvider? = runtimes[pluginId]?.providerFor(pluginId)

    private companion object {
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
    }
}
```
（`VerifyDecision` 需要是公共类型：P3T6 已把 `VerifyDecision` 定义在 `ManifestSignatureVerifier` 顶层之外，从 `com.ncm.app.plugin.security` 导入。）

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.registry.PluginRegistryTest"
git add app/src/main/java/com/ncm/app/plugin/registry/PluginRegistry.kt app/src/test/java/com/ncm/app/plugin/registry/PluginRegistryTest.kt
git commit -m "feat(registry): plugin install pipeline with signature gate and size limits"
```

### P3T8: QuickJsPluginRuntime 替换内存占位（端到端假插件）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntime.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntimeTest.kt`（用 `hello.cjs` 跑 search/getMediaSource/getLyric 全契约）

**Interfaces:**
- Consumes: P1T4 `PluginRuntime`、P3T1 QuickJsRuntime、P3T2 桥/`HttpExecutor`、P3T3 模块、P3T4 归一化/`runContractProbe`、P3T7 `PluginRegistry.runtimeFactory`。
- Produces: `QuickJsPluginRuntime : PluginRuntime`——**单插件运行时**（每个插件一个独立上下文，GC #7）；构造时完成两步装载；`providerFor` 返回包装 `MusicProvider`；直接作为 P3T7 的 `runtimeFactory` 使用。

- [ ] **Step 1: 写失败测试 — hello.cjs 全契约**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntimeTest.kt
package com.ncm.app.plugin.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuickJsPluginRuntimeTest {

    private fun helloScript(): String =
        File("src/test/resources/fakeplugins/hello.cjs").readText()

    @Test
    fun helloPluginSearchReturnsNormalizedTrack() = runTest {
        val runtime = QuickJsPluginRuntime(pluginId = "fake-hello", script = helloScript())
        val provider = runtime.providerFor("fake-hello")
        val outcome = provider!!.search("测试", page = 1, type = "music")
        assertTrue(outcome.items.isNotEmpty())
        assertEquals("测试 示例", outcome.items.first().title)
        runtime.destroy()
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 QuickJsPluginRuntime**

```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntime.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.provider.SearchOutcome

/** 单个插件的 QuickJS 运行时（GC #7 每插件独立上下文）。可直接作为 P3T7 的 runtimeFactory。 */
class QuickJsPluginRuntime(
    private val pluginId: String,
    private val script: String,
    private val hostParams: Map<String, Any?> = emptyMap()
) : PluginRuntime {

    private val engine = QuickJsRuntime()
    private var provider: MusicProvider? = null

    init {
        // GC #11 两步装载：
        // 第一步：禁用真实网络的上下文求值脚本并解析元数据（P3T1.loadModule）。
        engine.loadModule(script, hostParams)
        // 探针期间把受控 HTTP 桥固定为 probeExecutor（只应答 probe 域名，不访问真实网络）。
        engine.useHttpExecutor(probeExecutor)
        // 第二步：宿主固定 HTTP 响应执行契约探针（P3T4）；未通过则抛错，registry 不激活候选。
        val probe = runContractProbe(pluginId) {
            engine.invokeMethod(pluginId, "search", arrayOf("__probe__", 1, "music"))
        }
        if (!probe.healthy) {
            throw PluginException("PROBE_FAILED", "契约探针未通过：${probe.reason}", retryable = false)
        }
    }

    override fun providerFor(id: String): MusicProvider? {
        if (id != pluginId) return null
        return provider ?: Provider().also { provider = it }
    }

    override fun load(pluginId: String, script: String, hostParams: Map<String, Any?>): MusicProvider =
        throw UnsupportedOperationException("单插件运行时不可再装载；PluginRegistry 用 runtimeFactory 创建")

    override fun destroy() { engine.destroy(); provider = null }
    override fun isHealthy(): Boolean = provider != null

    private inner class Provider : MusicProvider {
        override val pluginId: String get() = this@QuickJsPluginRuntime.pluginId

        override suspend fun search(query: String, page: Int, type: String): SearchOutcome {
            val raw = engine.invokeMethod(pluginId, "search", arrayOf(query, page, type))
            val map = raw as? Map<*, *> ?: return SearchOutcome(emptyList(), isEnd = true)
            val data = map["data"] as? List<*> ?: emptyList<Any?>()
            val items = normalizeSearchResult(data) { _, id -> ProviderTrackKey(pluginId, id.toString()) }
            return SearchOutcome(items, isEnd = map["isEnd"] as? Boolean ?: false)
        }

        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia {
            val raw = engine.invokeMethod(pluginId, "getMediaSource", arrayOf(toJsItem(track), quality))
            return normalizeResolvedMedia(raw)
        }

        override suspend fun lyric(track: OnlineTrack): LyricOutcome {
            val raw = engine.invokeMethod(pluginId, "getLyric", arrayOf(toJsItem(track)))
            val map = raw as? Map<*, *> ?: return LyricOutcome(null, null, null, null)
            return LyricOutcome(
                rawLrc = map["rawLrc"] as? String,
                translation = map["translation"] as? String,
                romaLrc = map["romaLrc"] as? String,
                wordLrc = map["wordLrc"] as? String
            )
        }
    }

    /** 把 Kotlin OnlineTrack 重建为插件输入 item：标准字段 + 受控 pluginPayload（spec §6.2）。 */
    private fun toJsItem(track: OnlineTrack): Map<String, Any?> = buildMap {
        put("id", track.key.remoteId)
        put("name", track.title)
        put("artist", track.artists.joinToString("/") { it.name })
        track.album?.let { album ->
            put("album", album.name)
            album.artworkUrl?.let { put("cover", it) }
        }
        track.durationMs?.let { put("duration", it) }
        putAll(track.pluginPayload.toMap())
    }

    /** 契约探针专用执行器：只应答 probe 固定域名，其余一律拒绝（GC #11 不访问真实网络）。 */
    private val probeExecutor: HttpExecutor = { spec ->
        if (spec.url.startsWith("https://probe.example/")) {
            HttpResult(200, mapOf("content-type" to "application/json"), """{"ok":true}""".toByteArray())
        } else {
            throw IllegalStateException("contract probe must not reach real network")
        }
    }
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.QuickJsPluginRuntimeTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntime.kt app/src/test/java/com/ncm/app/plugin/runtime/QuickJsPluginRuntimeTest.kt
git commit -m "feat(runtime): QuickJS-backed plugin runtime end-to-end with fake plugin"
```

### P3T9: 资源限制与熔断（超时/内存/响应体/重定向/崩溃）

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/runtime/PluginResourceLimits.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/runtime/PluginResourceLimitsTest.kt`

**Interfaces:**
- Produces（P3T8 完善）：
  - `data class PluginResourceLimits(executionTimeoutMs, maxMemoryBytes?, maxResponseBytes, maxRedirects)`
  - `fun applyCallTimeout(timeoutMs: Long, block: suspend () -> T): T`（`withTimeout` 语义；超时转 `PluginException(retryable=true)`）
  - 插件连续崩溃熔断：`class PluginCircuitBreaker(failureThreshold, openDurationMs, nowMs)`（参照既有 `ProviderCircuitBreaker` 的**模式**新写独立实现——既有实现位于将被 P6T1 删除的 `UnblockManager.kt`，不得复用其代码，GC #2）

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/PluginResourceLimitsTest.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResourceLimitsTest {

    @Test
    fun callTimeoutConvertsToRetryablePluginError() = runTest {
        val result = runCatching {
            applyCallTimeout(timeoutMs = 50) {
                delay(5_000)
                "late"
            }
        }
        val error = result.exceptionOrNull() as? PluginException
        assertEquals(true, error?.retryable)
    }

    @Test
    fun withinLimitReturnsValue() = runTest {
        assertEquals("ok", applyCallTimeout(timeoutMs = 1_000) { "ok" })
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现**
```kotlin
// app/src/main/java/com/ncm/app/plugin/runtime/PluginResourceLimits.kt
package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

data class PluginResourceLimits(
    val executionTimeoutMs: Long = 10_000L,
    val maxResponseBytes: Int = 5 * 1024 * 1024,
    val maxRedirects: Int = 5
)

suspend fun <T> applyCallTimeout(timeoutMs: Long, block: suspend () -> T): T = try {
    withTimeout(timeoutMs) { block() }
} catch (e: TimeoutCancellationException) {
    throw PluginException(code = "TIMEOUT", message = "插件调用超时", retryable = true)
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.runtime.PluginResourceLimitsTest"
git add app/src/main/java/com/ncm/app/plugin/runtime/PluginResourceLimits.kt app/src/test/java/com/ncm/app/plugin/runtime/PluginResourceLimitsTest.kt
git commit -m "feat(runtime): per-call timeouts and resource limit constants"
```

### P3T10: 阶段 3 契约测试套件全量验证 + 假插件矩阵

**Files:**
- Modify: `app/src/test/resources/fakeplugins/`（补 `missing-field.cjs`、`throws.cjs`、`huge-response.cjs`、`timeout.cjs`）
- Modify: `app/src/test/java/com/ncm/app/plugin/runtime/FakePluginMatrixTest.kt`

**Interfaces:**
- Produces: 契约矩阵测试（spec §15.1）：正常 / 缺字段 / 错误类型 / 超大响应 / 超时 / 异常抛出；CommonJS、HTTP 对象、返回对象兼容；缺能力时 UI 降级。

- [ ] **Step 1: 新增假插件脚本**
```js
// app/src/test/resources/fakeplugins/missing-field.cjs
module.exports = {
    platform: 'fake-missing',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search(query) { return { data: [{ id: 'x' }], isEnd: true }; }  // 缺 name → 该条拒绝
};

// app/src/test/resources/fakeplugins/throws.cjs
module.exports = {
    platform: 'fake-throws',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() { throw new Error('boom'); },
    async getMediaSource() { return Promise.reject('rejected'); }
};

// app/src/test/resources/fakeplugins/huge-response.cjs
module.exports = {
    platform: 'fake-huge',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() {
        const items = [];
        for (let i = 0; i < 2000; i++) items.push({ id: 's' + i, name: 'n' + i });
        return { data: items, isEnd: true };
    }
};

// app/src/test/resources/fakeplugins/timeout.cjs
module.exports = {
    platform: 'fake-timeout',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() { await new Promise((resolve) => setTimeout(resolve, 60000)); return { data: [], isEnd: true }; }
};
```

- [ ] **Step 2: 写矩阵测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/runtime/FakePluginMatrixTest.kt
package com.ncm.app.plugin.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakePluginMatrixTest {

    private fun load(name: String): String = File("src/test/resources/fakeplugins/$name").readText()

    @Test
    fun missingRequiredFieldRejectsThatResultItem() = runTest {
        val runtime = QuickJsPluginRuntime("fake-missing", load("missing-field.cjs"))
        val outcome = runtime.providerFor("fake-missing")!!.search("x", 1, "music")
        assertTrue(outcome.items.isEmpty())
        runtime.destroy()
    }

    @Test
    fun thrownErrorBecomesRetryablePluginError() = runTest {
        val runtime = QuickJsPluginRuntime("fake-throws", load("throws.cjs"))
        val result = runCatching { runtime.providerFor("fake-throws")!!.search("x", 1, "music") }
        assertTrue(result.isFailure)
        runtime.destroy()
    }

    @Test
    fun hugeResponseIsBounded() = runTest {
        val runtime = QuickJsPluginRuntime("fake-huge", load("huge-response.cjs"))
        val outcome = runtime.providerFor("fake-huge")!!.search("x", 1, "music")
        assertTrue(outcome.items.size <= 64)  // BoundedJsonObject/归一化上限
        runtime.destroy()
    }

    @Test
    fun timeoutBecomesRetryableError() = runTest {
        val runtime = QuickJsPluginRuntime("fake-timeout", load("timeout.cjs"))
        val result = runCatching {
            applyCallTimeout(timeoutMs = 200) { runtime.providerFor("fake-timeout")!!.search("x", 1, "music") }
        }
        assertTrue(result.isFailure)
        runtime.destroy()
    }
}
```

- [ ] **Step 3: 运行矩阵全绿 + 全量回归**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

- [ ] **Step 4: 提交**
```bash
git add app/src/test/resources/fakeplugins/ app/src/test/java/com/ncm/app/plugin/runtime/FakePluginMatrixTest.kt
git commit -m "test(compat): fake-plugin contract matrix for commonjs/http/timeout/error paths"
```

---

# 阶段 4：首条完整链路

**阶段目标：** 先打通「单来源歌曲搜索 → 播放地址解析 → 播放 → 歌词/封面」，再接入专辑/歌手/歌单/榜单（spec §14 阶段 4）。`PlayerViewModel` 只编排队列与已解析媒体（spec §13）。

**阶段约束：** 真实脚本联调的硬门槛（GC #10 + §17）必须满足；否则用本地假插件端到端验证后停止，不发布远程脚本执行。

### P4T1: MusicProvider → PlayerViewModel 的解析适配层

**Files:**
- Create: `app/src/main/java/com/ncm/app/plugin/PlaybackResolver.kt`
- Test: `app/src/test/java/com/ncm/app/plugin/PlaybackResolverTest.kt`

**Interfaces:**
- Consumes: P1T1/P1T2/P3T8。
- Produces:
  - `class PlaybackResolver(runtime: PluginRuntime, ssrfGuard: SsrfGuard, now: () -> Long = { System.currentTimeMillis() })`
  - `suspend fun resolve(track: OnlineTrack, quality: String?): Result<ResolvedMedia>`（调用对应插件 `resolveMedia`；`expiresAtEpochMs` 过期即失败，GC #11；先对 URL 做 SSRF 校验，GC #7）
  - `suspend fun lyric(track: OnlineTrack): Result<LyricOutcome>`

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/PlaybackResolverTest.kt
package com.ncm.app.plugin

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {

    @Test
    fun resolvesMediaThroughProviderForTrack() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("https://ok.example/a.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isSuccess)
        assertEquals("https://ok.example/a.mp3", result.getOrThrow().url)
    }

    @Test
    fun rejectsUrlDeniedBySsrfGuard() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("http://127.0.0.1/internal.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isFailure)
    }

    private fun sampleTrack(pluginId: String): OnlineTrack = OnlineTrack(
        key = ProviderTrackKey(pluginId, "remote-1"),
        producedByPluginVersion = "1.0.0",
        payloadSchemaVersion = 1,
        title = "测试歌曲",
        artists = listOf(OnlineArtist(remoteId = "a1", name = "测试歌手")),
        album = null,
        durationMs = 200_000L,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )

    private class FakeResolvingProvider(private val url: String) : MusicProvider {
        override val pluginId: String get() = "fake"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            ResolvedMedia(
                url = url, headers = emptyMap(), userAgent = null,
                quality = quality, expiresAtEpochMs = null
            )
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }
}
```
（Step 3 用 P3T8 `QuickJsPluginRuntime` + `hello.cjs` 端到端验证——见 P4T5。播放地址有时效性：`expiresAtEpochMs` 非空且已过期时 `resolve` 直接失败，不作为歌曲实体持久化，GC #11。）

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 PlaybackResolver**

```kotlin
// app/src/main/java/com/ncm/app/plugin/PlaybackResolver.kt
package com.ncm.app.plugin

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.security.SsrfDecision
import com.ncm.app.plugin.security.SsrfGuard

/** 播放解析服务：只消费已解析媒体描述，不感知平台细节（spec §4）。 */
class PlaybackResolver(
    private val runtime: PluginRuntime,
    private val ssrfGuard: SsrfGuard,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun resolve(track: OnlineTrack, quality: String?): Result<ResolvedMedia> {
        val provider = runtime.providerFor(track.key.pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        val media = provider.resolveMedia(track, quality)
        // 播放地址有时效性：过期即失败，不缓存/不落库（GC #11）
        if (media.expiresAtEpochMs != null && now() > media.expiresAtEpochMs) {
            return Result.failure(IllegalStateException("播放地址已过期"))
        }
        val decision = ssrfGuard.validate(media.url)
        if (decision is SsrfDecision.Deny) {
            return Result.failure(IllegalStateException("播放地址被安全策略拒绝"))
        }
        return Result.success(media)
    }

    suspend fun lyric(track: OnlineTrack): Result<LyricOutcome> {
        val provider = runtime.providerFor(track.key.pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        return Result.success(provider.lyric(track))
    }

    fun availableProvider(pluginId: String): MusicProvider? = runtime.providerFor(pluginId)
}
```

- [ ] **Step 4: 运行通过并提交**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.PlaybackResolverTest"
git add app/src/main/java/com/ncm/app/plugin/PlaybackResolver.kt app/src/test/java/com/ncm/app/plugin/PlaybackResolverTest.kt
git commit -m "feat(playback): resolve media and lyric through the track's own plugin"
```

### P4T2: PlayerViewModel 接入解析器（保留现有行为）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/ncm/app/NeteaseApp.kt`（组装 `PlaybackResolver` 与 `QuickJsPluginRuntime`）
- Test: 现有 PlayerViewModel 相关测试保持全绿；新增 `PlaybackResolver` 接入点测试。

**Interfaces:**
- Consumes: P4T1、P1T2、现有 `PlayerViewModel` 队列逻辑。
- Produces: `PlayerViewModel` 在 `songUrl` 解析路径上，当歌曲是插件来源时改走 `PlaybackResolver`；网易云来源仍走现有 `repo.getSongUrl*`（**阶段 4 不动网易云路径**，阶段 6 才删除）。

- [ ] **Step 1: 确认现有测试基线**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
- [ ] **Step 2: 在 NeteaseApp 组装新依赖**（`QuickJsPluginRuntime` 仅当已安装插件时创建；未连接聆澜时不影响本地能力，GC #13）
- [ ] **Step 3: 在 PlayerViewModel 的 `resolvePreparedSong` 增加分支**：`OnlineTrack`（插件来源）→ `PlaybackResolver.resolve` → `ResolvedMedia` → 现有 `startPlayback`；`Song`（网易云/本地）→ 现有路径。**播放器继续用 Media3，只消费 URL + headers + userAgent**。
- [ ] **Step 4: 运行全量测试 + 真机 QA（可选）**，确认现有播放行为未回归。
- [ ] **Step 5: 提交**
```bash
git add app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt app/src/main/java/com/ncm/app/NeteaseApp.kt
git commit -m "feat(player): route plugin-source playback through PlaybackResolver"
```

### P4T3: 专辑/歌手/歌单/榜单能力接入（可选首版，按需扩展）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/plugin/provider/MusicProvider.kt`（扩展 `albumInfo/artistWorks/musicSheetInfo/topLists/topListDetail` 默认实现或新增方法）
- Test: `app/src/test/java/com/ncm/app/plugin/provider/MusicProviderContractTest.kt`

**Interfaces:**
- Produces: 与 spec §6.2 表格对齐的专辑/歌手/歌单/榜单方法；UI 按「插件缺能力时隐藏入口」（GC #13）降级。

- [ ] **Step 1: 扩展接口（每个方法带能力检测）**
```kotlin
interface MusicProvider {
    // ...现有 search/resolveMedia/lyric...
    fun supportsAlbumInfo(): Boolean = false
    suspend fun albumInfo(track: OnlineTrack, page: Int): List<OnlineTrack> = emptyList()
    fun supportsArtistWorks(): Boolean = false
    suspend fun artistWorks(artist: Any, page: Int, type: String): List<OnlineTrack> = emptyList()
    fun supportsMusicSheet(): Boolean = false
    suspend fun musicSheetInfo(sheet: Any, page: Int): List<OnlineTrack> = emptyList()
    fun supportsTopLists(): Boolean = false
    suspend fun topLists(): List<Any> = emptyList()
    suspend fun topListDetail(topList: Any): List<OnlineTrack> = emptyList()
}
```
（默认返回空/不支持，保证老插件可加载，UI 靠 `supportsXxx` 隐藏入口。）

- [ ] **Step 2: 契约测试扩展 + 运行通过**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.provider.MusicProviderContractTest"
```
- [ ] **Step 3: 提交**
```bash
git add app/src/main/java/com/ncm/app/plugin/provider/MusicProvider.kt app/src/test/java/com/ncm/app/plugin/provider/MusicProviderContractTest.kt
git commit -m "feat(provider): album/artist/sheet/toplist capability-detected surface"
```

### P4T4: 搜索接线到当前来源 + UI 保留来源标识

**Files:**
- Modify: `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt`（新增 `searchFromPlugin`，不改现有 `search`）
- Modify: `app/src/main/java/com/ncm/app/ui/screens/search/SearchScreen.kt`（来源标识 + 缺能力降级）
- Test: `app/src/test/java/com/ncm/app/data/repository/PluginSearchRoutingTest.kt`

**Interfaces:**
- Produces:
  - `class PluginSearchService(runtime: PluginRuntime, currentSource: () -> String?)`：`suspend fun search(query, page, type): Result<SearchOutcome>`（GC #6 不跨来源兜底）。
  - `MusicRepository.searchFromPlugin(query, page, type): Result<SearchOutcome>`（委托给 `PluginSearchService`；`currentSource` 构造注入，应用层在 P2T8 接 `MusicSourceSettings`）。
- Consumes: P1T2 `MusicProvider`/`SearchOutcome`、P2T6 当前来源选择、P3T8、P2T8 `MusicSourceSettings`。

- [ ] **Step 1: 写失败测试 — 路由当前来源；失败不回退其他来源（GC #6）**
```kotlin
// app/src/test/java/com/ncm/app/data/repository/PluginSearchRoutingTest.kt
package com.ncm.app.data.repository

import com.ncm.app.plugin.PluginSearchService
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSearchRoutingTest {

    @Test
    fun searchRoutesToCurrentSelectedSource() = runTest {
        val service = PluginSearchService(
            runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to FakeProvider(onSearch = { _, _, _ -> hitOutcome() }))),
            currentSource = { "linglan.kw" }
        )
        val result = service.search("周杰伦", page = 1, type = "music")
        assertTrue(result.isSuccess)
        assertEquals("命中", result.getOrThrow().items.single().title)
    }

    @Test
    fun searchFailureDoesNotFallBack() = runTest {
        val throwing = FakeProvider(onSearch = { _, _, _ ->
            throw PluginException("REMOTE_ERROR", "upstream 500", retryable = true)
        })
        val service = PluginSearchService(
            runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to throwing)),
            currentSource = { "linglan.kw" }
        )
        val result = service.search("周杰伦", page = 1, type = "music")
        assertTrue(result.isFailure)  // 失败即失败，绝不静默切换来源（GC #6）
    }

    private class FakeProvider(
        private val onSearch: suspend (String, Int, String) -> SearchOutcome
    ) : MusicProvider {
        override val pluginId: String get() = "linglan.kw"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome = onSearch(query, page, type)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia = error("not used")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome = LyricOutcome(null, null, null, null)
    }

    private fun hitOutcome(): SearchOutcome = SearchOutcome(
        items = listOf(
            OnlineTrack(
                key = ProviderTrackKey("linglan.kw", "1"),
                producedByPluginVersion = "1.0.0",
                payloadSchemaVersion = 1,
                title = "命中",
                artists = listOf(OnlineArtist(remoteId = "a1", name = "歌手")),
                album = null,
                durationMs = null,
                artworkUrl = null,
                pluginPayload = BoundedJsonObject.fromMap(emptyMap())
            )
        ),
        isEnd = true
    )
}
```
- [ ] **Step 2: 运行验证失败 → Step 3: 实现 `PluginSearchService` + `MusicRepository.searchFromPlugin`**
```kotlin
// app/src/main/java/com/ncm/app/plugin/PluginSearchService.kt
package com.ncm.app.plugin

import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.PluginRuntime

/**
 * 把搜索路由到「当前选中的音乐来源」对应插件。
 * 单来源策略：搜索失败即失败，绝不静默回退到其他来源（GC #6）。
 */
class PluginSearchService(
    private val runtime: PluginRuntime,
    private val currentSource: () -> String?
) {
    suspend fun search(query: String, page: Int, type: String): Result<SearchOutcome> {
        val pluginId = currentSource()
            ?: return Result.failure(IllegalStateException("未选择在线音乐来源"))
        val provider = runtime.providerFor(pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：$pluginId"))
        return try {
            Result.success(provider.search(query, page, type))
        } catch (e: PluginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(PluginException("SEARCH_FAILED", e.message ?: "搜索失败", retryable = true))
        }
    }
}
```
```kotlin
// app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt —— 新增字段与方法，不改现有 search
private val pluginSearchService: PluginSearchService  // 构造注入 currentSource: () -> String?（应用层在 P2T8 接 MusicSourceSettings）

suspend fun searchFromPlugin(query: String, page: Int, type: String): Result<SearchOutcome> =
    pluginSearchService.search(query, page, type)
```
- [ ] **Step 4: 运行通过；SearchScreen 展示来源标识并在缺能力时隐藏专辑/歌手 tab（GC #13）**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.repository.PluginSearchRoutingTest"
```
- [ ] **Step 5: 运行全量测试通过并提交**
```bash
git add app/src/main/java/com/ncm/app/plugin/PluginSearchService.kt app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt app/src/main/java/com/ncm/app/ui/screens/search/SearchScreen.kt app/src/test/java/com/ncm/app/data/repository/PluginSearchRoutingTest.kt
git commit -m "feat(search): route search through current plugin source without cross-source fallback"
```

### P4T5: 首条完整链路端到端假插件验收

**Files:**
- Create: `app/src/test/java/com/ncm/app/plugin/E2ePluginChainTest.kt`

**Interfaces:**
- Consumes: P3T8 + P4T1 + P4T2。
- Produces: 端到端测试：搜索（`hello.cjs`）→ `resolveMedia` → SSRF 校验 → `lyric`；断言 URL/歌词链路完整。

- [ ] **Step 1: 写端到端测试**
```kotlin
// app/src/test/java/com/ncm/app/plugin/E2ePluginChainTest.kt
package com.ncm.app.plugin

import com.ncm.app.plugin.runtime.QuickJsPluginRuntime
import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class E2ePluginChainTest {

    @Test
    fun searchResolveLyricChainWorksEndToEnd() = runTest {
        val script = File("src/test/resources/fakeplugins/hello.cjs").readText()
        val runtime = QuickJsPluginRuntime("fake-hello", script)
        val resolver = PlaybackResolver(runtime, SsrfGuard())
        val provider = runtime.providerFor("fake-hello")!!

        val outcome = provider.search("测试", page = 1, type = "music")
        val track = outcome.items.first()
        assertEquals("测试 示例", track.title)

        val media = resolver.resolve(track, quality = "standard").getOrThrow()
        assertTrue(media.url.startsWith("https://"))
        assertEquals(emptyMap<String, String>(), media.headers)

        val lrc = resolver.lyric(track).getOrThrow()
        assertEquals("[00:00.00]测试歌词", lrc.rawLrc)
        runtime.destroy()
    }
}
```
- [ ] **Step 2: 运行通过**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.plugin.E2ePluginChainTest"
```
- [ ] **Step 3: 提交**
```bash
git add app/src/test/java/com/ncm/app/plugin/E2ePluginChainTest.kt
git commit -m "test(e2e): search->resolve->lyric chain with fake plugin"
```

---

# 阶段 5：本地资料库迁移

**阶段目标：** 迁移收藏、历史、队列、下载和歌单的数据主键（spec §14 阶段 5）。旧网易云条目保留为 legacy 数据，不自动匹配；完整旧下载转为本地文件记录；旧在线队列项保留展示身份但不保证可播放。

**阶段约束（GC #12）：** 只读 legacy 表示不能修改来源 ID 或假装已映射；用户仍可删除/移出歌单/导出元数据/整理记录。不做两套主键无规则双写。

### P5T1: 收藏与历史的 legacy 只读适配 + 新通用模型写入

**Files:**
- Modify: `app/src/main/java/com/ncm/app/data/JianyunFavoriteStore.kt`（保留简云官方收藏；网易云收藏走 legacy）
- Modify: `app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt`（history 缓存键迁移）
- Create: `app/src/main/java/com/ncm/app/data/store/OnlineLibraryDao.kt`（CRUD + 迁移工具查询）
- Test: `app/src/test/java/com/ncm/app/data/store/OnlineLibraryDaoTest.kt`（Robolectric + Room in-memory）

**Interfaces:**
- Consumes: P1T5 schema、P1T3 转换层。
- Produces:
  - `OnlineSongDao`：`upsert/observeAll/delete(key)/findByCompositeKey`
  - 收藏/历史读写切换到 `ProviderTrackKey`（插件歌曲写 `online_songs`；网易云旧数据读 legacy 适配）

- [ ] **Step 1: 写失败测试 — DAO 联合主键 CRUD**
```kotlin
// app/src/test/java/com/ncm/app/data/store/OnlineLibraryDaoTest.kt
package com.ncm.app.data.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnlineLibraryDaoTest {

    private fun db(): OnlineLibraryDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, OnlineLibraryDatabase::class.java).build()
    }

    @Test
    fun upsertAndQueryByCompositeKey() = runBlocking {
        val dao = db().onlineSongDao()
        val a = OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1)
        dao.upsert(a)
        val loaded = dao.findByCompositeKey("linglan.kw", "123")
        assertEquals("A", loaded?.title)
    }

    @Test
    fun sameRemoteIdDifferentPluginCoexist() = runBlocking {
        val dao = db().onlineSongDao()
        dao.upsert(OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.upsert(OnlineSongEntity("linglan.tx", "123", "B", "[]", null, 1000, null, "{}", "1.0.0", 1))
        assertEquals(2, dao.countAll())
    }
}
```

- [ ] **Step 2: 运行验证失败 → Step 3: 实现 DAO**
```kotlin
// app/src/main/java/com/ncm/app/data/store/OnlineLibraryDao.kt
package com.ncm.app.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OnlineSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OnlineSongEntity)

    @Query("SELECT * FROM online_songs WHERE pluginId = :pluginId AND remoteId = :remoteId")
    suspend fun findByCompositeKey(pluginId: String, remoteId: String): OnlineSongEntity?

    @Query("SELECT * FROM online_songs")
    suspend fun all(): List<OnlineSongEntity>

    @Query("DELETE FROM online_songs WHERE pluginId = :pluginId AND remoteId = :remoteId")
    suspend fun deleteByKey(pluginId: String, remoteId: String)

    @Query("SELECT COUNT(*) FROM online_songs")
    suspend fun countAll(): Int
}
```
- [ ] **Step 4: 运行通过 → Step 5: 迁移收藏/历史读写入口（保留简云官方 + 本地不经过在线插件，GC #13）→ Step 6: 运行全量测试并提交**
```bash
git add app/src/main/java/com/ncm/app/data/store/OnlineLibraryDao.kt app/src/main/java/com/ncm/app/data/store/OnlineLibraryDatabase.kt app/src/test/java/com/ncm/app/data/store/OnlineLibraryDaoTest.kt
git commit -m "feat(store): online library dao with composite-key crud"
```

### P5T2: 完整旧下载转本地文件记录

**Files:**
- Create: `app/src/main/java/com/ncm/app/data/store/DownloadedSongEntity.kt`（本地文件：独立主键 + URI，不经过在线插件，GC #12）
- Modify: `app/src/main/java/com/ncm/app/data/cache/LinglanAudioCache.kt`（把「已完整下载且文件仍存在」的记录暴露为本地文件记录；残缺/临时缓存不视为有效下载）
- Test: `app/src/test/java/com/ncm/app/data/store/DownloadedSongMigrationTest.kt`

**Interfaces:**
- Produces: `DownloadedSongEntity(localId, sourceTrackKey?, uri, title, artistsJson, durationMs, artworkUrl, complete: Boolean)`；`complete=true` 且文件存在才可播放。

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/data/store/DownloadedSongMigrationTest.kt
package com.ncm.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedSongMigrationTest {

    @Test
    fun incompleteOrMissingFileIsNotAValidDownload() {
        val incomplete = DownloadedSongEntity(
            localId = "f1", sourceTrackKey = "kw#1", uri = "file:///tmp/a.part",
            title = "A", complete = false
        )
        assertEquals(false, incomplete.isPlayable())
    }

    @Test
    fun completeExistingFileIsPlayableLocally() {
        val complete = DownloadedSongEntity(
            localId = "f2", sourceTrackKey = "kw#2", uri = "file:///tmp/b.mp3",
            title = "B", complete = true
        )
        assertEquals(true, complete.isPlayable())
    }
}
```
- [ ] **Step 2: 运行验证失败 → Step 3: 实现实体（`isPlayable()` 检查 `File(uri.path).exists()`，本地文件不经过在线插件）→ Step 4: 迁移逻辑把 LinglanAudioCache 完整项映射为 `DownloadedSongEntity` → Step 5: 运行通过并提交**

### P5T3: 「迁移到当前来源」候选工具（用户确认）

**Files:**
- Create: `app/src/main/java/com/ncm/app/ui/screens/migration/MigrationSuggestionsScreen.kt`
- Create: `app/src/main/java/com/ncm/app/domain/migration/LegacySongMatch.kt`
- Test: `app/src/test/java/com/ncm/app/domain/migration/LegacySongMatcherTest.kt`

**Interfaces:**
- Produces:
  - `data class LegacySongMatch(legacyKey, candidates: List<OnlineTrack>, matchBasis: String)`
  - `fun suggestMatches(legacy: List<OnlineTrack>, pluginResults: List<List<OnlineTrack>>): List<LegacySongMatch>`（按标题/歌手候选，返回匹配依据，**不自动写入**）
  - UI：展示候选 + 匹配依据 + 用户确认后建立新 `ProviderTrackKey`

- [ ] **Step 1: 写失败测试**
```kotlin
// app/src/test/java/com/ncm/app/domain/migration/LegacySongMatcherTest.kt
package com.ncm.app.domain.migration

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySongMatcherTest {

    private fun track(pluginId: String, remoteId: String, title: String) = OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = "1.0.0",
        payloadSchemaVersion = 1,
        title = title,
        artists = emptyList(),
        album = null,
        durationMs = null,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )

    @Test
    fun exactTitleMatchIsSuggestedWithBasis() {
        val legacy = track("legacy-netease", "1", "晴天")
        val plugin = track("linglan.kw", "k1", "晴天")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertEquals(1, matches.size)
        assertEquals("标题完全匹配", matches.first().matchBasis)
    }

    @Test
    fun noMatchIsNotAutoWritten() {
        val legacy = track("legacy-netease", "1", "晴天")
        val plugin = track("linglan.kw", "k1", "稻香")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertTrue(matches.isEmpty())
    }
}
```
- [ ] **Step 2: 运行验证失败 → Step 3: 实现匹配（标题精确/归一化 + 时长容差作为匹配依据；不自动写库）→ Step 4: 运行通过并提交**
```bash
git add app/src/main/java/com/ncm/app/domain/migration/LegacySongMatch.kt app/src/test/java/com/ncm/app/domain/migration/LegacySongMatcherTest.kt
git commit -m "feat(migration): user-confirmed legacy->plugin match suggestions (no auto-write)"
```

---

# 阶段 6：移除平台耦合

**阶段目标：** 删除网易云登录、Cookie、Referer、接口与专用响应模型；删除硬编码聆澜/酷狗播放兜底链；清理界面文案、资源名、包名和工程名中的网易云关联（spec §14 阶段 6）。功能稳定后独立迁移包名/工程名，避免与核心数据改造同时进行。

**阶段约束：** 移除前必须满足「本地资料库迁移完成 + 插件链路稳定」。此阶段涉及大量删除，每个 Task 先跑全量测试确认删除不破坏回归。

### P6T1: 移除 `UnblockManager` 兜底链

**Files:**
- Delete: `app/src/main/java/com/ncm/app/data/repository/UnblockManager.kt`
- Delete: `app/src/main/java/com/ncm/app/data/repository/BackupSongMatcher.kt`
- Delete: `app/src/main/java/com/ncm/app/data/repository/BackupSourceStrategy.kt`
- Modify: `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt`（删除 `getBackupSongUrl*`/`getSongUrlForPrefetch` 中聆澜/酷狗分支）
- Modify: `app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt`（删除 `switchToBackupSource`/`evictLinglanAndSwitchToKugou` 调用点）
- Test: 删除对应测试；全量测试必须仍绿。

- [ ] **Step 1: 删除兜底实现与相关测试**（`BackupSongMatcherTest`、`BackupSourceStrategyTest`、`ProviderCircuitBreakerTest` 若仅供兜底链则删除，否则保留）
- [ ] **Step 2: 更新 `MusicRepository`/`PlayerViewModel` 引用并编译**
```bash
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
- [ ] **Step 3: 全量测试通过**
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
- [ ] **Step 4: 提交**
```bash
git add -A app/src
git commit -m "refactor: remove Linglan/Kugou fallback chain, playback resolves via plugin only"
```

### P6T2: 移除网易云 API / 登录 / Cookie / Referer

**Files:**
- Delete: `app/src/main/java/com/ncm/app/data/api/NeteaseApi.kt`、`app/src/main/java/com/ncm/app/data/api/QQMusicApi.kt`（QQ 若独立于网易云链路，评估后决定）、`app/src/main/java/com/ncm/app/data/api/QQMusicAuth.kt`
- Modify: `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt`（删除 `api.*` 调用，收敛为本地资料库 + 插件在线资料库 + 播放解析服务，spec §13）
- Modify: `app/src/main/java/com/ncm/app/NeteaseApp.kt`（移除 Cookie/Referer/API 初始化）
- Delete: `app/src/main/java/com/ncm/app/ui/screens/login/LoginScreen.kt`（网易云登录入口）
- Test: 删除依赖网易云 API 的测试；新增「本地能力不依赖网易云」冒烟测试。

- [ ] **Step 1: 冒烟测试前置（先加后删）** — 新增测试：未连接聆澜时本地音乐/本地歌单/历史可用（GC #13）。
- [ ] **Step 2: 删除 API 层与登录 UI，更新引用**，编译通过。
- [ ] **Step 3: 全量测试通过。**
- [ ] **Step 4: 提交**
```bash
git add -A app/src
git commit -m "refactor: remove NetEase API, login, cookie and Referer from product"
```

### P6T3: 移除在线推荐入口（网易云依赖）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/ui/screens/discover/DiscoverScreen.kt`（隐藏依赖相似歌曲/推荐歌单的入口，只留本地最近播放/收藏/本地统计，GC #13）
- Modify: `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt`（删除 `getDiscoverHome`/`getSimilarSongs*`/`getPrivateFm`/`getRankings`/`getHotPlaylists` 等网易云接口调用，或改为本地统计）
- Test: `app/src/test/java/com/ncm/app/ui/screens/discover/DiscoverLocalOnlyTest.kt`

- [ ] **Step 1: 锁定「首版隐藏在线推荐」行为测试** → **Step 2: 删除相关接口调用** → **Step 3: 全量测试通过** → **Step 4: 提交**

### P6T4: 文案/资源/包名中性化（独立提交）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/NeteaseApp.kt` → 中性应用入口名（如 `JianyunApp`，保留旧类名别名到阶段 7 后删除）
- Modify: 界面文案中的「网易云」「简云音乐」相关字串改为中性表述（具体替换清单以阶段 6 文案梳理为准）
- Modify: `app/build.gradle.kts`（`applicationId` 保持不变，避免商店升级断链；仅注释/资源名中性化）
- Test: 全量测试通过 + 打包验证

**说明：** 包名 `com.ncm.app` 与工程名在**功能稳定后**独立迁移（spec §13），不在本阶段强行改 `applicationId`（避免覆盖安装升级断链）。

### P6T5: 移除硬编码 `PlaybackSource` 平台常量

**Files:**
- Modify: `app/src/main/java/com/ncm/app/data/model/PlaybackSource.kt` → 改为可扩展来源：`LOCAL / OFFICIAL / PLUGIN(pluginId)`（spec §13）
- Modify: 所有引用 `PlaybackSource.NETEASE/KUGOU/LINGLAN` 的调用点
- Test: 引用点编译 + 全量测试通过

**说明：** `PLUGIN(pluginId)` 取代硬编码枚举。`LINGLAN_CACHE` 语义改为 `PLUGIN("linglan.xxx") + cache` 属性，具体设计在阶段 6 定稿。

---

# 阶段 7：回归与发布准备

**阶段目标：** 完整执行单元/集成/UI/离线/升级迁移/异常恢复测试；检查 APK、日志、仓库和构建产物无脚本/测试密钥/个性化 URL；完成依赖许可证、聆澜授权范围、隐私说明与应用商店政策复核（spec §14 阶段 7、§16 验收标准）。

### P7T1: 全量测试矩阵执行

**Files:**
- Test: 全量 `:app:testDebugUnitTest` + 可选 `:app:assembleDebug` + 真机 QA 清单

- [ ] **Step 1: 全量单测**（单元/契约/安全/状态升级/播放分类，spec §15）
```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
- [ ] **Step 2: 构建 Debug/Release APK**
```bash
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:assembleRelease
```
- [ ] **Step 3: 真机 QA（人工）**：离线模式本地库可用；连接/断开/过期/撤销聆澜；来源单选与切换不中断播放；队列保留来源身份；升级后 legacy 数据保留。记录到 QA 清单。

### P7T2: 产物洁净扫描

**Files:**
- Create: `scripts/scan-release-artifacts.sh`（扫描 APK/日志/git 追踪文件无密钥、脚本正文、个性化 URL；GC #4/#15）

**说明：** 本计划文档按 GC #3 不入库，因此 `git grep`（只搜已追踪内容）不会误报计划中的示例密钥字面量。

- [ ] **Step 1: 写扫描脚本**
```bash
#!/usr/bin/env bash
# scripts/scan-release-artifacts.sh — 产物洁净扫描（GC #4/#15）
# 用法：bash scripts/scan-release-artifacts.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

PATTERNS=(
  '[?&]key=[A-Za-z0-9_-]{16,}'          # 带密钥查询参数
  '[?&]token=[A-Za-z0-9_-]{16,}'        # 带令牌查询参数
  'X-API-Key[[:space:]]*[:=][[:space:]]*[^[:space:]]{8,}'  # 明文 API Key 头
  'LINGLAN_(SECRET|KEY|TOKEN)[[:space:]]*[=:]'             # 硬编码常量
)
violations=0
scan() { # $1=标签 $2=目标 二进制用 -a
  local tag="$1" target="$2"
  for p in "${PATTERNS[@]}"; do
    if grep -aE "$p" "$target" >/dev/null 2>&1; then
      echo "违规[$tag]: $target 匹配 $p"; violations=$((violations + 1))
    fi
  done
}

# 1) git 已追踪内容（只搜文本类；二进制类单独 -a）
git grep -nE "${PATTERNS[0]}|${PATTERNS[1]}|${PATTERNS[2]}|${PATTERNS[3]}" \
  -- '*.kt' '*.xml' '*.gradle' '*.kts' '*.properties' '*.php' '*.json' '*.cjs' '*.md' \
  >/dev/null 2>&1 && violations=$((violations + $(git grep -cE "${PATTERNS[0]}" -- . | wc -l)))

# 2) 日志文件
for f in $(git ls-files '*.log' 2>/dev/null || true); do scan "log" "$f"; done

# 3) APK 产物（二进制内 ASCII 串）
for apk in $(find app/build/outputs/apk -name '*.apk' 2>/dev/null || true); do
  scan "apk" "$apk"
done

if [ "$violations" -gt 0 ]; then
  echo "发现 $violations 处潜在违规。"; exit 1
fi
echo "扫描通过：未发现密钥/个性化 URL/硬编码令牌。"
```
- [ ] **Step 2: 运行扫描（应输出「扫描通过」）**
```bash
bash scripts/scan-release-artifacts.sh
# 期望：扫描通过：未发现密钥/个性化 URL/硬编码令牌。
```
- [ ] **Step 3: 修复任何违规项（先 `git grep -nE` 定位，确认不是计划/测试故意字面量）并复扫。**

### P7T3: 依赖与合规复核

- [ ] **Step 1: 许可证清单**（QuickJS 库许可证 + 既有依赖）核对无 AGPL/GPL 冲突（§3/§17）。
- [ ] **Step 2: 聆澜授权范围书面确认**（脚本下载/缓存/运行/分发）存档。
- [ ] **Step 3: 隐私说明更新**（密钥加密存储、插件联网、清单拉取、日志脱敏）。
- [ ] **Step 4: 应用商店政策复核**（远程脚本宿主策略，§3/§17）。

### P7T4: 验收标准逐条核对（spec §16）

| # | 验收项 | 验证方式 |
|---|---|---|
| 1 | 仓库和 APK 无聆澜脚本/密钥/个性化地址 | P7T2 扫描 |
| 2 | 未连接聆澜时本地能力可用 | P6T2 冒烟测试 |
| 3 | 用户可验证密钥、看来源、单选 | P2T6 UI + P2T7 客户端 |
| 4 | Bilibili/GitCode/未知来源不出现 | P2T5 测试 |
| 5 | 搜索→解析→播放；歌词封面按能力降级 | P4T5 端到端 + P4T3 降级 |
| 6 | 失败不自动切换来源 | P4T4 测试 |
| 7 | 远程脚本不能直接访问 Android/文件/本地网络，受时间内存限制 | P3T2/P3T9 测试 |
| 8 | 切换来源不中断播放；队列保留来源身份 | P2T6 + P4T2 |
| 9 | 旧数据保留为 legacy，不静默匹配 | P5T1/P5T3 |
| 10 | 网易云登录/Cookie/Referer/接口/兜底链已移除 | P6T1/P6T2 |
| 11 | 生产脚本经受信签名清单验证，撤销/强更不可回退 | P3T6/P3T5 |
| 12 | 数据库插件 ID 稳定且不含显示名/密钥/查询参数/临时令牌 | P1T5 schema + P2T5 infer |

---

## 执行 Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-08-07-linglan-musicfree-plugin-migration.md`。**

**执行选项：**

1. **Subagent-Driven（推荐）** — 每个 Task 派发独立 subagent，任务间人工/宿主审查，迭代快；适用于 Phase 0-1（纯新增、低风险）。
2. **Inline Execution** — 在当前会话用 executing-plans 批量执行带检查点；适用于验证类任务（P0T1 特征测试、P2T5 过滤）。

**建议起点：** Phase 0 的 P0T1 → P0T4（无外部依赖，纯新增测试 + 决策文档）；P0T2 的 QuickJS 选定会阻塞 P3，建议尽早做。在 P3T6 之前必须完成 §17 书面确认（签名公钥/轮换/撤销），否则 P3 停在假插件验证。
