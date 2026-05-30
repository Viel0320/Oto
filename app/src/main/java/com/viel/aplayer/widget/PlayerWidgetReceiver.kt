package com.viel.aplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.viel.aplayer.media.service.PlaybackService

/**
 * 详尽的中文注释：
 * 桌面小组件广播及指令桥接接收器（PlayerWidgetReceiver）。
 * 
 * 核心职责：
 * 1. 继承自 GlanceAppWidgetReceiver，绑定桌面组件 PlayerWidget 视图生命周期。
 * 2. 接收来自桌面小组件按钮点击所分发的自定义播控广播（播放/暂停、快退10秒、快进30秒）。
 * 3. 完美利用 Media3 的 SessionToken 和 MediaController 与前台播放服务 PlaybackService 建立绑定，
 *    实现组件间的解耦通信，即便服务目前未启动，MediaController 也会自动触发播放服务的安全拉起。
 */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget
        get() = PlayerWidget()

    companion object {
        private const val TAG = "PlayerWidgetReceiver"

        // 详尽的中文注释：定义接收器拦截的媒体控制 Action
        const val ACTION_PLAY_PAUSE = "com.viel.aplayer.widget.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.viel.aplayer.widget.ACTION_REWIND"
        const val ACTION_FORWARD = "com.viel.aplayer.widget.ACTION_FORWARD"
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return

        // 详尽的中文注释：若拦截到的是自定义的桌面播控动作，我们通过 MediaController 进行指令下发
        if (action in listOf(ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD)) {
            val pendingResult = goAsync() // 开启广播异步处理器，防止在主线程执行控制器握手造成短暂阻塞

            try {
                // 详尽的中文注释：必须将 context 强行转为全局 applicationContext，
                // 彻底规避 Android 系统对 BroadcastReceiver 默认 RestrictedContext 调用 bindService 的物理封禁，
                // 确保 MediaController 能够完美且标准地绑定 PlaybackService 媒体服务
                val appContext = context.applicationContext

                // 1. 创建指向 PlaybackService 的媒体 Session 令牌
                val sessionToken = SessionToken(
                    appContext,
                    ComponentName(appContext, PlaybackService::class.java)
                )

                // 2. 异步构造 MediaController
                val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
                
                controllerFuture.addListener({
                    try {
                        val controller = controllerFuture.get()
                        
                        // 3. 根据接收到的不同指令，对媒体控制器执行相应操作
                        when (action) {
                            ACTION_PLAY_PAUSE -> {
                                if (controller.isPlaying) {
                                    controller.pause()
                                } else {
                                    controller.play()
                                }
                            }
                            ACTION_REWIND -> {
                                controller.seekBack() // 调用 Media3 标准的向后快退控制
                            }
                            ACTION_FORWARD -> {
                                controller.seekForward() // 调用 Media3 标准的向前快进控制
                            }
                        }
                        
                        // 4. 操作完成后，安全释放 Controller 连接
                        MediaController.releaseFuture(controllerFuture)
                    } catch (e: Exception) {
                        Log.e(TAG, "执行桌面播控操作时 MediaController 异常故障: ${e.message}", e)
                    } finally {
                        pendingResult.finish() // 必须显式完成异步广播生命周期
                    }
                }, context.mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "初始化桌面播控控制器失败: ${e.message}", e)
                pendingResult.finish()
            }
        }
    }
}
