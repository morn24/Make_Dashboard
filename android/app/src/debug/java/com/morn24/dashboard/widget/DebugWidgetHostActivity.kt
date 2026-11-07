package com.morn24.dashboard.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Debug only: hosts the real Glance widget inside this activity at exact sizes (2x2, 4x2, 4x4 dp) so
 * its rendering and taps can be captured on an emulator without placing widgets on a launcher.
 * One-time setup over adb: `cmd appwidget grantbind --package com.morn24.dashboard`.
 * Optional intent extra `sizes` = "WxH,WxH" (dp) replaces the default three sizes.
 */
class DebugWidgetHostActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        host.deleteHost()
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, DashboardWidgetReceiver::class.java)
        val sizes = (intent.getStringExtra("sizes") ?: "150x150,330x150,330x420").split(',').map {
            val (w, h) = it.trim().split('x')
            w.toInt() to h.toInt()
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#7A8794"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val info = manager.getInstalledProvidersForPackage(packageName, null).first { it.provider == provider }
        var asked = false
        sizes.forEach { (w, h) ->
            val id = host.allocateAppWidgetId()
            val bound = manager.bindAppWidgetIdIfAllowed(id, provider)
            if (!bound) {
                // First run: ask the system once (choose "always allow"), then reopen this screen.
                host.deleteAppWidgetId(id)
                if (!asked) startActivity(
                    android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, host.allocateAppWidgetId())
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider),
                )
                asked = true
                column.addView(TextView(this).apply { text = "바인딩 권한 요청 중: 허용 후 이 화면을 다시 여세요." })
                return@forEach
            }
            val view: AppWidgetHostView = host.createView(applicationContext, id, info)
            view.setAppWidget(id, info)
            view.layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)).apply { bottomMargin = dp(8) }
            view.updateAppWidgetSize(null, w, h, w, h)
            column.addView(view)
        }
        setContentView(
            ScrollView(this).apply {
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        super.onStop()
        host.stopListening()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val HOST_ID = 4242
    }
}
