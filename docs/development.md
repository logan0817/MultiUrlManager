# 开发与验证

使用 Android Studio 当前项目配置的 JDK 17 和 Android SDK，保留共享 Gradle 缓存。Gradle JVM 与 Java/Kotlin 编译目标都采用 17；不要用系统 Java 25 启动旧 Kotlin 工具链。

## 工程与工具链

- `library-android`：公开管理器、配置页面、存储与测试。
- `app`：实际接入示例。
- `fixtures/consumer`：独立工程，从本轮文件 Maven 仓库解析真实 AAR/POM；包含 Kotlin 接口调用和开启 R8 的 minified 变体。`prepareReadmeExamples` 从中英文 README 各提取 5 份完整 Kotlin 示例，隔离包名后一起编译，源码正文保持不变；示例 app 另有实际编译的 Java 接入代码。
- `scripts/ci`：文档检查、工作流检查、产物身份、仪器输出解析和受控发布。

源码格式遵循根目录 [.editorconfig](../.editorconfig)。关键中文注释解释快照隔离、版本冲突、先落盘后发布、迁移备份、默认合并与页面重建边界；不以注释代替可运行验证。

工具链固定为 Gradle 8.14.3、AGP 8.13.0、Kotlin 1.9.23、JDK 17、SDK 36，最低 API 24。AGP 支持范围依据 [Android 官方版本说明](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes)。Gradle Wrapper 包含分发校验和；使用 Wrapper，不另外安装全局 Gradle。

`local.properties` 仅存本机 SDK 路径，遵循忽略规则，不提交绝对路径、签名或发布凭据。未配置时可在本地写入实际 SDK 位置。

## 本地检查

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
python3 scripts/ci/check-docs.py
bash ./gradlew :app:testDebugUnitTest :library-android:testDebugUnitTest :app:lintDebug :library-android:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :library-android:assembleDebugAndroidTest :library-android:publishReleasePublicationToCiRepository
python3 scripts/ci/check-api.py
bash ./gradlew -p fixtures/consumer :app:assembleDebug :app:assembleMinified :app:assembleMinifiedAndroidTest
```

工作流检查使用固定摘要的 actionlint；CI 在 Linux 执行 `python3 scripts/ci/check-workflows.py`。其他系统可自行安装对应平台 actionlint 1.7.7 后运行 `actionlint .github/workflows/*.yml`，不可直接运行 Linux 二进制。

连接隔离测试设备后可运行：

```sh
bash ./gradlew :app:connectedDebugAndroidTest :library-android:connectedDebugAndroidTest
bash ./gradlew -p fixtures/consumer :app:connectedMinifiedAndroidTest
```

仪器测试会修改测试应用中的配置，应使用测试设备及对应测试包；不要在含生产用户数据的应用上执行迁移/清空测试。API 24 和 API 36 的云端矩阵还会执行完整套件，运行前核实项目测试无生产服务副作用。

## 验证范围

JVM 测试关注 URL、单选、环境和失败结果，并直接用真实配置解析器读取中英文 README 的 JSON 示例，检查两份配置一致。文档脚本校验 Markdown/HTML 本地链接和图片、围栏、JSON/XML 示例语法；仪器测试覆盖 AtomicFile、迁移与页面；独立消费工程检验真实发布依赖和 R8。普通 Debug 编译不等于设备、混淆或发布已通过。

新增逻辑应提供行为测试，明确预期和错误路径，不依赖公网真实站点、设备已有数据或集合遍历顺序。版本升级同步 [迁移文档](migration-1.1.md) 与 [变更记录](../CHANGELOG.md)。CI 的实际执行路径与报告见 [CI 与发布](ci-and-release.md)。

## 发布前本地审核记录

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

普通云端 CI 已通过：[eb0a5e4 对应运行](https://github.com/logan0817/MultiUrlManager/actions/runs/39200952925) 的 quality、API 24/36 设备矩阵和 required 均成功。冷热缓存/fork/取消/merge_group 专项演练、发布环境保护、真实 Central 上传与正式发布仍未验收；普通 CI 成功不代表发布流程已完成。

本地验证日志位于忽略的 `build/verification/release-review`，正式 PR 应引用对应 CI run 和可追溯候选，不能以未提交工作区日志代替提交身份。历史各轮范围见 [更新记录](../CHANGELOG.md)。

混淆消费工程的 `consumer.pro` 仅为仪器测试运行器保留共享 Kotlin runtime、tracing 和公开测试桥接方法，避免测试 APK 排除应用共享依赖后找不到运行器需要的类。没有全包保留 MultiUrlManager；库实现仍参与 R8。该测试工程规则不进入库的 consumer rules，也不能作为生产包体积测量依据。
