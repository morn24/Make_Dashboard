package com.morn24.dashboard.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.morn24.dashboard.work.DashboardWorkScheduler

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DashboardWorkScheduler.onWidgetAdded(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DashboardWorkScheduler.onLastWidgetRemoved(context)
    }
}
