package com.ouor.mobilegit.ui.screens

import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ouor.mobilegit.ui.breakable
import com.ouor.mobilegit.ui.components.copyText
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
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
    // AND-17: 768px encode off the main thread
    val qr by produceState<ImageBitmap?>(null, url) { value = url?.let { withContext(Dispatchers.Default) { QrCodes.bitmap(it, 768).asImageBitmap() } } }

    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        val old = w?.attributes?.screenBrightness
        val bars = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val oldLight = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = true // white page: dark status icons
        w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }; it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose {
            oldLight?.let { bars.isAppearanceLightStatusBars = it }
            w?.let { it.attributes = it.attributes.apply { screenBrightness = old ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }; it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (repo == null) str(R.string.qr_title_web) else str(R.string.qr_title_clone, repo), color = Color(0xFF191F28), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = nav::back) { Icon(Icons.Outlined.Close, str(R.string.common_close), tint = Color(0xFF191F28)) }
        }
        if (url == null) {
            Spacer(Modifier.height(80.dp))
            Text(str(R.string.qr_no_network), color = Color(0xFF4E5968), textAlign = TextAlign.Center)
            return@Column
        }
        Spacer(Modifier.height(Space.lg))
        val qrMod = Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f)
        qr?.let { Image(it, str(R.string.qr_image_desc, url), qrMod, filterQuality = FilterQuality.None) } ?: androidx.compose.foundation.layout.Box(qrMod)
        Spacer(Modifier.height(Space.lg))
        Text(breakable(url), color = Color(0xFF191F28), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = when { url.length > 40 -> 16.sp; url.length > 24 -> 20.sp; else -> 26.sp },
            lineHeight = 30.sp, textAlign = TextAlign.Center)
        if (repo != null) Text(breakable("git clone $url"), color = Color(0xFF4E5968), fontFamily = Mono, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.sm))
        // review #10: say who scans this, and give copy/share for the PC case
        Text(str(R.string.qr_who), color = Color(0xFF333D4B), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.lg))
        Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF191F28))
            OutlinedButton(onClick = { ctx.copyText("mobilegit", url); android.widget.Toast.makeText(ctx, str(R.string.common_copied), android.widget.Toast.LENGTH_SHORT).show() }, colors = colors) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text(str(R.string.common_copy))
            }
            OutlinedButton(onClick = {
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url), null))
            }, colors = colors) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text(str(R.string.qr_share))
            }
        }
        Spacer(Modifier.height(Space.xl))
        if (addrs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            addrs.forEach { a ->
                FilterChip(selected = a == addr, onClick = { kind = a.kind }, label = { Text(a.kind.label()) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color(0xFF191F28), selectedContainerColor = Color(0xFFE5ECFF), selectedLabelColor = Color(0xFF0B2A80)))
            }
        }
        Text(str(R.string.qr_no_credentials), color = Color(0xFF4E5968), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Space.lg))
    }
}
