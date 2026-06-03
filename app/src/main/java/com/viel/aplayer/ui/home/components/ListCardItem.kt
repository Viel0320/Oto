package com.viel.aplayer.ui.home.components

// 补充导入 getValue 和 setValue 扩展函数以完美适配 Composable 的 by 属性代理逻辑 (H-15)
// 引入 miuix-blur 模糊视效相关的依赖，绘制极致性能的毛玻璃效果
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentlyItem(
    title: String,
    author: String,
    narrator: String,
    progressText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L, // 用于传递封面文件自愈重建时间戳，用以触发响应式强打破缓存
    // 新增长按回调函数，用于支持最近添加/播放区域的长按快捷菜单
    onLongClick: () -> Unit = {},
    // 新增 glassEffectMode 参数，使 RecentlyItem 可以响应全局磨砂玻璃雾化模式，默认值降级为传统不透明模式
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // 新增 coverColorArgb 可选参数，传递当前书籍封面的 ARGB 取色，默认为空，用于实现文字颜色同源取色融合
    coverColorArgb: Int? = null
) {
    // 判断当前是否启用 miuix-blur 模糊视效，对齐新命名的 MiuixBlur 枚举类型
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    val localBackdrop = rememberLayerBackdrop()

    Column(
        modifier = modifier
            .width(160.dp)
            .clip(RoundedCornerShape(16.dp))
            // 改用 combinedClickable 手势监听器响应点击 and 长按事件
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Background Cover (Sampling Source Only)
            // 
            // 将封面图或占位图包裹在独立的背景 Box 中，并仅将该背景 Box 注册为 layerBackdrop 采样源。
            // 这使进度 Badge（Surface）成为背景的“同级兄弟组件（Sibling）”而非“子组件（Child）”，
            // 从而彻底避免 Badge 在采样时将自己也画进模糊源中，实现极致纯净、完全正确的物理磨砂渲染层级。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isBlur) {
                            // 挂载 layerBackdrop 以便让 localBackdrop 能够正确捕获背景的渲染像素
                            Modifier.layerBackdrop(localBackdrop)
                        } else {
                            Modifier
                        }
                    )
            ) {
                // 利用 Coil 的 onError 物理防抖回调，完全剥离 Composable 重组中主线程同步调用 File.exists() 的性能隐患 (H-15)
                var isImageError by remember(coverPath) { mutableStateOf(false) }
                if ((coverPath != null) && !isImageError) {
                    // 使用 LocalContext 构建附带 lastScannedAt 作为更新戳的 ImageRequest，在底层打破 Coil 对于相同物理文件的本地与内存缓存
                    val context = LocalContext.current
                    val request = remember(coverPath, coverLastUpdated) {
                        ImageRequest.Builder(context)
                            .data(File(coverPath))
                            .memoryCacheKey("$coverPath?t=$coverLastUpdated")
                            .diskCacheKey("$coverPath?t=$coverLastUpdated")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { state ->
                            isImageError = true
                            // 当封面物理文件损坏、Scoped Storage 权限临时受阻等加载失败时，在控制台打印高清晰的可调试路径和根本原因
                            Log.e(
                                "RecentlyItem",
                                "RecentlyItem 封面加载失败！物理路径: $coverPath, 原因: ${state.result.throwable.message}",
                                state.result.throwable
                            )
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            
            // Progress Badge
            // 重构进度 Badge 容器为支持 miuix-blur 雾化的高雅白羽 Surface。
            // 当启用毛玻璃时，引入定制后可灵动调节透明度的模糊材质、透光 0.5.dp 微光描边边框；在传统模式下平滑退回为原生 Material 经典高饱和度小圆角容器。
            val isDark = isSystemInDarkTheme()

            // 
            // 升级采用基于明亮度判定（luminance）的 RGB 65% 物理通道混色强力拉伸对比度算法：
            // - 深色模式下：如果封面取色偏暗（luminance < 0.5f），与纯白色按 65% 比例拉伸混色（0.35f * rawColor + 0.65f），让文字在暗灰色磨砂底上展现出温润发光效果；
            // - 亮色模式下：如果封面取色偏亮（luminance > 0.5f），与纯黑色按 65% 比例拉伸混色（0.35f * rawColor），强力压低亮度以防止文字在乳白半透磨砂卡片上发生视觉消融。
            val coverColor = remember(coverColorArgb, isDark) {
                coverColorArgb?.let { argb ->
                    val rawColor = Color(argb)
                    val lum = rawColor.luminance()
                    if (isDark) {
                        if (lum < 0.5f) {
                            // 深色模式且取色偏暗时，应用 65% 白色强力提亮拉伸以保障对比度（0.35 * rawColor + 0.65）
                            Color(
                                red = rawColor.red * 0.35f + 0.65f,
                                green = rawColor.green * 0.35f + 0.65f,
                                blue = rawColor.blue * 0.35f + 0.65f,
                                alpha = 1f
                            )
                        } else {
                            rawColor
                        }
                    } else {
                        if (lum > 0.5f) {
                            // 亮色模式且取色偏亮时，应用 65% 黑色强力压暗拉伸以保障辨识度（0.35 * rawColor）
                            Color(
                                red = rawColor.red * 0.35f,
                                green = rawColor.green * 0.35f,
                                blue = rawColor.blue * 0.35f,
                                alpha = 1f
                            )
                        } else {
                            rawColor
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .then(
                        if (isBlur) {
                            Modifier
                                // 首先在 Modifier 链最前端裁剪圆角，杜绝毛玻璃溢出穿帮
                                .clip(RoundedCornerShape(12.dp))
                                // 使用 drawBackdrop 绘制高阶毛玻璃模糊背景
                                .drawBackdrop(
                                    backdrop = localBackdrop,
                                    shape = { RoundedCornerShape(12.dp) },
                                    effects = {
                                        blur(20f)
                                    }
                                )
                                // 使用 background 蒙版混合底色，亮色模式采用白羽半透底，深色模式采用玄羽半透底，保证最佳边缘对比度
                                .background(
                                    if (isDark) {
                                        Color.Black.copy(alpha = 0.4f)
                                    } else {
                                        Color.White.copy(alpha = 0.8f)
                                    }
                                )
                        } else {
                            Modifier
                        }
                    ),
                color = if (isBlur) {
                    // 毛玻璃模式下，由于底层的 drawBackdrop 已精细融合了半透明蒙版底色，
                    // 此处 Surface 应置为完全透明（Color.Transparent），防止双重蒙版物理重叠导致玻璃失去透光感与呼吸感。
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                },
                // 根据用户要求去掉胶囊描边以确立极简平滑的无边界透光感，此处不再传递任何描边配置
                border = null,
                shape = if (isBlur) {
                    RoundedCornerShape(12.dp)
                } else {
                    RoundedCornerShape(8.dp)
                }
            ) {
                Text(
                    text = progressText,
                    // 将左右内边距从 6.dp 增宽至 10.dp，使小胶囊的横向视觉延伸更加舒展、饱满且具备呼吸感
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    textAlign = TextAlign.Center,
                    // 使用 .copy(fontWeight = FontWeight.ExtraBold) 显式融于 Style 中，强制启用 ExtraBold (超强加粗) 字重，以在细小字号及毛玻璃背景下压榨出极致的边缘清晰度与轮廓质感
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (isBlur) {
                        // 胶囊 chip 文字使用封面取色与智能拉伸配色体系。
                        // 优先选用精细对比度处理后的 coverColor，在数据缺省时自动安全降级：
                        // - 亮色模式下使用原生 primary 强调色。
                        // - 深色模式下使用纯白色（androidx.compose.ui.graphics.Color.White），达成 100% 极佳对比度与高级毛玻璃观感。
                        coverColor ?: if (isDark) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatPeopleSubtitle(
                author.takeIf { it.isNotBlank() } ?: "Unknown",
                narrator.takeIf { it.isNotBlank() } ?: "Unknown"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true, name = "Recently Item NEW")
@Composable
fun RecentlyItemNewPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            RecentlyItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                progressText = "NEW",
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Recently Item Progress")
@Composable
fun RecentlyItemProgressPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            RecentlyItem(
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Unknown",
                progressText = "45%",
                onClick = {},
                // 在预览中显式开启毛玻璃，对齐更名后的 MiuixBlur 枚举
                glassEffectMode = GlassEffectMode.MiuixBlur
            )
        }
    }
}