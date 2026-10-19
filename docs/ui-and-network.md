# 页面与网络生效

库 manifest 注册 `BaseUrlManagerActivity`，默认不对外导出，使用 `BaseUrlManagerTheme` 和 adjustResize。宿主需要不同主题时覆盖该 Activity 的 `android:theme`，沿用现有颜色属性；不要开放 exported 作为调试入口。

## 编辑行为

环境切换、候选增删改、选择、恢复默认都进入页面草稿；保存成功后一次生效。取消或返回时，有变动会提示丢弃。删除当前选中项后不会默选其他服务器；必选键没有选择时保存失败。

底部主按钮为“保存”，统一提交整份草稿；右上角“添加”打开新增地址表单，菜单中保留重置入口。保存或重新加载期间禁用保存、添加与重置，避免重复操作；没有业务键时不允许添加，但仍可保存其他合法草稿变更。

切换环境时一次性重绑目标环境的候选列表，避免整批旧地址退场后新地址才入场造成短暂空白；同一环境内增删改仍使用差量更新。切换只更新草稿，保存后才生效。

点击地址卡片只改变选中状态，已有候选与业务分组保持原顺序，备注与创建时间保持不变。长按卡片可以编辑、删除或复制。添加与编辑使用独立表单，保留最近选择的业务键；同一业务键下不允许重复地址，编辑已有候选也不会移动位置。

页面保存期间防止重复提交，并在后台执行磁盘写入；失败时保留草稿。旋转和深浅色切换通过 ViewModel 保留草稿、表单输入与保存任务；草稿、表单、最近业务键和原始状态通过 savedInstanceState 保存，重建不重复提交。其他调用方提交新版本后，旧草稿保存会报冲突，页面可显式重载。

存储写入失败后，管理器会暂停后续保存，直到成功重新读取磁盘以确认状态。错误提示中的“重新加载”在后台读取存储，成功后放弃当前草稿并展示已确认状态；读取失败则保留草稿和保护状态，不会通过直接重试绕过存储确认。

配置尚未初始化、空候选、非法输入与保存失败都有提示。是否显示调试入口由宿主决定；生产环境建议按自己的构建与权限要求控制入口。

## 界面与系统栏

示例首页按当前环境和业务卡片展示生效地址，可滚动查看与复制。管理页采用统一的卡片、选择勾选与独立表单，并提供浅色和深色资源。横屏使用更紧凑的间距，为候选列表保留空间；表单可随键盘调整并滚动。

两个页面均处理 systemBars 与 displayCutout 的四侧安全区，并根据系统栏后方的实际背景设置图标明暗。API 24 无法使用深色导航按键，保留深色导航栏；较新系统使用透明系统栏。宿主覆盖库主题时，应提供成套的背景与前景颜色，并在深浅色下核对可读性。

管理页顶部状态栏安全区与 Toolbar 共用 `base_url_toolbar_bg_color`，形成连续的顶部背景；列表内容仍使用 `base_url_bg_color`。状态栏图标明暗依据 Toolbar 背景，导航栏图标明暗依据页面底色分别计算。宿主自定义深色 Toolbar、浅色页面时，两处图标也会分别适配。

## Activity Result

```kotlin
private val settings = registerForActivityResult(BaseUrlManagerContract()) { result ->
    if (result != null && result.changed) {
        refreshNetworkConfiguration()
    }
}
// 点击入口时
settings.launch(null)
```

`BaseUrlManagerResult` 包含 environmentId、revision、changed，以及当前环境已选中的 urls；取消返回 null。旧 requestCode 入口仍可使用，在 RESULT_OK 后调用 `BaseUrlManagerActivity.parseActivityResult(data)`。

传入 Bundle 可设置 `KEY_TITLE` 和 `KEY_REGEX`；正则作为额外地址约束，不能绕过 HTTP(S) 校验。实际示例见 [MainActivity.kt](../app/src/main/java/com/logan/multiurlmanager/MainActivity.kt)。

## 网络对象何时更新

框架保存的是配置。已经创建的 Retrofit 实例或 Service 不会自动修改 baseUrl。在监听或成功结果回调中获取 `snapshot().effectiveUrls()`，将完整映射交给宿主网络工厂，重建发生变化的 Service，再一次替换宿主持有的引用。

正在执行的请求继续使用创建请求时的 URL。跨环境 token、cookie、数据库与业务缓存由宿主处理，库不替宿主删除数据。不要在每次请求前重复初始化管理器，也不要依赖修改 `dynamicUrls` 触发网络更新。

核心不依赖网络框架。示例应用展示配置读取和监听；可编译的 [NetworkIntegrationExample.kt](../app/src/main/java/com/logan/multiurlmanager/NetworkIntegrationExample.kt) 使用 URI 和可替换 Service 映射演示整体更新，不会访问公网。真实认证、请求重试、在途取消策略需要接入项目自行决定。
