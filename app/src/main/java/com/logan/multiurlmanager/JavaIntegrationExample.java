package com.logan.multiurlmanager;

import android.app.Activity;
import android.content.Intent;

import com.logan.multiurlmanager.library.BaseUrlManager;
import com.logan.multiurlmanager.library.BaseUrlManagerActivity;
import com.logan.multiurlmanager.library.bean.BaseUrl;

import java.util.List;

/** 由示例模块实际编译的 Java 兼容入口。 */
public final class JavaIntegrationExample {
    private JavaIntegrationExample() {
    }

    public static String orderUrl() {
        BaseUrlManager manager = BaseUrlManager.Companion.getInstance();
        return manager == null ? null : manager.getUrl("order");
    }

    public static void open(Activity activity, int requestCode) {
        BaseUrlManagerActivity.startBaseUrlManager(activity, requestCode);
    }

    public static List<BaseUrl> parse(Intent result) {
        return BaseUrlManagerActivity.parseActivityResult(result);
    }
}
