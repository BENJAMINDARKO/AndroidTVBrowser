package com.example.tvbrowser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

class BrowserApp : Application() {
    lateinit var adBlockEngine: AdBlockEngine
        private set

    override fun onCreate() {
        super.onCreate()

        val processName = getProcessNameCompat()
        Log.d("TVBrowser", "BrowserApp onCreate in process: $processName")
        if (processName != null && processName != packageName) {
            return
        }

        adBlockEngine = AdBlockEngine()
        // Load the engine asynchronously to prevent main-thread block
        Thread {
            adBlockEngine.loadRules(this)
        }.start()
    }

    private fun getProcessNameCompat(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
