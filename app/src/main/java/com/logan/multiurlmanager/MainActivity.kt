package com.logan.multiurlmanager

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.logan.multiurlmanager.databinding.ActivityMainBinding
import com.logan.multiurlmanager.databinding.SampleUrlCardBinding
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerContract
import com.logan.multiurlmanager.library.UrlChangeListener

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val listener = UrlChangeListener { _, _ -> renderConfiguration() }
    private val settings = registerForActivityResult(BaseUrlManagerContract()) { renderConfiguration() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        configureSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.btnSettings.setOnClickListener { settings.launch(null) }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        val surface = ContextCompat.getColor(this, R.color.app_surface)
        val darkIcons = ColorUtils.calculateLuminance(surface) > 0.5
        window.statusBarColor = Color.TRANSPARENT
        // Android 7 无法显示深色导航图标，保留深色系统栏以保证按键可见。
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Color.TRANSPARENT
        } else {
            Color.rgb(23, 36, 61)
        }
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onStart() {
        super.onStart()
        BaseUrlManager.instance?.addListener(listener)
        renderConfiguration()
    }

    override fun onStop() {
        BaseUrlManager.instance?.removeListener(listener)
        super.onStop()
    }

    private fun renderConfiguration() {
        binding.urlCards.removeAllViews()
        val snapshot = BaseUrlManager.instance?.snapshot()
        if (snapshot == null) {
            binding.tvConfigInfo.setText(R.string.configuration_initialization_failed)
            binding.tvEnvironmentSummary.visibility = View.GONE
            binding.btnSettings.isEnabled = false
            return
        }
        binding.btnSettings.isEnabled = true
        binding.tvEnvironmentSummary.visibility = View.VISIBLE
        val environment = snapshot.environments.first { it.id == snapshot.environmentId }
        val effective = snapshot.effectiveUrls()
        binding.tvConfigInfo.text = getString(R.string.current_environment, environment.name)
        binding.tvEnvironmentSummary.text = resources.getQuantityString(
            R.plurals.home_environment_summary,
            effective.size,
            environment.id,
            effective.size
        )
        val businessKeys = (environment.urls.map { it.configKey } + environment.requiredKeys).distinct().sorted()
        businessKeys.forEach { key ->
            val card = SampleUrlCardBinding.inflate(layoutInflater, binding.urlCards, false)
            val selected = environment.urls.firstOrNull { it.configKey == key && it.select }
            card.tvBusinessKey.text = when (key) {
                "order" -> getString(R.string.home_order_service)
                "login" -> getString(R.string.home_login_service)
                else -> key
            }
            card.tvBusinessUrl.text = selected?.url ?: getString(R.string.home_no_selection)
            card.tvBusinessRemark.text = selected?.remark
            card.tvBusinessRemark.visibility = if (selected?.remark.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.urlCards.addView(card.root)
        }
        if (businessKeys.isEmpty()) {
            binding.urlCards.addView(
                TextView(this).apply {
                    setText(R.string.home_no_services)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_secondary))
                    textSize = 14f
                }
            )
        }
    }
}
