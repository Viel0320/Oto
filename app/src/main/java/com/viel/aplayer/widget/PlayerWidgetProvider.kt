package com.viel.aplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.media.AutoRewindManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 为本次桌面 widget Glance 迁移添加注释：
 * 保留原 manifest 入口类名，但改为 GlanceAppWidgetReceiver，让桌面小组件由声明式 Glance 内容渲染。
 */
class PlayerWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerGlanceWidget

    companion object {
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 为本次桌面 widget Glance 迁移添加注释：保留播放器核心调用的刷新入口，内部转发到 GlanceAppWidget.updateAll。
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                PlayerGlanceWidget.updateAll(appContext)
            }
        }
    }
}

/**
 * 为本次桌面 widget Glance 迁移添加注释：
 * GlanceAppWidget 负责在 WorkManager 渲染会话中读取播放器/数据库快照并声明式生成 RemoteViews。
 */
object PlayerGlanceWidget : GlanceAppWidget() {
    private const val WIDGET_COVER_SIZE_PX = 320

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readWidgetSnapshot(context)
        val cornerRadius = context.resolveSystemWidgetCornerRadius()
        val colorProviders = context.resolveWidgetColorProviders()

        provideContent {
            // 为本次桌面 widget Glance 迁移添加注释：Glance Material3 主题会在 Android 12+ 使用动态颜色 token，符合桌面小组件动态色预期。
            GlanceTheme(colors = colorProviders) {
                PlayerWidgetContent(
                    snapshot = snapshot,
                    cornerRadius = cornerRadius
                )
            }
        }
    }

    private suspend fun readWidgetSnapshot(context: Context): WidgetSnapshot {
        val repository = LibraryRepository.getInstance(context)
        val manager = PlaybackManager.getInstance(context)
        // 为本次桌面 widget Glance 迁移添加注释：仍只读取 PlaybackManager StateFlow 快照，避免在 Glance Worker 线程直接访问 MediaController。
        val activeBookId = manager.currentMediaItem.first()?.mediaId?.substringBefore(":")
            ?: manager.currentPlayingBookId
        val fallbackProgress = if (activeBookId == null) repository.getLastPlayedProgressSync() else null
        val book = (activeBookId ?: fallbackProgress?.bookId)?.let { repository.getBookById(it) }
        val isPlaying = manager.isPlaying.first()
        return WidgetSnapshot(
            title = book?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.player_widget_empty_title),
            subtitle = formatSubtitle(context, book),
            cover = loadCoverBitmap(book?.thumbnailPath ?: book?.coverPath),
            isPlaying = isPlaying
        )
    }

    private fun loadCoverBitmap(coverPath: String?): Bitmap? {
        if (coverPath.isNullOrBlank()) return null
        val file = File(coverPath)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = (maxSide / WIDGET_COVER_SIZE_PX).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        // 为本次桌面 widget Glance 迁移添加注释：封面仍降采样后交给 Glance，避免小组件后台刷新时解码大图造成内存峰值。
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun formatSubtitle(context: Context, book: BookEntity?): String {
        val contributors = listOfNotNull(
            book?.author?.takeIf { it.isNotBlank() },
            book?.narrator?.takeIf { it.isNotBlank() }
        )
        // 为本次桌面 widget Glance 迁移添加注释：优先显示作者/朗读者，空库或无元数据时显示安静的待播放状态。
        return contributors.joinToString(" · ").ifBlank {
            context.getString(R.string.player_widget_empty_subtitle)
        }
    }
}

@Composable
private fun PlayerWidgetContent(
    snapshot: WidgetSnapshot,
    cornerRadius: Dp
) {
    val context = LocalContext.current
    val openPlayerAction = actionStartActivity(MainActivity.createOpenPlayerOverlayIntent(context))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // 为本次桌面 widget Glance 迁移添加注释：改用 androidx.glance.color.ColorProvider 
            // 并同时指定 day/night 颜色以绕过新版本 Glance 的 API 限制，确保背景色在所有模式下保持一致。
            .background(ColorProvider(day = Color(0xFF111111), night = Color(0xFF111111)))
            .appWidgetBackground()
            .cornerRadius(cornerRadius)
            .clickable(openPlayerAction)
    ) {
        val coverProvider = snapshot.cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.ic_launcher_background)
        Image(
            provider = coverProvider,
            contentDescription = context.getString(R.string.player_widget_cover_description),
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier
                .fillMaxSize()
                // 为本次桌面 widget Glance 迁移添加注释：封面本身也使用同一系统圆角，避免图片边缘在 launcher 强制裁切时露出硬矩形。
                .cornerRadius(cornerRadius)
        )

        // 为本次桌面 widget Glance 迁移添加注释：封面上叠加全局暗色遮罩，确保不同封面亮度下文字与图标都稳定可读。
        Spacer(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = Color(0x66000000), night = Color(0x66000000)))
                .cornerRadius(cornerRadius)
        )

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.Vertical.Bottom,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally
        ) {
            // 为本次桌面 widget Glance 迁移添加注释：标题组放在按钮上方更高的位置，贴近用户箭头指示区域。
            Text(
                text = snapshot.title,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(day = Color.White, night = Color.White),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                modifier = GlanceModifier.fillMaxWidth()
            )
            Text(
                text = snapshot.subtitle,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(day = Color(0xFFEDEDED), night = Color(0xFFEDEDED)),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
            )
            Spacer(modifier = GlanceModifier.height(26.dp))
            PlayerControls(snapshot.isPlaying)
        }
    }
}

@Composable
private fun PlayerControls(isPlaying: Boolean) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        WidgetIconButton(
            icon = R.drawable.ic_replay_10,
            contentDescription = R.string.player_widget_skip_backward,
            action = actionRunCallback<SkipBackwardAction>()
        )
        CenterPlayButton(isPlaying)
        WidgetIconButton(
            icon = R.drawable.ic_forward_30,
            contentDescription = R.string.player_widget_skip_forward,
            action = actionRunCallback<SkipForwardAction>()
        )
    }
}

@Composable
private fun RowScope.CenterPlayButton(isPlaying: Boolean) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                // 为本次桌面 widget Glance 迁移添加注释：中心播放按钮保留浅粉圆形焦点。
                // 同样应用 day/night 显式参数以符合新版 API 规范。
                .background(ColorProvider(day = Color(0xFFF2C5F3), night = Color(0xFFF2C5F3)))
                .cornerRadius(20.dp)
                .clickable(actionRunCallback<TogglePlayPauseAction>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
                contentDescription = context.getString(R.string.player_widget_play_pause),
                modifier = GlanceModifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RowScope.WidgetIconButton(
    icon: Int,
    contentDescription: Int,
    action: androidx.glance.action.Action
) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .height(36.dp)
            .clickable(action),
        contentAlignment = Alignment.Center
    ) {
        // 为本次桌面 widget Glance 迁移添加注释：两侧按钮仍使用等宽槽位居中，避免 2x2 小尺寸下被 launcher 裁切。
        Image(
            provider = ImageProvider(icon),
            contentDescription = context.getString(contentDescription),
            modifier = GlanceModifier.size(24.dp)
        )
    }
}

class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlayerWidgetActions.handlePlaybackAction(context.applicationContext, PlayerWidgetAction.TOGGLE_PLAY_PAUSE)
        PlayerGlanceWidget.update(context, glanceId)
    }
}

class SkipBackwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlayerWidgetActions.handlePlaybackAction(context.applicationContext, PlayerWidgetAction.SKIP_BACKWARD)
        PlayerGlanceWidget.update(context, glanceId)
    }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlayerWidgetActions.handlePlaybackAction(context.applicationContext, PlayerWidgetAction.SKIP_FORWARD)
        PlayerGlanceWidget.update(context, glanceId)
    }
}

private enum class PlayerWidgetAction {
    TOGGLE_PLAY_PAUSE,
    SKIP_BACKWARD,
    SKIP_FORWARD
}

private object PlayerWidgetActions {
    private const val SKIP_BACKWARD_MS = 10_000L
    private const val SKIP_FORWARD_MS = 30_000L

    suspend fun handlePlaybackAction(context: Context, action: PlayerWidgetAction) {
        val manager = PlaybackManager.getInstance(context)
        when (action) {
            PlayerWidgetAction.TOGGLE_PLAY_PAUSE -> {
                // 为本次桌面 widget Glance 迁移添加注释：Glance 回调运行在后台线程，继续读取 StateFlow 快照判断队列与播放状态。
                val hasActiveQueue = manager.currentMediaItem.first() != null || manager.currentPlayingBookId != null
                val isPlaying = manager.isPlaying.first()
                if (!hasActiveQueue) {
                    restoreLastPlayedBook(context, manager, playWhenReady = true)
                } else if (isPlaying) {
                    manager.pause()
                } else {
                    manager.play()
                }
            }
            PlayerWidgetAction.SKIP_BACKWARD -> {
                // 为本次桌面 widget Glance 迁移添加注释：快退按钮沿用整本书全局进度回退 10 秒。
                if (ensurePlayablePlan(context, manager)) {
                    manager.seekTo((manager.currentPosition.first() - SKIP_BACKWARD_MS).coerceAtLeast(0L))
                }
            }
            PlayerWidgetAction.SKIP_FORWARD -> {
                // 为本次桌面 widget Glance 迁移添加注释：快进按钮沿用整本书全局进度前进 30 秒。
                if (ensurePlayablePlan(context, manager)) {
                    val duration = manager.duration.first()
                    val target = manager.currentPosition.first() + SKIP_FORWARD_MS
                    manager.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                }
            }
        }
    }

    private suspend fun ensurePlayablePlan(context: Context, manager: PlaybackManager): Boolean {
        if (manager.currentMediaItem.first() != null || manager.currentPlayingBookId != null) return true
        // 为本次桌面 widget Glance 迁移添加注释：冷启动且无队列时，快退/快进先恢复最近播放书籍再执行进度跳转。
        return restoreLastPlayedBook(context, manager, playWhenReady = false)
    }

    private suspend fun restoreLastPlayedBook(
        context: Context,
        manager: PlaybackManager,
        playWhenReady: Boolean
    ): Boolean {
        // 在恢复最后一本书之前，强力调用冷启动自愈逻辑以保证拿到已自愈的位置，完美防止后台协程并发导致的时序竞争。
        AutoRewindManager.getInstance(context).performColdStartSelfHealing()

        val repository = LibraryRepository.getInstance(context)
        val progress = repository.getLastPlayedProgressSync() ?: return false
        val plan = repository.getPlaybackPlan(progress.bookId) ?: return false
        // 为本次桌面 widget Glance 迁移添加注释：复用播放计划构建逻辑，确保 widget 与应用内播放使用同一套章节、封面、进度数据。
        manager.setBookPlaybackPlan(plan, playWhenReady)
        return true
    }
}

private fun Context.resolveSystemWidgetCornerRadius(): Dp {
    val typedValue = TypedValue()
    val resolved =
        // 为本次桌面 widget Glance 迁移添加注释：Android 12+ 读取系统 dialogCornerRadius，让 widget 内部圆角匹配 launcher 强制圆角裁切。
        theme.resolveAttribute(android.R.attr.dialogCornerRadius, typedValue, true)
    if (!resolved) return 28.dp
    val px = TypedValue.complexToDimension(typedValue.data, resources.displayMetrics)
    return (px / resources.displayMetrics.density).dp
}

private fun Context.resolveWidgetColorProviders(): androidx.glance.color.ColorProviders {
    // 为本次桌面 widget Glance 迁移添加注释：Android 12+ 从系统动态色生成 Glance Material3 ColorProviders，旧系统回退到稳定 Material3 色板。
    val light =
        dynamicLightColorScheme(this)
    val dark =
        dynamicDarkColorScheme(this)
    return ColorProviders(light, dark)
}

// 为本次桌面 widget Glance 迁移添加注释：不可变快照让 Glance 声明式 UI 只消费渲染数据，不直接触碰播放器或数据库对象。
private data class WidgetSnapshot(
    val title: String,
    val subtitle: String,
    val cover: Bitmap?,
    val isPlaying: Boolean
)
