# CI 与发布

工作流源文件：[ci.yml](../.github/workflows/ci.yml)、[release.yml](../.github/workflows/release.yml)。普通 CI 已在云端执行，具体提交是否通过以 [GitHub Actions 的对应运行](https://github.com/logan0817/MultiUrlManager/actions/workflows/ci.yml) 为准。1.1.0 已在本机上传并正式发布到 Central，公开文件与签名核对通过，见[本次记录](#110-上传验收记录)。本项目采用与 FlowBus 一致的本机发布方式：普通 CI 验证候选，本机 GPG 签名后上传并在 Central 正式发布。`release.yml` 保留为可选云端入口，尚未启用；无需为本机发布创建 GitHub release environment 或上传发布凭据。

## 普通验证

CI 覆盖 PR、main push、merge_group 与手动验证，使用标准 Ubuntu 24.04、JDK 17。功能分支没有额外 push 验证，避免同一 PR 重复运行。PR 新提交取消该 PR 旧运行；非 PR 按 run ID 独立分组，避免同一 main 分支的后续提交替换此前 pending 验证。发布单独串行，不取消正在执行的发布。

quality 显式初始化 SDK 命令路径，并创建仅包含 `android-36` 平台的临时 SDK 视图，复用已安装的构建工具和已接受的许可。这样运行器预装 API 37 等新平台时，不会改变 Lint 的最高平台判断；所有 Lint 检查保持启用。升级项目 SDK 基线时须同步该步骤。设备 job 仍分别使用 API 24/36 的完整模拟器环境。

| job | 内容 | 超时 |
| --- | --- | --- |
| quality | Python 脚本测试（含真实临时 GPG 密钥）、文档/图片/JSON/XML、actionlint、JVM（含 README JSON）、Lint、Debug/Release、仪器 APK、本地 Maven、publication/项目批次名、1.0.6 公开 ABI、真实 AAR/R8 与中英文 README Kotlin 示例编译 | 30 分钟 |
| device / API 24、36 | 下载同轮候选，校验身份与摘要，安装应用/库/混淆消费 APK，执行仪器测试及 Debug/Release 真实旧库覆盖升级 | 各 35 分钟 |
| required | 总是汇总 quality、device 的结果；失败、取消、跳过均不通过 | 5 分钟 |

设备矩阵 `fail-fast: false`，收集两端结果，不在设备 job 重建应用。`google_apis;x86_64` API 24/36 镜像已在云端完成下载、启动和测试执行；不能把设备启动成功视为测试通过，仍须核对该提交全部套件与 required 的终态。

仓库维护者可把 `CI / required` 作为预期的分支保护入口，实际 GitHub UI 显示名以首次运行结果为准；首次启用需核对再配置。工作流不会自行修改分支规则、合并队列、环境审批或仓库权限。

Actions 固定完整 SHA，checkout 不保留写凭据，PR 不使用 secrets，也不使用 pull_request_target 执行 PR 内容。Gradle 缓存仅由 setup-gradle 管理，v6 显式使用 `cache-provider: basic`；PR、合并队列和手动运行只读，main push 可写。没有额外 Build Scan 或付费缓存服务上传。缓存未命中仍执行完整任务。

## 公开 ABI 门槛

quality 在 publication 完成后、独立消费前运行 `python3 scripts/ci/check-api.py`。脚本下载并校验固定 SHA-256 的真实 Maven Central 1.0.6 AAR，再核对仓库基线与当前 Release AAR。当前覆盖 17 个公开类、105 个字段/方法，检查缺失类、类型关系与 JVM 成员描述符；本地已通过。范围与行为兼容限制见 [API 文档](api.md#已发布二进制兼容基线)。

## 同轮候选与测试判定

`scripts/ci/artifacts.py` 生成 `build/ci/candidate/manifest.json`，记录实际受测 SHA、PR head、event、run ID、attempt、版本、工具链、APK 包名/runner 与逐文件 SHA-256。候选包含仪器 APK 和本地 Maven 文件；缺少 AAR/POM/sources 即失败。

设备只下载 artifact `candidate-<run-id>-<attempt>`，核验身份、文件摘要与必需套件，拒绝未声明文件或越界路径。不能从历史成功运行补取缺失产物。

`scripts/ci/run-instrumentation.py` 安装 APK 并保存输出。解析器同时要求非零测试数、全部用例终态、成功摘要与唯一成功 instrumentation 终态；断言失败、崩溃、截断、零用例、全部跳过均失败。adb 退出码为 0 本身不构成通过。

报告保留 14 天：JVM/Lint、各 API 测试文本、logcat、错误诊断。采证失败不覆盖测试原始结论。取消时尽力上传，强制停止不保证产物一定可用。部分重跑不能复用上一次 attempt 的候选：发生暂态环境失败时使用 **Re-run all jobs**，不能仅重跑 device。当前不自动重试测试；定位为环境故障后由维护者确认再重跑，不能用重跑掩盖断言失败。

## 发布前置条件

本机发布与可选云端发布共用以下候选门槛：

1. `APP_VERSION_NAME` 为对应正式版本，不能含 SNAPSHOT；消费 fixture 使用同一版本。
2. tag 指向 main 上提交，且该 SHA 有完整成功的 main push CI，两个设备矩阵与 required 均已完成。
3. CI 候选仍在保留期内，版本包含 AAR、POM、sources、javadoc；版本、许可证、SCM 与依赖准确。

## 可选云端发布（未启用）

当前日常发布采用下文的本机流程，本节仅在未来明确选择云端发布时适用。发布工作流分为 `upload` 和 `publish` 两次显式手动操作，每次都输入同一正式版本的 `vX.Y.Z` tag。默认 `upload` 只上传并验收到 `VALIDATED`，不会正式发布；`publish` 必须指定此前验收的 deployment ID，不能隐式新建上传。两次操作都必须满足：

1. `release` environment 已由维护者设置保护和审批；仓库套餐是否支持需实际核实。未设置保护并不会自动获得人工审批，启用前必须确认。
2. 仅在该 environment 配置 `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、`SIGNING_KEY_BASE64`、`SIGNING_PASSWORD` secrets，并设置公开变量 `SIGNING_FINGERPRINT` 为预期发布密钥的完整 40 或 64 位十六进制指纹。签名私钥为 ASCII 或二进制私钥文件的 base64，不写入仓库或 artifacts。上传会显式选钥，并逐文件验签核对主钥或签名子钥指纹；导入成功本身不代表签名身份正确。

发布 workflow 仅允许从 main 发起。无凭据的 source job 验证 tag、版本、祖先关系与 CI 来源；经环境审批后 publish job 下载所选 CI 的同一候选，重新核验 manifest，使用临时 GPG 目录签名，并通过 [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/) 上传固定文件，不重新编译。

`upload` 采用 USER_MANAGED，批次名为 `io.github.logan0817-MultiUrlManager-<version>`，实际坐标仍为 `io.github.logan0817:multiurlmanager:<version>`。取得 deployment ID 后轮询状态，在 VALIDATED 核对 ID、坐标和部署文件与受测候选一致，然后结束。Central 可同时返回同一坐标的基础 purl 与 `?type=aar` purl，校验接受二者及其组合，仍拒绝重复、其他类型、业务库或版本；VALIDATED 的坐标为空也会失败。审核完成后，再以 `action=publish`、同一 tag 和该 deployment ID 调度工作流；重新核验候选与部署内容后才调用正式发布 API。PUBLISHED 响应可能不再提供组件 purl，此时仍要求原 deployment ID 一致，并逐个下载公开 AAR/POM/JAR/module 与受测候选对比 SHA-256；文件不可用或内容不一致均失败，不能只依据状态成功。[Central 官方 API](https://central.sonatype.org/publish/publish-portal-api/) 明确区分上传、VALIDATED 和正式发布。

普通 CI 不含生产凭据，候选签名前后内容不改动。临时签名材料随临时目录删除；`release-status` artifact 只保留公开签名包、完整指纹、deployment ID、公开状态与验证结果，不包含私钥或口令。

## 默认本机发布流程

本项目参考 KToast、FlowBus 的 `io.github.logan0817` namespace、GPG agent、标准用户级属性与项目批次命名，不复制它们的私有配置，也不引入它们为多模块发布适配的插件内部模型。当前单模块继续使用 Gradle `maven-publish` 生成候选，在本机签名并上传同一份已验收文件。

| 配置 | KToast / FlowBus 的本机约定 | MultiUrlManager 对应入口 |
| --- | --- | --- |
| Central Portal token | 用户级 `mavenCentralUsername` / `mavenCentralPassword` | 继续保存在本机用户级配置，仅供受控的本机上传与查询使用；不是网站登录密码，不上传至 GitHub |
| GPG 选钥 | `signing.gnupg.keyName` 完整指纹、本机 keyring | 本机签名直接复用，核对完整指纹；私钥保留在本机 keyring |
| GPG 程序与目录 | `signing.gnupg.executable` / `signing.gnupg.homeDir` | 离线准备按需复用；口令由 GPG agent 处理 |
| 发布阶段 | 上传 USER_MANAGED 后人工正式发布 | 上传已签名候选，核对 VALIDATED 后在原 deployment 正式发布 |
| 批次名 | namespace、项目名、版本 | `io.github.logan0817-MultiUrlManager-<version>` |

同账户不代表两个项目自动使用同一签名密钥。维护者应核对预期指纹与有效 token；仓库内离线签名脚本不会读取、复制或改写用户级 Central 凭据。本机 GPG agent 负责私钥解锁，不为本流程导出私钥或向 GitHub 配置签名口令。

离线检查真实 Gradle publication 与批次名，不签名、不上传，SNAPSHOT 也可执行：

```bash
bash ./gradlew verifyCentralDeploymentName
```

需要提前检查本机签名时，使用已经封存并核验摘要的**正式版本候选目录**，确保项目 `APP_VERSION_NAME` 与候选版本一致，并沿用用户级 `~/.gradle/gradle.properties` 的配置：

```properties
signing.gnupg.keyName=<完整发布密钥指纹>
```

```bash
bash ./gradlew prepareCentralBundle \
  -PcentralCandidateDir=/absolute/path/to/candidate \
  --no-configuration-cache
```

该任务只生成 `build/central/multiurlmanager-<version>.zip`，逐文件核对 GPG 签名及完整指纹，并为文件和签名生成 MD5/SHA-1/SHA-256/SHA-512。可用 `SIGNING_FINGERPRINT` 环境变量代替 `signing.gnupg.keyName`；GPG 程序可用 `GPG_EXECUTABLE` 指定。它不重新构建、不读取 Central token、不联网上传，也不会将本地 manifest 当作成功云端 CI 的证明；不能绕过正式发布的 main CI 来源检查。1.1.0 已通过独立上传和正式发布操作完成发行；若以后改为 SNAPSHOT，任务会在签名前拒绝执行。已存在的 ZIP 不自动覆盖，须先核对旧包。

日常发布按以下顺序执行：

1. 确认上述发布门槛，选定 tag 对应的成功 main CI run 和 attempt，下载该轮 `candidate-<run-id>-<attempt>`；核对归档摘要、manifest 身份与逐文件摘要，不能用本机重编译替换受测文件。
2. 对该候选执行上述 `prepareCentralBundle`，核对批次名、Maven 坐标与完整签名指纹，保留签名 ZIP。
3. 在 [Central Portal](https://central.sonatype.com/publishing) 上传签名 ZIP，采用 USER_MANAGED。也可由维护者在本机通过 [官方 Publisher API](https://central.sonatype.org/publish/publish-portal-api/) 上传；仓库内 `publish-central.py` 的默认远端入口使用云端内存签名配置，不直接读取用户级 Gradle token，不能把该入口当作本机 GPG 上传命令。
4. 保存 deployment ID；达到 VALIDATED 后核对部署内容与签名包一致，再对该 deployment 执行正式发布。中断时先查原 ID，不重复上传同版本。
5. 确认 PUBLISHED，下载公开 Maven 文件、签名和校验和，与受测候选及签名包比对；再用仅依赖公开 Maven Central 的独立宿主验证构建、R8 和设备消费。

本机发布的留存材料为候选身份、签名指纹、deployment ID、公开文件摘要和验证结果；原始设备日志及本机操作辅助文件放在忽略目录。凭据不进入源码、日志或发布包。1.1.0 已完成这条流程，无需再次上传或正式发布。可选云端流程的 `action`、environment 和 secrets 要求不适用于上述 Portal 手动操作。

本机入口使用 GPG agent，不读取传统 `signing.keyId`、`signing.password`、`signing.secretKeyRingFile`，也不把 `signing.gnupg.passphrase` 当作口令来源。云端口令仅经 stdin 传入隔离签名进程，不写入命令行或日志。[Gradle 签名文档](https://docs.gradle.org/8.14.3/userguide/signing_plugin.html) 说明了标准 GPG 配置与 agent 的关系。

## 1.1.0 上传验收记录

2026-10-19，在用户授权范围内复用 FlowBus 最近发布使用的本机 Central token 与 GPG agent，上传本项目的固定 CI 候选；私钥未导出到 GitHub，凭据未写入仓库或发布包。

- tag `v1.1.0` 固定提交 `063c92cdeba2c513c40730f00dd7bf86963e8c01`，对应 [CI run 39211596505 / attempt 1](https://github.com/logan0817/MultiUrlManager/actions/runs/39211596505)，quality、API 24、API 36、required 全部成功。
- 下载 artifact `candidate-39211596505-1`，ZIP SHA-256 为 `ba1d4e6c615b3e6476bdc3a9df0ba234c03592f3649ad56648a6c43863932b28`，manifest 身份和 43 个文件摘要全部通过。
- 主签名密钥指纹 `8BB25F160F45FD8919DC985A152B32281C3E9240`，已与本机保存的 FlowBus 1.2.0 公开 AAR 签名核对。
- 批次 `io.github.logan0817-MultiUrlManager-1.1.0`，deployment ID `6b499138-69fc-4180-a3bc-4832c9652ac3`，状态 VALIDATED，errors 与 warnings 均为空。
- 从该 deployment 下载的 5 份 Maven 文件、5 份签名及 40 份校验和共 50 个文件，与固定 CI 候选生成的签名包逐字节一致；签名身份再次验证通过。

上传仅执行一次，后续验证使用原 deployment ID 恢复查询；下载核验遇到一次 TLS EOF，查询重试后完成。实测发现并修复了 Central 返回基础与 AAR 两条 purl 时的过严断言，32 项本地脚本回归通过；后续脚本修复的云端结果以其对应提交的 CI 为准。原 tag 与受测 Maven 文件保持不变。

2026-10-19，发布脚本修复提交 `ac8cb24` 通过 [CI run 39217165952 / attempt 1](https://github.com/logan0817/MultiUrlManager/actions/runs/39217165952) 的 quality、API 24/36 和 required 后，获授权对原 deployment 执行一次正式 publish，最终返回 PUBLISHED。从 [Maven Central 公开目录](https://repo.maven.apache.org/maven2/io/github/logan0817/multiurlmanager/1.1.0/) 下载的 50 个文件与原签名包逐字节一致，5 份公开签名验签通过。

本次 PUBLISHED 响应的 purls 为空，errors 中包含 `Deployment components info not found`；公开文件实际可下载且全部一致。因此发布后恢复路径允许已发布状态缺少组件列表，仍强制核对原 deployment ID 与全部公开 Maven 产物；新增接受元数据缺失、拒绝公开文件篡改两项回归，本地脚本共 34 项通过。此状态差异不用于放宽 VALIDATED 阶段或跳过公开下载验证。

公开依赖消费验证另建独立宿主，移除本地 Maven 仓库，并将 `io.github.logan0817` 限定从 `https://repo.maven.apache.org/maven2/` 解析。实际解析坐标为 `io.github.logan0817:multiurlmanager:1.1.0`，AAR SHA-256 为 `d4237ddba47f663b2c73f80f3a18e380fdea0b62d87ab5eda10813ca39a6e897`，与公开文件及受测候选一致。Debug、R8 minified、minified 仪器 APK 和中英文共 10 份 README 示例编译通过（107 个任务，1 分 11 秒）。连接原 Android 16 真机（API 36）后，安装上述公开依赖构建的 minified 应用及仪器 APK，持久化重读和管理页启动完整 1 项通过（2.273 秒），并通过严格终态解析。测试后已停止专用消费包并返回首页；此前完整页面与迁移结果见 [开发验证记录](development.md)。

2026-10-19 确认继续采用本机发布，不配置 GitHub 发布凭据。可选 `release.yml` 的环境保护、secrets 和签名上传链路尚未实测，不属于本次本机发布的未完成项。发布恢复修复提交 `800fdca` 的 [CI run 39221044260 / attempt 2](https://github.com/logan0817/MultiUrlManager/actions/runs/39221044260/attempts/2) 已通过 quality、API 24/36 和 required；首轮 API 36 的单击后出现长按菜单失败保留诊断，日志同期存在明显绘制延迟，代码与断言不变地完整重跑一次通过，暂态输入延迟为有证据支持的判断。

## 中断与恢复

本机 Portal 操作按上文保存并复用原 deployment ID；以下 `action` 参数与签名 secrets 指可选云端脚本。

上传不是可盲重试的幂等操作。已取得 ID 后保存于日志和 `release-status` artifact；上传验收中断后使用 `action=upload` 并填写原 `deployment_id` 继续查询，禁止重新上传同版本。正式发布或发布后核验中断，使用 `action=publish` 和原 ID 恢复；缺少 ID 会在任何上传前失败。若上传响应丢失而没有 ID，先在 Portal 查找部署并核对坐标；未查明前不留空重传。

Central 校验失败时保留 status.json 调查；PUBLISHED 但公开下载暂不可用时只能补查询/核验，不能覆盖已发布版本。已发布内容不可修改，修正使用新版本号。恢复流程会核验部署中的文件与所选 CI 候选相同，并下载 `.asc` 逐文件验证签名身份后才继续发布；恢复 upload/publish 仍需要 `SIGNING_KEY_BASE64` 和 `SIGNING_FINGERPRINT`。

流程不创建 tag、GitHub Release、公开演示 APK 或修改分支规则。演示 APK 签名和外部发布范围应单独明确。首次正式发布后应使用独立宿主仅依赖 Maven Central 解析正式坐标，保存依赖传递与设备消费证据；1.1.0 的公开解析与构建结果见上文，公开文件摘要一致不代替完整宿主集成。

## 云端验收清单

首次启用需实际核验冷/热缓存、故意失败、取消、fork PR、merge_group、required 跳过判定、API 24/36、artifact 下载与发布环境保护。远端触发、规则调整、凭据配置和正式发布按用户授权执行；本地实现不代表已执行这些操作。

没有可比云端运行数据前，不报告耗时或费用下降。后续分别记录排队、准备、构建、设备、传输、总耗时与任务分钟数。

设备矩阵额外执行专用升级夹具（`fixtures/upgrade`）：旧真实 1.0.6 AAR 与新版 AAR 的 Debug/Release 包使用相同夹具包名和测试证书，通过 `adb install -r` 保留数据。每种变体覆盖旧 JSON 默认、用户自定义、旧代码 provider 三种场景，每场景为旧版写入、新版迁移、新进程重读，共 18 个阶段。清理仅限专用夹具包名；旧新 APK 全部写入同轮 manifest 并校验摘要。
