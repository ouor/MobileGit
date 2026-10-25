package com.ouor.mobilegit.ui.screens

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.activity.compose.BackHandler
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.ouor.mobilegit.ui.breakable
import com.ouor.mobilegit.ui.ago
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ouor.mobilegit.ui.components.rememberCopy
import com.ouor.mobilegit.ui.components.appSwitchColors
import com.ouor.mobilegit.ui.theme.CodeText
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
    val overview by core.overview.collectAsStateWithLifecycle(initialValue = null)
    val ngrokOff by core.server.ngrokOffAfterRestart.collectAsStateWithLifecycle()
    val stalled = overview?.checksStalled == true

    var sort by rememberSaveable { mutableStateOf(RepoSort.RECENT) }
    var filter by rememberSaveable { mutableStateOf(RepoFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var addSheet by rememberSaveable { mutableStateOf(false) }
    var addStep by rememberSaveable { mutableIntStateOf(0) } // 0 = choices, 1 = new local repo guide
    var guideName by rememberSaveable { mutableStateOf(DEFAULT_NAME) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lowStorage by produceState(false, lifecycle) {
        // AND-17: poll only while the screen is visible
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = withContext(Dispatchers.IO) { core.env.freeBytes() < core.settings.current.storageFloorBytes }; delay(30_000) }
        }
    }
    val damagedRepos by core.repos.damaged.collectAsStateWithLifecycle()
    val damagedState = remember(damagedRepos) { core.storageIssues() }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val batteryHint = remember(resumeTick, settings.batteryHintDismissedAt) { BatteryHelper.shouldShowHint(ctx, settings.batteryHintDismissedAt) }

    val all = items.orEmpty()
    val shown = remember(items, sort, filter, query) { RepoQuery.apply(all, sort, filter, query) }
    val problems = overview?.attention ?: 0
    val notifOk = remember(resumeTick) { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }

    val list = rememberLazyListState()
    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            val fabExpanded = all.isEmpty() || !list.canScrollForward
            // QA2-12: collapsed to "+", the icon carries the name
            ExtendedFloatingActionButton(onClick = { addSheet = true }, icon = { Icon(Icons.Outlined.Add, if (fabExpanded) null else str(R.string.main_add_repo)) },
                text = { Text(str(R.string.main_add_repo)) }, expanded = fabExpanded, shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("mobilegit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.go(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, str(R.string.main_settings)) }
                    OverflowMenu {
                        item(str(R.string.main_update_all)) { core.mirrors.updateAll(); snack(str(R.string.main_update_all_started)) }
                        item(str(R.string.main_check_all)) { core.mirrors.checkAll(); snack(str(R.string.main_check_all_started)) }
                        item(str(R.string.main_storage)) { nav.go(Routes.STORAGE) }
                        item(str(R.string.main_full_backup)) { nav.go(Routes.BACKUP) }
                        item(str(R.string.main_access_log)) { nav.go(Routes.ACCESS_LOG) }
                        item(str(R.string.main_settings)) { nav.go(Routes.SETTINGS) }
                    }
                }
            }
            item(key = "server") {
                ServerCard(server, addrs, settings.port, settings.effectiveAuthEnabled, settings.effectiveAnonymousRead,
                    onToggle = { on -> if (on) core.server.start() else core.server.stop() },
                    onQr = { nav.go(Routes.qr(null)) }, onPort = { nav.go(Routes.SETTINGS) }, onTailscale = { dialog = "tailscale" })
            }
            if (settings.ngrokEnabled) item(key = "ngrok") {
                // R2-06 AC3 / QA2-03 / RV2-03: the title follows the tunnel; while it is not live the body says first that nothing is open yet
                val live = server is ServerState.Running && tunnel is TunnelState.Connected
                val state = tunnelText(tunnel, server is ServerState.Running)
                Banner(Tone.Danger, if (settings.exposedWithoutPassword) Icons.Outlined.LockOpen else Icons.Outlined.Public,
                    if (settings.exposedWithoutPassword && live) str(R.string.main_exposed_no_password) else tunnelTitle(tunnel, server is ServerState.Running),
                    when {
                        !settings.exposedWithoutPassword -> state
                        live -> state + "\n" + str(if (settings.authEnabled) R.string.main_exposed_push_only_body else R.string.main_exposed_off_body)
                        else -> str(if (settings.authEnabled) R.string.main_exposed_pending_body else R.string.main_exposed_pending_off_body) + "\n" + state
                    },
                    detail = tunnelDetail(tunnel),
                    actionLabel = str(R.string.main_ngrok_off), onAction = { core.settings.update { it.copy(ngrokEnabled = false) }; snack(str(R.string.main_ngrok_off_done)) })
            }
            if (ngrokOff) item(key = "ngrokOff") {
                Banner(Tone.Info, Icons.Outlined.Public, str(R.string.server_notif_ngrok_off_after_restart), str(R.string.main_ngrok_off_after_restart_body),
                    actionLabel = str(R.string.main_ngrok_settings), onAction = { core.server.ackNgrokOff(); nav.go(Routes.SETTINGS) }, onDismiss = { core.server.ackNgrokOff() })
            }
            if (damagedState.isNotEmpty()) item(key = "damaged") {
                Banner(Tone.Danger, Icons.Outlined.ErrorOutline, str(R.string.main_damaged_title), str(R.string.main_damaged_body),
                    actionLabel = str(R.string.main_damaged_action), onAction = { nav.go(Routes.STORAGE) })
            }
            if (lowStorage) item(key = "storage") {
                Banner(Tone.Danger, Icons.Outlined.SdCardAlert, str(R.string.main_low_storage_title), str(R.string.main_low_storage_body),
                    actionLabel = str(R.string.main_low_storage_action), onAction = { nav.go(Routes.STORAGE) })
            }
            // RV2-05/06: one banner for one cause. A stalled background check is the battery problem showing, so it replaces the battery hint.
            if (stalled) item(key = "stalled") {
                Banner(Tone.Warning, Icons.Outlined.HourglassBottom, str(R.string.main_summary_stalled), str(R.string.main_summary_stalled_body),
                    actionLabel = str(R.string.main_battery_guide), onAction = { nav.go(Routes.BATTERY) })
            } else if (batteryHint) item(key = "battery") {
                Banner(Tone.Warning, Icons.Outlined.BatteryAlert, str(R.string.main_battery_title), str(R.string.main_battery_body),
                    actionLabel = str(R.string.main_battery_action), onAction = { nav.go(Routes.BATTERY) },
                    onDismiss = { core.settings.update { it.copy(batteryHintDismissedAt = System.currentTimeMillis()) }; snack(str(R.string.main_battery_later)) })
            }
            if (!notifOk && all.any { it.meta.mirror != null }) item(key = "notifOff") {
                Banner(Tone.Warning, Icons.Outlined.NotificationsOff, str(R.string.notif_off_title), str(R.string.notif_off_body),
                    actionLabel = str(R.string.notif_off_action), onAction = { openNotificationSettings(ctx) })
            }
            if (server !is ServerState.Running && server !is ServerState.Starting && !settings.serverOffHintDismissed && all.any { it.meta.mirror != null }) item(key = "serverOffHint") {
                Banner(Tone.Info, Icons.Outlined.Info, str(R.string.main_server_off_hint), null, onDismiss = { core.settings.update { it.copy(serverOffHintDismissed = true) } })
            }
            if (!settings.effectiveAuthEnabled && !settings.ngrokEnabled) item(key = "chips") {
                FlowRow(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    WarningChip(str(R.string.main_chip_password_off), Icons.Outlined.LockOpen, Tone.Danger) { nav.go(Routes.SETTINGS) }
                }
            }
            item(key = "repoHeader") {
                Column(Modifier.padding(top = Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.main_repos), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(Space.sm))
                        Text("${all.size}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { sort = if (sort == RepoSort.RECENT) RepoSort.NAME else RepoSort.RECENT }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs))
                            Text(if (sort == RepoSort.RECENT) str(R.string.main_sort_recent) else str(R.string.main_sort_name))
                        }
                    }
                    overview?.takeIf { all.isNotEmpty() }?.let { Summary(it, onProblems = { filter = RepoFilter.PROBLEM }) }
                    if (all.isNotEmpty()) {
                        SearchField(query) { query = it }
                        FlowRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            val opts = listOf(RepoFilter.ALL to str(R.string.main_filter_all), RepoFilter.MIRROR to str(R.string.main_filter_mirror, all.count { it.meta.type == RepoType.MIRROR }),
                                RepoFilter.LOCAL to str(R.string.main_filter_local, all.count { it.meta.type == RepoType.LOCAL })) +
                                (if (problems > 0 || filter == RepoFilter.PROBLEM) listOf(RepoFilter.PROBLEM to str(R.string.main_filter_problem, problems)) else emptyList())
                            opts.forEach { (f, label) ->
                                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) },
                                    leadingIcon = if (filter == f) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
                            }
                        }
                    }
                }
            }
            when {
                items == null -> item(key = "loading") { Loading() }
                all.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Outlined.Inventory2, str(R.string.main_empty_title), str(R.string.main_empty_body)) {
                        SecondaryButton(str(R.string.mirror_title), icon = Icons.Outlined.SyncAlt) { nav.go(Routes.add()) }
                        Spacer(Modifier.height(Space.sm))
                        SecondaryButton(str(R.string.bulk_title), icon = Icons.Outlined.Groups) { nav.go(Routes.BULK) }
                        Spacer(Modifier.height(Space.sm))
                        SecondaryButton(str(R.string.main_new_local), icon = Icons.Outlined.Computer) { addStep = 1; addSheet = true }
                    }
                }
                shown.isEmpty() -> item(key = "noMatch") {
                    EmptyState(Icons.Outlined.Search, str(R.string.main_no_match_title), str(R.string.main_no_match_body)) {
                        TextButton(onClick = { query = ""; filter = RepoFilter.ALL }) { Text(str(R.string.main_clear_filters)) }
                    }
                }
                else -> items(shown, key = { it.meta.id }) { item ->
                    RepoRow(item, settings.checkIntervalHours, onClick = { nav.go(Routes.repo(item.meta.name)) },
                        onUpdate = if (item.meta.mirror != null) ({ core.mirrors.updateNow(item.meta.name); snack(str(R.string.main_update_started, item.meta.name)) }) else null)
                }
            }
        }
    }

    if (addSheet) ModalBottomSheet(onDismissRequest = { addSheet = false; addStep = 0; guideName = DEFAULT_NAME },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // R2-12: back from the guide returns to the three choices; the typed name stays until the sheet closes
        BackHandler(enabled = addStep == 1) { addStep = 0 }
        if (addStep == 0) Column(Modifier.padding(horizontal = Space.lg).padding(bottom = Space.xl).navigationBarsPadding()) {
            Text(str(R.string.main_add_repo), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.sm))
            AddOption(Icons.Outlined.SyncAlt, str(R.string.mirror_title), str(R.string.main_add_mirror_sub)) { addSheet = false; nav.go(Routes.add()) }
            AddOption(Icons.Outlined.Groups, str(R.string.bulk_title), str(R.string.main_add_bulk_sub)) { addSheet = false; nav.go(Routes.BULK) }
            AddOption(Icons.Outlined.Computer, str(R.string.main_new_local), str(R.string.main_new_local_sub)) { addStep = 1 }
        } else PushGuide(addrs.firstOrNull()?.baseUrl, settings.port, settings.username, guideName, { guideName = it },
            onBack = { addStep = 0 }, onEmpty = { addSheet = false; addStep = 0; guideName = DEFAULT_NAME; nav.go(Routes.CREATE) })
    }

    when (dialog) {
        "tailscale" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(str(R.string.main_tailscale_title)) },
            text = {
                Text(str(R.string.main_tailscale_body) +
                    str(R.string.main_tailscale_vpn_note))
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(str(R.string.main_check)) } },
        )
    }
}

fun tunnelText(t: TunnelState, serverRunning: Boolean): String = when {
    !serverRunning -> str(R.string.tunnel_off_server_body)
    else -> when (t) {
        TunnelState.Off -> str(R.string.tunnel_preparing)
        is TunnelState.Connecting -> str(R.string.tunnel_connecting_attempt, t.attempt)
        is TunnelState.Connected -> str(R.string.tunnel_public_body, t.publicUrl)
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
    val primary = addrs.firstOrNull()
    var open by rememberSaveable { mutableStateOf(false) }
    val copy = rememberCopy()
    val (title, sub) = when (server) {
        is ServerState.Running -> str(R.string.main_server_on) to (primary?.baseUrl?.removePrefix("http://") ?: str(R.string.main_no_network_title))
        ServerState.Starting -> str(R.string.main_server_starting) to str(R.string.main_server_port, port)
        ServerState.Stopping -> str(R.string.main_server_stopping) to str(R.string.main_server_stopping_body)
        ServerState.Stopped -> str(R.string.main_server_off) to str(R.string.main_server_off_body)
        is ServerState.Failed -> str(R.string.main_server_off) to str(R.string.main_server_failed)
    }
    val showAddr = running && primary != null
    com.ouor.mobilegit.ui.components.SectionCard(padding = PaddingValues(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (running) tone(Tone.Success).content else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(Space.md))
            // the address itself copies on tap (main use); the chevron opens the details
            Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (showAddr) str(R.string.common_copy) else str(R.string.main_server_details)) { if (showAddr) copy(primary!!.baseUrl) else open = !open }) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // RV2-10: at large font sizes the address wraps only before the port, never inside "120"
                if (showAddr) Text(breakable(sub), style = CodeText.body, color = MaterialTheme.colorScheme.onSurface)
                else Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = if (server is ServerState.Failed) tone(Tone.Danger).content else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, str(R.string.main_server_details)) }
            Switch(checked = on, onCheckedChange = { onToggle(it) }, enabled = server !is ServerState.Stopping, colors = appSwitchColors(),
                modifier = Modifier.semantics { contentDescription = str(R.string.main_server_switch) })
        }
        if (server is ServerState.Failed) {
            Spacer(Modifier.height(Space.md))
            val portBusy = server.kind == ServerFailure.PORT_IN_USE
            Banner(if (blocked) Tone.Warning else Tone.Danger, Icons.Outlined.Close, server.message,
                when { portBusy -> str(R.string.main_server_port_in_use_body); blocked -> str(R.string.server_blocked_hint); else -> null },
                actionLabel = if (portBusy) str(R.string.main_change_port) else null, onAction = onPort, detail = server.detail)
        }
        if (addrs.isEmpty()) {
            Spacer(Modifier.height(Space.md))
            Banner(Tone.Warning, Icons.Outlined.WifiOff, str(R.string.main_no_network_title), str(R.string.main_no_network_body), actionLabel = str(R.string.main_tailscale_guide), onAction = onTailscale)
        }
        if (open) Column(Modifier.padding(top = Space.md, end = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(str(R.string.main_server_port_prefix, port) + when { !auth -> str(R.string.main_auth_none); anon -> str(R.string.main_auth_push_only); else -> str(R.string.main_auth_on) },
                style = MaterialTheme.typography.bodyMedium)
            addrs.forEach { a -> AddressBar(str(R.string.main_address_kind, a.kind.label()), a.baseUrl, enabled = running, onQr = if (running) onQr else null) }
            if (addrs.isNotEmpty()) Text(if (running) str(R.string.main_address_hint) else str(R.string.main_address_when_on), style = MaterialTheme.typography.bodyMedium)
            // review #11: the plain-HTTP warning belongs next to the address, not only in settings
            if (addrs.any { it.kind == AddressKind.WIFI || it.kind == AddressKind.HOTSPOT }) Banner(Tone.Warning, Icons.Outlined.Shield, str(R.string.main_plain_http), str(R.string.main_plain_http_body))
            if (addrs.none { it.kind == AddressKind.TAILSCALE }) {
                Text(str(R.string.main_tailscale_link), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onTailscale).padding(vertical = Space.xs))
            }
        }
    }
}

private const val DEFAULT_NAME = "my-project"

/** R2-11 "새 로컬 레포": push from a PC creates the repo; an empty repo is the fallback without a PC. */
@Composable
private fun PushGuide(baseUrl: String?, port: Int, username: String, name: String, onName: (String) -> Unit, onBack: () -> Unit, onEmpty: () -> Unit) {
    val n = name.trim().ifBlank { DEFAULT_NAME }
    val url = "${baseUrl ?: str(R.string.main_address_placeholder, port)}/$n.git"
    Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = Space.xs)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, str(R.string.common_back)) }
            Text(str(R.string.main_new_local), style = MaterialTheme.typography.titleLarge)
        }
        Text(str(R.string.main_push_body), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.OutlinedTextField(name, onName, Modifier.fillMaxWidth(), singleLine = true, label = { Text(str(R.string.main_push_name)) },
            shape = MaterialTheme.shapes.medium)
        Text(str(R.string.main_push_step), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Space.xs))
        core().ops.pushCommands(url).forEach { CommandBlock(it) }
        Text(str(R.string.main_push_account, username), style = MaterialTheme.typography.bodyMedium)
        Text(str(R.string.main_push_network), style = MaterialTheme.typography.bodyMedium)
        Text(str(R.string.main_push_vscode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // R2-07 AC2: LFS is not stored; how to push such a project anyway
        com.ouor.mobilegit.ui.components.AdvancedSection(title = str(R.string.main_push_lfs_title)) {
            Text(str(R.string.main_push_lfs_body), style = MaterialTheme.typography.bodyMedium)
            Text("bash", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CommandBlock("GIT_LFS_SKIP_PUSH=1 git push -u phone main")
            Text("PowerShell", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CommandBlock("\$env:GIT_LFS_SKIP_PUSH=1; git push -u phone main")
        }
        Text(str(R.string.main_push_size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        SecondaryButton(str(R.string.main_push_empty_instead), icon = Icons.Outlined.CreateNewFolder, onClick = onEmpty)
    }
}

/**
 * R2-02 / RV2-06: "미러 12개 · 정상 10 · 오류 1 · 주의 1 · 마지막 확인 10분 전 · 로컬 3개". Errors are red, warnings orange.
 * Each part carries its own " ·" so a wrapped line never starts with the separator (RV2-05).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(c: com.ouor.mobilegit.core.RepoOverview, onProblems: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList<Triple<String, Tone?, Boolean>> {
        if (c.mirrors > 0) add(Triple(str(R.string.main_summary_mirrors, c.mirrors), null, false))
        if (c.mirrors > 0 && c.errors + c.warnings > 0) add(Triple(str(R.string.main_summary_healthy, c.healthy), null, false))
        if (c.errors > 0) add(Triple(str(R.string.main_summary_errors, c.errors), Tone.Danger, true))
        if (c.warnings > 0) add(Triple(str(R.string.main_summary_attention, c.warnings), Tone.Warning, true))
        if (c.mirrors > 0 && c.errors + c.warnings == 0) add(Triple(str(R.string.main_summary_all_ok), Tone.Success, false))
        c.lastCheckAt?.let { add(Triple(str(R.string.main_summary_last_check, ago(it)), null, false)) }
        if (c.locals > 0) add(Triple(str(R.string.main_summary_locals, c.locals), null, false))
    }
    // QA2-07: the tappable parts are 48dp tall; centre every part so all text shares one baseline
    FlowRow(Modifier.fillMaxWidth().padding(top = Space.xs), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (text, t, click) ->
            val label = if (i < parts.lastIndex) "$text · " else text
            val m = if (click) Modifier.minimumInteractiveComponentSize().clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onProblems) else Modifier.padding(vertical = Space.xs)
            Text(label, style = style, color = t?.let { tone(it).content } ?: dim, fontWeight = if (t != null) FontWeight.SemiBold else null, modifier = m)
        }
    }
}

/** App notification settings (R2-16). */
fun openNotificationSettings(ctx: android.content.Context) {
    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value, onChange, Modifier.fillMaxWidth().padding(top = Space.xs), singleLine = true,
        placeholder = { Text(str(R.string.main_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, str(R.string.main_search_clear)) } },
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
