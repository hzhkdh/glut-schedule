package com.glut.schedule.ui.pages

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.glut.schedule.R
import com.glut.schedule.service.campus.SchoolOverviewDocument
import com.glut.schedule.service.campus.SchoolSong
import kotlinx.coroutines.delay

private enum class SongTextTab(val label: String) {
    LYRICS("歌词"), NOTES("注释"), MEANING("歌词大意"), MOTTO("校训释义")
}

@Composable
internal fun SchoolSongContent(
    song: SchoolSong,
    document: SchoolOverviewDocument,
    listState: LazyListState,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var textTab by rememberSaveable { mutableStateOf(SongTextTab.LYRICS) }
    var position by rememberSaveable { mutableLongStateOf(0L) }
    var requested by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    // 点播时才加载学校官网原片，阅读栏目和全屏界面共用同一个播放器。
    val player = remember(requested) {
        if (!requested) null else ExoPlayer.Builder(context).build().apply {
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
            setAudioAttributes(attributes, true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(song.videoUrl), position)
        }
    }
    if (player != null) {
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { playbackFailed = true }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    position = player.currentPosition
                }

                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    position = new.positionMs
                }
            }
            player.addListener(listener)
            player.prepare()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()
            onDispose {
                position = player.currentPosition
                player.removeListener(listener)
                player.release()
            }
        }
        DisposableEffect(lifecycle, player) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    position = player.currentPosition
                    player.pause()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(player, playing) {
            if (playing) while (true) {
                delay(1_000)
                position = player.currentPosition
            }
        }
    }

    val retryPlayback = {
        playbackFailed = false
        player?.prepare()
        player?.play()
        Unit
    }
    if (fullscreen && player != null) {
        // 横竖屏只替换播放器视图，ExoPlayer 继续由同一个校歌页面持有。
        FullscreenSongVideo(player, playbackFailed, { onFullscreenChange(false) }, retryPlayback)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "school-song-video") {
            Text(song.title, color = SchoolOverviewNavy, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("2025 版校歌 MV", color = OverviewSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                if (player == null) SongVideoPoster { requested = true }
                else SongVideoPlayer(player, playbackFailed, false, onFullscreenChange, retryPlayback)
            }
        }
        item(key = "school-song-tabs") {
            SongTextTabs(textTab) { textTab = it }
        }
        item(key = "school-song-reading") {
            SelectionContainer { SongText(song, textTab) }
        }
        item(key = "school-song-sources") { OverviewSources(document) }
    }
}

@Composable
private fun SongTextTabs(selected: SongTextTab, onSelect: (SongTextTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OverviewHighlight)
            .padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SongTextTab.entries.forEach { tab ->
            val active = selected == tab
            Box(Modifier.weight(if (tab == SongTextTab.LYRICS || tab == SongTextTab.NOTES) 0.8f else 1.2f)
                .heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                .background(if (active) OverviewSurface else Color.Transparent)
                .selectable(active, role = Role.Tab) { onSelect(tab) }
                .padding(horizontal = 2.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(tab.label, color = if (active) SchoolOverviewNavy else OverviewSecondary,
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SongText(song: SchoolSong, tab: SongTextTab) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(OverviewSurface)
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tab) {
            SongTextTab.LYRICS -> song.lyrics.forEach {
                SongParagraph(it, modifier = Modifier.fillMaxWidth(), alignment = TextAlign.Center)
            }
            SongTextTab.NOTES -> song.notes.forEach { note ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SongTextHeading(note.term)
                    SongParagraph(note.text)
                }
            }
            SongTextTab.MEANING -> song.meaning.forEach { SongParagraph(it) }
            SongTextTab.MOTTO -> song.mottoSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SongTextHeading(section.title)
                    section.paragraphs.forEach { SongParagraph(it) }
                }
            }
        }
    }
}

@Composable
private fun SongTextHeading(text: String) {
    Text(text, color = SchoolOverviewNavy, style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SongParagraph(text: String, modifier: Modifier = Modifier, alignment: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, color = OverviewText, style = MaterialTheme.typography.bodyMedium,
        lineHeight = 23.sp, textAlign = alignment)
}

@Composable
private fun SongVideoPoster(onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onPlay)
        .semantics { contentDescription = "播放校歌视频" }, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.school_song_poster), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.68f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SongVideoPlayer(
    player: ExoPlayer,
    failed: Boolean,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("校歌视频暂时无法播放", color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = onRetry) { Text("重试", color = Color(0xFFD3E7FC)) }
            }
        }
    } else {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setEnableComposeSurfaceSyncWorkaround(true)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
            update = { view ->
                view.setFullscreenButtonClickListener { onFullscreenChange(it) }
                view.setFullscreenButtonState(fullscreen)
            }
        )
    }
}

@Composable
private fun FullscreenSongVideo(
    player: ExoPlayer,
    failed: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit
) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val bars = WindowInsetsCompat.Type.systemBars()
        val oldBehavior = controller?.systemBarsBehavior
        // 在 Activity 自己的窗口内旋转，避免旋转中的弹窗销毁触发提前退出。
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(bars)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (oldBehavior != null) controller?.systemBarsBehavior = oldBehavior
            controller?.show(bars)
            if (activity != null && originalOrientation != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        SongVideoPlayer(player, failed, true, { onClose() }, onRetry)
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Outlined.Close, "退出全屏", tint = Color.White)
        }
    }
}
