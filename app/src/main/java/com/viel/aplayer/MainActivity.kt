package com.viel.aplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viel.aplayer.ui.navigation.APlayerApp
class MainActivity : ComponentActivity() {
    // 为本次桌面 widget 改动添加注释：用 Compose 可观察状态承接外部 Intent 请求，使 onNewIntent 能在 Activity 已存在时再次拉起播放页 overlay。
    private var shouldOpenPlayerOverlay by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 为本次桌面 widget 改动添加注释：冷启动时先读取小组件传入的打开播放页请求，避免 setContent 后丢失初始 Intent 语义。
        shouldOpenPlayerOverlay = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false) == true
        enableEdgeToEdge()
        
        // 禁用整个 Activity 的自动填充（包括其所有子视图）
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            APlayerApp(
                openPlayerOverlayRequest = shouldOpenPlayerOverlay,
                onOpenPlayerOverlayConsumed = {
                    // 为本次桌面 widget 改动添加注释：播放页 overlay 请求被 Compose 宿主消费后复位，防止普通重组重复打开。
                    shouldOpenPlayerOverlay = false
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 为本次桌面 widget 改动添加注释：应用已经在前台或后台任务栈中时，小组件点击会走这里，直接转换成一次新的 overlay 打开请求。
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false)) {
            shouldOpenPlayerOverlay = true
        }
    }

    companion object {
        // 为本次桌面 widget 改动添加注释：集中定义小组件打开播放页 overlay 的 Intent extra，避免不同入口手写字符串造成漂移。
        const val EXTRA_OPEN_PLAYER_OVERLAY = "com.viel.aplayer.extra.OPEN_PLAYER_OVERLAY"

        // 为本次桌面 widget Glance 迁移添加注释：为 Glance actionStartActivity 构造复用任务栈的 Intent，点击非按钮区域即可回到应用并拉起播放页 overlay。
        fun createOpenPlayerOverlayIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYER_OVERLAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
