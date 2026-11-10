package com.logan.multiurlmanager.compatibility

import com.logan.multiurlmanager.library.BaseUrlManagerResult
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl

/** 同一源码分别对发行 1.1.0 与当前库编译；运行时始终加载当前 AAR。 */
fun main() {
    val url = BaseUrl("api", "https://example.com/", true)
    val environment = UrlEnvironment("prod", "Production", listOf(url))
    val snapshot = UrlSnapshot(7, "prod", listOf(environment))
    check(snapshot.copy().effectiveUrls()["api"] == url.url)
    check(environment.copy(name = "Live").requiredKeys.isEmpty())
    check(url.copy(remark = "copied").select)
    val result = BaseUrlManagerResult("prod", 7, true, listOf(url))
    check(result.copy(changed = false).revision == 7L)
    val successes = listOf<SaveResult>(SaveResult.Success(snapshot, true),
        SaveResult.Failure(SaveResult.Reason.VALIDATION, "invalid"),
        SaveResult.Failure(SaveResult.Reason.STORAGE, "storage"),
        SaveResult.Failure(SaveResult.Reason.CONFLICT, "conflict"))
    check(successes.map { value ->
        when (value) {
            is SaveResult.Success -> "success"
            is SaveResult.Failure -> when (value.reason) {
                SaveResult.Reason.VALIDATION -> "validation"
                SaveResult.Reason.STORAGE -> "storage"
                SaveResult.Reason.CONFLICT -> "conflict"
            }
        }
    } == listOf("success", "validation", "storage", "conflict"))
    JavaCompatibility.verify()
    println("Kotlin/Java 1.1.0 构造、copy、默认参数与穷尽结果分支消费通过")
}
