package com.xixi.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xixi.music.R
import com.xixi.music.data.model.Song
import com.xixi.music.ui.AppViewModel
import com.xixi.music.ui.components.SongRow

/**
 * 首页：顶部搜索框（statusBarsPadding）+ 推荐列表 / 搜索结果（每页 20 条）。
 *
 * 队列规则：从哪个列表点击，队列就是该列表（把整个 songs 列表交给播放器）。
 */
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val recommend by viewModel.recommend.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val searchLoading by viewModel.searchLoading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val error by viewModel.homeError.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    val songs: List<Song> = if (searching) results else recommend
    val listTitle = if (searching) {
        stringResource(R.string.home_search_result)
    } else {
        stringResource(R.string.home_recommend)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding)
    ) {
        // 顶部搜索区：状态栏内边距，避免被 Android 15 状态栏遮挡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.action_search)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                    }
                )
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // 列表标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = listTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            if (searching && results.isNotEmpty()) {
                Text(
                    text = "共 ${results.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // 加载中：只有还没有任何结果时才显示整屏 loading
                (searchLoading && results.isEmpty()) || (searching && results.isEmpty() && error.isBlank()) -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // 错误
                error.isNotBlank() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { viewModel.retryHome() }) {
                            Text(text = stringResource(R.string.action_retry))
                        }
                    }
                }

                // 空结果
                songs.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searching) {
                                stringResource(R.string.empty_result)
                            } else {
                                stringResource(R.string.loading)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 列表
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(
                            items = songs,
                            key = { index, song -> "${song.songId}-$index" }
                        ) { index, song ->
                            SongRow(
                                song = song,
                                index = index,
                                isCurrent = playerState.hasSong && playerState.songId == song.songId,
                                onClick = {
                                    keyboard?.hide()
                                    viewModel.playFromList(songs, index)
                                }
                            )
                        }

                        // 分页加载更多
                        if (searching) {
                            item(key = "load-more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        loadingMore -> CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp)
                                        )

                                        hasMore -> TextButton(onClick = { viewModel.loadMore() }) {
                                            Text(text = stringResource(R.string.action_load_more))
                                        }

                                        else -> Text(
                                            text = stringResource(R.string.no_more),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
