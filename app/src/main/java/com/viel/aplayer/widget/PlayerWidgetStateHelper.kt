package com.viel.aplayer.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * 详尽的中文注释：
 * 桌面小组件状态同步助手类（PlayerWidgetStateHelper）。
 * 
 * 核心职责：
 * 1. 负责将播放服务（PlaybackService）中的实时期有声书播放状态、标题、作者、本地封面图路径持久化存入 Glance 独占的 DataStore 中。
 * 2. 状态写入完成后，触发 GlanceAppWidgetManager 对全部正在桌面运行的小组件进行即时刷新，保证 UI 状态的实时一致性。
 * 3. 剥离了核心播放逻辑和复杂的界面绘制，保证组件间的绝对解耦，杜绝制造上帝类。
 */
object PlayerWidgetStateHelper {

    private const val TAG = "PlayerWidgetStateHelper"

    // 详尽的中文注释：定义用于 Glance 内部 Preferences 状态管理的 Key
    val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
    val KEY_TITLE = stringPreferencesKey("title")
    val KEY_AUTHOR = stringPreferencesKey("author")
    val KEY_COVER_PATH = stringPreferencesKey("cover_path")

    /**
     * 详尽的中文注释：
     * 异步更新小组件的数据状态，并强制触发桌面 Widget 重绘。
     * 
     * @param context 上下文环境
     * @param isPlaying 是否正在播放有声书
     * @param title 有声书书籍名称
     * @param author 有声书书籍作者
     * @param coverPath 有声书本地物理封面图文件路径
     */
    suspend fun updateWidgetState(
        context: Context,
        isPlaying: Boolean,
        title: String?,
        author: String?,
        coverPath: String?
    ) {
        try {
            // 1. 获取 Glance 小组件管理器
            val manager = GlanceAppWidgetManager(context)
            // 获取桌面上所有由 PlayerWidget 实例注册的组件 ID
            val glanceIds = manager.getGlanceIds(PlayerWidget::class.java)
            
            if (glanceIds.isEmpty()) {
                // 若桌面上并没有放置该小组件，直接略过状态同步以节约系统 DataStore 读写开销
                return
            }

            // 2. 遍历所有小组件实例，利用 Glance 框架提供的 updateAppWidgetState 对其 Datastore 进行写入
            glanceIds.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { preferences ->
                    preferences.toMutablePreferences().apply {
                        this[KEY_IS_PLAYING] = isPlaying
                        this[KEY_TITLE] = title ?: ""
                        this[KEY_AUTHOR] = author ?: ""
                        this[KEY_COVER_PATH] = coverPath ?: ""
                    }
                }
            }

            // 3. 触发组件的实际界面重绘刷新
            PlayerWidget().updateAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "更新桌面小组件 DataStore 状态时遇到物理异常: ${e.message}", e)
        }
    }
}
