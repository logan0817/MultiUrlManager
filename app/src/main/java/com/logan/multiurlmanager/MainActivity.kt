package com.logan.multiurlmanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.logan.multiurlmanager.databinding.ActivityMainBinding
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.R
import com.logan.multiurlmanager.library.utils.BaseUrlUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    val SET_BASE_URL_REQUEST_CODE = 0X01

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setupInsets()
        binding.btnSettings.setOnClickListener {
//            BaseUrlManagerActivity.startBaseUrlManager(this@MainActivity, SET_BASE_URL_REQUEST_CODE)
            BaseUrlManagerActivity.startBaseUrlManager(
                this@MainActivity, SET_BASE_URL_REQUEST_CODE, bundleOf(
                    BaseUrlManagerActivity.KEY_TITLE to "BaseUrl Configuration",
                    BaseUrlManagerActivity.KEY_REGEX to BaseUrlManagerActivity.HTTP_URL_REGEX,
                )
            )

        }
        val configInfoText = BaseUrlUtil.loadDynamicBaseUrlConfigs(context = this)
            .map { it.value }
            .filter { it.select }
            .map { "${it.configKey} : ${it.url}" }
            .joinToString("\r\n")
        binding.tvConfigInfo.text = configInfoText

    }

    private fun setupInsets() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(toolbar)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                SET_BASE_URL_REQUEST_CODE -> {
                    val baseUrls = BaseUrlManagerActivity.parseActivityResult(data);
                    Log.d(
                        "baseUrls",
                        baseUrls?.joinToString(";").toString()
                    )
                    val configInfoText = baseUrls
                        ?.map { "${it.configKey} : ${it.url}" }
                        ?.joinToString("\r\n")
                    binding.tvConfigInfo.text = configInfoText
                }
            }
        }
    }
}
