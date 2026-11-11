package com.logan.multiurlmanager.compatibility;

import com.logan.multiurlmanager.library.BaseUrlManager;
import com.logan.multiurlmanager.okhttp.RouteContext;
import com.logan.multiurlmanager.okhttp.RouteKey;
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor;
import java.util.Map;
import okhttp3.HttpUrl;

public final class JavaCompatibility12 {
    private JavaCompatibility12() {}

    public static void verify() throws ReflectiveOperationException {
        RouteKey key = new RouteKey("order");
        HttpUrl url = HttpUrl.get("https://target.example/");
        RouteContext context = new RouteContext("production", 12L, key.getConfigKey(), url);
        RouteContext copy = context.copy("production", 12L, "order", url);
        if (!context.equals(copy) || !key.equals(key.copy("order"))) {
            throw new AssertionError("1.2.0 网络模型不兼容");
        }
        BaseUrlManager.class.getMethod("isReadOnly");
        BaseUrlManager.class.getMethod("businessName", String.class);
        BaseUrlManager.Companion.Builder.class.getMethod("setFixedEnvironment", String.class);
        BaseUrlManager.Companion.Builder.class.getMethod("setBusinessNames", Map.class);
        UrlRoutingInterceptor.class.getConstructor(BaseUrlManager.class, Map.class);
    }
}
