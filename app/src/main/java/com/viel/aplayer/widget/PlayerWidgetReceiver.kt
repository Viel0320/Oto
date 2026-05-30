package com.viel.aplayer.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 详尽的中文注释：
 * 桌面小组件生命周期桥接接收器（PlayerWidgetReceiver）。
 * 
 * 核心职责：
 * 1. 继承自 GlanceAppWidgetReceiver，仅绑定并管理桌面组件 PlayerWidget 视图生命周期的渲染及 APPWIDGET_UPDATE 广播更新。
 * 2. 贯彻组件隔离原则，原本由本接收器处理的自定义播控指令已全部物理安全剥离至非公开的 PlayerWidgetActionReceiver 中运行。
 *    本接收器只暴露系统级 widget 更新行为，任何外部恶意虚假广播均无法触达播控链路，系统级安全性极大提升。
 */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = PlayerWidget()
}
