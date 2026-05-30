package com.viel.aplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
// 详尽的中文注释：导入 produceState 和 getValue 扩展，以便在 Compose 组合内声明并绑定异步加载的状态变量
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
// 详尽的中文注释：导入 Dispatchers 和 withContext 协程分发器工具，以实现完全非阻塞的后台磁盘 I/O 线程调度
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import java.io.File

/**
 * 详尽的中文注释：
 * 现代声明式桌面媒体控制小组件（PlayerWidget）。
 * 
 * 核心职责：
 * 1. 继承自 GlanceAppWidget，采用 Jetpack Glance 声明式开发规范。
 * 2. 依靠 PreferencesGlanceStateDefinition 与应用主进程 DataStore 进行数据绑定，实时监听并呈现播放状态。
 * 3. 完美兼容 Material 3 动态取色（GlanceTheme），在 Android 12+ 上可直接取用系统壁纸调色板。
 * 4. 提供严谨的 2x2 双行自适应比例布局，左右、上下结构紧凑美观。
 */
class PlayerWidget : GlanceAppWidget() {

    // 详尽的中文注释：指定 Glance 内部数据存储定义为 Preferences
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // 1. 获取持久化存在 Datastore 中的播放元数据
            val prefs = currentState<Preferences>()
            val isPlaying = prefs[PlayerWidgetStateHelper.KEY_IS_PLAYING] ?: false
            
            // 详尽的中文注释：对标题和作者空状态进行友好占位降级
            val title = prefs[PlayerWidgetStateHelper.KEY_TITLE].let { 
                if (it.isNullOrEmpty()) "APlayer" else it 
            }
            val author = prefs[PlayerWidgetStateHelper.KEY_AUTHOR].let { 
                if (it.isNullOrEmpty()) "有声书播放器" else it 
            }
            val coverPath = prefs[PlayerWidgetStateHelper.KEY_COVER_PATH] ?: ""

            // 2. 详尽的中文注释：使用 produceState 配合 withContext(Dispatchers.IO) 在后台协程中完成图片的解码和物理文件校验，
            // 彻底杜绝在 Compose/Glance 组合（Composition）流程中同步进行 File.exists() 和 BitmapFactory 磁盘 I/O 阻塞。
            // 同时保留 120 像素下采样阈值，从根源上控制加载的 Bitmap 体积，安全防范桌面小组件跨进程传输可能引发的 TransactionTooLargeException
            val bitmap by produceState<Bitmap?>(initialValue = null, coverPath) {
                value = withContext(Dispatchers.IO) {
                    if (coverPath.isNotEmpty()) {
                        val file = File(coverPath)
                        if (file.exists()) {
                            try {
                                val options = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true // 仅扫描边界，不分配像素内存
                                }
                                BitmapFactory.decodeFile(coverPath, options)
                                
                                val targetSize = 120
                                var inSampleSize = 1
                                if (options.outHeight > targetSize || options.outWidth > targetSize) {
                                    val halfHeight = options.outHeight / 2
                                    val halfWidth = options.outWidth / 2
                                    while (halfHeight / inSampleSize >= targetSize && halfWidth / inSampleSize >= targetSize) {
                                        inSampleSize *= 2
                                    }
                                }
                                
                                options.inSampleSize = inSampleSize
                                options.inJustDecodeBounds = false
                                BitmapFactory.decodeFile(coverPath, options)
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    } else null
                }
            }

            // 3. 将 UI 包裹在 GlanceTheme 内以完美开启 Material 3 动态取色
            GlanceTheme {
                WidgetLayout(
                    context = context,
                    isPlaying = isPlaying,
                    title = title,
                    author = author,
                    coverBitmap = bitmap
                )
            }
        }
    }

    /**
     * 详尽的中文注释：
     * 构建 2x2 桌面 Widget 高保真 UI 排版。
     */
    @Composable
    private fun WidgetLayout(
        context: Context,
        isPlaying: Boolean,
        title: String,
        author: String,
        coverBitmap: Bitmap?
    ) {
        // 详尽的中文注释：点击卡片外层空白区域，将通过 SingleTop 的方式平滑打开 MainActivity 回到应用主页面，不再自动拉起全屏播放器界面
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(openAppIntent))
        ) {
            // 详尽的中文注释：1. 将有声书封面设置为桌面组件的全屏铺满背景
            if (coverBitmap != null) {
                Image(
                    provider = ImageProvider(coverBitmap),
                    contentDescription = "Background Cover",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 详尽的中文注释：无封面时降级为预置的默认占位图背景
                Image(
                    provider = ImageProvider(R.drawable.widget_cover_placeholder),
                    contentDescription = "Background Placeholder",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // 详尽的中文注释：2. 叠加一层精心调配的半透明黑色蒙层（不透明度55%），并且使用 ColorProvider 适配 Glance 的底层要求以保证兼容性与可读性
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color.Black.copy(alpha = 0.55f)))
            ) {}

            // 详尽的中文注释：3. 容器整体垂直与水平居中，并适当缩减上下内边距，保持极其精致的微缩版面比例
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 详尽的中文注释：标题及作者介绍行整体水平居中对齐，采用纯白与半透明白色以获得完美的对比效果
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = author,
                        style = TextStyle(
                            color = ColorProvider(Color.White.copy(alpha = 0.7f)),
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(top = 1.dp)
                    )
                }

                // 详尽的中文注释：下半部实时期播控操作排，居中对齐，间距缩小，并在底部追加 8.dp 边距防止按钮过于贴近边缘
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 详尽的中文注释：快退 10 秒控制按钮，大小由 32dp 缩小至 24dp。
                    // 路由目标类已由导出的接收器安全重构为非公开（exported=false）的 PlayerWidgetActionReceiver，规避越权拉起漏洞
                    val rewindIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_REWIND
                    }
                    Image(
                        provider = ImageProvider(R.drawable.ic_replay_10),
                        contentDescription = "Seek Backward",
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(rewindIntent)),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                    )

                    // 详尽的中文注释：将操作按钮之间的间隔距离从 20dp 收缩至 16dp，避免布局过宽
                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    // 详尽的中文注释：核心播放/暂停按钮，容器大小微调至 34dp，内部图标大小增至 22dp。
                    // 路由目标类已由导出的接收器安全重构为非公开（exported=false）的 PlayerWidgetActionReceiver，杜绝前台服务被外部非法拉起
                    val playPauseIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_PLAY_PAUSE
                    }
                    val playPauseIconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                    Box(
                        modifier = GlanceModifier
                            .size(34.dp)
                            .cornerRadius(17.dp)
                            .background(GlanceTheme.colors.primary)
                            .clickable(actionSendBroadcast(playPauseIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(playPauseIconRes),
                            contentDescription = "Play or Pause",
                            modifier = GlanceModifier.size(22.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary)
                        )
                    }

                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    // 详尽的中文注释：快进 30 秒控制按钮，大小由 32dp 缩小至 24dp。
                    // 路由目标类已由导出的接收器安全重构为非公开（exported=false）的 PlayerWidgetActionReceiver，彻底防范虚假广播操控
                    val forwardIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_FORWARD
                    }
                    Image(
                        provider = ImageProvider(R.drawable.ic_forward_30),
                        contentDescription = "Seek Forward",
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(forwardIntent)),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                    )
                }
            }
        }
    }
}
