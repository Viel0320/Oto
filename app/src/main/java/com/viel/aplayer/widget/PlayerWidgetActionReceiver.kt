package com.viel.aplayer.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.viel.aplayer.media.service.PlaybackService

/**
 * 详尽的中文注释：
 * 专用于桌面小组件内部播控动作的非公开广播接收器（PlayerWidgetActionReceiver）。
 * 
 * 核心职责：
 * 1. 物理配置为 exported="false"，仅接收并拦截应用内部 PendingIntent 所派发的同包进程安全媒体控制动作广播。
 * 2. 在绝对隔离的安全沙箱环境下，异步完成 MediaController 握手与服务绑定，从底层彻底隔离了前台服务被外部第三方应用非法拉起与越权控制的漏洞。
 */
class PlayerWidgetActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PlayerWidgetActionReceiver"

        // 详尽的中文注释：定义从小组件接收器中剥离并迁移至此的媒体控制自定义 Action 常量，分别对应播放/暂停、快退10秒、快进30秒
        const val ACTION_PLAY_PAUSE = "com.viel.aplayer.widget.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.viel.aplayer.widget.ACTION_REWIND"
        const val ACTION_FORWARD = "com.viel.aplayer.widget.ACTION_FORWARD"
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 详尽的中文注释：在绝对安全的沙箱隔离环境下处理自定义的媒体控制广播动作，保障服务通信的隔离性
        if (action in listOf(ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD)) {
            val pendingResult = goAsync() // 开启广播异步处理器，防止在主线程执行控制器握手造成短暂阻塞

            try {
                // 详尽的中文注释：使用 context.applicationContext 全局上下文，避开 RestrictedContext 对 bindService 的系统限制，标准绑定前台播放服务
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
                        Log.e(TAG, "执行桌面播控操作时 MediaController 发生异常: ${e.message}", e)
                    } finally {
                        pendingResult.finish() // 必须显式完成异步广播生命周期，防止广播泄漏
                    }
                }, context.mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "初始化桌面播控控制器失败: ${e.message}", e)
                pendingResult.finish()
            }
        }
    }
}
