# API 与线程约定

入口类型位于 `com.logan.multiurlmanager.library`，候选地址 `BaseUrl` 位于 `.bean`。初始化持有 applicationContext；同一进程初始化一次。配置不同的重复初始化会失败，避免悄悄切换默认配置。库不提供多进程写入同步。

## 查询

| 入口 | 返回与约定 |
| --- | --- |
| `BaseUrlManager.instance` | 未初始化时为 null |
| `snapshot()` | 与内部状态隔离的深拷贝；revision 用于编辑冲突检测 |
| `getUrl(configKey)` | 当前环境选中地址的 String，未选中为 null |
| `getBaseUrl(configKey, defaultUrl)` | 保留旧 BaseUrl? 返回类型；显式 fallback 经过校验且不持久化 |
| `snapshot().effectiveUrls()` | 当前环境业务键到地址的映射 |
| `defaultEnvironment(id)` | 指定环境的默认候选副本；未知 id 失败 |

`UrlSnapshot` 记录 revision、environmentId 和全部 environments；`urls` 是当前环境候选。副本中的 BaseUrl 仍为兼容的可变对象，改副本后必须显式保存才会影响管理器。

## 保存与编辑

`save(candidate)` 校验整个快照、检查 revision、持久化后才更新内存。结果是 `SaveResult.Success(snapshot, changed)` 或 `SaveResult.Failure(reason, message)`；reason 为 VALIDATION、STORAGE、CONFLICT。无变化时 changed 为 false；存储失败时不报告成功，并暂停后续写入，需 `refreshData()` 成功重新读取或重启确认磁盘状态后再编辑。

```kotlin
val draft = manager.snapshot()
val next = draft.copy(environmentId = "production")
manager.saveAsync(next) { result ->
    when (result) {
        is SaveResult.Success -> render(result.snapshot)
        is SaveResult.Failure -> showError(result.reason.name)
    }
}
```

示例中的 `render` 和 `showError` 由宿主提供。页面使用同一个异步保存路径。冲突时保留用户草稿，重新加载后再编辑，不覆盖其他调用方的新状态。

`switchEnvironment(id)`、`select(configKey, url)`、`restoreDefaults()` 提供同步便捷操作。同步保存和旧写入 API 会访问磁盘，调用方应放在后台线程；UI 使用 `saveAsync`。旧 `addBaseURL`、`updateBaseURL`、`remove`、`clear` 保留签名，失败抛出异常，不能当作无条件成功。

`clear()` 清空所有环境候选并保留必选声明；下一次普通保存需补齐当前环境的必选键；其他清空环境可先在草稿切换并恢复后再保存。恢复当前环境默认值使用 `restoreDefaults()`。删除选中地址使该键未选择；若它属于 requiredKeys 则拒绝不完整保存。

## 变更监听

```kotlin
private val listener = UrlChangeListener { previous, current ->
    val changedKeys = (previous.effectiveUrls().keys + current.effectiveUrls().keys)
        .filter { previous.effectiveUrls()[it] != current.effectiveUrls()[it] }
    rebuildServices(changedKeys, current.effectiveUrls())
}
manager.addListener(listener)
// 生命周期结束
manager.removeListener(listener)
```

通知在主线程按提交顺序派发，一个整体提交产生一次完整前后状态。只有有效映射或当前环境变化才通知；新增未选择的历史候选不会触发网络配置通知。监听器异常会记录诊断并继续通知其他监听器。宿主负责移除监听器和更新网络对象。

## Java 与兼容入口

Builder 可从 `BaseUrlManager.builder(context)` 调用，Java 中通过 `BaseUrlManager.Companion.getInstance()` 获取实例。BaseUrl 的现有构造与 Parcelable 保留，实际 Java 入口见 [JavaIntegrationExample.java](../app/src/main/java/com/logan/multiurlmanager/JavaIntegrationExample.java)，真实 AAR 消费源码见 [消费 fixture](../fixtures/consumer)。Activity 的旧 `startBaseUrlManager` 与 `parseActivityResult` 继续可用，新入口见 [页面文档](ui-and-network.md)。

公开 `dynamicUrls` 已弃用：getter 返回深拷贝，直接修改不会生效；请转为快照加显式保存。详细行为变化见 [迁移说明](migration-1.1.md)。

## 已发布二进制兼容基线

已从 Maven Central 下载真实 1.0.6 AAR，并以 SHA-256 `cffa13b0bf3fb43c209621e135701d7925024ff83ca5354910ca8aafc790be9e` 固定身份。仓库 [ABI 基线](api-baseline-1.0.6.json) 覆盖 17 个公开类、105 个字段/方法；[check-api.py](../scripts/ci/check-api.py) 对比类/接口类型、父类型、成员名称、JVM descriptor 与 static 属性，当前 Release AAR 检查通过。

该门槛允许新增入口，禁止删除旧基线入口，CI 在生成本地 Maven 候选后执行。它不证明所有行为完全相同；可变集合、环境选择、保存与清空的有意行为变化仍以 [迁移说明](migration-1.1.md) 为准。基线不包含 BuildConfig、匿名类、编译器访问桥，也不代替实际旧宿主运行与 API 36 设备验收。
