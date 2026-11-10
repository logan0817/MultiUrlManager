# 常见问题

## 初始化失败或 instance 为 null

确认在同一进程的 Application 初始化，并在 Manifest 注册该 Application；初始化失败时保留异常原因。默认环境 id 必须存在于配置中。首次选择的优先级是：显式 `setDefaultEnvironment` / `setFileConfigKey` > JSON 的 `defaultEnvironment` > 兼容回退 `release_defaults`。README 示例已在 JSON 指定 `production`，无需额外调用 `setDefaultEnvironment`。库不根据宿主 DEBUG 自动推断环境。

重复初始化必须使用一致配置。宿主 assets 的文件名必须为 `base_urls_config.json`，schemaVersion 仅支持 1；未知格式或非法字段不会悄悄使用其他地址。参见 [配置](docs/configuration.md)。

## 改了列表或 JSON，但请求仍使用旧地址

`dynamicUrls` 是副本，直接修改不生效，改用快照显式保存。已有用户覆盖会影响默认配置；核对当前环境与保存结果，必要时在页面恢复当前环境默认值并保存。

仅使用核心库时，已有网络对象不会自动改变 baseUrl。收到变更通知后由宿主替换对应 Service；正在执行的请求保持旧地址。参见 [页面与网络](docs/ui-and-network.md)。

使用 1.2.0 可选路由时，确认已注册 `UrlRoutingInterceptor`，请求带有 `RouteKey`，业务键和来源 Base URL 匹配。只添加依赖不会启用路由；已进入拦截器的请求沿用进入时的配置，后续受管请求才读取新配置。完整步骤见[网络路由](docs/network-routing.md)。

## 管理页只读或正式包仍能切到开发环境

`setFixedEnvironment("production")` 会使核心和页面只读，仅使用包内指定环境。需要切换的调试包应使用普通模式，并在新进程初始化；同一进程不能用重复初始化更换运行模式。

普通模式下，Release 覆盖安装仍会保留已有选择；修改 `defaultEnvironment` 或仅替换 Release 的同名 JSON，不能强制已有用户切换到生产环境。正式包要求固定生产时，由宿主显式启用固定模式；要裁剪包内其他环境，还需提供完整的 Release 专用 JSON。见[固定环境模式](docs/configuration.md#固定环境模式120)。

## 地址无法保存

只接受有效 HTTP(S)，不能包含用户名密码、query、fragment；端口必须有效。批量数据不能有重复候选或同组多选；requiredKeys 必须有选择。保存冲突表示草稿 revision 过期，应重新加载再编辑。

STORAGE 失败不能视为成功。保留草稿，检查应用内部存储空间、文件权限与诊断。写入结果无法确认时后续写入会暂停，应重新读取状态或重启确认后再编辑。不要通过清空用户数据掩盖写入失败。

## 升级后出现历史配置或损坏状态

旧记录没有环境身份，会进入 `legacy` 环境，首次保持旧地址。原 SharedPreferences 保留作迁移前备份；1.1 后续修改不会回写旧备份。全部旧记录无效或新状态损坏时需要明确处理，不静默切换默认服务器。备份、恢复与降级限制见 [迁移](docs/migration-1.1.md)。

## Gradle、SDK 或 Java 错误

接入自己的 App 时，`minSdk` 至少 24；当前 AndroidX 传递依赖的元数据要求 `compileSdk` 至少 35、AGP 至少 8.6.0。`checkDebugAarMetadata` 错误应按首个错误核对这两项，不能只提高 `minSdk`。这些最低要求不代表所有版本组合都已验证。

本仓库已验证 JDK 17、Gradle 8.14.3、AGP 8.13.0 与 SDK 36。开发本仓库时使用项目 Wrapper，确认 Android Studio Gradle JVM 和命令行 JAVA_HOME 指向可用 JDK 17；`local.properties` 仅保留本机 SDK 路径。不要用删除全局缓存或刷新全部依赖作为通用修复。

构建首次错误比末尾失败摘要更有用。一次构建没有输出时检查原进程，不重复启动。完整入口见 [开发](docs/development.md)。

## 混淆或发布消费问题

先生成本轮本地 Maven 候选，再构建独立消费 fixture，避免用源码 project 依赖代替 AAR 验收。状态序列化使用显式字段映射；不要添加全包 keep 掩盖真实字段/反射问题。混淆编译通过后仍要运行 minified 仪器测试。

CI required 失败时检查 quality 与两个设备矩阵的结果；设备输出必须有完整测试终态。发布中断先查 deployment ID，禁止重复上传同版本。参见 [CI 与发布](docs/ci-and-release.md)。
