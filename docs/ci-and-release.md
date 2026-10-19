# CI 与发布

工作流源文件：[ci.yml](../.github/workflows/ci.yml)、[release.yml](../.github/workflows/release.yml)。这些是可执行流程定义，尚未完成首次云端验收；本地静态检查不能替代 GitHub、模拟器、缓存和凭据环境的实际运行。

## 普通验证

CI 覆盖 PR、main push、merge_group 与手动验证，使用标准 Ubuntu 24.04、JDK 17。功能分支没有额外 push 验证，避免同一 PR 重复运行。PR 新提交取消该 PR 旧运行；非 PR 按 run ID 独立分组，避免同一 main 分支的后续提交替换此前 pending 验证。发布单独串行，不取消正在执行的发布。

| job | 内容 | 超时 |
| --- | --- | --- |
| quality | Python 脚本测试（含真实临时 GPG 密钥）、文档/图片/JSON/XML、actionlint、JVM（含 README JSON）、Lint、Debug/Release、仪器 APK、本地 Maven、publication/项目批次名、1.0.6 公开 ABI、真实 AAR/R8 与中英文 README Kotlin 示例编译 | 30 分钟 |
| device / API 24、36 | 下载同轮候选，校验身份与摘要，安装应用/库/混淆消费 APK，执行仪器测试及 Debug/Release 真实旧库覆盖升级 | 各 35 分钟 |
| required | 总是汇总 quality、device 的结果；失败、取消、跳过均不通过 | 5 分钟 |

设备矩阵 `fail-fast: false`，收集两端结果，不在设备 job 重建应用。Google 官方 SDK 仓库列出的 `google_apis;x86_64` API 24/36 镜像已核对存在；云端可下载和启动仍需实测。

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

发布工作流分为 `upload` 和 `publish` 两次显式手动操作，每次都输入同一正式版本的 `vX.Y.Z` tag。默认 `upload` 只上传并验收到 `VALIDATED`，不会正式发布；`publish` 必须指定此前验收的 deployment ID，不能隐式新建上传。两次操作都经过 `release` environment 保护。必须满足：

1. `APP_VERSION_NAME` 为对应正式版本，不能含 SNAPSHOT；消费 fixture 使用同一版本。
2. tag 指向 main 上提交，且该 SHA 有完整成功的 main push CI，两个设备矩阵与 required 均已完成。
3. CI 候选仍在保留期内，版本包含 AAR、POM、sources、javadoc；版本、许可证、SCM 与依赖准确。
4. `release` environment 已由维护者设置保护和审批；仓库套餐是否支持需实际核实。未设置保护并不会自动获得人工审批，启用前必须确认。
5. 仅在该 environment 配置 `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、`SIGNING_KEY_BASE64`、`SIGNING_PASSWORD` secrets，并设置公开变量 `SIGNING_FINGERPRINT` 为预期发布密钥的完整 40 或 64 位十六进制指纹。签名私钥为 ASCII 或二进制私钥文件的 base64，不写入仓库或 artifacts。上传会显式选钥，并逐文件验签核对主钥或签名子钥指纹；导入成功本身不代表签名身份正确。

发布 workflow 仅允许从 main 发起。无凭据的 source job 验证 tag、版本、祖先关系与 CI 来源；经环境审批后 publish job 下载所选 CI 的同一候选，重新核验 manifest，使用临时 GPG 目录签名，并通过 [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/) 上传固定文件，不重新编译。

`upload` 采用 USER_MANAGED，批次名为 `io.github.logan0817-MultiUrlManager-<version>`，实际坐标仍为 `io.github.logan0817:multiurlmanager:<version>`。取得 deployment ID 后轮询状态，在 VALIDATED 核对 ID、坐标和部署文件与受测候选一致，然后结束。审核完成后，再以 `action=publish`、同一 tag 和该 deployment ID 调度工作流；重新核验候选与部署内容后才调用正式发布 API。PUBLISHED 后逐个下载公开 AAR/POM/JAR/module 对比 SHA-256。[Central 官方 API](https://central.sonatype.org/publish/publish-portal-api/) 明确区分上传、VALIDATED 和正式发布。

普通 CI 不含生产凭据，候选签名前后内容不改动。临时签名材料随临时目录删除；`release-status` artifact 只保留公开签名包、完整指纹、deployment ID、公开状态与验证结果，不包含私钥或口令。

## 同账户配置与本机离线准备

本项目参考 KToast、FlowBus 的 `io.github.logan0817` namespace、GPG agent、标准用户级属性与项目批次命名，不复制它们的私有配置，也不引入它们为多模块发布适配的插件内部模型。当前单模块继续使用 Gradle `maven-publish` 生成候选、Python 上传同一份已验收文件。

| 配置 | KToast / FlowBus 的本机约定 | MultiUrlManager 对应入口 |
| --- | --- | --- |
| Central Portal token | 用户级 `mavenCentralUsername` / `mavenCentralPassword` | 由维护者将同一有效 token 的两部分配置到 release environment 的 `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`；不是网站登录密码 |
| GPG 选钥 | `signing.gnupg.keyName` 完整指纹、本机 keyring | 离线准备直接复用；云端使用 `SIGNING_FINGERPRINT` 和受保护的私钥 secret |
| GPG 程序与目录 | `signing.gnupg.executable` / `signing.gnupg.homeDir` | 离线准备按需复用；口令由 GPG agent 处理 |
| 发布阶段 | 上传 USER_MANAGED 后人工正式发布 | 默认 upload，另选 publish 并指定 deployment ID |
| 批次名 | namespace、项目名、版本 | `io.github.logan0817-MultiUrlManager-<version>` |

同账户不代表 GitHub 仓库自动共享 secrets，也不代表两个项目当前使用同一签名密钥。维护者应核对预期指纹与有效 token；脚本不会自动读取、复制或改写用户级 Central 凭据。

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

该任务只生成 `build/central/multiurlmanager-<version>.zip`，逐文件核对 GPG 签名及完整指纹，并为文件和签名生成 MD5/SHA-1/SHA-256/SHA-512。可用 `SIGNING_FINGERPRINT` 环境变量代替 `signing.gnupg.keyName`；GPG 程序可用 `GPG_EXECUTABLE` 指定。它不重新构建、不读取 Central token、不联网上传，也不会将本地 manifest 当作成功云端 CI 的证明；不能绕过正式发布的 main CI 来源检查。当前开发版本是 SNAPSHOT，会在签名前拒绝该任务。已存在的 ZIP 不自动覆盖，须先核对旧包。

本机入口使用 GPG agent，不读取传统 `signing.keyId`、`signing.password`、`signing.secretKeyRingFile`，也不把 `signing.gnupg.passphrase` 当作口令来源。云端口令仅经 stdin 传入隔离签名进程，不写入命令行或日志。[Gradle 签名文档](https://docs.gradle.org/8.14.3/userguide/signing_plugin.html) 说明了标准 GPG 配置与 agent 的关系。

## 中断与恢复

上传不是可盲重试的幂等操作。已取得 ID 后保存于日志和 `release-status` artifact；上传验收中断后使用 `action=upload` 并填写原 `deployment_id` 继续查询，禁止重新上传同版本。正式发布或发布后核验中断，使用 `action=publish` 和原 ID 恢复；缺少 ID 会在任何上传前失败。若上传响应丢失而没有 ID，先在 Portal 查找部署并核对坐标；未查明前不留空重传。

Central 校验失败时保留 status.json 调查；PUBLISHED 但公开下载暂不可用时只能补查询/核验，不能覆盖已发布版本。已发布内容不可修改，修正使用新版本号。恢复流程会核验部署中的文件与所选 CI 候选相同，并下载 `.asc` 逐文件验证签名身份后才继续发布；恢复 upload/publish 仍需要 `SIGNING_KEY_BASE64` 和 `SIGNING_FINGERPRINT`。

流程不创建 tag、GitHub Release、公开演示 APK 或修改分支规则。演示 APK 签名和外部发布范围应单独明确。首次正式发布后仍需用干净宿主仅依赖 Maven Central 解析正式坐标，保存依赖传递与设备消费证据；公开文件摘要一致不代替完整宿主集成。

## 云端验收清单

首次启用需实际核验冷/热缓存、故意失败、取消、fork PR、merge_group、required 跳过判定、API 24/36、artifact 下载与发布环境保护。远端触发、规则调整、凭据配置和正式发布按用户授权执行；本地实现不代表已执行这些操作。

没有可比云端运行数据前，不报告耗时或费用下降。后续分别记录排队、准备、构建、设备、传输、总耗时与任务分钟数。

设备矩阵额外执行专用升级夹具（`fixtures/upgrade`）：旧真实 1.0.6 AAR 与新版 AAR 的 Debug/Release 包使用相同夹具包名和测试证书，通过 `adb install -r` 保留数据。每种变体覆盖旧 JSON 默认、用户自定义、旧代码 provider 三种场景，每场景为旧版写入、新版迁移、新进程重读，共 18 个阶段。清理仅限专用夹具包名；旧新 APK 全部写入同轮 manifest 并校验摘要。
