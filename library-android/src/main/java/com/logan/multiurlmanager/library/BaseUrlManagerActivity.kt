package com.logan.multiurlmanager.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.bean.BaseUrlSection
import java.util.regex.Pattern


/**
 * 配置管理界面。
 *
 * @author logan
 * @date 2025/12/02
 */
class BaseUrlManagerActivity : AppCompatActivity() {

    // Lazy initialization of views
    private val recyclerView by lazy { findViewById<RecyclerView>(R.id.recyclerView) }
    private val configKeySpinner by lazy { findViewById<Spinner>(R.id.configKeySpinner) }
    private val etUrl by lazy { findViewById<EditText>(R.id.etUrl) }
    private val btnAdd by lazy { findViewById<View>(R.id.btnAdd) }

    // baseUrlManager 实例
    private val baseUrlManager: BaseUrlManager? = BaseUrlManager.instance

    private lateinit var baseURLAdapter: BaseUrlAdapter
    private var regex: String? = BaseUrlManager.HTTP_URL_REGEX
    private val inputMethodManager: InputMethodManager?
        get() = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.base_url_manager_activity)
        setupInsets()
        initUI()
        initListeners()
    }

    private fun setupInsets() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = resources.getString(R.string.base_url_title)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.base_url_top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.actionSave -> {
                saveSelected()
                true // 返回 true 表示事件已处理
            }

            R.id.actionReset -> {
                baseUrlManager?.clear()
                baseUrlManager?.refreshData()
                baseURLAdapter.setNewInstance(getBaseURLSections())
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initUI() {

        intent.extras?.let { bundle ->
            regex = bundle.getString(BaseUrlManager.KEY_REGEX) ?: BaseUrlManager.HTTP_URL_REGEX
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@BaseUrlManagerActivity)
            addItemDecoration(
                DividerItemDecoration(
                    this@BaseUrlManagerActivity,
                    DividerItemDecoration.VERTICAL
                )
            )
            baseURLAdapter = BaseUrlAdapter(getBaseURLSections())
            adapter = baseURLAdapter
        }

        val apiDomainItems = baseUrlManager?.dynamicUrls?.groupBy { it.configKey }?.map { it.key }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            apiDomainItems ?: listOf()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        configKeySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                // 获取被选中的字符串内容
                val selectedItem = parent.getItemAtPosition(position).toString()
                Log.d("configKeySpinner", "您选择了: $selectedItem (位置: $position)")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // 当没有任何项被选中时
            }
        }
        configKeySpinner.adapter = adapter
    }

    private fun initListeners() {
        btnAdd.setOnClickListener {
            addBaseURL()
        }
    }

    private fun getBaseURLSections(): MutableList<BaseUrlSection> {
        val baseUrlSections = mutableListOf<BaseUrlSection>()
        val groupedBaseUrl: Map<String, List<BaseUrl>>? =
            baseUrlManager?.dynamicUrls?.groupBy { it.configKey }
        groupedBaseUrl?.forEach { (key, list) ->
            baseUrlSections.add(BaseUrlSection(headerText = key))
            list.forEach {
                baseUrlSections.add(BaseUrlSection(baseUrl = it.copy()))
            }
        }
        return baseUrlSections
    }

    /**
     * 保存选中的 Url
     */
    private fun saveSelected() {
        val manager = baseUrlManager ?: return
        val urls = baseURLAdapter.listData.filter { !it.isHeader }.map { it.baseUrl!! }
        // 1. 设置 BaseUrl 映射
        manager.updateBaseURL(urls)
        manager.refreshData()

        val intent = Intent().apply {
            val selectDomains = urls.filter { it.select }.toMutableList() as ArrayList
            putParcelableArrayListExtra(KEY_URLS_INFO, selectDomains)
        }
        setResult(RESULT_OK, intent)
        onBackPressed()
    }

    /**
     * 添加 Url
     */
    private fun addBaseURL() {
        val selectedKey = configKeySpinner.selectedItem?.toString()
        if (selectedKey.isNullOrBlank()) {
            Toast.makeText(this, "Spinner had no choice ", Toast.LENGTH_SHORT).show()
            return
        }

        val url = etUrl.text.toString().trim()
        if (url.isNullOrEmpty()) {
            etUrl.startAnimation(AnimationUtils.loadAnimation(this, R.anim.base_url_shake))
            return
        }

        if (regex?.let { Pattern.matches(it, url) } == false) {
            etUrl.startAnimation(AnimationUtils.loadAnimation(this, R.anim.base_url_shake))
            Toast.makeText(this, "The baseurl does not conform to the rules.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (!url.endsWith("/")) {
            etUrl.startAnimation(AnimationUtils.loadAnimation(this, R.anim.base_url_shake))
            Toast.makeText(this, "baseUrl must end in /", Toast.LENGTH_SHORT).show()
            return
        }
        baseUrlManager?.addBaseURL(BaseUrl(configKey = selectedKey, url = url, select = false))
        baseURLAdapter.setNewInstance(getBaseURLSections())

        etUrl.clearFocus()

        inputMethodManager?.hideSoftInputFromWindow(etUrl.windowToken, 0)

        val count = baseURLAdapter.itemCount
        if (count > 0) {
            recyclerView.smoothScrollToPosition(count - 1)
        }
    }

    companion object {

        const val KEY_URLS_INFO: String = "key_urls_info"

        @JvmOverloads
        fun startBaseUrlManager(activity: Activity, requestCode: Int, bundle: Bundle? = null) {
            val intent = Intent(activity, BaseUrlManagerActivity::class.java).apply {
                if (bundle != null && bundle.size() > 0) {
                    putExtras(bundle)
                }
            }
            activity.startActivityForResult(intent, requestCode)
        }

        @JvmOverloads
        fun startBaseUrlManager(fragment: Fragment, requestCode: Int, bundle: Bundle? = null) {
            val context = fragment.context ?: return
            val intent = Intent(context, BaseUrlManagerActivity::class.java).apply {
                if (bundle != null && bundle.size() > 0) {
                    putExtras(bundle)
                }
            }
            fragment.startActivityForResult(intent, requestCode)
        }

        /**
         * 解析 onActivityResult 中的结果
         */
        fun parseActivityResult(data: Intent?): List<BaseUrl>? {
            return data?.getParcelableArrayListExtra(KEY_URLS_INFO)
        }

    }
}