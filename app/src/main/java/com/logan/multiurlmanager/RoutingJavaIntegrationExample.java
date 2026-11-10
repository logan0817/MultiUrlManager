package com.logan.multiurlmanager;

import com.logan.multiurlmanager.library.BaseUrlManager;
import com.logan.multiurlmanager.okhttp.RouteContext;
import com.logan.multiurlmanager.okhttp.RouteKey;
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor;
import java.util.Collections;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Tag;

/** Java 接入示例；调用方负责消费和关闭响应体，以及按自身生命周期管理 Client。 */
public final class RoutingJavaIntegrationExample implements AutoCloseable {
    private final OkHttpClient client;
    private final Service service;

    public RoutingJavaIntegrationExample(BaseUrlManager manager, Function<RouteContext, String> tokenFor) {
        String source = "https://bootstrap.example/api/";
        client = new OkHttpClient.Builder()
                .addInterceptor(new UrlRoutingInterceptor(manager, Collections.singletonMap("order", source)))
                .addInterceptor(chain -> {
                    RouteContext context = chain.request().tag(RouteContext.class);
                    if (context == null) {
                        throw new java.io.IOException("Missing route context");
                    }
                    // 以本次请求的固定环境取 token，不在这里再次读取 manager。
                    String token = tokenFor.apply(context);
                    okhttp3.Request.Builder request = chain.request().newBuilder();
                    if (token != null) {
                        request.header("Authorization", "Bearer " + token);
                    }
                    return chain.proceed(request.build());
                })
                .build();
        service = new Retrofit.Builder().baseUrl(source).client(client).build().create(Service.class);
    }

    public Call<ResponseBody> order() {
        return service.order(new RouteKey("order"));
    }

    @Override
    public void close() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    public interface Service {
        @GET("orders")
        Call<ResponseBody> order(@Tag RouteKey route);
    }
}
