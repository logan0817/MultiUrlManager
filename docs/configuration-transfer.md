# JSON 文件配置交换（1.3.0）

本功能从已发布的 `1.3.0` 开始提供，1.2.0 不包含这些入口。它用于开发、测试之间交换地址配置，不同步账号、token、Cookie 或业务缓存。

## 两台设备如何交换

1. 发送方在管理页菜单选择“导出配置”，勾选环境，通过系统文件保存器保存 JSON。导出的始终是已保存配置；页面草稿不会被自动保存或导出。
2. 用自己的文件传输方式把 JSON 交给同事。库不会主动上传或发送文件。
3. 接收方在管理页选择“导入配置”，选择 JSON 文件，再选择需要导入的环境和模式。默认使用合并。
4. 核对新增、删除、备注、选择以及保留本机内容的预览。确认后内容只进入草稿。
5. 点击管理页底部“保存”才生效。取消、文件错误或容量超限都保留原草稿与正式配置。

固定环境模式禁止导入，只允许导出当前固定环境。文件中的默认环境不会自动切换本机环境；显示名称映射和固定模式由宿主控制。

## 合并与替换

| 模式 | 行为 |
| --- | --- |
| 合并（默认） | 已有环境名称、候选、备注和选择保留，仅追加缺失候选。已有业务即使未选中，也不自动采用文件中的选择；真正新增的业务组或环境采用文件选择。 |
| 替换选定环境 | 替换所选环境的候选、备注和选择；已有环境 id、名称和必选约束保留。没有选中的其他环境不变。 |

环境按 id 匹配，地址按“业务键 + 规范化 URL”匹配。允许新增环境，但业务键必须已由包内配置或导入前的配置声明。文件不能通过新增环境自我声明未知业务，也不能减少宿主的必选业务约束。

替换可能删除本机候选，预览会列出删除项；预览期间其他调用方保存配置，必须重新加载并预览，不会原样覆盖。存储失败继续使用现有重新加载保护机制。

## 可交换文件

使用[可移植配置格式 v1](schemas/portable-configuration-v1.schema.json)，不包含本机 revision、内部 defaults 或存储路径。可从已保存配置导出，也可参考[示例文件](examples/team-development.json)；业务键需匹配接收方的项目。

```json
{
  "schemaVersion": 1,
  "defaultEnvironment": "team-dev",
  "environments": [
    {
      "id": "team-dev",
      "name": "Team development",
      "requiredKeys": ["order", "login"],
      "urls": [
        {"configKey": "order", "url": "https://order.dev.example.com/", "select": true},
        {"configKey": "login", "url": "https://login.dev.example.com/", "select": true}
      ]
    }
  ]
}
```

示例域名不提供真实接口；先验证配置变化，再按[网络路由指南](network-routing.md)验证真实请求。地址和备注可能包含团队内部信息，导出前核对所选环境；不要把凭据写入 URL 或备注。

## 容量和校验

- 文件最多 256 KiB UTF-8、32 个环境、1,000 个候选，读取超限即停止。
- 页面另外核对完整恢复 Bundle 的实际 Parcel 尺寸，预算 256 KiB，包含原始快照、合并后草稿、表单与展示状态；同时检查保存后重新打开时的“新原始快照 + 新草稿”。不是只检查文件大小，也不把系统其他组件的恢复数据当作已知固定开销。
- 错误类型、重复字段、非法 URL、重复候选、同组多选、未知业务或选定环境缺少必选选择时，整次导入失败，不截断或部分应用。
- 超限时减少选定环境或拆分文件；正式状态与原草稿不会被替换。这不是对核心查询和保存 API 新增的全局容量限制，也不保证所有宿主共享 Binder 预算均足够。
- 新格式严格校验字段类型；旧数组仅用于原初始化兼容，不作为团队导入格式。已存数据继续兼容读取。

## 生命周期

旋转时由页面状态持有正在读取的结果与草稿，避免持有旧 Activity。文件选择取消或提供器错误会明确结束操作；不自动重试写入。进程被系统回收后，未完成的文件操作可能需要重新选择；不能将未确认的写入报告为成功。

## 代码接入

在后台读取文件为 UTF-8 字符串后调用 `inspectImport` 获取环境清单，再按用户选择生成预览。以下函数只生成草稿，调用者必须在用户确认后才调用 `saveAsync`：

```kotlin
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.ImportMode
import com.logan.multiurlmanager.library.ImportResult

fun previewTeamConfiguration(
    manager: BaseUrlManager,
    json: String,
    environmentIds: Set<String>
): ImportResult {
    val draft = manager.snapshot()
    return manager.previewImport(json, draft, environmentIds, ImportMode.MERGE)
}
```

Java 使用 `manager.previewImport(json, manager.snapshot(), environmentIds, ImportMode.MERGE)`，通过 `instanceof ImportResult.Ready` / `ImportResult.Failure` 处理结果。`Ready.getDraft()` 仍带原 revision；不要自行更新 revision 绕过冲突。`exportConfiguration(environmentIds)` 返回已提交配置 JSON，非法或超限选择抛出异常，应在文件写入前处理。

`inspectImport` 和 `previewImport` 的失败原因包含 READ_ONLY、CONFLICT、VALIDATION、UNKNOWN_BUSINESS、LIMIT；它们独立于原来的 `SaveResult`，不改变旧调用方的穷尽分支。读取、解析和写文件放在后台；界面确认前还需验证草稿未改变并检查恢复数据容量。内置页面已经完成这些步骤。
