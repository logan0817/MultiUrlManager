package com.logan.multiurlmanager.library.internal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.ViewModel
import com.logan.multiurlmanager.library.ConfigurationTransferLimits
import com.logan.multiurlmanager.library.ImportEnvironmentInfo
import com.logan.multiurlmanager.library.ImportMode
import com.logan.multiurlmanager.library.ImportResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/** 文件内容仅留在 ViewModel，避免把大 JSON 放入 Binder 恢复数据。 */
internal class ConfigurationTransferState : ViewModel() {
    enum class Stage { IDLE, IMPORT_PICKER, EXPORT_PICKER, IMPORT_SELECTION, EXPORT_SELECTION, PREVIEW, WORKING }

    sealed class Outcome {
        data class Loaded(val json: String, val environments: List<ImportEnvironmentInfo>) : Outcome()
        data class Preview(val result: ImportResult, val base: String, val revision: Long) : Outcome()
        data class Export(val json: String) : Outcome()
        data class Notice(val resource: Int, val detail: String? = null) : Outcome()
    }

    var stage = Stage.IDLE
    var initialized = false
    var json: String? = null
    var environments = emptyList<ImportEnvironmentInfo>()
    var selectedIds = linkedSetOf<String>()
    var mode = ImportMode.MERGE
    var preview: ImportResult.Ready? = null
    var previewBase: String? = null
    var previewRevision = 0L
    var exportJson: String? = null
    var outcome: Outcome? = null
    var observer: (() -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val busy: Boolean get() = stage == Stage.WORKING || stage == Stage.IMPORT_PICKER || stage == Stage.EXPORT_PICKER

    // task 只捕获 application ContentResolver、管理器和输入值，不捕获 Activity。
    fun submit(errorResource: Int, task: () -> Outcome) {
        outcome = null
        stage = Stage.WORKING
        worker.execute {
            val result = try {
                task()
            } catch (_: Exception) {
                Outcome.Notice(errorResource)
            }
            main.post {
                outcome = result
                observer?.invoke()
            }
        }
    }

    fun clear() {
        stage = Stage.IDLE
        json = null
        environments = emptyList()
        selectedIds.clear()
        preview = null
        previewBase = null
        exportJson = null
    }

    override fun onCleared() {
        observer = null
        worker.shutdown()
    }
}

internal object ConfigurationTransferUi {
    const val MAX_RESTORATION_BYTES = 256 * 1024

    fun readUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val remaining = ConfigurationTransferLimits.MAX_BYTES - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count == -1) break
            require(count <= remaining) { "Import file exceeds the size limit" }
            output.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    fun fitsRestorationBudget(state: Bundle): Boolean {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.dataSize() <= MAX_RESTORATION_BYTES
        } finally {
            parcel.recycle()
        }
    }
}
