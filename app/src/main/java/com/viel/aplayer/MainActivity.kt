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
import com.viel.aplayer.ui.search.SearchActivity

class MainActivity : ComponentActivity() {
    // 为本次桌面 widget 改动添加注释：用 Compose 可观察状态承接外部 Intent 请求，使 onNewIntent 能在 Activity 已存在时再次拉起播放页 overlay。
    private var shouldOpenPlayerOverlay by mutableStateOf(false)

    // 为每一次改动添加详尽的中文注释：
    // 记录从 SearchActivity 回传的待打开书籍 ID。
    // 当搜索页用户点击某本书的卡片后，SearchActivity 通过 setResult 将该 bookId 写入 Intent，
    // 本 Launcher 收到后将其存入此状态，APlayerApp 观察到变化后调用 DetailViewModel.selectBook 打开详情 Overlay。
    private var pendingDetailBookId by mutableStateOf<String?>(null)

    // 为每一次改动添加详尽的中文注释：
    // 记录从 SearchActivity 回传的"立即播放"书籍 ID。
    // 与 pendingDetailBookId 区分：该书籍不走详情 Overlay，而是直接调用 PlayerViewModel.loadBook 并展开全屏播放器。
    private var pendingPlayBookId by mutableStateOf<String?>(null)

    // 为每一次改动添加详尽的中文注释：
    // 记录是否需要仅打开播放器 Overlay（不涉及书籍切换），对应 SearchActivity.EXTRA_OPEN_PLAYER。
    private var shouldOpenPlayerFromSearch by mutableStateOf(false)

    // 为每一次改动添加详尽的中文注释：
    // ActivityResultLauncher 负责启动 SearchActivity 并接收其回传结果。
    // 使用 StartActivityForResult 合约，兼容 API 21+；系统动画在 launch 时通过 ActivityOptionsCompat 传入。
    // 当 resultCode == RESULT_OK 时，从 data Intent 中读取 extra 并赋给对应 Compose 状态，触发 APlayerApp 的响应逻辑。
    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            when {
                // 为每一次改动添加详尽的中文注释：收到"仅打开播放器"指令
                data.getBooleanExtra(SearchActivity.EXTRA_OPEN_PLAYER, false) -> {
                    shouldOpenPlayerFromSearch = true
                }
                // 为每一次改动添加详尽的中文注释：收到"加载并播放"指令（附带书籍 ID + 立即播放标记）
                data.getBooleanExtra(SearchActivity.EXTRA_SHOULD_PLAY, false) -> {
                    pendingPlayBookId = data.getStringExtra(SearchActivity.EXTRA_SELECTED_BOOK_ID)
                }
                // 为每一次改动添加详尽的中文注释：收到"打开详情"指令（仅书籍 ID，不立即播放）
                else -> {
                    pendingDetailBookId = data.getStringExtra(SearchActivity.EXTRA_SELECTED_BOOK_ID)
                }
            }
        }
    }

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
                },
                // 为每一次改动添加详尽的中文注释：
                // 将搜索页回传的"打开详情"书籍 ID 传入 APlayerApp，由其内部调用 DetailViewModel.selectBook 打开详情 Overlay。
                // APlayerApp 消费后通过回调将此状态复位，防止重组时重复触发。
                pendingDetailBookId = pendingDetailBookId,
                onPendingDetailBookIdConsumed = { pendingDetailBookId = null },
                // 为每一次改动添加详尽的中文注释：
                // 将搜索页回传的"立即播放"书籍 ID 传入 APlayerApp，由其调用 PlayerViewModel.loadBook 并展开全屏播放器。
                pendingPlayBookId = pendingPlayBookId,
                onPendingPlayBookIdConsumed = { pendingPlayBookId = null },
                // 为每一次改动添加详尽的中文注释：
                // 将搜索页回传的"仅打开播放器"指令传入 APlayerApp。
                openPlayerFromSearchRequest = shouldOpenPlayerFromSearch,
                onOpenPlayerFromSearchConsumed = { shouldOpenPlayerFromSearch = false },
                // 为每一次改动添加详尽的中文注释：
                // 将 searchLauncher 传入 APlayerApp，供 NavHost 中的 Home 页搜索按钮及详情页"按作者搜索"等入口调用。
                searchLauncher = searchLauncher
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
