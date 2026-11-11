package com.logan.multiurlmanager.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
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
import androidx.activity.result.contract.ActivityResultContracts
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
import com.logan.multiurlmanager.library.internal.ConfigurationTransferState
import com.logan.multiurlmanager.library.internal.ConfigurationTransferUi
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
        var collapsedGroups = mutableSetOf<String>()
        var summaryExpanded = false
        var invalidKey: String? = null
        var presentationRestored = false
        var knownKeys = linkedSetOf<String>()
    }

    private val editor by lazy { ViewModelProvider(this)[EditorState::class.java] }
    private val transfer by lazy { ViewModelProvider(this)[ConfigurationTransferState::class.java] }
    private var transferDialog: AlertDialog? = null
    private val manager get() = BaseUrlManager.instance
    private lateinit var listAdapter: BaseUrlAdapter
    private lateinit var environmentSpinner: Spinner
    private lateinit var status: TextView
    private var bindingSpinner = false
    private var customRegex: Pattern? = null
    private var originalSnapshot: UrlSnapshot? = null
    private var configurationError = false
    private val dialogs = mutableSetOf<AlertDialog>()
    private val configurationListener = UrlChangeListener { _, _ -> renderUrls() }

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            transfer.clear()
            showError(getString(R.string.base_url_transfer_cancelled))
        } else {
            val currentManager = manager
            if (currentManager == null || currentManager.isReadOnly) {
                transfer.clear()
                showError(getString(R.string.base_url_read_only))
            } else {
                val resolver = applicationContext.contentResolver
                transfer.submit(R.string.base_url_import_read_error) {
                    val json = requireNotNull(resolver.openInputStream(uri)).use(ConfigurationTransferUi::readUtf8)
                    when (val result = currentManager.inspectImport(json)) {
                        is ImportInspectionResult.Success -> ConfigurationTransferState.Outcome.Loaded(json, result.environments)
                        is ImportInspectionResult.Failure -> ConfigurationTransferState.Outcome.Notice(
                            R.string.base_url_import_invalid, result.message
                        )
                    }
                }
            }
        }
        renderUrls()
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = transfer.exportJson
        if (uri == null) {
            transfer.clear()
            showError(getString(R.string.base_url_transfer_cancelled))
        } else if (json == null) {
            transfer.clear()
            showError(getString(R.string.base_url_transfer_restart))
        } else {
            val resolver = applicationContext.contentResolver
            transfer.submit(R.string.base_url_export_write_error) {
                requireNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                ConfigurationTransferState.Outcome.Notice(R.string.base_url_export_success)
            }
        }
        renderUrls()
    }

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
            groupTitle = { key -> businessTitle(key) }
            groupSummary = { key ->
                val selected = editor.draft?.urls?.firstOrNull { it.configKey == key && it.select }?.url
                val value = selected ?: getString(R.string.base_url_no_selection)
                getString(
                    if (groupId(key) in editor.collapsedGroups) R.string.base_url_group_collapsed
                    else R.string.base_url_group_expanded,
                    value
                )
            }
            isGroupCollapsed = { key -> groupId(key) in editor.collapsedGroups }
            onToggleGroup = { key ->
                val id = groupId(key)
                if (!editor.collapsedGroups.remove(id)) editor.collapsedGroups.add(id)
                renderUrls()
            }
        }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@BaseUrlManagerActivity)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            adapter = listAdapter
        }
        if (editor.draft == null) {
            try {
                // 固定模式不能恢复普通模式遗留的草稿，否则只读页面会展示不生效的地址。
                editor.draft = if (manager?.isReadOnly == true) manager?.snapshot()
                    else savedInstanceState?.getBundle(STATE_DRAFT)?.let(::restoreDraft) ?: manager?.snapshot()
                editor.original = if (manager?.isReadOnly == true) editor.draft?.let(StateCodec::encode)
                    else savedInstanceState?.getString(STATE_ORIGINAL) ?: editor.draft?.let(StateCodec::encode)
            } catch (_: Exception) {
                // 损坏的恢复数据不能静默替换成已提交状态，避免用户误以为草稿仍在。
                configurationError = true
            }
        }
        try {
            originalSnapshot = editor.original?.let(StateCodec::decode)
        } catch (_: Exception) {
            configurationError = true
        }
        if (!editor.presentationRestored) {
            editor.collapsedGroups.addAll(savedInstanceState?.getStringArrayList(STATE_COLLAPSED).orEmpty())
            editor.summaryExpanded = savedInstanceState?.getBoolean(STATE_SUMMARY) ?: false
            editor.invalidKey = savedInstanceState?.getString(STATE_INVALID_KEY)
            editor.knownKeys.addAll(savedInstanceState?.getStringArrayList(STATE_KNOWN_KEYS).orEmpty())
            editor.presentationRestored = true
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
                if (!bindingSpinner && !editor.saving && !transfer.busy && manager?.isReadOnly != true && target != draft.environmentId) {
                    editor.draft = draft.copy(environmentId = target)
                    editor.invalidKey = null
                    renderUrls(environmentChanged = true)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        if (editor.form == null && manager?.isReadOnly != true) {
            editor.form = savedInstanceState?.getBundle(STATE_FORM)
        }
        if (editor.lastKey == null) {
            editor.lastKey = savedInstanceState?.getString(STATE_KEY)
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.changeSummaryToggle)?.setOnClickListener {
            editor.summaryExpanded = !editor.summaryExpanded
            renderUrls()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelEditing()
        })
        if (!transfer.initialized) {
            transfer.initialized = true
            if (savedInstanceState?.getBoolean(STATE_TRANSFER_PENDING) == true) {
                transfer.outcome = ConfigurationTransferState.Outcome.Notice(R.string.base_url_transfer_restart)
            }
        }
        render()
        if (!configurationError && editor.form != null && manager?.isReadOnly != true) {
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
        manager?.addListener(configurationListener)
        editor.observer = { consumeResult() }
        consumeResult()
        transfer.observer = { consumeTransfer() }
        consumeTransfer()
        renderUrls()
    }

    override fun onStop() {
        // 暂停回调到页面；异步结果仍保存在 ViewModel，由下一次 onStart 消费。
        editor.observer = null
        transfer.observer = null
        manager?.removeListener(configurationListener)
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
        writeEditorState(outState, editor.draft)
        super.onSaveInstanceState(outState)
    }

    private fun writeEditorState(outState: Bundle, draft: UrlSnapshot?) {
        draft?.let { outState.putBundle(STATE_DRAFT, storeDraft(it)) }
        outState.putString(STATE_ORIGINAL, editor.original)
        outState.putBundle(STATE_FORM, editor.form)
        outState.putString(STATE_KEY, editor.lastKey)
        outState.putStringArrayList(STATE_COLLAPSED, ArrayList(editor.collapsedGroups))
        outState.putStringArrayList(STATE_KNOWN_KEYS, ArrayList(editor.knownKeys))
        outState.putBoolean(STATE_SUMMARY, editor.summaryExpanded)
        outState.putString(STATE_INVALID_KEY, editor.invalidKey)
        outState.putBoolean(STATE_TRANSFER_PENDING, transfer.stage != ConfigurationTransferState.Stage.IDLE)
    }

    private fun fitsImportBudget(draft: UrlSnapshot): Boolean {
        val state = Bundle()
        writeEditorState(state, draft)
        val hierarchy = SparseArray<Parcelable>()
        window.decorView.saveHierarchyState(hierarchy)
        state.putSparseParcelableArray("viewHierarchy", hierarchy)
        if (!ConfigurationTransferUi.fitsRestorationBudget(state)) return false
        // 保存后重新打开时，原始快照也会变为导入后的大配置；必须同时覆盖这份恢复状态。
        state.putString(STATE_ORIGINAL, StateCodec.encode(draft))
        return ConfigurationTransferUi.fitsRestorationBudget(state)
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
        businessKeys().forEach { key ->
            sections += BaseUrlSection(headerText = key)
            if (groupId(key) !in editor.collapsedGroups) {
                sections += environment?.urls.orEmpty().filter { it.configKey == key }
                    .map { BaseUrlSection(baseUrl = it.copy()) }
            }
        }
        if (environmentChanged) {
            findViewById<RecyclerView>(R.id.recyclerView).itemAnimator?.endAnimations()
            listAdapter.replaceEnvironment(sections)
        } else {
            listAdapter.setNewInstance(sections)
        }
        listAdapter.refreshGroupHeaders()
        val current = manager?.snapshot()
        val label = if (manager?.isReadOnly == true) R.string.base_url_fixed_environment else R.string.base_url_draft_environment
        findViewById<TextView>(R.id.draftEnvironmentLabel)?.setText(label)
        environmentSpinner.contentDescription = getString(label)
        findViewById<TextView>(R.id.activeEnvironmentText)?.text = getString(
            R.string.base_url_active_environment, current?.let(::environmentName).orEmpty()
        )
        val statusMessage = when {
            configurationError -> R.string.base_url_restore_error
            draft == null || manager == null -> R.string.base_url_not_initialized
            editor.saving -> R.string.base_url_saving
            transfer.busy -> R.string.base_url_transfer_working
            manager?.isReadOnly == true -> R.string.base_url_read_only
            sections.isEmpty() -> R.string.base_url_empty
            else -> R.string.base_url_actions_hint
        }
        status.setText(statusMessage)
        status.visibility = if (statusMessage == R.string.base_url_actions_hint &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) View.GONE else View.VISIBLE
        val ready = !editor.saving && !transfer.busy && !configurationError && draft != null && manager != null
        val enabled = ready && manager?.isReadOnly != true
        environmentSpinner.isEnabled = enabled
        findViewById<View>(R.id.btnSave).isEnabled = enabled
        val invalid = editor.invalidKey?.takeIf { key -> environment?.urls?.none { it.configKey == key && it.select } == true }
        findViewById<TextView>(R.id.validationText)?.apply {
            visibility = if (invalid == null) View.GONE else View.VISIBLE
            text = invalid?.let { getString(R.string.base_url_missing_required, businessTitle(it)) }.orEmpty()
        }
        val baseline = originalSnapshot
        val changes = if (!configurationError && draft != null && baseline != null) {
            changeSummary(baseline, draft)
        } else emptyList()
        val summaryToggle = findViewById<TextView>(R.id.changeSummaryToggle)
        val summaryText = findViewById<TextView>(R.id.changeSummaryText)
        val summaryScroll = findViewById<View>(R.id.changeSummaryScroll)
        // 旧宿主覆盖布局时仍可使用原地址列表，不能因为缺少新摘要区域而变成空白。
        if (changes.isEmpty() || summaryToggle == null || summaryText == null || summaryScroll == null) {
            editor.summaryExpanded = false
        }
        summaryToggle?.apply {
            text = if (changes.isEmpty()) getString(R.string.base_url_no_changes)
                else if (editor.summaryExpanded) resources.getQuantityString(
                    R.plurals.base_url_hide_changes, changes.size, changes.size
                ) else getString(R.string.base_url_review_changes, changes.size)
            isEnabled = ready && changes.isNotEmpty()
            isFocusable = changes.isNotEmpty()
        }
        summaryText?.text = changes.joinToString("\n\n")
        summaryScroll?.visibility = if (editor.summaryExpanded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recyclerView).visibility = if (editor.summaryExpanded) View.GONE else View.VISIBLE
        invalidateOptionsMenu()
    }

    private fun environmentName(snapshot: UrlSnapshot): String =
        snapshot.environments.firstOrNull { it.id == snapshot.environmentId }?.name ?: snapshot.environmentId

    private fun businessTitle(key: String): String {
        val name = manager?.businessName(key) ?: key
        return if (name == key) key else "$name · $key"
    }

    private fun groupId(key: String): String = "${editor.draft?.environmentId?.length}:${editor.draft?.environmentId}$key"

    /** 对照进入编辑时的完整状态，包含当前未展示环境的候选和备注修改。 */
    private fun changeSummary(previous: UrlSnapshot, next: UrlSnapshot): List<String> = buildList {
        if (previous.environmentId != next.environmentId) {
            add(getString(R.string.base_url_change_environment, environmentName(previous), environmentName(next)))
        }
        next.environments.forEach { environment ->
            val old = previous.environments.firstOrNull { it.id == environment.id }
            val oldUrls = old?.urls.orEmpty()
            val location = getString(R.string.base_url_change_location, environment.name)
            val keys = (oldUrls.map { it.configKey } + environment.urls.map { it.configKey }).distinct()
            keys.forEach { key ->
                val before = oldUrls.firstOrNull { it.configKey == key && it.select }?.url
                val after = environment.urls.firstOrNull { it.configKey == key && it.select }?.url
                if (before != after) add(getString(
                    R.string.base_url_change_selection, location, businessTitle(key),
                    before ?: getString(R.string.base_url_no_selection), after ?: getString(R.string.base_url_no_selection)
                ))
            }
            environment.urls.forEach { value ->
                val before = oldUrls.firstOrNull { it.configKey == value.configKey && it.url == value.url }
                if (before == null) add(getString(R.string.base_url_change_added, location, businessTitle(value.configKey), value.url))
                else if (before.remark != value.remark) add(getString(
                    R.string.base_url_change_remark, location, businessTitle(value.configKey), value.url,
                    before.remark.orEmpty(), value.remark.orEmpty()
                ))
            }
            oldUrls.filter { value -> environment.urls.none { it.configKey == value.configKey && it.url == value.url } }
                .forEach { add(getString(R.string.base_url_change_removed, location, businessTitle(it.configKey), it.url)) }
        }
    }

    private fun spinnerAdapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun changeUrls(update: (List<BaseUrl>) -> List<BaseUrl>): Boolean {
        if (editor.saving || transfer.busy || configurationError || manager?.isReadOnly == true) return false
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
        if (editor.saving || transfer.busy) return
        if (manager?.isReadOnly == true) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(value.configKey, value.url))
            return
        }
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
        editor.knownKeys.addAll(manager?.knownBusinessKeys().orEmpty())
        editor.knownKeys.addAll(environment?.urls?.map { it.configKey }.orEmpty())
        editor.knownKeys.addAll(environment?.requiredKeys.orEmpty())
        return (environment?.urls?.map { it.configKey }.orEmpty() + environment?.requiredKeys.orEmpty() + editor.knownKeys).distinct()
    }

    private fun edit(value: BaseUrl) = showAddressForm(value)

    @Suppress("DEPRECATION")
    private fun showAddressForm(value: BaseUrl? = null, restoring: Boolean = false) {
        if (editor.saving || transfer.busy || configurationError || manager?.isReadOnly == true) return
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
        key.adapter = spinnerAdapter(keys.map(::businessTitle))
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
                        (original ?: BaseUrl(keys[key.selectedItemPosition], "")).copy(
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
        val enabled = editor.draft != null && manager != null && !editor.saving && !transfer.busy && !configurationError && manager?.isReadOnly != true
        menu.findItem(R.id.actionAdd)?.isEnabled = enabled && businessKeys().isNotEmpty()
        menu.findItem(R.id.actionReset)?.isEnabled =
            enabled && editor.draft?.environmentId?.let { defaultEnvironment(it) } != null
        menu.findItem(R.id.actionImport)?.isEnabled = enabled
        menu.findItem(R.id.actionExport)?.isEnabled = editor.draft != null && manager != null && !editor.saving && !transfer.busy && !configurationError
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
        R.id.actionImport -> {
            launchImport()
            true
        }
        R.id.actionExport -> {
            beginExport()
            true
        }
        R.id.actionReset -> {
            editor.draft?.takeIf { manager?.isReadOnly != true }?.let { draft ->
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

    private fun launchImport() {
        if (editor.saving || transfer.busy || configurationError || manager?.isReadOnly != false) return
        transfer.clear()
        transfer.stage = ConfigurationTransferState.Stage.IMPORT_PICKER
        try {
            importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            transfer.clear()
            showError(getString(R.string.base_url_transfer_provider_missing))
        }
        renderUrls()
    }

    private fun beginExport() {
        if (editor.saving || transfer.busy || configurationError) return
        val snapshot = manager?.snapshot() ?: return
        transfer.clear()
        transfer.environments = snapshot.environments.map { ImportEnvironmentInfo(it.id, it.name, it.urls.size) }
        transfer.selectedIds.add(snapshot.environmentId)
        transfer.stage = ConfigurationTransferState.Stage.EXPORT_SELECTION
        consumeTransfer()
    }

    private fun consumeTransfer() {
        val outcome = transfer.outcome
        transfer.outcome = null
        when (outcome) {
            is ConfigurationTransferState.Outcome.Loaded -> {
                transfer.json = outcome.json
                transfer.environments = outcome.environments
                transfer.selectedIds = outcome.environments.map { it.id }.toCollection(linkedSetOf())
                transfer.mode = ImportMode.MERGE
                transfer.stage = ConfigurationTransferState.Stage.IMPORT_SELECTION
            }
            is ConfigurationTransferState.Outcome.Preview -> {
                when (val result = outcome.result) {
                    is ImportResult.Ready -> {
                        transfer.preview = result
                        transfer.previewBase = outcome.base
                        transfer.previewRevision = outcome.revision
                        transfer.stage = ConfigurationTransferState.Stage.PREVIEW
                    }
                    is ImportResult.Failure -> {
                        transfer.stage = ConfigurationTransferState.Stage.IMPORT_SELECTION
                        showError(getString(R.string.base_url_import_invalid) + "\n" + result.message)
                        renderUrls()
                        return
                    }
                }
            }
            is ConfigurationTransferState.Outcome.Export -> {
                transfer.exportJson = outcome.json
                transfer.stage = ConfigurationTransferState.Stage.EXPORT_PICKER
                try {
                    exportDocument.launch("multiurlmanager-configuration.json")
                } catch (_: ActivityNotFoundException) {
                    transfer.clear()
                    showError(getString(R.string.base_url_transfer_provider_missing))
                }
            }
            is ConfigurationTransferState.Outcome.Notice -> {
                transfer.clear()
                showError(getString(outcome.resource) + outcome.detail?.let { "\n$it" }.orEmpty())
            }
            null -> Unit
        }
        renderUrls()
        if (transferDialog?.isShowing == true) return
        when (transfer.stage) {
            ConfigurationTransferState.Stage.IMPORT_SELECTION -> showTransferSelection(importing = true)
            ConfigurationTransferState.Stage.EXPORT_SELECTION -> showTransferSelection(importing = false)
            ConfigurationTransferState.Stage.PREVIEW -> showImportPreview()
            else -> Unit
        }
    }

    private fun showTransferSelection(importing: Boolean) {
        val environments = transfer.environments
        val checked = environments.map { it.id in transfer.selectedIds }.toBooleanArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (importing) R.string.base_url_import_select else R.string.base_url_export_select)
            .setMultiChoiceItems(environments.map { "${it.name} · ${it.id} (${it.candidateCount})" }.toTypedArray(), checked) { _, index, selected ->
                if (selected) transfer.selectedIds.add(environments[index].id)
                else transfer.selectedIds.remove(environments[index].id)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> transfer.clear() }
            .setPositiveButton(if (importing) R.string.base_url_import_merge else R.string.base_url_export, null)
            .apply {
                if (importing) setNeutralButton(R.string.base_url_import_replace, null)
            }
            .create()
        dialog.setOnShowListener {
            fun proceed(mode: ImportMode) {
                if (transfer.selectedIds.isEmpty()) {
                    showError(getString(R.string.base_url_transfer_choose_environment))
                    return
                }
                if (importing) {
                    transfer.mode = mode
                    requestImportPreview()
                } else {
                    val currentManager = manager ?: return
                    val ids = transfer.selectedIds.toSet()
                    transfer.submit(R.string.base_url_export_write_error) {
                        ConfigurationTransferState.Outcome.Export(currentManager.exportConfiguration(ids))
                    }
                }
                dialog.dismiss()
                renderUrls()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { proceed(ImportMode.MERGE) }
            if (importing) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { proceed(ImportMode.REPLACE_ENVIRONMENTS) }
        }
        dialog.setOnCancelListener { transfer.clear() }
        transferDialog = dialog
        dialog.showManaged {
            // dismiss 回调可能晚于后台结果，不能清掉下一步预览或失败后待重试的输入。
            if (transferDialog === dialog) transferDialog = null
        }
        if (!importing && editor.draft?.let(StateCodec::encode) != editor.original) {
            showError(getString(R.string.base_url_export_committed_only))
        }
    }

    private fun requestImportPreview() {
        val currentManager = manager ?: return
        val json = transfer.json ?: return
        val draft = editor.draft ?: return
        val base = StateCodec.encode(draft)
        val revision = currentManager.snapshot().revision
        val ids = transfer.selectedIds.toSet()
        val mode = transfer.mode
        val rule = customRegex
        val ruleError = getString(R.string.base_url_import_custom_rule)
        transfer.submit(R.string.base_url_import_read_error) {
            val result = currentManager.previewImport(json, draft, ids, mode)
            // 文件添加地址与手工表单遵守同一宿主规则；既有候选继续允许选择或修改备注。
            val checked = if (result is ImportResult.Ready && rule != null &&
                result.draft.environments.any { environment ->
                    val existing = draft.environments.firstOrNull { it.id == environment.id }?.urls.orEmpty()
                    environment.urls.any { candidate ->
                        existing.none { it.configKey == candidate.configKey && it.url == candidate.url } &&
                            !rule.matcher(candidate.url).matches()
                    }
                }
            ) ImportResult.Failure(ImportFailureReason.VALIDATION, ruleError) else result
            ConfigurationTransferState.Outcome.Preview(
                checked, base, revision
            )
        }
    }

    private fun showImportPreview() {
        val preview = transfer.preview ?: return
        val summary = preview.changes.joinToString("\n\n") { change ->
            val type = when (change.type) {
                ImportChangeType.ENVIRONMENT_ADDED -> R.string.base_url_import_environment_added
                ImportChangeType.CANDIDATE_ADDED -> R.string.base_url_import_candidate_added
                ImportChangeType.CANDIDATE_REMOVED -> R.string.base_url_import_candidate_removed
                ImportChangeType.REMARK_CHANGED -> R.string.base_url_import_remark_changed
                ImportChangeType.SELECTION_CHANGED -> R.string.base_url_import_selection_changed
                ImportChangeType.LOCAL_PRESERVED -> R.string.base_url_import_local_preserved
            }
            listOfNotNull(
                "[${change.environmentId}] ${getString(type)}",
                change.configKey?.let(::businessTitle), change.url,
                change.previous?.let { getString(R.string.base_url_import_before, it) },
                change.current?.let { getString(R.string.base_url_import_after, it) }
            ).joinToString("\n")
        }.ifEmpty { getString(R.string.base_url_no_changes) }
        val modeLabel = getString(if (transfer.mode == ImportMode.MERGE) R.string.base_url_import_merge else R.string.base_url_import_replace)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.base_url_import_preview, modeLabel))
            .setMessage(getString(R.string.base_url_import_draft_only) + "\n\n" + summary)
            .setNegativeButton(android.R.string.cancel) { _, _ -> transfer.clear() }
            .setPositiveButton(R.string.base_url_import_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (manager?.snapshot()?.revision != transfer.previewRevision || editor.draft?.let(StateCodec::encode) != transfer.previewBase) {
                    transfer.stage = ConfigurationTransferState.Stage.IMPORT_SELECTION
                    dialog.dismiss()
                    showError(getString(R.string.base_url_import_stale))
                    return@setOnClickListener
                }
                if (!fitsImportBudget(preview.draft)) {
                    dialog.dismiss()
                    transfer.clear()
                    showError(getString(R.string.base_url_import_capacity))
                    return@setOnClickListener
                }
                editor.draft = preview.draft
                editor.invalidKey = null
                transfer.clear()
                dialog.dismiss()
                render()
                showError(getString(R.string.base_url_import_applied))
            }
        }
        dialog.setOnCancelListener { transfer.clear() }
        transferDialog = dialog
        dialog.showManaged {
            if (transferDialog === dialog) transferDialog = null
        }
    }

    private fun defaultEnvironment(id: String): UrlEnvironment? = try {
        manager?.defaultEnvironment(id)
    } catch (_: NoSuchElementException) {
        null
    }

    private fun save() {
        if (editor.saving || transfer.busy || manager?.isReadOnly == true) return
        val draft = editor.draft ?: return
        val currentManager = manager ?: return
        val environment = draft.environments.first { it.id == draft.environmentId }
        val missing = environment.requiredKeys.firstOrNull { key -> environment.urls.none { it.configKey == key && it.select } }
        if (missing != null) {
            editor.invalidKey = missing
            editor.summaryExpanded = false
            editor.collapsedGroups.remove(groupId(missing))
            renderUrls()
            findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(
                listAdapter.listData.indexOfFirst { it.headerText == missing }.coerceAtLeast(0)
            )
            showError(getString(R.string.base_url_validation_error))
            return
        }
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
                originalSnapshot = it
                editor.invalidKey = null
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
                            originalSnapshot = editor.draft
                            editor.invalidKey = null
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
        if (editor.saving || transfer.busy) return
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
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (transfer.stage == ConfigurationTransferState.Stage.IMPORT_SELECTION) {
                    window.decorView.post { consumeTransfer() }
                }
            }
            .create().showManaged()
    }

    companion object {
        private const val STATE_KNOWN_KEYS = "knownBusinessKeys"
        private const val STATE_TRANSFER_PENDING = "transferPending"
        private const val STATE_DRAFT = "draft"
        private const val STATE_ORIGINAL = "original"
        private const val STATE_FORM = "form"
        private const val STATE_KEY = "businessKey"
        private const val STATE_COLLAPSED = "collapsedGroups"
        private const val STATE_SUMMARY = "summaryExpanded"
        private const val STATE_INVALID_KEY = "invalidKey"
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
