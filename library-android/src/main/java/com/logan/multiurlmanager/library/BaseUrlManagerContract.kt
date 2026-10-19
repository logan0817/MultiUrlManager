package com.logan.multiurlmanager.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import com.logan.multiurlmanager.library.bean.BaseUrl

/** 保存成功时的结果；取消返回 null，urls 只包含当前环境已选中的地址。 */
data class BaseUrlManagerResult(
    val environmentId: String,
    val revision: Long,
    val changed: Boolean,
    val urls: List<BaseUrl>
)

/** 使用 Activity Result API 打开管理页，仅在成功保存后返回提交结果。 */
class BaseUrlManagerContract : ActivityResultContract<Bundle?, BaseUrlManagerResult?>() {
    override fun createIntent(context: Context, input: Bundle?): Intent =
        Intent(context, BaseUrlManagerActivity::class.java).apply { input?.let(::putExtras) }

    override fun parseResult(resultCode: Int, intent: Intent?): BaseUrlManagerResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return null
        }
        return BaseUrlManagerResult(
            intent.getStringExtra(BaseUrlManagerActivity.KEY_ENVIRONMENT_ID) ?: return null,
            intent.getLongExtra(BaseUrlManagerActivity.KEY_REVISION, 0),
            intent.getBooleanExtra(BaseUrlManagerActivity.KEY_CHANGED, false),
            BaseUrlManagerActivity.parseActivityResult(intent).orEmpty()
        )
    }
}
