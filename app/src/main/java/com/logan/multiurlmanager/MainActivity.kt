package com.logan.multiurlmanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.logan.multiurlmanager.databinding.ActivityMainBinding
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.utils.BaseUrlUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    val SET_BASE_URL_REQUEST_CODE = 0X01

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnSettings.setOnClickListener {
            BaseUrlManagerActivity.startBaseUrlManager(this@MainActivity, SET_BASE_URL_REQUEST_CODE)
        }
        val configInfoText = BaseUrlUtil.loadDynamicBaseUrlConfigs(context = this)
            .map { it.value }
            .filter { it.select }
            .map { "${it.configKey} : ${it.url}" }
            .joinToString("\r\n")
        binding.tvConfigInfo.text = configInfoText

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
