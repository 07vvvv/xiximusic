package com.xixi.music.ui.login

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xixi.music.R
import com.xixi.music.data.model.QrStatus
import com.xixi.music.ui.AppViewModel
import com.xixi.music.ui.components.VipBadge

/**
 * 登录页：QQ 音乐二维码登录（每 2 秒轮询）+ 手动粘贴 Cookie 备用。
 */
@Composable
fun LoginScreen(
    viewModel: AppViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val userInfo by viewModel.userInfo.collectAsStateWithLifecycle()
    val qrImage by viewModel.qrImage.collectAsStateWithLifecycle()
    val qrStatus by viewModel.qrStatus.collectAsStateWithLifecycle()
    val qrLoading by viewModel.qrLoading.collectAsStateWithLifecycle()

    var manualCookie by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // 第一次进入登录页自动拉取二维码
    LaunchedEffect(Unit) {
        if (!isLoggedIn && qrImage.isBlank()) {
            viewModel.startQrLogin()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.login_tip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoggedIn) {
            LoggedInCard(
                nickname = nickname.ifBlank { userInfo?.nickname.orEmpty() },
                avatar = avatar.ifBlank { userInfo?.avatar.orEmpty() },
                vip = userInfo?.vip == true,
                uin = userInfo?.uin.orEmpty(),
                onLogout = { viewModel.logout() }
            )
        } else {
            QrCard(
                qrImage = qrImage,
                qrStatus = qrStatus,
                qrLoading = qrLoading,
                context = context,
                onRefresh = { viewModel.startQrLogin() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))

        // 手动 Cookie 备用方案
        TextButton(onClick = { showManual = !showManual }) {
            Text(
                text = if (showManual) "收起手动 Cookie" else stringResource(R.string.login_manual)
            )
        }

        if (showManual) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualCookie,
                onValueChange = { manualCookie = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text(text = stringResource(R.string.login_manual_hint)) },
                label = { Text(text = "Cookie") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveManualCookie(manualCookie)
                    manualCookie = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.login_manual_save))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "提示：Cookie 以明文保存在本机 DataStore 中，仅供个人自用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.login_guest_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 二维码卡片 */
@Composable
private fun QrCard(
    qrImage: String,
    qrStatus: String,
    qrLoading: Boolean,
    context: android.content.Context,
    onRefresh: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                when {
                    qrLoading -> CircularProgressIndicator()
                    qrImage.isNotBlank() -> AsyncImage(
                        model = ImageRequest.Builder(context).data(qrImage).build(),
                        contentDescription = "登录二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(200.dp)
                    )

                    else -> Text(
                        text = stringResource(R.string.login_refresh_qr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = statusText(qrStatus),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = when (qrStatus) {
                    QrStatus.EXPIRED, QrStatus.REFUSED -> MaterialTheme.colorScheme.error
                    QrStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onRefresh) {
                Text(text = stringResource(R.string.login_refresh_qr))
            }
        }
    }
}

/** 已登录卡片 */
@Composable
private fun LoggedInCard(
    nickname: String,
    avatar: String,
    vip: Boolean,
    uin: String,
    onLogout: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (avatar.isNotBlank()) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nickname.ifBlank { stringResource(R.string.login_logged_as, "QQ 用户") },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (vip) {
                    Spacer(modifier = Modifier.width(6.dp))
                    VipBadge()
                }
            }

            if (uin.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "uin: $uin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.login_logout))
            }
        }
    }
}

@Composable
private fun statusText(status: String): String = when (status) {
    QrStatus.WAITING -> stringResource(R.string.login_waiting)
    QrStatus.SCANNED -> stringResource(R.string.login_scanned)
    QrStatus.CONFIRMED -> stringResource(R.string.login_confirmed)
    QrStatus.EXPIRED -> stringResource(R.string.login_expired)
    QrStatus.REFUSED -> stringResource(R.string.login_refused)
    else -> stringResource(R.string.login_waiting)
}
