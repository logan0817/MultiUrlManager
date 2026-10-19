# 配置

`environmentId` 是环境的稳定标识，`configKey` 是业务键。一个环境包含每个业务键的多个候选地址；同一键最多选择一个。不同环境的选择与自定义候选独立保存。

## JSON

配置文件固定为宿主 `src/main/assets/base_urls_config.json`。格式版本为 1，字段定义见 [JSON Schema](schemas/configuration-v1.schema.json)。Schema 表达结构，环境 id 唯一、同组单选、必选键和地址语义由核心校验器进一步检查：

```json
{
  "schemaVersion": 1,
  "defaultEnvironment": "dev",
  "environments": [
    {
      "id": "dev",
      "name": "开发环境",
      "requiredKeys": ["login"],
      "urls": [
        {"configKey": "login", "url": "https://dev.example.com/api/", "select": true, "remark": "登录服务"}
      ]
    },
    {
      "id": "production",
      "name": "生产环境",
      "urls": [
        {"configKey": "login", "url": "https://example.com/api/", "select": true}
      ]
    }
  ]
}
```

`id` 与 `name` 必须非空；id 不能重复。`requiredKeys` 默认空，作为当前环境提交时，声明的键必须存在选中地址。`select` 默认 false，`remark` 可省略。未知 schema 版本、缺失文件、非法字段或同组多选导致初始化失败，不静默加载其他服务器。

JSON 可通过顶层 `defaultEnvironment` 声明首次初始化的环境，例如 `dev` 或 `production`，地址也全部保存在 JSON 中。Application 只需调用 `BaseUrlManager.builder(this).build()`。环境 id 必须存在；字段错误时初始化失败。

首次初始化环境选择优先级：显式 `setDefaultEnvironment` / `setFileConfigKey` > JSON 的 `defaultEnvironment` > 兼容回退 `release_defaults`。未提供 JSON 默认字段的已有配置仍可显式指定环境；代码 provider 不读取 JSON，继续使用显式环境或兼容回退。已有持久化状态优先于首次默认值，不因重启或更换构建类型自动切换。

旧 JSON 的 `debug_defaults`、`release_defaults` 及其他顶层数组仍可解析，`setFileConfigKey(key)` 是默认环境设置的兼容入口。

## 代码配置

```kotlin
BaseUrlManager.builder(context)
    .setEnvironments(listOf(
        UrlEnvironment(
            id = "dev",
            name = "开发环境",
            urls = listOf(BaseUrl("login", "https://dev.example.com/", true)),
            requiredKeys = setOf("login")
        )
    ))
    .setDefaultEnvironment("dev")
    .build()
```

来源优先级：`setEnvironments` > `setDefaultProvider` > JSON。旧 provider 返回的候选属于宿主选择的默认环境。provider 明确返回空集合时保留空环境，不回退 JSON。所有来源均经过同一校验。

## 地址规则

- 仅允许 HTTP(S)，要求有效 host；支持本地调试 host、IPv4、IPv6。
- 端口为 1–65535；禁止 URL 中的用户名密码、query、fragment。
- 去掉业务键和 URL 两端空白，保留业务键大小写与 URL 路径语义，规范化 scheme/host 大小写及默认端口，自动补末尾 `/`。
- 批量数据中同键、同标准化 URL 的重复项与同组多选会拒绝保存；单条添加相同候选更新备注并保留已有选择，显式选择会取消同组其他选择。
- 未选中的可选键查询返回 null。只有调用方显式传入 fallback 才使用它，fallback 不写入存储。

有效映射使用当前环境选中候选；没有选择时仅使用调用方显式 fallback。初始化合并默认更新时保留用户覆盖与显式删除/清空，未修改的组跟随默认更新。默认值升级、覆盖合并、旧数据归属与恢复行为见 [迁移说明](migration-1.1.md)。完整的当前示例见 [应用配置](../app/src/main/assets/base_urls_config.json)。

## 跟着 Demo 理解环境与业务

Debug Demo 包含环境 id `dev`、`test`、`production`，以及业务键 `order`、`login`。显示名称和备注解释业务用途，网络查询仍使用原业务键。

| 演示内容 | 含义 | 主地址示例 |
| --- | --- | --- |
| Development（开发联调，dev） | 开发人员联调一整套服务 | order.dev.example.com、login.dev.example.com |
| Testing（测试验收，test） | 测试人员验收另一整套服务 | order.test.example.com、login.test.example.com |
| Production（生产环境，production） | 正式服务的地址示例 | order.example.com、login.example.com |
| 订单服务（order） | 同一个环境中，可单独选择订单主地址或备用地址 | order-backup.dev.example.com |
| 登录服务（login） | 与订单独立的业务地址 | login.dev.example.com |

1. **切换整套环境**：从首页进入管理页，选择 Testing（测试验收）并保存。首页 order 和 login 都变为 test 地址。只切换下拉框而不保存时，生效配置保持不变。
2. **只切换订单备用服务**：保持当前环境，选择 order 分组里的 backup 地址并保存。首页订单地址改变，登录地址保持不变。
3. **查看生产环境**：选择 Production（生产环境）并保存，order 变为 `https://order.example.com/`，login 变为 `https://login.example.com/`。选择 `https://order-backup.example.com/` 只切换订单备用服务。
4. **添加自定义候选并验证取消**：点击右上角“添加”，选择 order，填写 `https://order-local.example.com/` 和备注。新增候选默认不选中；点击卡片才能选择。返回并放弃草稿，首页地址和候选都保持原状。

这些域名只用于演示，不发送网络请求。已有安装会保留用户选择；改过备注或选过备用地址的组也不会被新版演示默认值覆盖。管理页的“重置”只将当前环境草稿恢复默认，保存后生效，会丢弃该环境的自定义候选。

从旧 Demo 升级时，可能先看到 `searchApiDomain` / `gitDomain` / `githubDomain`，这是旧数据保留在历史配置的结果。显式选择 dev、test 或 production 并保存后才能体验新案例，无须清除应用数据。Release 仅提供 production 默认环境。首次安装、旧版迁移、新版默认更新的区别见 [升级场景表](migration-1.1.md#升级后究竟使用哪一套地址)。

## Debug / Release 打包使用哪份配置

Demo 的地址和默认环境均由固定 JSON 提供；`App.kt` 只执行 `builder(this).build()`，没有硬编码 URL、`BuildConfig.DEBUG` 判断或代码配置开关。

| 构建 | 生效的源码配置文件 | APK 内文件 | 无已存数据时的默认值 | 包内环境 |
| --- | --- | --- | --- | --- |
| Debug | `app/src/main/assets/base_urls_config.json` | `assets/base_urls_config.json` | 文件中的 `defaultEnvironment: dev` | 开发、测试、生产 |
| Release | `app/src/release/assets/base_urls_config.json` 覆盖 main 同名文件 | `assets/base_urls_config.json` | 文件中的 `defaultEnvironment: production` | 仅生产 |

`bash ./gradlew :app:assembleRelease` 生成 Release APK。打包时按 Android 构建源集优先级选择同名 asset，整个 JSON 文件被替换，不会把两份 environments 拼接。签名不会决定配置来源。库 AAR 不包含宿主业务地址，也无法根据库自身的 Release 变体决定宿主环境。

生产案例使用 example.com 保留域名，不是真实线上服务。接入业务项目时编辑宿主 Release JSON 中的生产 URL，无需在 Kotlin 中逐条配置地址。继续使用旧 `debug_defaults` / `release_defaults` 固定数组格式也可以：未显式指定键时新版回退 `release_defaults`。若希望 Debug 自动使用另一套固定数据，可在 `src/debug/assets` 放同名文件，并将希望生效的地址放入 `release_defaults`；这个兼容键名不再代表实际构建类型。新项目推荐用带 `defaultEnvironment` 的新格式。不要把该字段直接加进无 schemaVersion 的旧数组格式。

**Release 打包只决定包内默认配置，不会清空设备数据。** 覆盖安装同 applicationId 的 Release 包时，新版快照或旧版迁移数据仍优先，原来选中的 dev/test/legacy 可以继续保留，已存环境也不会因从包内移除而被删除。只有无已存数据的首次初始化才默认 production。正式宿主若要求 Release 无条件锁定生产，需要另行定义运行与迁移策略，不能把这里的兼容保留规则理解成强制生产。
