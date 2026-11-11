package com.logan.multiurlmanager.library

/** 文件交换的资源上限，不限制既有核心查询和保存 API。 */
object ConfigurationTransferLimits {
    const val MAX_BYTES: Int = 256 * 1024
    const val MAX_ENVIRONMENTS: Int = 32
    const val MAX_CANDIDATES: Int = 1000
}

enum class ImportMode {
    MERGE,
    REPLACE_ENVIRONMENTS
}

enum class ImportFailureReason {
    READ_ONLY,
    CONFLICT,
    VALIDATION,
    UNKNOWN_BUSINESS,
    LIMIT
}

data class ImportEnvironmentInfo(val id: String, val name: String, val candidateCount: Int)

sealed class ImportInspectionResult {
    data class Success(val environments: List<ImportEnvironmentInfo>) : ImportInspectionResult()
    data class Failure(val reason: ImportFailureReason, val message: String) : ImportInspectionResult()
}

enum class ImportChangeType {
    ENVIRONMENT_ADDED,
    CANDIDATE_ADDED,
    CANDIDATE_REMOVED,
    REMARK_CHANGED,
    SELECTION_CHANGED,
    LOCAL_PRESERVED
}

/** 预览展示数据只返回给调用方，不写日志；URL 和备注可能包含私有服务信息。 */
data class ImportChange(
    val type: ImportChangeType,
    val environmentId: String,
    val configKey: String? = null,
    val url: String? = null,
    val previous: String? = null,
    val current: String? = null
)

sealed class ImportResult {
    /** 确认后仍需显式 saveAsync；revision 保留本机草稿版本，不采用导入文件版本。 */
    data class Ready(val draft: UrlSnapshot, val changes: List<ImportChange>) : ImportResult()
    data class Failure(val reason: ImportFailureReason, val message: String) : ImportResult()
}
