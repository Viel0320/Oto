package com.viel.aplayer.widget

// Compose state imports. Imports produceState and getValue extensions to declare and bind asynchronously loaded state variables within compositions.
// ColorProvider migration. The legacy unit.ColorProvider package is deprecated; refactored to color.ColorProvider in Glance 1.1.0+ structures to avoid compiler deprecation warnings.
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
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.media.SeekStepPresentation

/**
 * Modern declarative desktop media control widget.
 * 
 * Core Responsibilities:
 * 1. Extends GlanceAppWidget, adhering to Jetpack Glance declarative coding standards.
 * 2. Utilizes PreferencesGlanceStateDefinition to bind with the main process DataStore for real-time state monitoring and presentation.
 * 3. Supports Material 3 dynamic color styling (GlanceTheme), adapting directly to the system wallpaper color palette on Android 12+.
 * 4. Renders a tight 2x2 adaptive dual-line layout with compact visual balance.
 */
class PlayerWidget : GlanceAppWidget() {

    // Datastore preferences configuration. Configures Glance internal state storage to use Preferences definition.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // 1. Read persisted playback metadata from the Datastore.
            val prefs = currentState<Preferences>()
            val isPlaying = prefs[PlayerWidgetStateHelper.KEY_IS_PLAYING] ?: false
            
            // Placeholder fallbacks. Provide friendly placeholder fallbacks for empty metadata cases.
            val title = prefs[PlayerWidgetStateHelper.KEY_TITLE].let { 
                if (it.isNullOrEmpty()) "APlayer" else it 
            }
            val author = prefs[PlayerWidgetStateHelper.KEY_AUTHOR].let { 
                if (it.isNullOrEmpty()) context.getString(R.string.player_widget_fallback_author) else it
            }
            val coverPath = prefs[PlayerWidgetStateHelper.KEY_COVER_PATH] ?: ""
            // Widget Seek Step State (Validate stored widget step values before resolving resources)
            // Widget DataStore can lag behind service updates, so invalid values fall back to the same direction defaults as AppSettings.
            val backwardStep = SeekStepSeconds.fromSecondsOrDefault(
                prefs[PlayerWidgetStateHelper.KEY_SEEK_BACKWARD_SECONDS],
                SeekStepSeconds.Ten
            )
            val forwardStep = SeekStepSeconds.fromSecondsOrDefault(
                prefs[PlayerWidgetStateHelper.KEY_SEEK_FORWARD_SECONDS],
                SeekStepSeconds.Twenty
            )

            // 2. State bridging optimization. The UI composition layer only bridges Glance states to Bitmap representations; I/O bounds, downsampling, and fallbacks are delegated to WidgetCoverArtRenderer.
            // This prevents scattering disk operations and BitmapFactory operations within UI components, ensuring the widget retrieves a small memory-safe bitmap rather than using main-feed large image cache footprints.
            val bitmap by produceState<Bitmap?>(initialValue = null, coverPath) {
                value = WidgetCoverArtRenderer.loadCoverBitmap(coverPath)
            }

            // 3. Wrap the composition in GlanceTheme to inherit Material 3 dynamic color schemes.
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
     * Widget UI construction. Renders the 2x2 desktop widget layout with high fidelity.
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
        // Card click navigation. Clicking empty card areas launches MainActivity smoothly via SingleTop, instead of automatically pulling up full-screen player interfaces.
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(openAppIntent))
        ) {
            // 1. Render the physical cover art as the full-screen cropped background.
            if (coverBitmap != null) {
                Image(
                    provider = ImageProvider(coverBitmap),
                    contentDescription = context.getString(R.string.player_widget_background_cover_description),
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback background. Use the bundled default neon-gradient placeholder when no cover is present.
                Image(
                    provider = ImageProvider(R.drawable.widget_cover_placeholder),
                    contentDescription = context.getString(R.string.player_widget_background_placeholder_description),
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // 2. Dark scrim overlay. Superimpose a 55% transparent black scrim; ColorProvider requires explicit day and night definitions for identical colors.
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(day = Color.Black.copy(alpha = 0.55f), night = Color.Black.copy(alpha = 0.55f)))
            ) {}

            // 3. Compact flex columns. Center content containers vertically and horizontally, contracting vertical paddings to preserve miniature layouts.
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Meta alignment. Align text information horizontally using solid white and translucent white colors for optimal readability contrast.
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            // Solid white text color. Due to the lack of single-parameter constructors in Glance ColorProvider, pass identical colors for day and night parameters.
                            color = ColorProvider(day = Color.White, night = Color.White),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = author,
                        style = TextStyle(
                            // Translucent subtitle color. Pass identical day and night values to satisfy the dual-argument constructor and avoid compile issues.
                            color = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f)),
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(top = 1.dp)
                    )
                }

                // Playback row layout. Align playback controls horizontally with a compact width, appending an 8.dp bottom margin to keep icons off visual screen edges.
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip backward action. Downscale the button size to 24dp.
                    // The receiver is securely routed to the non-exported PlayerWidgetActionReceiver to prevent unauthorized system service access.
                    val rewindIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_REWIND
                    }
                    Image(
                        provider = ImageProvider(SeekStepPresentation.backwardIcon(backwardStep)),
                        contentDescription = context.getString(SeekStepPresentation.backwardLabel(backwardStep)),
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(rewindIntent)),
                        // Icon Color Filtering (Color tint filtering for rewind button icon, using two-argument ColorProvider.day/night to comply with Glance API specifications)
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )

                    // Icon spacing contraction. Shrink spacer width to 16dp to avoid layout overflow.
                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    // Play/pause control button. Renders a 34dp container wrapping a 22dp icon.
                    // The destination receiver is routed to the non-exported PlayerWidgetActionReceiver to prevent third-party applications from launching the service.
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
                            contentDescription = context.getString(R.string.player_widget_play_pause),
                            modifier = GlanceModifier.size(22.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary)
                        )
                    }

                    Box(modifier = GlanceModifier.width(16.dp)) {}

                    // Skip forward action. Downscale the button size to 24dp.
                    // Employs the non-exported PlayerWidgetActionReceiver to prevent external playback control command injections.
                    val forwardIntent = Intent(context, PlayerWidgetActionReceiver::class.java).apply {
                        action = PlayerWidgetActionReceiver.ACTION_FORWARD
                    }
                    Image(
                        provider = ImageProvider(SeekStepPresentation.forwardIcon(forwardStep)),
                        contentDescription = context.getString(SeekStepPresentation.forwardLabel(forwardStep)),
                        modifier = GlanceModifier
                            .size(24.dp)
                            .clickable(actionSendBroadcast(forwardIntent)),
                        // Color filter tinting. Use a dual-argument ColorProvider to satisfy the new API constructors.
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )
                }
            }
        }
    }
}
