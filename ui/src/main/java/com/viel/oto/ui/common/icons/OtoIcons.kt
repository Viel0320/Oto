package com.viel.oto.ui.common.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.viel.oto.shared.R

/**
 * Provides ImageVector accessors backed by checked-in drawable resources.
 *
 * The app keeps Material icon path data as local XML drawables so UI code no longer depends on
 * the Compose Material icon catalog at compile time.
 */
object OtoIcons {
    object AutoMirrored {
        object Rounded {
            val ArrowBack: ImageVector
                @Composable get() = ImageVector.vectorResource(R.drawable.ic_auto_mirrored_rounded_arrow_back)

            val List: ImageVector
                @Composable get() = ImageVector.vectorResource(R.drawable.ic_auto_mirrored_rounded_list)

            val OpenInNew: ImageVector
                @Composable get() = ImageVector.vectorResource(R.drawable.ic_auto_mirrored_rounded_open_in_new)

            val VolumeDown: ImageVector
                @Composable get() = ImageVector.vectorResource(R.drawable.ic_auto_mirrored_rounded_volume_down)

        }
    }

    object Default {
        val Visibility: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_filled_visibility)

        val VisibilityOff: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_filled_visibility_off)

    }

    object Rounded {
        val Add: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_add)

        val Bedtime: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_bedtime)

        val BlurOn: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_blur_on)

        val BookmarkAdd: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_bookmark_add)

        val Check: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_check)

        val CheckCircle: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_check_circle)

        val Clear: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_clear)

        val Cloud: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_cloud)

        val CloudDownload: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_cloud_download)

        val CloudUpload: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_cloud_upload)

        val Contrast: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_contrast)

        val DarkMode: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_dark_mode)

        val Delete: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_delete)

        val Edit: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_edit)

        val Error: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_error)

        val Event: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_event)

        val FastForward: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_fast_forward)

        val FastRewind: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_fast_rewind)

        val FolderOpen: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_folder_open)

        val FormatListNumbered: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_format_list_numbered)

        val GraphicEq: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_graphic_eq)

        val History: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_history)

        val Http: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_http)

        val Info: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_info)

        val KeyboardArrowDown: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_keyboard_arrow_down)

        val LinearScale: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_linear_scale)

        val MoreVert: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_more_vert)

        val NotificationsOff: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_notifications_off)

        val Palette: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_palette)

        val Pause: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_pause)

        val PlayArrow: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_play_arrow)

        val RecordVoiceOver: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_record_voice_over)

        val Refresh: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_refresh)

        val Remove: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_remove)

        val Replay: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_replay)

        val RestartAlt: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_restart_alt)

        val Restore: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_restore)

        val Save: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_save)

        val Search: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_search)

        val Security: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_security)

        val SkipNext: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_skip_next)

        val SkipPrevious: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_skip_previous)

        val Snooze: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_snooze)

        val Speed: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_speed)

        val Storage: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_storage)

        val Sync: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_sync)

        val Timelapse: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_timelapse)

        val Translate: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_translate)

        val Tune: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_tune)

        val Vibration: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_vibration)

        val ViewModule: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_view_module)

        val Warning: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_warning)

        val Widgets: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_widgets)

        val Wifi: ImageVector
            @Composable get() = ImageVector.vectorResource(R.drawable.ic_rounded_wifi)

    }
}
