package com.ouor.mobilegit.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.QrCodes
import com.ouor.mobilegit.core.AddressKind
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.label
import com.ouor.mobilegit.ui.theme.Mono
import com.ouor.mobilegit.ui.theme.Space

/**
 * A-08 QR 전체 화면 (F-16 AC2, AC4). Always black-on-white for scanning from a projector; brightness max and
 * keep-screen-on while visible. [repo] null = web UI address.
 */
@Composable
fun QrScreen(repo: String?, nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf<AddressKind?>(null) }
    val addr = addrs.firstOrNull { it.kind == kind } ?: addrs.firstOrNull()
    val url = addr?.let { if (repo == null) it.baseUrl + "/" else it.cloneUrl(repo) }
    val qr = remember(url) { url?.let { QrCodes.bitmap(it, 768).asImageBitmap() } }

    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        val old = w?.attributes?.screenBrightness
        w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }; it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose {
            w?.let { it.attributes = it.attributes.apply { screenBrightness = old ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }; it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (repo == null) "웹 화면 주소" else "$repo clone 주소", color = Color(0xFF191F28), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = nav::back) { Icon(Icons.Outlined.Close, "닫기", tint = Color(0xFF191F28)) }
        }
        if (url == null || qr == null) {
            Spacer(Modifier.height(80.dp))
            Text("접속 가능한 네트워크가 없어요.\n와이파이·핫스팟·Tailscale 중 하나에 연결하세요.", color = Color(0xFF4E5968), textAlign = TextAlign.Center)
            return@Column
        }
        Spacer(Modifier.height(Space.lg))
        Image(qr, "QR 코드: $url", Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f), filterQuality = FilterQuality.None)
        Spacer(Modifier.height(Space.lg))
        Text(url, color = Color(0xFF191F28), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (url.length > 40) 22.sp else 28.sp,
            lineHeight = 34.sp, textAlign = TextAlign.Center)
        if (repo != null) Text("git clone $url", color = Color(0xFF4E5968), fontFamily = Mono, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.sm))
        Spacer(Modifier.height(Space.xl))
        if (addrs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            addrs.forEach { a ->
                FilterChip(selected = a == addr, onClick = { kind = a.kind }, label = { Text(a.kind.label()) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color(0xFF191F28), selectedContainerColor = Color(0xFFE5ECFF), selectedLabelColor = Color(0xFF0B2A80)))
            }
        }
        Text("주소에는 계정 정보가 들어가지 않아요", color = Color(0xFF6B7684), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = Space.lg))
    }
}
