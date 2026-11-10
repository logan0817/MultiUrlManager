# 可选 OkHttp 动态路由（1.2.0 待发布）

本能力已进入 `1.2.0` 正式版本候选，尚未发布到 Maven Central。核心 `multiurlmanager` 不引入 OkHttp 或 Retrofit。只有选择网络适配时，才依赖 `io.github.logan0817:multiurlmanager-okhttp:1.2.0`；候选验证使用本轮文件 Maven 仓库。已发布的 1.1.0 不包含此制品。

## 接入

当前验证组合为 OkHttp / MockWebServer 4.12.0、Retrofit 2.11.0、Kotlin 1.9.23、JDK 17、最低 API 24；最终通过范围以开发验收记录为准，不承诺所有 OkHttp 大版本均兼容。网络模块依赖核心与 OkHttp；Retrofit 由宿主显式声明。

```kotlin
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.okhttp.RouteContext
import com.logan.multiurlmanager.okhttp.RouteKey
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Tag

interface OrderApi {
    @GET("orders")
    fun orders(@Tag route: RouteKey): Call<ResponseBody>
}

fun orderApi(manager: BaseUrlManager): OrderApi {
    val source = "https://bootstrap.example/api/"
    val client = OkHttpClient.Builder()
        .addInterceptor(UrlRoutingInterceptor(manager, mapOf("order" to source)))
        .build()
    return Retrofit.Builder().baseUrl(source).client(client).build().create(OrderApi::class.java)
}

// 宿主调用 orderApi(manager).orders(RouteKey("order"))，在自己的后台执行或 enqueue。
// 响应体使用 use / close；宿主管理 client 生命周期。
```

`source` 只是登记给路由的来源 Base URL。管理器初始化且当前环境已选中 `order` 后，执行请求时会换成选中的目标；配置缺失不会向 bootstrap 地址发送受管请求。现有 Service 可以继续复用。完整生命周期与鉴权示例见 [Kotlin](../app/src/main/java/com/logan/multiurlmanager/NetworkIntegrationExample.kt) 中的 `RoutingNetworkExample` 和 [Java](../app/src/main/java/com/logan/multiurlmanager/RoutingJavaIntegrationExample.java)。示例构造不发送请求。

## 路由规则

- 只处理带 `RouteKey` 的请求，无标记请求完全不修改，内部标记不会作为 HTTP header 发给服务器。
- 业务必须登记 source Base URL，并在当前快照有选中目标；未知业务、无选择、来源 origin 或路径不符均抛出 `IOException`，不偷偷回退旧地址。
- source / target 必须是无用户名密码、无 query / fragment 且末尾有 `/` 的 HTTP(S) Base URL。路径按编码段精确匹配，不使用字符串替换。
- 来源 `https://old.example/api/v1/`、请求 `api/v1/orders/42?detail=true`、目标 `https://new.example/gateway/` 得到 `https://new.example/gateway/orders/42?detail=true`。method、body、query 保留。
- Retrofit 的 `@GET("/orders")` 会绕开 Base URL 的路径，若不在登记前缀范围内则拒绝；第三方 `@Url` 不附加 `RouteKey`，或使用独立 client。
- 每个 Call 进入 application interceptor 时读取一次已提交快照。已创建但尚未进入的请求使用进入时的配置；已进入的请求固定原选址。后续切换不会重路由该 Call 的重试与重定向；重定向、401、TLS 与证书固定仍遵循宿主 OkHttp 配置。
- 仅候选或备注变化时，后续请求仍读到相同有效地址。未保存、保存失败或冲突不会改变生效配置；不依赖主线程监听分发来更新网络映射。

## 鉴权与请求上下文

路由为受管请求附加不可变 `RouteContext`，包含 `environmentId`、`revision`、`configKey`、`targetBaseUrl`。鉴权、签名等拦截器注册在路由之后，以该请求的上下文选凭据；不能再次独立查询管理器当前环境。

受管请求若已经携带 Authorization、Proxy-Authorization、Cookie、Host、Signature 或 Signature-Input 会明确拒绝，包括同 host 不同环境。宿主应在路由之后生成这些头；自定义签名和其他敏感头需要宿主自行声明并按相同上下文管理。CookieJar、Authenticator、登录态、业务缓存及取消策略属于宿主，库不提供自动凭据隔离。

同 host 的不同环境不能只依赖 CookieJar 的域名分组隔离。需要跨环境会话时，采用由请求上下文选凭据的宿主方案或独立 client；不要通过关闭 TLS 验证、放宽证书固定或共享旧 Cookie 绕过接入问题。

## 验证

网络模块的单元测试使用本机 MockWebServer，通过实际 Retrofit / OkHttp 调用核对目标、编码路径、query、body、鉴权一致性、延迟进入、在途切换、重定向和错误；不访问线上服务。设备测试另用真实管理器的 saveAsync、冲突、存储失败及固定模式驱动回环 HTTP，验证保存状态与请求的一致性。设备持久化、旧版升级、R8 消费分别由对应模块与消费工程验证，当前通过范围见[开发验收记录](development.md#120-正式版本候选准备2026-11-10)，不能用纯路由测试替代。
