# 可选 OkHttp 动态路由（1.2.0）

本能力已随 `1.2.0` 发布到 Maven Central。核心 `multiurlmanager` 不引入 OkHttp 或 Retrofit。需要后续请求自动使用保存的地址时，添加 `multiurlmanager-okhttp`，再注册路由和请求标记。

## 接入前先完成核心配置

先按 [README 快速接入](../README.md#快速接入)创建 JSON 并初始化管理器，确认 `getUrl("order")` 能读到当前环境的地址。本页继续使用 `order` 业务键；如果你的配置使用其他键，登记来源和请求的 `RouteKey` 也要一起修改。

当前验证组合为 OkHttp 4.12.0、Retrofit 2.11.0、Kotlin 1.9.23、JDK 17、最低 API 24；构建前提见 README。网络模块依赖核心与 OkHttp，Retrofit 由宿主显式声明。

### 1. 添加依赖与网络权限

在宿主应用模块的 `build.gradle.kts` 添加网络制品。下面使用 Retrofit；已有兼容版本可沿用，直接使用 OkHttp 则无需添加 Retrofit：

```kotlin
dependencies {
    implementation("io.github.logan0817:multiurlmanager-okhttp:1.3.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
}
```

宿主配置 `mavenCentral()` 即可解析，无需本地 Maven 仓库。网络制品传递依赖核心与 OkHttp，核心单独使用时仍不引入网络框架。

在现有 `AndroidManifest.xml` 的 `<manifest>` 内、`<application>` 外添加网络权限；同时在现有 `<application>` 内注册下方示例页面，保留原 Application 和其他配置：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application>
        <activity
            android:name="com.example.demo.RoutingDemoActivity"
            android:exported="false" />
    </application>
</manifest>
```

### 2. 创建可发送请求的示例页面

新建 `RoutingDemoActivity.kt`，替换示例包名。页面内有两个按钮：一个真正异步发送订单请求，另一个打开管理页。Service 只在 `onCreate()` 创建一次，保存地址后可以复用。

```kotlin
package com.example.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.okhttp.RouteKey
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Tag

class RoutingDemoActivity : Activity() {
    private lateinit var client: OkHttpClient
    private lateinit var service: OrderApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = checkNotNull(BaseUrlManager.instance)
        val source = "https://bootstrap.example/api/"
        client = OkHttpClient.Builder()
            .addInterceptor(UrlRoutingInterceptor(manager, mapOf("order" to source)))
            .build()
        service = Retrofit.Builder().baseUrl(source).client(client).build()
            .create(OrderApi::class.java)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(Button(this@RoutingDemoActivity).apply {
                text = "Request orders"
                setOnClickListener { requestOrders() }
            })
            addView(Button(this@RoutingDemoActivity).apply {
                text = "Manage base URLs"
                setOnClickListener {
                    startActivity(Intent(this@RoutingDemoActivity, BaseUrlManagerActivity::class.java))
                }
            })
        })
    }

    private fun requestOrders() {
        service.orders(RouteKey("order")).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                Log.d("MultiUrlNetwork", "HTTP ${response.code()} url=${response.raw().request.url}")
                response.body()?.close()
                response.errorBody()?.close()
            }

            override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                Log.e("MultiUrlNetwork", "Request failed", error)
            }
        })
    }

    override fun onDestroy() {
        if (::client.isInitialized) {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        super.onDestroy()
    }
}

interface OrderApi {
    @GET("orders")
    fun orders(@Tag route: RouteKey): Call<ResponseBody>
}
```

从已有 Activity 的按钮打开示例页面：

```kotlin
startActivity(android.content.Intent(this, com.example.demo.RoutingDemoActivity::class.java))
```

`source` 是路由识别的来源前缀，必须与 Retrofit 的 `baseUrl` 一致；它不必是真实服务器。请求带上 `RouteKey("order")` 后，路由会按当前选中的订单 Base URL 替换来源，再保留 `orders` 这个相对路径。不要给不属于该来源的第三方请求加这个标记。

`orders(...)` 只创建 Call，**`enqueue(...)` 才真正异步发送请求**。示例打印 HTTP 状态码和实际请求 URL，不读取响应内容；成功与错误响应体都会关闭，网络失败走 `onFailure`。示例页面独占 client，销毁时取消请求并释放资源；接入自己项目的共享 client 时，按共享持有者的生命周期管理，不能照搬页面销毁逻辑关闭全局 client。

### 3. 保存配置后验证后续请求

先将 JSON 中的订单地址替换为自己的可访问服务；示例 `example.com` 域名不提供真实接口。若服务的 Base URL 包含 `/api/` 等路径前缀，保留该前缀，`@GET("orders")` 将接在它后面。使用本地 HTTP 服务时，还需满足宿主的 Android 明文网络策略；优先使用 HTTPS。

1. 运行 App，进入 `RoutingDemoActivity`，在 Logcat 用 `MultiUrlNetwork` 过滤。
2. 点击 **Request orders**，查看 HTTP 状态与实际 URL。按 README 原始配置拼接，生产地址应为 `https://order.example.com/orders`；换成自己的地址后，以自己的服务为准。
3. 点击 **Manage base URLs**，切到 **Development** 并保存，返回原示例页面。
4. 再次点击 **Request orders**。使用同一 Service，后续请求应改发开发地址；原始示例拼接为 `https://order.dev.example.com/orders`。
5. 再切到订单备用地址并保存、请求，检查目标随之改变。取消或保存失败时，后续请求目标应保持不变。

`HTTP 404` 等非 2xx 响应仍会进入 `onResponse`，应结合实际 URL 判断路由是否正确，再检查接口路径与服务响应。`onFailure` 则包含路由校验或 DNS、TLS、连接等失败；先看具体异常，不能把网络失败当作路由已验证。配置缺失不会向 bootstrap 地址偷偷发送受管请求。

有登录鉴权的项目还应阅读下方[鉴权与请求上下文](#鉴权与请求上下文)，再接入自己的凭据。完整鉴权与生命周期示例见 [Kotlin](../app/src/main/java/com/logan/multiurlmanager/NetworkIntegrationExample.kt) 中的 `RoutingNetworkExample` 和 [Java](../app/src/main/java/com/logan/multiurlmanager/RoutingJavaIntegrationExample.java)。

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
