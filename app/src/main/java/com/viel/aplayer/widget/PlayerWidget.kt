package com.viel.aplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.glance.color.ColorProvider
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
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.media.SeekStepPresentation
import com.viel.aplayer.shared.settings.SeekStepSeconds

/**
 * Declarative desktop media control widget.
 *
 * Reads Glance preferences written by the playback process, renders compact metadata and transport
 * controls, and relies on GlanceTheme for launcher-provided dynamic color support.
 */
class PlayerWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val isPlaying = prefs[PlayerWidgetStateHelper.KEY_IS_PLAYING] ?: false

            val title = prefs[PlayerWidgetStateHelper.KEY_TITLE].let {
                if (it.isNullOrEmpty()) "Oto" else it
            }
            val author = prefs[PlayerWidgetStateHelper.KEY_AUTHOR].let {
                if (it.isNullOrEmpty()) context.getString(R.string.player_widget_fallback_author) else it
            }
            val coverPath = prefs[PlayerWidgetStateHelper.KEY_COVER_PATH] ?: ""
            val backwardStep = SeekStepSeconds.fromSecondsOrDefault(
                prefs[PlayerWidgetStateHelper.KEY_SEEK_BACKWARD_SECONDS],
                SeekStepSeconds.Ten
            )
            val forwardStep = SeekStepSeconds.fromSecondsOrDefault(
                prefs[PlayerWidgetStateHelper.KEY_SEEK_FORWARD_SECONDS],
                SeekStepSeconds.Twenty
            )

            val bitmap by produceState<Bitmap?>(initialValue = null, coverPath) {
                value = WidgetCoverArtRenderer.loadCoverBitmap(coverPath)
            }

            GlanceTheme {
                WidgetLayout(
                    context = context,
                    isPlaying = isPlaying,
                    title = title,
                    author = author,
                    coverBitmap = bitmap,
                    backwardStep = backwardStep,
                    forwardStep = forwardStep
                )
            }
        }
    }

    /**
     * Renders the compact widget layout from already-resolved playback state.
     */
    @Composable
    private fun WidgetLayout(
        context: Context,
        isPlaying: Boolean,
        title: String,
        author: String,
        coverBitmap: Bitmap?,
        backwardStep: SeekStepSeconds,
        forwardStep: SeekStepSeconds
    ) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(openAppIntent))
        ) {
            if (coverBitmap != null) {
                Image(
                    provider = ImageProvider(coverBitmap),
                    contentDescription = context.getString(R.string.player_widget_background_cover_description),
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.widget_cover_placeholder),
                    contentDescription = context.getString(R.string.player_widget_background_placeholder_description),
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(day = Color.Black.copy(alpha = 0.55f), night = Color.Black.copy(alpha = 0.55f)))
            ) {}

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            color = ColorProvider(day = Color.White, night = Color.White),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = author,
                        style = TextStyle(
                            color = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f)),
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(top = 1.dp)
                    )
                }

                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rewindIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_REWIND
                    }
                    Image(
                        provider = ImageProvider(SeekStepPresentation.backwardIcon(backwardStep)),
                        contentDescription = context.getString(SeekStepPresentation.backwardLabel(backwardStep)),
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(rewindIntent)),
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )

                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    val playPauseIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_PLAY_PAUSE
                    }
                    val playPauseIconRes = PlayerWidgetPlaybackPresentation.playPauseIcon(isPlaying)
                    val playPauseLabelRes = PlayerWidgetPlaybackPresentation.playPauseContentDescription(isPlaying)
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
                            contentDescription = context.getString(playPauseLabelRes),
                            modifier = GlanceModifier.size(22.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary)
                        )
                    }

                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    val forwardIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_FORWARD
                    }
                    Image(
                        provider = ImageProvider(SeekStepPresentation.forwardIcon(forwardStep)),
                        contentDescription = context.getString(SeekStepPresentation.forwardLabel(forwardStep)),
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(forwardIntent)),
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )
                }
            }
        }
    }
}
