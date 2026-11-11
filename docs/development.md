# 开发与验证

使用 Android Studio 当前项目配置的 JDK 17 和 Android SDK，保留共享 Gradle 缓存。Gradle JVM 与 Java/Kotlin 编译目标都采用 17；不要用系统 Java 25 启动旧 Kotlin 工具链。

## 工程与工具链

- `library-android`：公开管理器、配置页面、存储与测试。
- `library-okhttp`：1.2.0 可选网络路由、真实 Retrofit / MockWebServer 测试；原核心制品不新增网络依赖。
- `fixtures/compatibility`：真实发行 1.1.0、1.2.0 编译后替换当前 AAR 运行，以及当前源码重编译；`fixtures/upgrade/app11`、`app12` 验证对应版本的保留数据升级。
- `app`：实际接入示例。
- `fixtures/consumer`：独立工程，从本轮文件 Maven 仓库解析真实 AAR/POM；包含 Kotlin 接口调用和开启 R8 的 minified 变体。`prepareReadmeExamples` 从中英文 README 各提取 6 份完整 Kotlin 示例，并提取网络指南的完整请求页面，再加配置交换指南示例，共 14 份。编译时隔离包名，将示例宿主 BuildConfig import 指向消费者实际生成的类，其余调用正文保持不变；Debug 和 minified 两种变体均编译，检查两种 BuildConfig 及真实 Retrofit 回调签名的编译兼容性。示例 app 另有实际编译的 Java 接入代码；独立消费工程的 `JavaConfigurationExchange` 直接调用新导出、检查和两种预览入口，随 Debug/R8 编译并在仪器测试中执行。
- `scripts/ci`：文档检查、工作流检查、产物身份、仪器输出解析和受控发布。

源码格式遵循根目录 [.editorconfig](../.editorconfig)。关键中文注释解释快照隔离、版本冲突、先落盘后发布、迁移备份、默认合并与页面重建边界；不以注释代替可运行验证。

1.3.0 开发验收沿用工具链，版本与实际结果见 [1.3.0 计划](plans/v1.3.0-implementation-plan.md)。候选 schema 3 新增 1.2.0 Debug/Release 升级套件，共 10 套件、30 APK、每端 36 个升级阶段；兼容旧 schema 1/2 的历史候选读取。

工具链固定为 Gradle 8.14.3、AGP 8.13.0、Kotlin 1.9.23、JDK 17、SDK 36，最低 API 24。AGP 支持范围依据 [Android 官方版本说明](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes)。Gradle Wrapper 包含分发校验和；使用 Wrapper，不另外安装全局 Gradle。

`local.properties` 仅存本机 SDK 路径，遵循忽略规则，不提交绝对路径、签名或发布凭据。未配置时可在本地写入实际 SDK 位置。

## 本地检查

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
python3 scripts/ci/check-docs.py
bash ./gradlew :app:testDebugUnitTest :library-android:testDebugUnitTest :library-okhttp:testDebugUnitTest :app:lintDebug :library-android:lintDebug :library-okhttp:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :library-android:assembleDebugAndroidTest :library-okhttp:assembleDebugAndroidTest :library-android:publishReleasePublicationToCiRepository :library-okhttp:publishReleasePublicationToCiRepository verifyCentralDeploymentName
python3 scripts/ci/check-api.py
bash ./gradlew -p fixtures/compatibility run
bash ./gradlew -p fixtures/compatibility -PcompatBaseline=1.2.0 run
bash ./gradlew -p fixtures/compatibility -PcompatBaseline=current run
bash ./gradlew -p fixtures/consumer :app:assembleDebug :app:assembleMinified :app:assembleMinifiedAndroidTest
```

工作流检查执行 `python3 scripts/ci/check-workflows.py`。脚本按 Linux/macOS 和 amd64/arm64 选择 actionlint 1.7.7，核对固定摘要后在临时目录运行，并检查外部 Action 的完整 SHA 引用；不需要全局安装工具。

连接隔离测试设备后可运行：

```sh
bash ./gradlew :app:connectedDebugAndroidTest :library-android:connectedDebugAndroidTest :library-okhttp:connectedDebugAndroidTest
bash ./gradlew -p fixtures/consumer :app:connectedMinifiedAndroidTest
```

仪器测试会修改测试应用中的配置，应使用测试设备及对应测试包；不要在含生产用户数据的应用上执行迁移/清空测试。API 24 和 API 36 的云端矩阵还会执行完整套件，运行前核实项目测试无生产服务副作用。

## 验证范围

JVM 测试关注 URL、单选、环境和失败结果，并直接用真实配置解析器读取中英文 README 的 JSON 示例，检查两份配置一致。文档脚本校验 Markdown/HTML 本地链接和图片、围栏、JSON/XML 示例语法；仪器测试覆盖 AtomicFile、迁移与页面；独立消费工程检验真实发布依赖和 R8。普通 Debug 编译不等于设备、混淆或发布已通过。

新增逻辑应提供行为测试，明确预期和错误路径，不依赖公网真实站点、设备已有数据或集合遍历顺序。版本升级同步迁移文档与 [变更记录](../CHANGELOG.md)。CI 的实际执行路径与报告见 [CI 与发布](ci-and-release.md)。1.2.0 在 API 24/36 上收集 8 套必需设备套件（app、library、okhttp、R8 consumer、1.0.6/1.1.0 Debug/Release 升级），候选共 22 个 APK 和两个同版本 Maven 坐标；候选 schema 2 保留旧 schema 1 / 1.1 发布恢复读取能力。

## 1.3.0 开发版本云端验收（2026-11-11）

功能提交 `7c21dbe` 的[完整 CI #25](https://github.com/logan0817/MultiUrlManager/actions/runs/42970920750)首轮通过 quality、API 24、API 36 和 required。两端各执行页面 34 项、核心 26 项、网络 2 项、真实 AAR/R8 消费 1 项，共 63 项测试；真实 1.0.6/1.1.0/1.2.0 的 Debug/Release 升级共 36 阶段全部通过。包括新文件回调、容量保护、预览冲突与恢复、Java/Kotlin 接入和固定模式隔离。

本机红米验证、截图采样不稳定性、候选身份和浏览器归档下载限制见 [1.3.0 验收记录](plans/v1.3.0-implementation-plan.md)。开发版本保持 `1.3.0-SNAPSHOT`，本次验收不包含合并、tag、签名或正式发布。

## 1.2.0 正式版本候选准备（2026-11-10）

开发验收收尾提交 `0bc3fa5` 的 [完整 CI #20](https://github.com/logan0817/MultiUrlManager/actions/runs/42771730604) 已通过 quality、API 24、API 36 和 required，两端原始报告均核对为 42 项测试、24 个升级阶段通过、零跳过。此前功能提交 #19 的详细范围保留在下文。

本轮将根工程版本从 `1.2.0-SNAPSHOT` 调整为 `1.2.0`；Demo versionCode 保持 `8`，高于 1.1.0 的 `7`。两个 publication 与消费/升级夹具继续读取同一版本属性，不单独维护版本。准备阶段中英文 README 和能力文档明确候选尚未发布，公共安装示例保留 Central 已有的 1.1.0；正式发布后已更新为可公开解析的 1.2.0。

本地验证已通过：41 项 JVM、51 项脚本测试、Debug/Release 与仪器 APK 构建、应用 Debug/Release 及两库 Debug Lint、双 publication/批次名、两代公开 ABI、Kotlin/Java 旧二进制与源码消费、独立 Debug/R8 消费构建及中英文 10 份 README 示例编译。复用 Android Studio 的 JDK 17、SDK 与共享缓存，Gradle 限制 `--max-workers=2`，未强制重建；本地主构建 391 项任务中执行 103 项，其余 288 项增量复用。直接核对两变体 APK 为 `1.2.0 / versionCode 8`、打包 JSON 与源文件逐字节一致，两个制品的 AAR/POM/sources/javadoc/module 齐全，核心不引入 OkHttp，可选制品依赖同版本核心与 OkHttp 4.12.0。日志与 publication 核验记录在忽略目录 `build/verification/v1.2-release-candidate-20261110/`。

正式候选 `dd662cb` 的开发分支 CI #21 通过后，已快进合并 main，并取得 [main push CI #22](https://github.com/logan0817/MultiUrlManager/actions/runs/42779772691) 全部成功结果；每端 42 项测试、24 个升级阶段通过，零跳过。原始 main 候选归档与 72 个文件摘要均在本机核验通过，后续 tag 与签名固定这份候选。2026-11-10 已创建 `v1.2.0` 并完成本机 Central 上传验收，随后对原部署正式发布到 PUBLISHED，公开 100 个文件与原签名包一致、10 份签名通过；身份与恢复信息见 [1.2.0 上传验收记录](ci-and-release.md#120-上传验收记录)。公开依赖独立 Debug/R8/测试 APK 与 10 份 README 示例构建通过，API 33 真机消费 1 项通过、零跳过（1.548 秒），包含实际路由。前述 SNAPSHOT 成功不作为本次正式版本产物验收证据。

## 1.2.0-SNAPSHOT 本地开发验收（2026-10-30）

本轮基于 `main` / `885898f` 的未提交工作区，本地候选的 event 为 `local`；不能用它替代已提交版本的云端 CI 或正式发布候选。

| 检查 | 当前结果 |
| --- | --- |
| JVM | 核心与配置 25 项、可选网络 16 项通过；网络使用真实 Retrofit / OkHttp / MockWebServer |
| 应用与库 | Debug/Release、三模块仪器 APK、应用 Debug/Release Lint、核心与网络 Debug Lint、双 publication 通过 |
| 二进制 ABI | 当前 Release AAR 对比真实 1.0.6（17 类/105 成员）及 1.1.0（27 类/239 成员）通过 |
| 消费 | 同一 Kotlin/Java 用法以 1.1.0 编译后运行于当前 AAR，以及以当前源码重编译均通过；独立 Debug/R8 与仪器 APK 构建通过；两代旧库/新版的 Debug/Release 及测试 APK 全部构建通过，设备消费待执行 |
| 制品与配置 | 核心 POM 不引入 OkHttp；适配制品依赖同版本核心与 OkHttp 4.12.0；Debug/Release JSON 与源码逐字节一致，分别为 dev 三环境及 production 单环境 |
| 本地候选 | schema 2 的 8 套件、22 APK、两个 Maven 制品，共 82 文件封存及摘要校验通过；SNAPSHOT 均解析为 `1.2.0-20261030.091951-2` |
| 脚本 | 45 项非签名测试通过；完整 51 项中的 6 项临时 GPG 签名组因本机 agent 连接失败未完成，CI 保留完整检查 |
| 设备与升级 | 真机由其他任务占用，按使用安排暂缓完整仪器及覆盖升级；API 24 本轮未运行，不复用 1.1.0 的旧结果宣称通过 |

本地 Maven 发布的 SNAPSHOT 文件带 timestamp/buildNumber。候选脚本按真实 `maven-metadata.xml` 核对坐标、更新时间、classifier、扩展名与同轮版本，封存所选轮次及其 metadata/checksum，并在 manifest 的 `snapshotVersions` 固定两个制品的解析版本。缺少最新文件、metadata 陈旧、夹带旧轮次或错误坐标均失败，不从旧完整轮次补齐。正式发布仍拒绝 SNAPSHOT。

构建复用 Android Studio 工具链和共享缓存，后续构建限制为 `--max-workers=2`。本轮启动的 API 24 模拟器已按用户要求关闭，后续设备验证使用连接的 Android 16 真机；Demo 原 APK、files/shared_prefs 与原状态摘要已备份到忽略目录，最后一次辅助脚本退出时已丢弃草稿，原状态文件与测试前备份的 SHA-256 一致；辅助脚本的真实进程恢复没有通过，尚未执行的设备步骤不得标为通过。

## 1.2.0-SNAPSHOT 真机补验（2026-10-31）

连接 OnePlus Android 13（API 33）真机执行补验。设备中已有 Demo 的签名与本机候选不同，因此通过忽略目录中的临时 Gradle init 脚本，只将验证 Demo 的 applicationId 改为 `com.logan.multiurlmanager.verification`；namespace、业务源码与库制品保持同源，原 Demo 不卸载、不覆盖。随后已重新构建标准包名 Demo，标准与隔离候选分别封存，均为 schema 2、8 套件/22 APK/82 文件。

| 检查 | 本轮结果 |
| --- | --- |
| 管理页 | 18 项通过，覆盖展示名、折叠、变更摘要、固定模式只读与复制、错误定位、Bundle 新 ViewModel 恢复、深浅色与横屏 |
| 核心存储与固定模式 | 20 项通过，包含原有 16 项存储回归与 4 项固定模式文件隔离、损坏文件绕过及普通模式恢复 |
| 网络端到端 | 2 项通过，真实 manager.saveAsync 驱动回环 HTTP，覆盖成功提交、冲突、注入存储失败、保护重读及固定模式 |
| R8 消费 | 独立真实 Maven 制品的 minified 消费 1 项通过 |
| 1.1.0 覆盖升级 | Debug、Release 各 3 阶段通过：真实 1.1.0 写入、多环境/自定义/删除候选/备注保留、新版编辑后新进程再读 |
| 真实进程退出恢复 | HOME 后 am kill 确认旧 PID 消失；Launcher 恢复为新 PID，表单 URL/备注和未保存选择恢复；取消草稿后磁盘 SHA-256 不变 |
| 脚本 | 最终完整 51 项通过，包括 6 项真实临时 GPG 签名测试；本机运行将测试临时目录限定为 `/tmp`，不接触用户密钥环 |
| 原 Demo 数据 | 原 APK 与 files/shared_prefs 已备份，存量 legacy 偏好及其他原有文件逐项摘要一致；原应用没有新版状态文件，本轮没有为其创建或迁移状态 |

设备首次网络测试被现有 Charles 抓包代理接管，返回代理错误页；仅测试客户端新增 `Proxy.NO_PROXY`，让设备回环流量直达 MockWebServer，重建、Lint 与两项端到端复测通过，宿主代理策略不变。1.1.0 升级夹具首次比较失败只涉及旧版自身重读时的业务分组顺序；旧版写入后先调用真实 1.1.0 的 refreshData，再记录耐久读取基准，保留完整 JSON、revision、候选、备注和选择比较，修正后两变体均通过。

1.0.6 Debug/Release 首轮各完成 asset/custom 两场景的 6 阶段，但第三场景安装被厂商安装器拒绝，返回 `-99`，同一时刻日志记录 `PC install attack detected`。放慢安装间隔后的有限复测仍被拒绝，故两套完整升级测试未通过，未修改安全设置或使用其他安装身份绕过。首次失败和复测日志均保留；不能将已过的部分阶段作为整套通过证据。

本轮 API 24/36 未执行，API 33 不能替代这两个 CI 端点。深浅色和横屏已有设备截图及断言；额外的 1.3 倍系统字号检查因 shell 无 WRITE_SETTINGS 权限未执行，不调整权限。CI 尚未触发，正式签名/上传/发布未执行。

原始证据在忽略目录 `build/verification/v1.2-20261031-api33/`，包含两轮候选、逐套输出、logcat、进程恢复报告和截图。页面/核心/R8 的最终 APK 与首轮成功输入逐字节一致，按身份复用对应通过结果；只对变化的网络及 1.1.0 测试 APK、被拦截的升级项重跑，最终汇总仍明确记录两个 1.0.6 套件失败。标准包名最新候选为 `build/verification/v1.2-20261031-candidate/`，本地未提交源码身份另行记录；不把 base commit 的 SHA 当作本次未提交内容的云端验收身份。

### 同日后续补验

设备状态随后发生变化：只读检查得到 `verifier_verify_adb_installs=0`、`persist.sys.permission.enable=false`；本会话未修改这些安全开关。在此新条件下，复用已封存候选重跑 1.0.6 两变体升级，Debug 的 asset/custom/code 三场景完整 **9 阶段通过**。Release 的 asset/custom 两场景 **6 阶段通过**，code 场景安装仍被厂商识别为 `PC install attack` 并返回 `-99`，所以 Release 整套仍未通过。本轮仅对界面中名称为 `Upgrade verification`、版本为 legacy 的本项目安装器确认页，按实际 resource-id 和 bounds 点击“继续安装”；不处理密码、身份验证或修改安装安全规则。新证据保存在 `build/verification/v1.2-20261031-upgrade06-completion/`，原失败记录保留。

额外显示检查现已完成：普通字号浅色/深色竖屏、1.3 倍字号竖屏/横屏共 4 张截图，保存按钮与滚动列表区域可见，已人工核对。测试前持久记录原字号、旋转、自动旋转及深浅色设置，结束后逐项恢复成功；编辑过程未保存，配置文件 SHA-256 不变。证据为 `build/verification/v1.2-20261031-visual-completion/summary.json` 及同目录截图。此前 WRITE_SETTINGS 失败属于首次尝试，不能覆盖本次成功结果；首次失败日志仍保留。

### 本轮最终补验与覆盖核对

随后只补跑 1.0.6 Release 的 code 场景：旧版写入、新版覆盖后读取/编辑、新进程重读共 3 阶段全部通过。与逐文件摘要一致的前两个场景合并后，Release 完整 **9 阶段通过**；新报告位于 `build/verification/v1.2-release06-final-scenario/summary.json`，此前失败记录仍保留。安装确认同时核对本项目名称、legacy/current 版本、按钮 resource-id 和 bounds，不改设备安全设置。

契约覆盖核对补充了新增候选、删除候选及选中变化的完整预览断言，并验证重建后预览仍完整、未保存时配置不变；折叠用例扩展到切换环境后返回。最终页面套件重新在 API 33 真机完整执行，**19 项全部通过**。仅测试源码变化，库、Demo 功能及其他夹具输入未变；无变化的套件按候选 APK 摘要复用既有成功证据。标准/隔离测试 APK 重新构建、Debug Lint、文档检查、本机 actionlint 和差异空白检查通过。

最终 API 33 上 **8 套必需设备套件全部取得通过证据**：页面 19 项、存储/固定模式 20 项、网络 2 项、R8 1 项、1.0.6 Debug/Release 各 9 阶段、1.1.0 Debug/Release 各 3 阶段。汇总为 `build/verification/v1.2-20261031-validation-summary.json`；包含本地源码摘要、各套件输入及原始报告位置。这是身份核对后的分批补验，不表示一次完整云端 CI 全绿。契约与用例映射见 [1.2 开发验收方案](plans/v1.2.0-implementation-plan.md#契约与测试对应)。

上述 API 33 补验结束时，API 24/36 和云端 CI 尚未执行；后续 API 36 结果见下节。M4 保持未完成，不能将一个系统端点的结果替代其他端点。所有结果保留本地，未创建提交、推送或触发远端 CI；未启动模拟器，原 Demo 数据与显示设置已核对保留。

## 1.2.0-SNAPSHOT Android 16 真机补验（2026-10-31）

用户连接 Android 16 / API 36 真机后，直接复用此前封存的隔离 Demo 候选。全部源码输入摘要一致，无需重新构建。最终 **8 套必需功能套件取得通过证据**：页面 19 项、存储/固定模式 20 项、网络 2 项、R8 1 项，1.0.6 Debug/Release 各 9 阶段、1.1.0 Debug/Release 各 3 阶段，均无跳过。

首次安装独立验证包被 USB 安装确认拦截，用户确认后继续；两个历史 1.0.6 测试 APK 签名与本次候选不同，仅清理对应专用测试包后重跑。独立验证 Demo 的页面启动随后被厂商后台启动策略拒绝，日志为 `Abort background activity starts`；用户手动允许该验证包的后台显示权限后，读取得到 `MIUIOP(10021): allow`。本会话未通过命令修改权限或全局安全开关。

页面默认类顺序完整执行时两轮均为 18 项通过、1 项失焦失败，失败用例为 `selectedDraftSurvivesAddingAndRecreation_cancelDoesNotPersist`；日志显示 `com.xiaomi.aicr` 拉起浏览器，应用没有处于 RESUMED 状态。该用例单独执行通过，耗时 3.418 秒。保持 APK 与所有断言不变，随后指定 `MainActivityTest,EditorPresentationTest` 类顺序完整执行，**19 项通过、零跳过**。现象指向测试间的系统界面干扰，但小爱识屏的具体触发链路未确认；不能将调整类顺序后的成功描述为默认顺序问题已修复，也不能将此前失败轮次计为通过。所有原始输出、系统抢占现场与失败记录保留。

最终汇总在 `build/verification/v1.2-20261031-api36/final-summary.json`，按测试/升级阶段严格解析终态，记录同一候选的 APK 摘要、实际报告路径和执行限制。浅色、深色、横屏管理页及深色表单截图已核对，保存入口和可滚动内容区域正常。原 Demo 未覆盖，其 files/shared_prefs 的全部文件大小与 SHA-256 均与测试前备份一致；字号、旋转与深浅色系统设置一致。测试进程已停止，临时安装界面 XML 已清理。

剩余为 API 24 与云端 CI。真实进程回收与 1.3 倍字号专项仍以 API 33 的既有证据为准；本轮 API 36 使用上述类顺序执行完整页面用例，不代替未运行的默认云端流程。未提交、推送或触发远端操作。

## 1.2.0-SNAPSHOT 审查修正验证（2026-11-10）

修正 R8 消费夹具的验证缺口：先保存并重读 `https://consumer.example.com/`，再通过真实 `OkHttpClient.newCall().execute()` 执行来源为 `https://bootstrap.example/api/v1/` 的请求。断言改写后的 URL、编码路径/query、保留的 RouteKey，以及 RouteContext 的环境、revision、业务键和目标地址。仅末端测试拦截器返回响应，路由实现参与 R8 和实际执行，不访问公网；响应体和 Client 资源均释放。专用消费应用与测试 APK 补充验证用版本信息，使安装工具可核验包身份与版本，不影响库或 Demo。

配置文档同时区分普通模式的旧数组兼容和 Release Demo 的固定 production 策略；明确改用 `release_defaults` 等键时须同步调整固定 id，或由宿主选择普通模式。

通过共享 Android Studio 环境运行消费工程 `:app:assembleDebug :app:assembleMinified :app:assembleMinifiedAndroidTest :app:lintMinified`，全部成功，中英文 README 示例随构建编译。经安装工具核对两个包的版本，并逐字节核对安装 APK 与封存候选后，一加 Android 13 / API 33 真机消费测试 **1 项通过、零跳过（1.597 秒）**，覆盖上述实际路由、存储重读和管理页启动。15 份文档校验、EditorConfig 空白/编码检查、XML 语法及暂存/工作区差异检查通过。没有独立 Kotlin formatter 配置，采用现有风格与 Lint 检查。原 Demo 数据与本轮备份摘要一致，专用消费测试进程已停止。

新候选位于 `build/verification/v1.2-review-fixes-20261110/candidate/`，保留 8 套件、82 文件，只更新两个消费 APK；其余库、Demo、网络和升级夹具输入未变，既有结果保留原测试日期与范围。安装、构建和真机原始证据见同目录上级的 `summary.json` 及日志。新增的 R8 路由断言本轮仅在 API 33 执行，API 36 仍是 10 月 31 日旧消费用例的通过结果；API 24 和云端 CI 未执行。未启动模拟器，未提交、推送或发布。

## 1.2.0-SNAPSHOT 云端首轮诊断（2026-11-10）

首轮 quality 成功，两个设备矩阵的存储 20 项、网络 2 项、R8 消费 1 项、1.0.6 Debug/Release 各 9 阶段、1.1.0 Debug/Release 各 3 阶段均通过；页面 API 24 两项失败、API 36 一项失败，因此 required 失败。原运行已按用户要求删除，诊断归档仅保留在本地 `build/verification/v1.2-ci-20261110/`，不能将此轮记作完整成功。

API 24 的固定模式复制用例在 instrumentation 线程首次获取 ClipboardManager 时触发未创建 Looper 的 Handler 异常，修正为主线程获取服务。两端的新增候选恢复用例在小屏视口查找尚未创建的 RecyclerView 行而失败；现在先断言重建后的列表包含新增候选和原选择，再滚动到候选并断言真实显示，保存/取消和未落盘检查仍保留。生产实现未变。最终兼容结果以后续 `feat/v1.2.0-development` 提交的完整 CI 为准。

修正后运行 #17 的 API 24 全部通过，API 36 新增候选已恢复，但原备用选择断言失败。该轮 logcat 在备用地址点击期间明确记录系统 `ClipboardOverlay` 触摸窗口被移除；前一复制用例遗留的系统浮层拦截触摸。#18 尝试从可访问窗口标题等待退出未识别到系统浮层，后续日志仍在触摸时记录 ClipboardOverlay，因此该轮不能计为修复成功。复制用例现在在恢复剪贴板后，通过 UiAutomation 执行只读的 `dumpsys window windows`，有界等待实际窗口列表中该浮层退出；不关闭系统浮层功能、不跳过复制断言，也不调整默认测试顺序。若 10 秒内浮层不退出则明确失败。具体云端结果以新提交为准。

## 1.2.0-SNAPSHOT 云端完整验收（2026-11-10）

开发分支 `feat/v1.2.0-development` 的功能提交 `7b69cc3730ba78880c24eb52e70da0b373eb7125` 已通过 [完整 CI #19](https://github.com/logan0817/MultiUrlManager/actions/runs/42769822120)：quality、API 24、API 36 和 required 全部成功。quality 覆盖 41 项 JVM、51 项 Python、Lint、Debug/Release、两代公开 ABI、Kotlin/Java 源码及旧二进制消费、真实双制品候选构建与校验。设备 job 下载并核验同轮候选身份及摘要后执行测试。

| 每端设备套件 | API 24 | API 36 |
| --- | --- | --- |
| 页面（默认类顺序） | 19 项通过 | 19 项通过 |
| 存储与固定模式 | 20 项通过 | 20 项通过 |
| 网络端到端 | 2 项通过 | 2 项通过 |
| R8 实际路由消费 | 1 项通过 | 1 项通过 |
| 真实 1.0.6 Debug / Release 覆盖升级 | 各 9 阶段通过 | 各 9 阶段通过 |
| 真实 1.1.0 Debug / Release 覆盖升级 | 各 3 阶段通过 | 各 3 阶段通过 |

两端下载的原始报告均通过严格仪器终态解析，并额外核对零跳过；每端合计 **42 项测试、24 个升级阶段**。本地汇总为 `build/verification/v1.2-ci-20261110/42769822120/verification.json`，报告与逐阶段原文在同目录 API 子目录。未额外下载云端完整候选归档做本机摘要复核，候选检查结果来自两端成功的 CI 身份校验步骤。

此轮证明主线程获取剪贴板服务、小屏列表恢复检查及系统 ClipboardOverlay 实际窗口等待修正后的完整默认顺序通过；#17、#18 的失败记录保留。云端自动化结果不替代 API 33 真机的真实进程退出恢复、大字号专项，也不证明小米真机系统干扰已消除。本轮本机未启动模拟器，API 24/36 模拟器仅在已授权的云端矩阵运行。版本保持 `1.2.0-SNAPSHOT`，未创建 PR、合并或发布。

## 发布前本地审核记录

2026-10-19，发布收尾文档提交 `097df12` 的 [CI run 39232161996 / attempt 1](https://github.com/logan0817/MultiUrlManager/actions/runs/39232161996/attempts/1) 在 API 36 页面用例 `deletingRequiredSelectionSurvivesRotationAndBlocksSave` 的删除确认点击处失败。日志显示删除菜单点击已执行，随后 Espresso 选中 `has-window-focus=false`、类型为 `BASE_APPLICATION` 的 Activity 根窗口，约 10 秒后抛出 `RootViewWithoutFocusException`；重建和保存断言尚未执行。同轮 quality、API 24，以及 API 36 的存储 16 项、R8 1 项、Debug/Release 升级各 9 阶段均通过。修正仅将该用例的菜单与确认按钮限定在 Dialog 根窗口，保留真实点击、重建、必选校验及未落盘断言；不修改库实现或放宽测试。修正后的结果以对应提交 CI 为准，不将失败轮次计为通过。

2026-10-19 正式版本候选准备：`APP_VERSION_NAME` 从 SNAPSHOT 改为 `1.1.0`，Demo versionCode 为 `7`，旧 `1.0.6` 为 `6`。本轮未修改持久化、默认配置或页面行为；本地 JVM 23 项、Debug/Release 与测试 APK 构建、publication/批次名、真实 AAR/R8 消费及中英文 README 示例编译、1.0.6 公开 ABI 和文档检查通过。直接解包核对两变体 JSON 与源码逐字节一致：Debug 默认 dev、包含三环境；Release 默认 production、只包含生产。AAR、POM、module、sources、javadoc 均存在，坐标一致。正式候选须在对应 main 提交的完整 CI 通过后，才能用于后续 tag 和上传；版本号改为正式格式不表示已经发布。

此前功能提交 [8be5e45 的完整 CI 第 2 次运行](https://github.com/logan0817/MultiUrlManager/actions/runs/39208002616/attempts/2) 的 quality、API 24/36 和 required 全部成功。第 1 次 API 24 失败发生在首次交互前后，预装 Messages 在此之前崩溃；窗口无焦点和截图压暗高度支持系统崩溃弹窗遮挡，未取得当时窗口焦点转储，故不当作直接观察确认。保留失败记录后，代码和断言不变地完整重跑一次通过。

2026-10-19 提交前审查后的最终本地结果：JVM **23 项**、Python **29 项**、API 24 页面 **14 项**、存储迁移 **16 项**、R8 消费 **1 项**通过。真实 1.0.6 AAR 的 Debug/Release 覆盖升级各 **9 个阶段**通过，包含迁移后编辑及新进程重读；该流程通过与 CI 相同的候选 manifest 和设备驱动执行，日志为 `build/verification/release-review/precommit-device-api24.log`。Debug/Release 打包 JSON 与源码逐字节相同，相关 Lint、ABI、publication、原生 actionlint、文档及最终差异检查通过。该阶段设备仅有 API 24 模拟器；后续云端与 Android 16 真机结果见下文。下表保留 10 月 16 日既有验证范围，不能替代最新候选的结果。

2026-10-19 Android 16 原真机补验使用以下可追溯输入：首轮 Demo、存储与 R8 消费包从 `eb0a5e4` 同源构建并复用本机原测试证书，避免云端 Debug 证书不同而卸载用户 Demo；Debug/Release 升级夹具直接使用 [CI run 39200952925](https://github.com/logan0817/MultiUrlManager/actions/runs/39200952925) 的原始候选，归档 SHA-256 与内部 48 项文件摘要核验通过。真实旧库的 Debug/Release 各 9 个阶段通过，包含旧固定 JSON 默认、用户自定义记录和旧代码 provider 三类场景的旧库写入、覆盖安装新版、再启动重读。Release 为真实 release 变体、测试证书签名，不能替代线上宿主和生产分发渠道验收。存储 16 项通过；其持久化实现未在后续页面修正中变化。

本次真机检查发现并修复页面销毁时地址表单和提示弹窗未释放的 `WindowLeaked`；页面统一释放所持窗口，销毁回调不清空重建页面使用的表单状态。测试保留真实 Espresso 点击、返回码、持久化和表单恢复断言，补充旧表单窗口已分离检查。另针对系统转场期间输入未送达窗口增加有界空闲等待，成功保存后等待 Activity 自然销毁，避免测试框架重复启动辅助 Activity 导致约 45 秒的清理等待。此前失败日志保留，不能用专项通过替代完整套件；一次主题恢复测试还记录了设备触摸屏的弹窗外点击，因此自动测试期间需避免与人工操作交叉。

最终页面完整 **14 项通过（43.629 秒）**，包含深浅色、横屏、切环境逐帧可见性和表单重建，最终日志未再出现本应用的 `WindowLeaked`。修复后重新发布本地 AAR 并重建独立 minified 消费包，真机 R8 消费 **1 项通过**。Demo 原配置已逐字节恢复；真机原始日志、包摘要和恢复核对记录仅保留于忽略目录 `build/verification/device-api36-20261019`，不把含无关设备信息的日志提交或上传。

2026-10-16 对当前工作区执行了以下检查。它们证明本地候选的对应范围，不代表云端 CI 或正式发布已经通过。

| 检查 | 本轮结果与范围 |
| --- | --- |
| JVM | 22 项通过，覆盖核心 17 项、配置 5 项；包括默认副本隔离、严格 schema 版本和中英文 README JSON 的真实解析 |
| 应用与库 | Debug/Release 应用、库 Release AAR、测试 APK、应用两变体 Lint 和库 Debug Lint 通过 |
| 文档示例 | 中英文共 10 份 Kotlin 示例随独立 Maven 消费工程编译通过；图片/链接、JSON/XML 代码块与 13 份文档校验通过 |
| 公开 ABI | 真实 1.0.6 的 17 类、105 个公开成员对比通过 |
| API 24 页面 | 最终产物完整 14 项通过，包含环境切换逐帧可见性、保存/取消、冲突、重建、表单、深浅色与横屏 |
| API 24 存储 | 最终产物完整 13 项通过，包含旧数据迁移、真实 1.0.6 Debug/Release asset 数据、legacy 名称冲突、AtomicFile、中断、损坏和默认升级 |
| API 36 真机存储 | 允许安装后，当前候选完整 12 项通过；不依赖页面前台状态 |
| API 36 真机页面 | 最终重试完整 14 项通过，耗时 182.716 秒；覆盖环境切换逐帧可见性、保存/取消、状态栏、主题与横屏 |
| 真实 AAR/R8 | 最终 publication 的独立 Debug/minified 构建通过；API 24 和 API 36 混淆消费各 1 项通过，覆盖持久化重读与打开管理页 |
| CI 脚本 | 21 项行为测试通过，包含无效图片、损坏示例、错误产物、失败/崩溃/截断、分阶段发布及真实临时 GPG 多密钥选钥、验签与校验和 |
| 打包配置 | 解包核对 Debug 默认 dev 且包含三套环境，Release 默认 production 且只包含生产；JSON 与各自源文件逐字节一致 |
| publication | 核对实际 POM 坐标、MIT、SCM/开发者信息、传递依赖，AAR/sources/javadoc/module 文件存在且可读取 |

页面最终复测曾出现一次输入法窗口拦截取消按钮触控的失败，日志明确指向 InputMethod。测试在点击前显式收起键盘后，专项 1 项与完整 14 项均通过；没有删除断言或绕过点击权限。原失败记录保留在本地验证输出，不能把该轮失败报告当成通过。

Android 16 真机最初拒绝安装测试包，先前尝试还遇到锁屏、设备断开和启动等待，不能计为通过。用户允许安装并手动调整权限后，最终页面完整 14 项通过（182.716 秒），存储完整 12 项通过。期间看到的白屏已定位为 AndroidX 测试辅助包的 `InstrumentationActivityInvoker$EmptyActivity`，最终测试继续执行完成；已有线程栈指向 Activity 启动等待，未见库主线程死锁证据。不能据此将所有等待确定归因于同一项厂商权限。结束后已停止本轮测试进程，并将 Demo 配置与测试前备份逐字节核对恢复（production，revision 7）。

API 36 独立 R8 消费测试起初在 `ConsumerActivity` 启动时等待 45 秒后失败，同次启动存在该包后台弹出权限的拒绝记录。用户手动允许 `Release consumer` 后，AppOps 对应项从 `ignore` 变为 `allow`，相同 APK 完整 1 项在 2.74 秒内通过；因此这项启动超时已由权限调整后的等价复测排除，未为通过测试修改库或放宽断言。测试进程已结束，Demo 配置仍与原备份逐字节一致。

2026-10-16 的旧包检查仅构建 Debug/Release 并核对 asset，手写偏好的迁移测试不能证明同包覆盖升级。原 Gradle 设备任务在 API 24 完成 13 项（XML 零失败），随后另一台 Android 13 设备启动超时导致整轮失败；不能把整轮任务写成通过。此前记录中“旧生产包升级完整验证通过”的表述已撤回，以后续真实发行库覆盖升级证据为准。

2026-10-19 提交前审查修复了两个数据问题：默认候选被删除后重启复活，以及旧 URL 规范化碰撞时丢失选中状态。另修复主题测试依赖系统深浅色、设备测试超时残留进程、本机工作流检查下载错误平台二进制、Central deployment 恢复时缺少签名身份校验。核心新增 1 项 JVM、3 项设备回归。新增 `fixtures/upgrade` 使用校验过 SHA-256 的真实 Maven 1.0.6 AAR，冻结 `801d130` 的原 JSON；分别构建 Debug/Release 夹具，旧库实际写入、同 applicationId/测试证书覆盖安装当前库、新进程再次核验。场景包括旧 JSON 默认、用户自定义选择/备注、旧 App 的代码 provider。夹具使用专用包名，测试证书不是生产签名，因此验证的是真实库数据升级链路，不代表任意线上宿主或生产签名分发渠道均已验收。


参考同账户 KToast、FlowBus 后，新增离线 `verifyCentralDeploymentName` 与 `prepareCentralBundle`，本机签名复用标准 `signing.gnupg.*` 属性与 GPG agent；上传仍只消费已通过 CI 的固定候选。Central 默认上传验收到 `VALIDATED`，正式发布必须另选 `publish` 并给出 deployment ID。实际 publication 模型与本地 Maven 任务通过；离线签名使用临时测试密钥验证，未使用生产私钥或 token。操作说明见 [CI 与发布](ci-and-release.md)。

普通云端 CI 已通过：[eb0a5e4 对应运行](https://github.com/logan0817/MultiUrlManager/actions/runs/39200952925) 的 quality、API 24/36 设备矩阵和 required 均成功。该阶段尚未验收冷热缓存/fork/取消/merge_group 专项演练、发布环境保护及真实 Central 上传发布；后续 1.1.0 已在本机完成正式发布和公开文件核验，见 [发布记录](ci-and-release.md#110-上传验收记录)。当前已明确采用本机发布，可选云端发布环境保护与凭据链路尚未启用，不作为本机发版的前置条件。

2026-10-19 正式发布后，另建仅从公开 Maven Central 解析本库的独立宿主，实际 AAR 摘要与原受测候选一致；Debug、R8 minified、仪器 APK 和中英文 10 份 README 示例编译通过。连接原 Android 16 真机（API 36）后，公开依赖 minified 消费测试完整 1 项通过（2.273 秒），覆盖持久化重读和管理页启动；严格仪器终态校验通过，测试后停止专用消费包并返回首页。发布恢复脚本共 34 项测试、13 份文档检查通过；公开文件与部署身份见 [发布记录](ci-and-release.md#110-上传验收记录)。

本地验证日志位于忽略的 `build/verification/release-review`，正式 PR 应引用对应 CI run 和可追溯候选，不能以未提交工作区日志代替提交身份。历史各轮范围见 [更新记录](../CHANGELOG.md)。

混淆消费工程的 `consumer.pro` 仅为仪器测试运行器保留共享 Kotlin runtime、tracing 和公开测试桥接方法，避免测试 APK 排除应用共享依赖后找不到运行器需要的类。没有全包保留 MultiUrlManager；库实现仍参与 R8。该测试工程规则不进入库的 consumer rules，也不能作为生产包体积测量依据。
