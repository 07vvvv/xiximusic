package com.xixi.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xixi.music.R
import com.xixi.music.player.PlaybackMode
import com.xixi.music.ui.AppViewModel
import com.xixi.music.ui.components.SongCover
import com.xixi.music.ui.components.VipBadge
import com.xixi.music.util.LrcParser
import com.xixi.music.util.formatDuration
import kotlinx.coroutines.delay

/**
 * 播放页：封面 / 歌名 / 歌手 / 进度 / 控制 / 歌词实时滚动（可手动滚动）。
 *
 * 刻意不提供：音质信息、音质选择、下载、缓存、歌单、收藏。
 */
@Composable
fun NowPlayingScreen(
    viewModel: AppViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        if (!state.hasSong) {
            EmptyPlayer(modifier = Modifier.fillMaxSize())
            return@Column
        }

        // ---------------- 封面 ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            SongCover(
                url = state.cover,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                cornerRadius = 18
            )
            if (state.isBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------------- 歌曲信息 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.player_unknown_song) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (state.isVip) {
                Spacer(modifier = Modifier.width(6.dp))
                VipBadge()
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = state.artist.ifBlank { stringResource(R.string.player_unknown_artist) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ---------------- 进度条 ----------------
        ProgressSection(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = { viewModel.seekTo(it) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ---------------- 控制区 ----------------
        ControlSection(
            isPlaying = state.isPlaying,
            mode = state.playbackMode,
            onPrevious = { viewModel.previous() },
            onNext = { viewModel.next() },
            onToggle = { viewModel.togglePlayPause() },
            onCycleMode = { viewModel.cyclePlaybackMode() },
            queueInfo = if (state.queueSize > 0) {
                "${state.index + 1}/${state.queueSize}"
            } else {
                ""
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ---------------- 歌词 ----------------
        LyricsSection(
            lines = state.lyricLines,
            positionMs = state.positionMs,
            loading = state.lyricLoading,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyPlayer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.player_no_song),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "去「首页」搜索或点击推荐列表中的歌曲开始播放",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 进度条 + 时间（拖动过程中只更新本地状态，松手才真正 seek） */
@Composable
private fun ProgressSection(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val safeDuration = if (durationMs > 0L) durationMs else 1L
    val sliderValue = if (dragging) {
        dragValue
    } else {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    }
    val shownPosition = if (dragging) (dragValue * safeDuration).toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * safeDuration).toLong())
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(shownPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 上一首 / 播放暂停 / 下一首 / 播放模式 */
@Composable
private fun ControlSection(
    isPlaying: Boolean,
    mode: PlaybackMode,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
    onCycleMode: () -> Unit,
    queueInfo: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 播放模式（顺序/列表循环/单曲循环/随机），点击循环切换
            OutlinedButton(
                onClick = onCycleMode,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = modeLabel(mode),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = onPrevious) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.desc_prev),
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.desc_pause else R.string.desc_play
                    ),
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.desc_next),
                    modifier = Modifier.size(36.dp)
                )
            }

            // 队列位置（无音质信息展示）
            Text(
                text = queueInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun modeLabel(mode: PlaybackMode): String = stringResource(mode.labelRes)

/**
 * 歌词区：实时滚动 + 当前行高亮 + 支持手动滚动。
 *
 * 内存优化：歌词只来自当前歌曲（PrefetchCache 中只有 1 首），
 * 离开播放页后随缓存过期自动释放。
 */
@Composable
private fun LyricsSection(
    lines: List<LrcParser.Line>,
    positionMs: Long,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    // 当前歌词行（-1 表示前奏）。位置每 250ms 更新一次，
    // 但 LazyColumn 只会重组可见的那几行，开销可控。
    val currentIndex = if (lines.isEmpty()) -1 else LrcParser.findIndex(lines, positionMs)

    // 自动滚动到当前行
    LaunchedEffect(currentIndex, follow, lines) {
        if (!follow || currentIndex < 0 || lines.isEmpty()) return@LaunchedEffect
        delay(150)
        runCatching {
            listState.animateScrollToItem(currentIndex.coerceIn(0, lines.size - 1))
        }
    }

    // 用户手动滚动后暂停自动跟随；滚回当前行附近时恢复
    val scrolling by remember(listState) {
        derivedStateOf { listState.isScrollInProgress }
    }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            val first = listState.firstVisibleItemIndex
            if (currentIndex >= 0 && kotlin.math.abs(first - currentIndex) > 2) {
                follow = false
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        when {
            loading && lines.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.player_lyric_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            lines.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.player_lyric_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { index, line -> "$index-${line.timeMs}" }
                    ) { index, line ->
                        LyricLine(
                            line = line,
                            isCurrent = index == currentIndex,
                            onClick = {
                                follow = true
                            }
                        )
                    }
                }

                if (!follow) {
                    OutlinedButton(
                        onClick = { follow = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "回到当前",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLine(
    line: LrcParser.Line,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line.text,
            style = if (isCurrent) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
        if (line.translation.isNotBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                textAlign = TextAlign.Center
            )
        }
    }
}

