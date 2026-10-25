package com.ouor.mobilegit.ui.screens

import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.BatteryHelper
import com.ouor.mobilegit.core.AccessAction
import com.ouor.mobilegit.core.AccessLogStore
import com.ouor.mobilegit.core.AccessRoute
import com.ouor.mobilegit.core.CredentialStore
import com.ouor.mobilegit.core.RemoteCredential
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.ago
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.ConfirmDialog
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.LocalSnack
import com.ouor.mobilegit.ui.components.OverflowMenu
import com.ouor.mobilegit.ui.components.Pill
import com.ouor.mobilegit.ui.components.PrimaryButton
import com.ouor.mobilegit.ui.components.RowGroup
import com.ouor.mobilegit.ui.components.ScreenColumn
import com.ouor.mobilegit.ui.components.SecondaryButton
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.components.SectionHeader
import com.ouor.mobilegit.ui.components.SwitchRow
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.label
import com.ouor.mobilegit.ui.style
import com.ouor.mobilegit.ui.theme.CodeText
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import com.ouor.mobilegit.ui.time

// ---------------- A-11 원격 자격 증명 ----------------

/** A-11 (F-07). [prefillHost] opens the add dialog for that host (from A-03/A-07/notifications). */
@Composable
fun CredentialsScreen(prefillHost: String?, nav: Nav) {
    val core = core()
    val snack = LocalSnack.current
    val items by core.credentials.items.collectAsStateWithLifecycle()
    val repos by core.repos.repos.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RemoteCredential?>(null) }
    var adding by rememberSaveable { mutableStateOf(prefillHost != null) }
    var deleting by remember { mutableStateOf<RemoteCredential?>(null) }

    AppScaffold(str(R.string.add_003), onBack = nav::back, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(str(R.string.add_036)) }, shape = MaterialTheme.shapes.large)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.sm, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                Text(str(R.string.manage_001),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.isEmpty()) item { EmptyState(Icons.Outlined.Key, str(R.string.manage_002), str(R.string.manage_003)) }
            items(items, key = { it.id }) { c ->
                val used = core.credentials.usageCount(c.id, repos)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = Space.lg, top = Space.md, bottom = Space.md, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label.ifBlank { c.host }, style = MaterialTheme.typography.titleSmall)
                            Text("${c.host} · ${c.username ?: str(R.string.manage_004, CredentialStore.defaultUser(c.host))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Pill("${c.tokenPrefix}••••", Tone.Neutral)
                                Pill(if (used > 0) str(R.string.manage_005, used) else str(R.string.manage_006), if (used > 0) Tone.Info else Tone.Neutral)
                            }
                        }
                        IconButton(onClick = { editing = c }) { Icon(Icons.Outlined.Edit, str(R.string.manage_007, c.host)) }
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Outlined.DeleteOutline, str(R.string.manage_008, c.host)) }
                    }
                }
            }
        }
    }
    if (adding || editing != null) CredentialDialog(editing, prefillHost.takeIf { editing == null }, onDismiss = { adding = false; editing = null })
    deleting?.let { c ->
        val used = core.credentials.usageCount(c.id, repos)
        ConfirmDialog(str(R.string.manage_009), if (used > 0) str(R.string.manage_010, used) else str(R.string.manage_011), str(R.string.manage_012), destructive = true,
            onConfirm = { core.credentials.delete(c.id); deleting = null; snack(str(R.string.manage_013)) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun CredentialDialog(existing: RemoteCredential?, host0: String?, onDismiss: () -> Unit) {
    val core = core()
    val snack = LocalSnack.current
    var host by remember { mutableStateOf(existing?.host ?: host0 ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var token by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val ok = host.isNotBlank() && (existing != null || token.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) str(R.string.add_036) else str(R.string.manage_014)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(str(R.string.manage_015)) }, placeholder = { Text("github.com") }, enabled = existing == null)
            OutlinedTextField(token, { token = it; err = null }, singleLine = true, label = { Text(if (existing == null) str(R.string.manage_016) else str(R.string.manage_017)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(str(R.string.manage_018)) },
                placeholder = { Text(if (host.isBlank()) str(R.string.manage_019) else CredentialStore.defaultUser(CredentialStore.normalizeHost(host))) })
            OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(str(R.string.manage_020)) }, placeholder = { Text(str(R.string.manage_021)) })
            Text(str(R.string.manage_022), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = ok, onClick = {
            runCatching {
                if (existing == null) core.credentials.add(host, token, user, label.trim())
                else core.credentials.update(existing.id, token.ifBlank { null }, user, label.trim())
            }.onSuccess { snack(str(R.string.manage_023)); onDismiss() }.onFailure { err = it.message }
        }) { Text(str(R.string.manage_024)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.common_023)) } })
}

// ---------------- A-12 백그라운드 안내 ----------------

private val makerGuides get() = listOf(
    BatteryHelper.Maker.SAMSUNG to (str(R.string.manage_025) to str(R.string.manage_026)),
    BatteryHelper.Maker.XIAOMI to (str(R.string.manage_027) to str(R.string.manage_028)),
    BatteryHelper.Maker.ONEPLUS to (str(R.string.manage_029) to str(R.string.manage_030)),
    BatteryHelper.Maker.GOOGLE to (str(R.string.manage_031) to str(R.string.manage_032)),
)

/** A-12 (F-22 AC3). */
@Composable
fun BatteryScreen(nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val s by core.settings.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val ignoring = remember(tick) { BatteryHelper.isIgnoringOptimizations(ctx) }
    val maker = remember { BatteryHelper.maker() }
    val ordered = makerGuides.sortedByDescending { it.first == maker }

    AppScaffold(str(R.string.manage_033), onBack = nav::back) { padding ->
        ScreenColumn(padding) {
            if (ignoring) Banner(Tone.Success, Icons.Outlined.CheckCircle, str(R.string.manage_034), str(R.string.manage_035))
            else Banner(Tone.Warning, Icons.Outlined.BatteryAlert, str(R.string.manage_036), str(R.string.manage_037))
            if (!ignoring) PrimaryButton(str(R.string.manage_038)) { runCatching { ctx.startActivity(BatteryHelper.requestIntent(ctx)) }.onFailure { ctx.startActivity(BatteryHelper.settingsIntent()) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SecondaryButton(str(R.string.manage_039), Modifier.weight(1f)) { ctx.startActivity(BatteryHelper.settingsIntent()) }
                SecondaryButton(str(R.string.manage_040), Modifier.weight(1f)) { ctx.startActivity(BatteryHelper.appDetailsIntent(ctx)) }
            }

            SectionHeader(str(R.string.manage_041))
            ordered.forEach { (m, g) ->
                SectionCard(title = g.first + if (m == maker) str(R.string.manage_042) else "") { Text(g.second, style = MaterialTheme.typography.bodyMedium) }
            }
            if (maker == BatteryHelper.Maker.OTHER) SectionCard(title = str(R.string.manage_043)) {
                Text(str(R.string.manage_044), style = MaterialTheme.typography.bodyMedium)
            }

            SectionHeader(str(R.string.manage_045))
            SectionCard {
                listOf(
                    str(R.string.manage_046),
                    str(R.string.manage_047),
                    str(R.string.manage_048),
                    str(R.string.manage_049),
                ).forEach { Text("• ${it}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Space.xs)) }
            }
            RowGroup { SwitchRow(str(R.string.manage_050), s.startOnBoot, { v -> core.settings.update { it.copy(startOnBoot = v) } }) }
            Spacer(Modifier.height(Space.lg))
        }
    }
}

// ---------------- A-13 접속 기록 ----------------

/** A-13 (F-23). */
@Composable
fun AccessLogScreen(nav: Nav) {
    val core = core()
    val entries by core.accessLog.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 ngrok, 2 auth failures
    var confirm by remember { mutableStateOf(false) }
    val shown = remember(entries, filter) { AccessLogStore.filter(entries, ngrokOnly = filter == 1, authFailuresOnly = filter == 2).sortedByDescending { it.at } }

    AppScaffold(str(R.string.main_009), onBack = nav::back, actions = { OverflowMenu { item(str(R.string.manage_051), enabled = entries.isNotEmpty()) { confirm = true } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(bottom = Space.sm)) {
                    items(listOf(str(R.string.manage_052, entries.size), str(R.string.manage_053), str(R.string.fmt_019)).withIndex().toList()) { (i, l) -> FilterChip(filter == i, { filter = i }, { Text(l) }) }
                }
                Text(str(R.string.manage_054), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.sm))
            }
            if (shown.isEmpty()) item { EmptyState(Icons.Outlined.ListAlt, if (entries.isEmpty()) str(R.string.manage_055) else str(R.string.manage_056), str(R.string.manage_057)) }
            items(shown, key = { "${it.at}-${it.clientIp}-${it.status}-${it.repoName}-${it.action}" }) { e -> AccessRow(e) }
        }
    }
    if (confirm) ConfirmDialog(str(R.string.manage_058), str(R.string.manage_059), str(R.string.manage_060), destructive = true, onConfirm = { core.accessLog.clear(); confirm = false }, onDismiss = { confirm = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessRow(e: com.ouor.mobilegit.core.AccessLogEntry) {
    val (al, at) = e.authResult.style()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm + Space.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (e.action) { AccessAction.READ -> "clone·fetch"; AccessAction.PUSH -> "push"; AccessAction.WEB -> str(R.string.manage_061) } + (e.repoName?.let { " · ${it}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${ago(e.at)} ${time(e.at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(e.clientIp, style = CodeText.small, modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(Modifier.width(Space.xxs))
                Pill(e.route.label(), if (e.route == AccessRoute.NGROK) Tone.Warning else Tone.Neutral)
                Pill(al, at)
                Pill("${e.status}", if (e.status >= 400) Tone.Danger else Tone.Neutral)
            }
        }
    }
}

/** Shared helper: notification permission state (A-01). */
fun hasNotificationPermission(ctx: android.content.Context) =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

