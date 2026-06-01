package io.github.xiaotong6666.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class ToolsApp : Application() {
    companion object {
        private const val GLOBAL_PREFS = "global"

        private val handler = Handler(Looper.getMainLooper())
        lateinit var context: Application
        lateinit var thisPackageName: String
        private var nightMode = false
        private var config: SharedPreferences? = null

        val globalConfig: SharedPreferences
            get() {
                if (config == null) {
                    config = context.getSharedPreferences(GLOBAL_PREFS, Context.MODE_PRIVATE)
                }
                return config!!
            }

        val isNightMode: Boolean
            get() = nightMode

        fun getBoolean(key: String, defaultValue: Boolean): Boolean = globalConfig.getBoolean(key, defaultValue)

        fun setBoolean(key: String, value: Boolean) {
            globalConfig.edit().putBoolean(key, value).apply()
        }

        fun getString(key: String, defaultValue: String): String? = globalConfig.getString(key, defaultValue)

        fun toast(message: String, time: Int) {
            handler.post {
                Toast.makeText(context, message, time).show()
            }
        }

        fun toast(message: String) {
            handler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        fun toast(message: Int, time: Int) {
            handler.post {
                Toast.makeText(context, message, time).show()
            }
        }

        fun post(runnable: Runnable) {
            handler.post(runnable)
        }

        fun postDelayed(runnable: Runnable, delayMillis: Long) {
            handler.postDelayed(runnable, delayMillis)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        nightMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_YES) != 0
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = this
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        nightMode = uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES
        thisPackageName = packageName
    }
}
