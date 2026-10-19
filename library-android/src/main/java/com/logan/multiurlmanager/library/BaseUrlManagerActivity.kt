package com.logan.multiurlmanager.library

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.bean.BaseUrlSection
import com.logan.multiurlmanager.library.internal.StateCodec
import com.logan.multiurlmanager.library.internal.UrlRules
import java.util.regex.Pattern

/** 页面只编辑草稿；磁盘提交由管理器串行执行。 */
class BaseUrlManagerActivity : AppCompatActivity() {
    /** 保留旋转期间的草稿和异步提交结果，避免重建页面时重复写入。 */
    class EditorState : ViewModel() {
        var draft: UrlSnapshot? = null
        var original: String? = null
        var saving = false
        var result: SaveResult? = null
        var observer: (() -> Unit)? = null
        var form: Bundle? = null
        var lastKey: String? = null
        var reloadResult: Result<UrlSnapshot>? = null
    }

    private val editor by lazy { ViewModelProvider(this)[EditorState::class.java] }
    private val manager get() = BaseUrlManager.instance
    private lateinit var listAdapter: BaseUrlAdapter
    private lateinit var environmentSpinner: Spinner
    private lateinit var status: TextView
    private var bindingSpinner = false
    private var customRegex: Pattern? = null
    private var configurationError = false
    private val dialogs = mutableSetOf<AlertDialog>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.base_url_manager_activity)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.base_url_title)
        val root = findViewById<View>(R.id.root_layout)
        val topBar = findViewById<View>(R.id.topBarContainer)
        configureSystemBars(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = 0, right = bars.right, bottom = bars.bottom)
            // 顶部安全区由 Toolbar 容器绘制，避免透明状态栏透出列表底色。
            topBar.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        environmentSpinner = findViewById(R.id.environmentSpinner)
        status = findViewById(R.id.statusText)
        listAdapter = BaseUrlAdapter(mutableListOf()).apply {
            sourceLabel = { value ->
                val defaults = editor.draft?.environmentId?.let { defaultEnvironment(it) }?.urls.orEmpty()
                getString(
                    when {
                        value.select && defaults.none {
                            it.configKey == value.configKey && it.url == value.url && it.select
                        } -> R.string.base_url_override
                        defaults.any {
                            it.configKey == value.configKey && it.url == value.url
                        } -> R.string.base_url_default
                        else -> R.string.base_url_custom
                    }
                )
            }
            onSelect = { value -> changeUrls { UrlRules.upsert(it, value.copy(select = true)) } }
            onActions = { _, value -> showActions(value) }
        }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@BaseUrlManagerActivity)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            adapter = listAdapter
        }
        if (editor.draft == null) {
            try {
                editor.draft = savedInstanceState?.getBundle(STATE_DRAFT)?.let(::restoreDraft) ?: manager?.snapshot()
                editor.original = savedInstanceState?.getString(STATE_ORIGINAL) ?: editor.draft?.let(StateCodec::encode)
            } catch (_: Exception) {
                // 损坏的恢复数据不能静默替换成已提交状态，避免用户误以为草稿仍在。
                configurationError = true
            }
        }
        try {
            intent.getStringExtra(KEY_REGEX)?.let { customRegex = Pattern.compile(it) }
        } catch (_: Exception) {
            configurationError = true
        }
        environmentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val draft = editor.draft ?: return
                val target = draft.environments.getOrNull(position)?.id ?: return
                if (!bindingSpinner && !editor.saving && target != draft.environmentId) {
                    editor.draft = draft.copy(environmentId = target)
                    renderUrls(environmentChanged = true)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        if (editor.form == null) {
            editor.form = savedInstanceState?.getBundle(STATE_FORM)
        }
        if (editor.lastKey == null) {
            editor.lastKey = savedInstanceState?.getString(STATE_KEY)
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelEditing()
        })
        render()
        if (!configurationError && editor.form != null) {
            showAddressForm(restoring = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars(root: View) {
        val topColor = TypedValue().also {
            theme.resolveAttribute(R.attr.base_url_toolbar_bg_color, it, true)
        }.data
        val bottomColor = TypedValue().also {
            theme.resolveAttribute(R.attr.base_url_bg_color, it, true)
        }.data
        window.statusBarColor = Color.TRANSPARENT
        // Android 7 不支持深色导航图标，使用深色背景保证系统按键可见。
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Color.TRANSPARENT
        } else {
            Color.rgb(23, 36, 61)
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(topColor) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(bottomColor) > 0.5
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onStart() {
        super.onStart()
        editor.observer = { consumeResult() }
        consumeResult()
    }

    override fun onStop() {
        // 暂停回调到页面；异步结果仍保存在 ViewModel，由下一次 onStart 消费。
        editor.observer = null
        super.onStop()
    }

    override fun onDestroy() {
        // 重建只释放旧窗口，表单状态由 ViewModel 保留；移除回调避免清空新页面的表单。
        dialogs.forEach { dialog ->
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        dialogs.clear()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        editor.draft?.let { outState.putBundle(STATE_DRAFT, storeDraft(it)) }
        outState.putString(STATE_ORIGINAL, editor.original)
        outState.putBundle(STATE_FORM, editor.form)
        outState.putString(STATE_KEY, editor.lastKey)
        super.onSaveInstanceState(outState)
    }

    private fun storeDraft(draft: UrlSnapshot) = Bundle().apply {
        putLong(KEY_REVISION, draft.revision)
        putString(KEY_ENVIRONMENT_ID, draft.environmentId)
        // Bundle 同时支持进程重建；不将尚未保存的草稿写入正式配置文件。
        putParcelableArrayList(
            "environments",
            ArrayList(draft.environments.map { env ->
                Bundle().apply {
                    putString("id", env.id)
                    putString("name", env.name)
                    putStringArrayList("requiredKeys", ArrayList(env.requiredKeys))
                    putParcelableArrayList("urls", ArrayList(env.urls))
                }
            })
        )
    }

    @Suppress("DEPRECATION")
    private fun restoreDraft(bundle: Bundle): UrlSnapshot {
        val environments = requireNotNull(bundle.getParcelableArrayList<Bundle>("environments")).map { env ->
            UrlEnvironment(
                requireNotNull(env.getString("id")),
                requireNotNull(env.getString("name")),
                requireNotNull(env.getParcelableArrayList<BaseUrl>("urls")),
                env.getStringArrayList("requiredKeys").orEmpty().toSet()
            )
        }
        return UrlSnapshot(
            bundle.getLong(KEY_REVISION),
            requireNotNull(bundle.getString(KEY_ENVIRONMENT_ID)),
            environments
        )
    }

    private fun normalized(value: BaseUrl): BaseUrl = UrlRules.normalize(value).also {
        require(customRegex?.matcher(it.url)?.matches() != false) { "Custom URL rule rejected the address" }
    }

    private fun render() {
        val draft = editor.draft
        // 绑定选项时产生的选择回调不能被当作用户切换环境。
        bindingSpinner = true
        environmentSpinner.adapter = spinnerAdapter(draft?.environments?.map { it.name }.orEmpty())
        environmentSpinner.setSelection(
            draft?.environments?.indexOfFirst { it.id == draft.environmentId }?.coerceAtLeast(0) ?: 0
        )
        bindingSpinner = false
        renderUrls()
    }

    private fun renderUrls(environmentChanged: Boolean = false) {
        val draft = editor.draft
        val environment = draft?.environments?.firstOrNull { it.id == draft.environmentId }
        val sections = mutableListOf<BaseUrlSection>()
        environment?.urls?.groupBy { it.configKey }?.forEach { (key, values) ->
            sections += BaseUrlSection(headerText = key)
            sections += values.map { BaseUrlSection(baseUrl = it.copy()) }
        }
        if (environmentChanged) {
            findViewById<RecyclerView>(R.id.recyclerView).itemAnimator?.endAnimations()
            listAdapter.replaceEnvironment(sections)
        } else {
            listAdapter.setNewInstance(sections)
        }
        status.text = getString(
            when {
                configurationError -> R.string.base_url_restore_error
                draft == null || manager == null -> R.string.base_url_not_initialized
                editor.saving -> R.string.base_url_saving
                sections.isEmpty() -> R.string.base_url_empty
                else -> R.string.base_url_actions_hint
            }
        )
        val enabled = !editor.saving && !configurationError && draft != null && manager != null
        environmentSpinner.isEnabled = enabled
        findViewById<View>(R.id.btnSave).isEnabled = enabled
        findViewById<RecyclerView>(R.id.recyclerView).isEnabled = enabled
        invalidateOptionsMenu()
    }

    private fun spinnerAdapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun changeUrls(update: (List<BaseUrl>) -> List<BaseUrl>): Boolean {
        if (editor.saving || configurationError) return false
        val draft = editor.draft ?: return false
        return try {
            val urls = update(draft.urls)
            // 只替换当前环境；其他环境的未保存修改继续保留在同一份草稿中。
            editor.draft = draft.copy(environments = draft.environments.map {
                if (it.id == draft.environmentId) it.copy(urls = urls) else it
            })
            renderUrls()
            true
        } catch (_: Exception) {
            showError(getString(R.string.base_url_invalid_url))
            false
        }
    }

    private fun showActions(value: BaseUrl) {
        if (editor.saving) return
        val actions = arrayOf(
            getString(R.string.base_url_edit),
            getString(R.string.base_url_delete),
            getString(R.string.base_url_copy)
        )
        AlertDialog.Builder(this)
            .setTitle(value.configKey)
            .setItems(actions) { _, action ->
                when (action) {
                    0 -> edit(value)
                    1 -> AlertDialog.Builder(this)
                        .setMessage(
                            if (value.select) R.string.base_url_delete_selected
                            else R.string.base_url_delete_confirm
                        )
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            changeUrls { urls ->
                                urls.filterNot {
                                    it.configKey == value.configKey && it.url == value.url
                                }
                            }
                        }
                        .create().showManaged()
                    2 -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(value.configKey, value.url))
                }
            }
            .create().showManaged()
    }

    private fun businessKeys(): List<String> {
        val environment = editor.draft?.environments?.firstOrNull { it.id == editor.draft?.environmentId }
        return (environment?.urls?.map { it.configKey }.orEmpty() + environment?.requiredKeys.orEmpty()).distinct()
    }

    private fun edit(value: BaseUrl) = showAddressForm(value)

    @Suppress("DEPRECATION")
    private fun showAddressForm(value: BaseUrl? = null, restoring: Boolean = false) {
        if (editor.saving || configurationError) return
        val keys = businessKeys()
        if (keys.isEmpty()) return
        val state = if (restoring) {
            editor.form ?: return
        } else {
            Bundle().apply {
                putParcelable("original", value)
                putString("key", value?.configKey ?: editor.lastKey ?: keys.first())
                putString("url", value?.url.orEmpty())
                putString("remark", value?.remark.orEmpty())
            }
        }
        editor.form = state
        val original = state.getParcelable<BaseUrl>("original")
        val form = layoutInflater.inflate(R.layout.base_url_address_form, null)
        val key = form.findViewById<Spinner>(R.id.configKeySpinner)
        val url = form.findViewById<EditText>(R.id.etUrl)
        val remark = form.findViewById<EditText>(R.id.etRemark)
        key.adapter = spinnerAdapter(keys)
        key.setSelection(keys.indexOf(state.getString("key")).coerceAtLeast(0))
        key.isEnabled = original == null
        url.setText(state.getString("url"))
        remark.setText(state.getString("remark"))
        key.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                state.putString("key", keys[position])
                editor.lastKey = keys[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        fun retainInput(input: EditText, name: String) {
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    state.putString(name, s.toString())
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        retainInput(url, "url")
        retainInput(remark, "remark")
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (original == null) R.string.base_url_add_address else R.string.base_url_edit)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (original == null) R.string.base_url_add else R.string.base_url_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited = try {
                    normalized(
                        (original ?: BaseUrl(key.selectedItem.toString(), "")).copy(
                            url = url.text.toString(),
                            remark = remark.text.toString()
                        )
                    )
                } catch (_: Exception) {
                    url.error = getString(R.string.base_url_invalid_url)
                    return@setOnClickListener
                }
                val duplicate = editor.draft?.urls.orEmpty().any {
                    it.configKey == edited.configKey && it.url == edited.url &&
                        (original == null || it.url != original.url)
                }
                if (duplicate) {
                    url.error = getString(R.string.base_url_duplicate)
                    return@setOnClickListener
                }
                if (changeUrls { urls ->
                    if (original == null) {
                        UrlRules.upsert(urls, edited)
                    } else {
                        urls.map {
                            if (it.configKey == original.configKey && it.url == original.url) edited
                            else it.copy()
                        }
                    }
                }) {
                    dialog.dismiss()
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.showManaged {
            // 用户主动关闭后不应在重建时重新弹出；销毁页面时不会调用此回调。
            editor.form = null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.base_url_top_app_bar_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val enabled = editor.draft != null && manager != null && !editor.saving && !configurationError
        menu.findItem(R.id.actionAdd)?.isEnabled = enabled && businessKeys().isNotEmpty()
        menu.findItem(R.id.actionReset)?.isEnabled =
            enabled && editor.draft?.environmentId?.let { defaultEnvironment(it) } != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            cancelEditing()
            true
        }
        R.id.actionAdd -> {
            showAddressForm()
            true
        }
        R.id.actionReset -> {
            editor.draft?.let { draft ->
                AlertDialog.Builder(this)
                    .setMessage(R.string.base_url_reset_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val defaults = defaultEnvironment(draft.environmentId) ?: return@setPositiveButton
                        editor.draft = draft.copy(environments = draft.environments.map {
                            if (it.id == defaults.id) defaults else it
                        })
                        renderUrls()
                    }
                    .create().showManaged()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun defaultEnvironment(id: String): UrlEnvironment? = try {
        manager?.defaultEnvironment(id)
    } catch (_: NoSuchElementException) {
        null
    }

    private fun save() {
        if (editor.saving) return
        val draft = editor.draft ?: return
        val currentManager = manager ?: return
        editor.saving = true
        renderUrls()
        // 只捕获 ViewModel，重建时不会持有旧 Activity 或再次提交。
        val state = editor
        currentManager.saveAsync(draft) { result ->
            state.saving = false
            state.result = result
            state.observer?.invoke()
        }
    }

    private fun consumeResult() {
        editor.reloadResult?.let { result ->
            editor.reloadResult = null
            result.onSuccess {
                editor.draft = it
                editor.original = StateCodec.encode(it)
                render()
            }.onFailure { showStorageError() }
        }
        val result = editor.result ?: return
        editor.result = null
        renderUrls()
        when (result) {
            is SaveResult.Success -> {
                val selected = ArrayList(result.snapshot.urls.filter { it.select })
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putParcelableArrayListExtra(KEY_URLS_INFO, selected)
                        putExtra(KEY_ENVIRONMENT_ID, result.snapshot.environmentId)
                        putExtra(KEY_REVISION, result.snapshot.revision)
                        putExtra(KEY_CHANGED, result.changed)
                    }
                )
                finish()
            }
            is SaveResult.Failure -> {
                if (result.reason == SaveResult.Reason.CONFLICT) {
                    // 不用旧草稿覆盖并发提交；由用户决定是否载入最新配置。
                    AlertDialog.Builder(this)
                        .setMessage(R.string.base_url_conflict)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.base_url_reload) { _, _ ->
                            editor.draft = manager?.snapshot()
                            editor.original = editor.draft?.let(StateCodec::encode)
                            render()
                        }
                        .create().showManaged()
                } else if (result.reason == SaveResult.Reason.STORAGE) {
                    showStorageError()
                } else {
                    showError(getString(R.string.base_url_validation_error))
                }
            }
        }
    }

    private fun showStorageError() {
        renderUrls()
        AlertDialog.Builder(this)
            .setMessage(R.string.base_url_storage_error)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.base_url_reload) { _, _ ->
                val currentManager = manager ?: return@setPositiveButton
                editor.saving = true
                renderUrls()
                val state = editor
                currentManager.reloadAsync { result ->
                    state.saving = false
                    state.reloadResult = result
                    state.observer?.invoke()
                }
            }
            .create().showManaged()
    }

    private fun cancelEditing() {
        if (editor.saving) return
        val dirty = editor.draft?.let(StateCodec::encode) != editor.original
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.base_url_discard_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.base_url_discard) { _, _ -> finish() }
            .create().showManaged()
    }

    private fun AlertDialog.showManaged(onDismiss: () -> Unit = {}) {
        dialogs.add(this)
        setOnDismissListener {
            if (dialogs.remove(this)) onDismiss()
        }
        show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create().showManaged()
    }

    companion object {
        private const val STATE_DRAFT = "draft"
        private const val STATE_ORIGINAL = "original"
        private const val STATE_FORM = "form"
        private const val STATE_KEY = "businessKey"
        const val KEY_TITLE = "key_title"
        const val KEY_REGEX = "key_regex"
        const val HTTP_URL_REGEX = "^https?://.+"
        const val KEY_URLS_INFO = "key_urls_info"
        const val KEY_ENVIRONMENT_ID = "environment_id"
        const val KEY_REVISION = "revision"
        const val KEY_CHANGED = "changed"

        @JvmStatic
        @JvmOverloads
        fun startBaseUrlManager(activity: Activity, requestCode: Int, bundle: Bundle? = null) {
            activity.startActivityForResult(
                Intent(activity, BaseUrlManagerActivity::class.java).apply { bundle?.let(::putExtras) },
                requestCode
            )
        }

        @JvmStatic
        @JvmOverloads
        fun startBaseUrlManager(fragment: Fragment, requestCode: Int, bundle: Bundle? = null) {
            val context = fragment.context ?: return
            fragment.startActivityForResult(
                Intent(context, BaseUrlManagerActivity::class.java).apply { bundle?.let(::putExtras) },
                requestCode
            )
        }

        @Suppress("DEPRECATION")
        @JvmStatic
        fun parseActivityResult(data: Intent?): List<BaseUrl>? = data?.getParcelableArrayListExtra(KEY_URLS_INFO)
    }
}
