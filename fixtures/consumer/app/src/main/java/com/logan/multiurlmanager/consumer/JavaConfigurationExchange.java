package com.logan.multiurlmanager.consumer;

import com.logan.multiurlmanager.library.BaseUrlManager;
import com.logan.multiurlmanager.library.ImportInspectionResult;
import com.logan.multiurlmanager.library.ImportMode;
import com.logan.multiurlmanager.library.ImportResult;
import com.logan.multiurlmanager.library.UrlSnapshot;

import java.util.Collections;
import java.util.Set;

/** Java 宿主直接消费真实 AAR 的导出、检查与两种预览模式，并参与 R8 运行验收。 */
public final class JavaConfigurationExchange {
    private JavaConfigurationExchange() {}

    public static boolean verify(BaseUrlManager manager) {
        UrlSnapshot before = manager.snapshot();
        Set<String> ids = Collections.singleton(before.getEnvironmentId());
        String json = manager.exportConfiguration(ids);
        ImportInspectionResult inspection = manager.inspectImport(json);
        if (!(inspection instanceof ImportInspectionResult.Success)
                || ((ImportInspectionResult.Success) inspection).getEnvironments().size() != 1
                || !manager.knownBusinessKeys().contains("api")) {
            return false;
        }
        for (ImportMode mode : ImportMode.values()) {
            ImportResult result = manager.previewImport(json, before, ids, mode);
            if (!(result instanceof ImportResult.Ready)) return false;
            ImportResult.Ready ready = (ImportResult.Ready) result;
            if (!before.equals(ready.getDraft()) || !ready.getChanges().isEmpty()) return false;
        }
        ImportResult malformed = manager.previewImport("{", before, ids, ImportMode.MERGE);
        return malformed instanceof ImportResult.Failure
                && !((ImportResult.Failure) malformed).getMessage().isEmpty()
                && before.equals(manager.snapshot());
    }
}
