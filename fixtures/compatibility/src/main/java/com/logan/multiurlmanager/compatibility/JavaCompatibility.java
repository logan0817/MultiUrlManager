package com.logan.multiurlmanager.compatibility;

import com.logan.multiurlmanager.library.BaseUrlManagerResult;
import com.logan.multiurlmanager.library.SaveResult;
import com.logan.multiurlmanager.library.UrlEnvironment;
import com.logan.multiurlmanager.library.UrlSnapshot;
import com.logan.multiurlmanager.library.bean.BaseUrl;
import java.util.Collections;

public final class JavaCompatibility {
    private JavaCompatibility() {}

    public static void verify() {
        BaseUrl url = new BaseUrl("api", "https://example.com/", true, "Java", 1L);
        UrlEnvironment env = new UrlEnvironment("prod", "Production", Collections.singletonList(url), Collections.emptySet());
        UrlSnapshot snapshot = new UrlSnapshot(3L, "prod", Collections.singletonList(env));
        UrlSnapshot copied = snapshot.copy(4L, snapshot.getEnvironmentId(), snapshot.getEnvironments());
        BaseUrlManagerResult result = new BaseUrlManagerResult("prod", 4L, true, copied.getUrls());
        SaveResult.Success success = new SaveResult.Success(copied, true);
        if (result.getRevision() != 4L || !success.getChanged()
                || !"https://example.com/".equals(copied.effectiveUrls().get("api"))) {
            throw new AssertionError("Java 发行接口不兼容");
        }
        for (SaveResult.Reason reason : SaveResult.Reason.values()) {
            switch (reason) {
                case VALIDATION:
                case STORAGE:
                case CONFLICT:
                    break;
                default:
                    throw new AssertionError("未知发行结果分支");
            }
        }
    }
}
