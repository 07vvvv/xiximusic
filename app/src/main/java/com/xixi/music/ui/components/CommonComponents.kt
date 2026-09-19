package com.xixi.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xixi.music.R
import com.xixi.music.data.model.Song
import com.xixi.music.player.PlayerUiState
import com.xixi.music.util.formatDuration

/** 专辑封面：统一走 Coil，失败时回退到占位矢量图 */
@Composable
fun SongCover(
    url: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 10
) {
    val context = LocalContext.current
    if (url.isBlank()) {
        CoverPlaceholder(modifier = modifier, cornerRadius = cornerRadius)
        return
    }
    AsyncImage(        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = stringResource(R.string.desc_cover),
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(cornerRadius.dp))
    )
}

@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier, cornerRadius: Int = 10) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** VIP 小标签（未登录也能看到，点击会提示登录） */
@Composable
fun VipBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.vip_tag),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/**
 * 列表项：封面 + 歌名 + 歌手 + VIP 标识。
 */
@Composable
fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unknownSong = stringResource(R.string.player_unknown_song)
    val unknownArtist = stringResource(R.string.player_unknown_artist)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongCover(url = song.cover, modifier = Modifier.size(48.dp))

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name.ifBlank { unknownSong },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.vip) {
                    Spacer(modifier = Modifier.width(6.dp))
                    VipBadge()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            val subtitle = buildString {
                append(song.displaySinger.ifBlank { unknownArtist })
                if (song.album.isNotBlank()) {
                    append(" · ")
                    append(song.album)
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (song.durationSeconds > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDuration(song.durationSeconds * 1000L),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 常驻底部迷你播放条。
 *
 * Android 15 适配：本条位于 NavigationBar 之上，因此这里不再叠加 navigationBarsPadding
 * （由外层 NavigationBar 统一处理系统导航栏内边距），避免出现双重空白。
 */
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.hasSong) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 极细的播放进度线。
            // 只使用 material3 长期稳定的参数（progress lambda / modifier / color / trackColor），
            // 避免依赖 drawStopIndicator 这类较新版本才有的可选参数。
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SongCover(url = state.cover, modifier = Modifier.size(42.dp), cornerRadius = 8)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.player_unknown_song) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.artist.ifBlank { stringResource(R.string.player_unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.desc_pause else R.string.desc_play
                        )
                    )
                }

                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.desc_next)
                    )
                }
            }
        }
    }
}
