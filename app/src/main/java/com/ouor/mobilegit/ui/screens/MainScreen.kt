package com.ouor.mobilegit.ui.screens

import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.BatteryHelper
import com.ouor.mobilegit.app.ServerState
import com.ouor.mobilegit.core.AddressKind
import com.ouor.mobilegit.core.Badge
import com.ouor.mobilegit.core.RepoFilter
import com.ouor.mobilegit.core.RepoQuery
import com.ouor.mobilegit.core.RepoSort
import com.ouor.mobilegit.core.RepoType
import com.ouor.mobilegit.core.TunnelState
import com.ouor.mobilegit.app.ServerFailure
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.components.AddressBar
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.CommandBlock
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.LocalSnack
import com.ouor.mobilegit.ui.components.Loading
import com.ouor.mobilegit.ui.components.OverflowMenu
import com.ouor.mobilegit.ui.components.RepoRow
import com.ouor.mobilegit.ui.components.SecondaryButton
import com.ouor.mobilegit.ui.components.WarningChip
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.label
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import com.ouor.mobilegit.ui.theme.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A-02 메인: 서버 스위치, 접속 주소, 경고, 레포 목록 (F-01, F-02, F-04, F-05, F-09, F-12, F-16, F-17, F-22). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val server by core.server.state.collectAsStateWithLifecycle()
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
    val settings by core.settings.state.collectAsStateWithLifecycle()
    val tunnel by core.tunnel.state.collectAsStateWithLifecycle()
    val items by core.repoItems.collectAsStateWithLifecycle(initialValue = null)

    var sort by rememberSaveable { mutableStateOf(RepoSort.RECENT) }
    var filter by rememberSaveable { mutableStateOf(RepoFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var addSheet by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val lowStorage by produceState(false) {
        while (true) { value = withContext(Dispatchers.IO) { core.env.freeBytes() < core.settings.current.storageFloorBytes }; delay(30_000) }
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val batteryHint = remember(resumeTick, settings.batteryHintDismissedAt) { BatteryHelper.shouldShowHint(ctx, settings.batteryHintDismissedAt) }

    val all = items.orEmpty()
    val shown = remember(items, sort, filter, query) { RepoQuery.apply(all, sort, filter, query) }
    val delayed = all.count { Badge.DELAYED in it.badges }
    val problems = all.count { it.badges.any { b -> b in com.ouor.mobilegit.core.Badges.problem } }

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { addSheet = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(str(R.string.main_001)) },
                shape = MaterialTheme.shapes.large)
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("mobilegit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.go(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, str(R.string.main_002)) }
                    OverflowMenu {
                        item(str(R.string.main_003)) { core.mirrors.updateAll(); snack(str(R.string.main_004)) }
                        item(str(R.string.main_005)) { core.mirrors.checkAll(); snack(str(R.string.main_006)) }
                        item(str(R.string.main_007)) { nav.go(Routes.STORAGE) }
                        item(str(R.string.main_008)) { nav.go(Routes.BACKUP) }
                        item(str(R.string.main_009)) { nav.go(Routes.ACCESS_LOG) }
                        item(str(R.string.main_002)) { nav.go(Routes.SETTINGS) }
                    }
                }
            }
            if (settings.ngrokEnabled) item(key = "ngrok") {
                Banner(Tone.Danger, Icons.Outlined.Public, tunnelTitle(tunnel, server is ServerState.Running), tunnelText(tunnel, server is ServerState.Running), detail = tunnelDetail(tunnel),
                    actionLabel = str(R.string.main_011), onAction = { core.settings.update { it.copy(ngrokEnabled = false) }; snack(str(R.string.main_012)) })
            }
            if (lowStorage) item(key = "storage") {
                Banner(Tone.Danger, Icons.Outlined.SdCardAlert, str(R.string.main_013), str(R.string.main_014),
                    actionLabel = str(R.string.main_015), onAction = { nav.go(Routes.STORAGE) })
            }
            item(key = "server") {
                ServerCard(server, addrs, settings.port, settings.effectiveAuthEnabled, settings.effectiveAnonymousRead,
                    onToggle = { on -> if (on) core.server.start() else core.server.stop() },
                    onQr = { nav.go(Routes.qr(null)) }, onPort = { nav.go(Routes.SETTINGS) }, onTailscale = { dialog = "tailscale" })
            }
            val chips = !settings.effectiveAuthEnabled || batteryHint || delayed > 0
            if (chips) item(key = "chips") {
                FlowRow(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (!settings.effectiveAuthEnabled) WarningChip(str(R.string.main_016), Icons.Outlined.LockOpen, Tone.Danger) { nav.go(Routes.SETTINGS) }
                    if (delayed > 0) WarningChip(str(R.string.main_017, delayed), Icons.Outlined.HourglassBottom) { dialog = "delayed" }
                    if (batteryHint) WarningChip(str(R.string.main_018), Icons.Outlined.BatteryAlert,
                        onDismiss = { core.settings.update { it.copy(batteryHintDismissedAt = System.currentTimeMillis()) } }) { nav.go(Routes.BATTERY) }
                }
            }
            item(key = "repoHeader") {
                Column(Modifier.padding(top = Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.main_019), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(Space.sm))
                        Text("${all.size}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { sort = if (sort == RepoSort.RECENT) RepoSort.NAME else RepoSort.RECENT }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs))
                            Text(if (sort == RepoSort.RECENT) str(R.string.main_020) else str(R.string.main_021))
                        }
                    }
                    if (all.isNotEmpty()) {
                        SearchField(query) { query = it }
                        LazyRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            val opts = listOf(RepoFilter.ALL to str(R.string.main_022), RepoFilter.MIRROR to str(R.string.main_023, all.count { it.meta.type == RepoType.MIRROR }),
                                RepoFilter.LOCAL to str(R.string.main_024, all.count { it.meta.type == RepoType.LOCAL }), RepoFilter.PROBLEM to str(R.string.main_025, problems))
                            items(opts) { (f, label) -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) }) }
                        }
                    }
                }
            }
            when {
                items == null -> item(key = "loading") { Loading() }
                all.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Outlined.Inventory2, str(R.string.main_026), str(R.string.main_027)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SecondaryButton(str(R.string.add_006), icon = Icons.Outlined.SyncAlt) { nav.go(Routes.add()) }
                            SecondaryButton(str(R.string.main_028), icon = Icons.Outlined.CreateNewFolder) { nav.go(Routes.CREATE) }
                        }
                        Spacer(Modifier.height(Space.sm))
                        SecondaryButton(str(R.string.main_029), icon = Icons.Outlined.Groups) { nav.go(Routes.BULK) }
                        Spacer(Modifier.height(Space.lg))
                        Text(str(R.string.main_030), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.xs))
                        CommandBlock("git push ${addrs.firstOrNull()?.baseUrl ?: str(R.string.main_031, settings.port)}/my-project.git main")
                    }
                }
                shown.isEmpty() -> item(key = "noMatch") {
                    EmptyState(Icons.Outlined.Search, str(R.string.main_032), str(R.string.main_033)) {
                        TextButton(onClick = { query = ""; filter = RepoFilter.ALL }) { Text(str(R.string.main_034)) }
                    }
                }
                else -> items(shown, key = { it.meta.id }) { item ->
                    RepoRow(item, onClick = { nav.go(Routes.repo(item.meta.name)) },
                        onUpdate = if (item.meta.mirror != null) ({ core.mirrors.updateNow(item.meta.name); snack(str(R.string.main_035, item.meta.name)) }) else null)
                }
            }
        }
    }

    if (addSheet) ModalBottomSheet(onDismissRequest = { addSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Space.lg).padding(bottom = Space.xl).navigationBarsPadding()) {
            Text(str(R.string.main_001), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.sm))
            AddOption(Icons.Outlined.SyncAlt, str(R.string.add_006), str(R.string.main_036)) { addSheet = false; nav.go(Routes.add()) }
            AddOption(Icons.Outlined.Groups, str(R.string.add_047), str(R.string.main_037)) { addSheet = false; nav.go(Routes.BULK) }
            AddOption(Icons.Outlined.CreateNewFolder, str(R.string.add_087), str(R.string.main_038)) { addSheet = false; nav.go(Routes.CREATE) }
        }
    }

    when (dialog) {
        "delayed" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(str(R.string.main_039)) },
            text = { Text(str(R.string.main_040)) },
            confirmButton = { TextButton(onClick = { dialog = null; nav.go(Routes.BATTERY) }) { Text(str(R.string.main_041)) } },
            dismissButton = { TextButton(onClick = { dialog = null; filter = RepoFilter.PROBLEM }) { Text(str(R.string.main_042)) } },
        )
        "tailscale" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(str(R.string.main_043)) },
            text = {
                Text(str(R.string.main_044) +
                    str(R.string.main_045))
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(str(R.string.main_046)) } },
            dismissButton = { TextButton(onClick = { dialog = null; nav.go(Routes.SETTINGS) }) { Text(str(R.string.main_047)) } },
        )
    }
}

fun tunnelText(t: TunnelState, serverRunning: Boolean): String = when {
    !serverRunning -> str(R.string.main_048)
    else -> when (t) {
        TunnelState.Off -> str(R.string.main_049)
        is TunnelState.Connecting -> str(R.string.main_050, t.attempt)
        is TunnelState.Connected -> str(R.string.main_051, t.publicUrl)
        is TunnelState.Retrying -> str(R.string.tunnel_retrying, com.ouor.mobilegit.ui.time(t.atMillis))
        is TunnelState.TokenRejected -> str(R.string.tunnel_token_rejected)
    }
}

/** Raw ngrok error, shown only under "자세히" (QA-07). */
fun tunnelDetail(t: TunnelState): String? = when (t) { is TunnelState.Retrying -> t.reason; is TunnelState.TokenRejected -> t.reason; else -> null }

fun tunnelTitle(t: TunnelState, serverRunning: Boolean): String = when {
    serverRunning && t is TunnelState.Connected -> str(R.string.tunnel_title_connected)
    serverRunning && t is TunnelState.TokenRejected -> str(R.string.tunnel_title_failed)
    else -> str(R.string.tunnel_title_pending)
}

@Composable
private fun ServerCard(
    server: ServerState, addrs: List<com.ouor.mobilegit.core.ServerAddress>, port: Int, auth: Boolean, anon: Boolean,
    onToggle: (Boolean) -> Unit, onQr: () -> Unit, onPort: () -> Unit, onTailscale: () -> Unit,
) {
    val running = server is ServerState.Running
    val blocked = server is ServerState.Failed && server.kind == ServerFailure.BACKGROUND_START_BLOCKED
    val on = running || server is ServerState.Starting || blocked // blocked: the core restarts it once the app is open
    val (title, sub, t) = when (server) {
        is ServerState.Running -> Triple(str(R.string.main_054), str(R.string.main_055, server.port) + when { !auth -> str(R.string.main_056); anon -> str(R.string.main_057); else -> str(R.string.main_058) }, Tone.Success)
        ServerState.Starting -> Triple(str(R.string.main_059), str(R.string.main_060, port), Tone.Info)
        ServerState.Stopping -> Triple(str(R.string.main_061), str(R.string.main_062), Tone.Neutral)
        ServerState.Stopped -> Triple(str(R.string.main_063), str(R.string.main_064), Tone.Neutral)
        is ServerState.Failed -> Triple(str(R.string.main_063), str(R.string.main_065), Tone.Danger)
    }
    com.ouor.mobilegit.ui.components.SectionCard {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(role = Role.Switch, enabled = server !is ServerState.Stopping) { onToggle(!on) },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (running) tone(Tone.Success).content else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (t == Tone.Danger) tone(t).content else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = null, enabled = server !is ServerState.Stopping)
        }
        if (server is ServerState.Failed) {
            Spacer(Modifier.height(Space.md))
            val portBusy = server.kind == ServerFailure.PORT_IN_USE
            Banner(if (blocked) Tone.Warning else Tone.Danger, Icons.Outlined.Close, server.message,
                when { portBusy -> str(R.string.main_066); blocked -> str(R.string.server_blocked_hint); else -> null },
                actionLabel = if (portBusy) str(R.string.main_067) else null, onAction = onPort, detail = server.detail)
        }
        Spacer(Modifier.height(Space.md))
        if (addrs.isEmpty()) {
            Banner(Tone.Warning, Icons.Outlined.WifiOff, str(R.string.main_068), str(R.string.main_069),
                actionLabel = str(R.string.main_070), onAction = onTailscale)
        } else {
            val primary = addrs.first()
            AddressBar(str(R.string.main_071, primary.kind.label()), primary.baseUrl, large = true, enabled = running, onQr = onQr)
            addrs.drop(1).forEach { a -> Spacer(Modifier.height(Space.xs)); AddressBar(str(R.string.main_071, a.kind.label()), a.baseUrl, enabled = running) }
            Text(
                if (running) str(R.string.main_072) else str(R.string.main_073),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm, start = Space.xs),
            )
            if (addrs.none { it.kind == AddressKind.TAILSCALE }) {
                Text(str(R.string.main_074), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Space.xs).clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onTailscale).padding(Space.xs))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value, onChange, Modifier.fillMaxWidth().padding(top = Space.xs), singleLine = true,
        placeholder = { Text(str(R.string.main_075)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, str(R.string.main_076)) } },
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun AddOption(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
