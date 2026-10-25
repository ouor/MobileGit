package com.ouor.mobilegit.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.ouor.mobilegit.ui.archiveGroupOf
import com.ouor.mobilegit.ui.tone
import com.ouor.mobilegit.core.PushEvent
import androidx.compose.ui.text.font.FontWeight
import com.ouor.mobilegit.ui.components.AdvancedSection
import com.ouor.mobilegit.ui.components.SecondaryButton
import androidx.compose.material.icons.outlined.Refresh
import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.ExportKind
import com.ouor.mobilegit.app.Notifier
import com.ouor.mobilegit.app.ServerState
import com.ouor.mobilegit.core.CreatedBy
import com.ouor.mobilegit.core.CredentialStore
import com.ouor.mobilegit.core.ForkMode
import com.ouor.mobilegit.core.OriginStatus
import com.ouor.mobilegit.core.RepoListItem
import com.ouor.mobilegit.core.RepoMeta
import com.ouor.mobilegit.core.RepoNames
import com.ouor.mobilegit.core.SyncEvent
import com.ouor.mobilegit.core.SyncKind
import com.ouor.mobilegit.core.SyncResult
import com.ouor.mobilegit.ui.ExportRunner
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.clock
import com.ouor.mobilegit.ui.bytes
import com.ouor.mobilegit.ui.components.AddressBar
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.BadgeRow
import com.ouor.mobilegit.ui.components.CommandBlock
import com.ouor.mobilegit.ui.components.ConfirmDialog
import com.ouor.mobilegit.ui.components.Divider
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.InfoRow
import com.ouor.mobilegit.ui.components.Loading
import com.ouor.mobilegit.ui.components.LocalSnack
import com.ouor.mobilegit.ui.components.NavRow
import com.ouor.mobilegit.ui.components.OverflowMenu
import com.ouor.mobilegit.ui.components.Pill
import com.ouor.mobilegit.ui.components.PrimaryButton
import com.ouor.mobilegit.ui.components.RowGroup
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.components.SwitchRow
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.dateTime
import com.ouor.mobilegit.ui.label
import com.ouor.mobilegit.ui.rememberExportRunner
import com.ouor.mobilegit.ui.shortSha
import com.ouor.mobilegit.ui.style
import com.ouor.mobilegit.ui.theme.CodeText
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import com.ouor.mobilegit.ui.theme.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A-07 레포 상세 (F-08~F-12, F-16, F-17, F-19, F-20, F-21, F-23 AC4, F-24). */
@Composable
fun RepoDetailScreen(name: String, nav: Nav) {
    val core = core()
    val items by core.repoItems.collectAsStateWithLifecycle(initialValue = null)
    val item = items?.firstOrNull { it.meta.name.equals(name, ignoreCase = true) }
    val export = rememberExportRunner()
    val screenScope = rememberCoroutineScope() // outlives dialogs: delete removes the repo, which disposes the dialog
    var sheet by remember { mutableStateOf<String?>(null) } // export | fork | delete
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = LocalSnack.current

    AppScaffold(item?.meta?.name ?: name, onBack = nav::back, actions = {
        if (item != null) OverflowMenu {
            item(str(R.string.detail_export)) { sheet = "export" }
            if (item.meta.mirror != null) {
                if (item.progress == null) item(str(R.string.detail_check_only)) { runCatching { core.mirrors.checkNow(item.meta.name) }.onSuccess { snack(str(R.string.main_check_all_started)) } }
                item(str(R.string.detail_fork)) { sheet = "fork" }
            }
            item(str(R.string.common_delete)) { sheet = "delete" }
        }
    }) { padding ->
        when {
            items == null -> Box(Modifier.padding(padding)) { Loading() }
            item == null -> Box(Modifier.padding(padding)) { EmptyState(Icons.Outlined.Search, str(R.string.detail_not_found_title), str(R.string.detail_not_found_body)) }
            else -> {
                val meta = item.meta
                val events by remember(meta.name) { core.repos.eventsFlow(meta.name) }.collectAsStateWithLifecycle(emptyList())
                val pushes by remember(meta.name) { core.repos.pushEventsFlow(meta.name) }.collectAsStateWithLifecycle(emptyList())
                // one push = all refs with the same `at`; flow is newest first so groups stay in order
                val pushGroups = remember(pushes) { pushes.groupBy { it.at }.values.toList() }
                val isMirror = meta.mirror != null
                val showHistory = true
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    item(key = "header") { Header(item) }
                    if (showHistory) item(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                            Tab(tab == 0, { tab = 0 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(str(R.string.detail_tab_info)) })
                            Tab(tab == 1, { tab = 1 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(if (isMirror) str(R.string.detail_tab_sync_history, events.size) else str(R.string.detail_push_tab, pushGroups.size)) })
                        }
                    }
                    if (tab == 0 || !showHistory) {
                        item(key = "info") { InfoTab(item, export, onSheet = { sheet = it }, nav = nav) }
                    } else {
                        if (!isMirror) {
                            if (pushGroups.isEmpty()) item { EmptyState(Icons.Outlined.History, str(R.string.detail_history_empty_title), str(R.string.detail_push_empty)) }
                            items(pushGroups, key = { it.first().at }) { PushRow(it) }
                        } else {
                            if (events.isEmpty()) item { EmptyState(Icons.Outlined.History, str(R.string.detail_history_empty_title), str(R.string.detail_history_empty_body)) }
                            items(events, key = { it.startedAt.toString() + it.kind }) { EventRow(it) { g -> nav.go(Routes.archives(meta.name, g)) } }
                        }
                    }
                }
                when (sheet) {
                    "export" -> ExportSheet(meta, export) { sheet = null }
                    "fork" -> ForkSheet(meta, nav) { sheet = null }
                    "delete" -> DeleteDialog(meta, nav, screenScope, onExport = { sheet = "export" }) { sheet = null }
                }
            }
        }
    }
}

@Composable
private fun Header(item: RepoListItem) {
    val m = item.meta
    Column(Modifier.padding(top = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (m.mirror != null) str(R.string.detail_kind_mirror) else str(R.string.detail_kind_local), Tone.Neutral, if (m.mirror != null) Icons.Outlined.SyncAlt else Icons.Outlined.Folder)
        }
        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs))
        Text((if (m.sizeMeasuredAt != null) bytes(m.sizeBytes) else str(R.string.detail_size_unmeasured)) + (m.defaultBranch?.let { str(R.string.default_branch_suffix, it) } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
        BadgeRow(item.badges, Modifier.padding(top = Space.sm))
    }
}

@Composable
private fun InfoTab(item: RepoListItem, export: ExportRunner, onSheet: (String) -> Unit, nav: Nav) {
    val core = core()
    val m = item.meta
    val server by core.server.state.collectAsStateWithLifecycle()
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
    val urls = remember(addrs, m.name) { core.cloneUrls(m.name, addrs) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        if (export.running) Banner(Tone.Info, Icons.Outlined.Upload, str(R.string.detail_exporting), str(R.string.detail_exporting_body))
        val mir = m.mirror
        if (mir != null && mir.originStatus == OriginStatus.GONE) Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.detail_origin_gone_title),
            str(R.string.detail_origin_gone_body), actionLabel = str(R.string.detail_export_now), onAction = { onSheet("export") })

        // clone card (F-16): first for every repo, it is what the PC needs
        SectionCard(title = str(R.string.detail_clone_title)) {
            if (server !is ServerState.Running) {
                Banner(Tone.Warning, Icons.Outlined.WifiOff, str(R.string.detail_server_off_title), str(R.string.detail_server_off_body), actionLabel = str(R.string.detail_server_on), onAction = { core.server.start() })
                Spacer(Modifier.height(Space.sm))
            }
            if (urls.isEmpty()) Text(str(R.string.detail_no_network), style = MaterialTheme.typography.bodyMedium)
            urls.forEach { (a, u) -> AddressBar(str(R.string.main_address_kind, a.kind.label()), u); Spacer(Modifier.height(Space.xs)) }
            urls.firstOrNull()?.let {
                Spacer(Modifier.height(Space.xs))
                Text(str(R.string.detail_clone_command), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommandBlock("git clone ${it.second}")
                TextButton(onClick = { nav.go(Routes.qr(m.name)) }, modifier = Modifier.padding(top = Space.xs)) {
                    Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text(str(R.string.detail_clone_qr))
                }
            }
            Text(str(R.string.detail_account_hint, core.settings.current.username), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.sm))
        }

        if (m.mirror == null && m.lastPushAt == null && m.forkedFromUrl == null) SectionCard(title = str(R.string.detail_empty_title)) {
            Text(str(R.string.detail_empty_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Space.sm))
            core.ops.pushCommands(urls.firstOrNull()?.second ?: str(R.string.detail_clone_url_placeholder, core.settings.current.port, m.name)).forEach {
                CommandBlock(it); Spacer(Modifier.height(Space.xs))
            }
        }

        if (mir != null) OriginCard(item, nav, onSheet) else LocalInfoCard(m)

        if (m.hasLfs) {
            Banner(Tone.Warning, Icons.Outlined.Inventory2, str(R.string.detail_lfs_title), str(R.string.detail_lfs_body) + " " + str(R.string.detail_lfs_push_hint))
            CommandBlock("GIT_LFS_SKIP_PUSH=1 git push")
        }
        if (m.submoduleUrls.isNotEmpty()) SectionCard(title = str(R.string.detail_submodules_title, m.submoduleUrls.size)) {
            Text(str(R.string.detail_submodules_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.submoduleUrls.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
                    Text(u, style = CodeText.small, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { nav.go(Routes.add(u)) }) { Text(str(R.string.mirror_title)) }
                }
            }
        }
        if (mir != null || m.archivedRefCount > 0) RowGroup {
            if (m.archivedRefCount == 0) Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(Space.md + Space.xs))
                Column {
                    Text(str(R.string.detail_archive_title), style = MaterialTheme.typography.bodyLarge)
                    Text(str(R.string.detail_archive_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else NavRow(str(R.string.detail_archive_title), { nav.go(Routes.archives(m.name)) }, icon = Icons.Outlined.Archive,
                subtitle = str(R.string.detail_archive_count, m.archivedRefCount) + (m.historyChangedAt?.let { " · " + str(R.string.detail_archive_last, clock(it)) } ?: ""))
        }

        // 고급 stays last before 관리 (design-system 9)
        if (mir != null) AdvancedSection { RowGroup { PullRefsRow(m.name, mir.includePullRefs) } }

        Text(str(R.string.detail_manage), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        RowGroup {
            NavRow(str(R.string.detail_export), { onSheet("export") }, subtitle = str(R.string.detail_export_sub), icon = Icons.Outlined.Upload)
            if (m.mirror != null) { Divider(); NavRow(str(R.string.detail_fork), { onSheet("fork") }, subtitle = str(R.string.detail_fork_sub), icon = Icons.Outlined.CallSplit) }
        }
        Spacer(Modifier.height(Space.lg))
        RowGroup {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSheet("delete") }.padding(horizontal = Space.lg, vertical = Space.md + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(Space.md + Space.xs))
                Text(str(R.string.detail_delete), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OriginCard(item: RepoListItem, nav: Nav, onSheet: (String) -> Unit) {
    val core = core()
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val m = item.meta
    val mir = m.mirror!!
    val creds by core.credentials.items.collectAsStateWithLifecycle()
    val host = CredentialStore.hostOf(mir.remoteUrl)
    val (statusText, statusTone) = mir.originStatus.style()
    SectionCard(title = str(R.string.mirror_origin), trailing = { if (mir.originStatus != OriginStatus.OK) Pill(statusText, statusTone) }) {
        AddressBar(str(R.string.detail_origin_url), mir.remoteUrl)
        Spacer(Modifier.height(Space.sm))
        val sameTime = mir.lastCheckAt != null && mir.lastCheckAt == mir.lastSuccessUpdateAt
        if (sameTime) InfoRow(str(R.string.detail_checked_updated), clock(mir.lastCheckAt!!))
        else {
            InfoRow(str(R.string.detail_last_check), mir.lastCheckAt?.let { clock(it) } ?: str(R.string.detail_not_yet))
            InfoRow(str(R.string.detail_last_update), mir.lastSuccessUpdateAt?.let { clock(it) } ?: str(R.string.detail_not_yet))
        }
        val settings by core.settings.state.collectAsStateWithLifecycle()
        val next = (mir.lastCheckAt ?: m.createdAt) + settings.checkIntervalMillis
        InfoRow(str(R.string.detail_check_interval), (if (mir.autoUpdateExcluded) str(R.string.detail_check_only_mode) + " · " else "") +
            str(R.string.detail_check_next, settings.checkIntervalHours, if (next > System.currentTimeMillis()) clock(next) else str(R.string.detail_check_soon)))
        InfoRow(str(R.string.detail_scope), str(if (mir.includePullRefs ?: settings.mirrorPullRefs) R.string.detail_scope_mirror_pull else R.string.detail_scope_mirror) + " · " + str(R.string.detail_scope_no_lfs))
        if (mir.pendingChanges > 0) InfoRow(str(R.string.detail_changes), str(R.string.detail_changes_value, mir.pendingChanges), valueTone = Tone.Info)
        mir.waitingReason.label()?.let { InfoRow(str(R.string.status_waiting), it, valueTone = Tone.Warning) }
        if (mir.waitingForForeground) InfoRow(str(R.string.status_waiting), str(R.string.detail_waiting_foreground), valueTone = Tone.Warning)
        mir.lastError?.let { InfoRow(str(R.string.detail_last_error), str(Notifier.categoryRes(it)) + if (mir.consecutiveFailures > 1) str(R.string.detail_failures_suffix, mir.consecutiveFailures) else "", valueTone = Tone.Danger) }
        mir.nextRetryAt?.takeIf { it > System.currentTimeMillis() }?.let { InfoRow(str(R.string.detail_next_retry), clock(it)) }
        if (mir.originStatus == OriginStatus.AUTH_REQUIRED) {
            Spacer(Modifier.height(Space.sm))
            Banner(Tone.Warning, Icons.Outlined.Key, str(R.string.detail_auth_title), str(R.string.detail_auth_body), actionLabel = str(R.string.detail_manage_tokens), onAction = { nav.go(Routes.credentials(host)) })
        }
        item.progress?.let { p ->
            Spacer(Modifier.height(Space.md))
            Text(if (p.kind == SyncKind.CHECK) str(R.string.detail_checking) else (p.progress?.task ?: str(R.string.detail_fetching)), style = MaterialTheme.typography.bodySmall)
            p.progress?.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = Space.xs)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.md))
        SecondaryButton(if (item.progress?.kind == SyncKind.CHECK) str(R.string.detail_checking) else if (item.progress != null) str(R.string.detail_updating) else str(R.string.common_update_now),
            Modifier.fillMaxWidth(), enabled = item.progress == null, icon = Icons.Outlined.Refresh) {
            core.mirrors.updateNow(m.name); snack(str(R.string.detail_update_started))
        }
        Text(str(R.string.detail_read_only_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.sm))
        androidx.compose.material3.OutlinedButton(onClick = { onSheet("fork") }, Modifier.padding(top = Space.xs).heightIn(min = 48.dp)) { Text(str(R.string.detail_fork_short)) }
    }
    RowGroup {
        SwitchRow(str(R.string.detail_auto_update), !mir.autoUpdateExcluded, { v -> scope.launch(Dispatchers.IO) { core.mirrors.setAutoUpdateExcluded(m.name, !v) } },
            subtitle = str(R.string.detail_auto_update_sub))
        Box(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
            CredentialPicker(creds.filter { it.host == host }, mir.credentialId, { id -> scope.launch(Dispatchers.IO) { core.mirrors.setCredential(m.name, id) } }, host) {
                nav.go(Routes.credentials(host))
            }
        }
    }
}

/** CR-2: per-repo PR ref mirroring (null = 전역 설정). Turning it off warns that fetched PR refs are deleted without archive. */
@Composable
private fun PullRefsRow(name: String, current: Boolean?) {
    val core = core()
    val scope = rememberCoroutineScope()
    val global by core.settings.state.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<List<Boolean?>?>(null) } // wraps the pending choice (which may itself be null)
    val effective = current ?: global.mirrorPullRefs
    fun set(v: Boolean?) = scope.launch(Dispatchers.IO) { core.mirrors.setIncludePullRefs(name, v) }
    Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
        Text(str(R.string.detail_pull_refs), style = MaterialTheme.typography.bodyLarge)
        Text(str(R.string.detail_pull_refs_sub, if (effective) str(R.string.detail_pull_refs_on) else str(R.string.detail_pull_refs_off)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.sm))
        val opts = listOf(null to str(R.string.detail_pull_refs_follow), true to str(R.string.detail_pull_refs_include), false to str(R.string.detail_pull_refs_exclude))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (v, label) ->
                SegmentedButton(current == v, {
                    if (effective && !(v ?: global.mirrorPullRefs)) ask = listOf(v) else set(v)
                }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label, maxLines = 1) }
            }
        }
    }
    ask?.let { pending ->
        ConfirmDialog(str(R.string.detail_pull_refs_off_title), PULL_OFF_WARNING, str(R.string.detail_pull_refs_exclude), destructive = true,
            onConfirm = { set(pending[0]); ask = null }, onDismiss = { ask = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalInfoCard(m: RepoMeta) {
    val core = core()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val branches by produceState(emptyList<String>(), m) { value = withContext(Dispatchers.IO) { runCatching { core.ops.branches(m.name) }.getOrDefault(emptyList()) } }
    var open by remember { mutableStateOf(false) }
    SectionCard(title = str(R.string.detail_tab_info)) {
        InfoRow(str(R.string.detail_last_push), m.lastPushAt?.let { "${clock(it)} · ${m.lastPushIp ?: str(R.string.detail_ip_unknown)}" } ?: str(R.string.detail_not_yet))
        InfoRow(str(R.string.detail_created_by), when (m.createdBy) { CreatedBy.PUSH -> str(R.string.detail_created_push); CreatedBy.APP_EMPTY -> str(R.string.detail_created_app); CreatedBy.FORK -> str(R.string.detail_created_fork); else -> str(R.string.common_kind_mirror) } + " · ${dateTime(m.createdAt)}")
        m.forkedFromUrl?.let { InfoRow(str(R.string.detail_forked_from), "${it}\n${dateTime(m.forkedAt)}") }
    }
    if (branches.isNotEmpty()) AdvancedSection {
        SectionCard {
        if (branches.isNotEmpty()) {
            ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(m.defaultBranch ?: "-", {}, readOnly = true, label = { Text(str(R.string.detail_default_branch)) }, shape = MaterialTheme.shapes.medium,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(open, { open = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(text = { Text(b) }, onClick = {
                            open = false
                            scope.launch { runCatching { core.ops.setDefaultBranch(m.name, b) }.onSuccess { snack(str(R.string.detail_default_branch_changed, b)) }.onFailure { snack(it.message ?: str(R.string.detail_default_branch_failed)) } }
                        })
                    }
                }
            }
        }
            Text(str(R.string.detail_branch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        }
    }
}

@Composable
private fun EventRow(e: SyncEvent, onArchive: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    val (rl, rt) = e.result.style()
    val expandable = e.refChanges.isNotEmpty() || e.errorDetail != null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(enabled = expandable) { open = !open }.padding(Space.md).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // R2-04 AC2: a failure is titled by its reason
                Text(if (e.result == SyncResult.FAILED) str(Notifier.categoryRes(e.errorCategory)) else if (e.kind == SyncKind.CHECK) str(R.string.main_check) else str(R.string.detail_event_update),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Space.sm)); Pill(rl, rt); Spacer(Modifier.width(Space.xs)); Pill(e.trigger.label(), Tone.Neutral)
                Spacer(Modifier.weight(1f))
                Text(clock(e.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val summary = buildList {
                if (e.refChanges.isNotEmpty()) add(str(R.string.detail_event_refs_changed, e.refChanges.size))
                if (e.newCommitCount > 0) add(str(R.string.detail_event_new_commits, if (e.newCommitCount >= 10000) "10000+" else e.newCommitCount.toString()))
                if (e.archivedRefCount > 0) add(str(R.string.detail_event_archived, e.archivedRefCount))
                e.waitingReason?.label()?.let { add(it) }
                add(str(R.string.detail_event_time, "%.1f".format(e.durationMs / 1000.0)))
            }
            Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (e.result == SyncResult.FAILED) tone(Tone.Danger).content else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs))
            if (expandable) Text(if (open) str(R.string.detail_collapse) else str(R.string.detail_expand), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Space.xs))
            if (open) {
                (if (all) e.refChanges else e.refChanges.take(5)).forEach { c ->
                    Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Pill(c.kind.label(c.ref), c.kind.tone); Spacer(Modifier.width(Space.sm))
                        Text(c.ref.removePrefix("refs/heads/").removePrefix("refs/tags/").removePrefix("refs/"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(Space.sm))
                        Text(when { c.oldSha == null -> shortSha(c.newSha); c.newSha == null -> shortSha(c.oldSha); else -> "${shortSha(c.oldSha)} → ${shortSha(c.newSha)}" },
                            style = CodeText.small, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (!all && e.refChanges.size > 5) TextButton(onClick = { all = true }) { Text(str(R.string.detail_more_changes, e.refChanges.size - 5)) }
                e.refChanges.firstNotNullOfOrNull { archiveGroupOf(it.archivedAs) }?.let { g ->
                    TextButton(onClick = { onArchive(g) }) { Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text(str(R.string.detail_view_archive)) }
                }
                e.errorDetail?.let {
                    Text(it, style = CodeText.small, modifier = Modifier.padding(top = Space.sm).fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.background).padding(Space.sm))
                }
            }
        }
    }
}

/** One push (all refs sharing `at`): created / deleted / forced are labelled, not only coloured. */
@Composable
private fun PushRow(g: List<PushEvent>) {
    val e = g.first()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.detail_push_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(Space.sm))
                Pill(e.route.label(), Tone.Neutral)
                Spacer(Modifier.weight(1f))
                Text(clock(e.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            g.forEach { p ->
                val name = p.branch ?: p.ref.removePrefix("refs/tags/").removePrefix("refs/")
                val (label, t) = when {
                    p.oldSha == null -> str(R.string.detail_push_created) to Tone.Success
                    p.newSha == null -> str(R.string.detail_push_deleted) to Tone.Danger
                    p.forced -> str(R.string.detail_push_forced) to Tone.Warning
                    else -> null to Tone.Neutral
                }
                Row(Modifier.padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (label != null) { Pill(label, t); Spacer(Modifier.width(Space.sm)) }
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Space.sm))
                    Text(if (p.oldSha == null) shortSha(p.newSha) else if (p.newSha == null) shortSha(p.oldSha) else "${shortSha(p.oldSha)} → ${shortSha(p.newSha)}",
                        style = CodeText.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(str(R.string.detail_push_from, e.clientIp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(m: RepoMeta, export: ExportRunner, onDismiss: () -> Unit) {
    val core = core()
    var kind by rememberSaveable { mutableStateOf(ExportKind.BUNDLE) }
    var step by rememberSaveable { mutableIntStateOf(0) } // 0 = what, 1 = branch + where
    var branch by rememberSaveable { mutableStateOf(m.defaultBranch) }
    var open by remember { mutableStateOf(false) }
    val branches by produceState(emptyList<String>(), m.name) { value = withContext(Dispatchers.IO) { runCatching { core.ops.branches(m.name) }.getOrDefault(emptyList()) } }
    val ref = branch.takeIf { kind == ExportKind.SOURCE_ZIP }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        BackHandler(enabled = step == 1) { step = 0 }
        Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            SheetTitle(str(R.string.detail_export), onBack = if (step == 1) ({ step = 0 }) else null)
            if (step == 0) {
                Choice(kind == ExportKind.BUNDLE, "git bundle", str(R.string.export_bundle_body, m.name)) { kind = ExportKind.BUNDLE }
                Choice(kind == ExportKind.SOURCE_ZIP, str(R.string.export_source_zip), str(R.string.export_source_zip_body)) { kind = ExportKind.SOURCE_ZIP }
                androidx.compose.material3.Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(44.dp).padding(top = Space.xs), shape = MaterialTheme.shapes.small) { Text(str(R.string.common_next)) }
            } else {
                if (kind == ExportKind.SOURCE_ZIP && branches.isNotEmpty()) ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(branch ?: "HEAD", {}, readOnly = true, label = { Text(str(R.string.export_branch)) }, shape = MaterialTheme.shapes.medium,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(open, { open = false }) { branches.forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { branch = b; open = false }) } }
                }
                ExportWarning()
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    com.ouor.mobilegit.ui.components.SecondaryButton(str(R.string.export_share), Modifier.weight(1f)) { export.start(kind, listOf(m.name), toFile = false, ref = ref); onDismiss() }
                    androidx.compose.material3.Button(onClick = { export.start(kind, listOf(m.name), toFile = true, ref = ref); onDismiss() }, Modifier.weight(1f).height(44.dp), shape = MaterialTheme.shapes.small) { Text(str(R.string.export_save_file)) }
                }
                Text(str(R.string.export_restore_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** R2-10 AC1: said right above the save buttons. Shared with the full backup screen. */
@Composable
fun ExportWarning() {
    Banner(Tone.Warning, Icons.Outlined.LockOpen, str(R.string.export_unencrypted_title), str(R.string.export_unencrypted_body))
}

/** Sheet title with an optional back arrow for the second step (R2-12). */
@Composable
fun SheetTitle(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.padding(end = Space.xs)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, str(R.string.common_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Choice(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).selectable(selected, onClick = onClick, role = Role.RadioButton)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).padding(Space.md), verticalAlignment = Alignment.Top) {
        RadioButton(selected, null)
        Spacer(Modifier.width(Space.sm))
        Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForkSheet(m: RepoMeta, nav: Nav, onDismiss: () -> Unit) {
    val core = core()
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ForkMode.COPY) }
    var step by rememberSaveable { mutableIntStateOf(0) } // 0 = how, 1 = name / confirm
    var newName by rememberSaveable { mutableStateOf("${m.name}-local") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameErr = if (mode == ForkMode.COPY) RepoNames.validate(newName) ?: if (core.repos.exists(newName)) str(R.string.repo_name_taken) else if (core.repos.damaged.value.any { it.name.equals(newName, true) }) str(R.string.add_name_damaged) else null else null
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        // drop focus from the name field before it leaves the composition
        BackHandler(enabled = step == 1 && !busy) { focus.clearFocus(); step = 0 }
        Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            SheetTitle(str(R.string.detail_fork), onBack = if (step == 1) ({ step = 0 }) else null)
            if (step == 0) {
                ForkChoice(mode == ForkMode.COPY, str(R.string.fork_copy), str(R.string.fork_copy_stays), str(R.string.fork_copy_changes)) { mode = ForkMode.COPY }
                ForkChoice(mode == ForkMode.CONVERT, str(R.string.fork_convert), str(R.string.fork_convert_stays), str(R.string.fork_convert_changes), warning = str(R.string.fork_irreversible)) { mode = ForkMode.CONVERT }
                androidx.compose.material3.Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(44.dp), shape = MaterialTheme.shapes.small) { Text(str(R.string.common_next)) }
            } else {
                if (mode == ForkMode.COPY) OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.fork_new_name)) },
                    singleLine = true, isError = nameErr != null, supportingText = nameErr?.let { { Text(it) } }, shape = MaterialTheme.shapes.medium)
                else Banner(Tone.Danger, Icons.Outlined.Warning, str(R.string.fork_convert_confirm_title), str(R.string.fork_convert_changes))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                androidx.compose.material3.Button(enabled = !busy && nameErr == null, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { val r = core.ops.fork(m.name, mode, newName.trim().takeIf { mode == ForkMode.COPY }); onDismiss(); nav.replace(Routes.repo(r.name)) }
                        catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = MaterialTheme.shapes.small,
                    colors = if (mode == ForkMode.CONVERT) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.ButtonDefaults.buttonColors()) {
                    Text(if (busy) str(R.string.fork_running) else if (mode == ForkMode.COPY) str(R.string.fork_copy_confirm) else str(R.string.fork_convert_confirm))
                }
            }
            Text(str(R.string.fork_upstream_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** R2-14 AC1: "남는 것 / 바뀌는 것" side by side under each way. */
@Composable
private fun ForkChoice(selected: Boolean, title: String, stays: String, changes: String, warning: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).selectable(selected, onClick = onClick, role = Role.RadioButton)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).padding(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null); Spacer(Modifier.width(Space.sm)); Text(title, style = MaterialTheme.typography.titleSmall) }
        val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        val cells = listOf(str(R.string.fork_stays) to stays, str(R.string.fork_changes) to changes)
        @Composable fun Cell(h: String, b: String, m: Modifier) = Column(m) {
            Text(h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(b, style = MaterialTheme.typography.bodySmall)
        }
        if (stacked) Column(Modifier.padding(top = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) { cells.forEach { (h, b) -> Cell(h, b, Modifier) } }
        else Row(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.md)) { cells.forEach { (h, b) -> Cell(h, b, Modifier.weight(1f)) } }
        if (warning != null) Text(warning, style = MaterialTheme.typography.labelLarge, color = tone(Tone.Danger).content, modifier = Modifier.padding(top = Space.sm))
    }
}

@Composable
private fun DeleteDialog(m: RepoMeta, nav: Nav, scope: kotlinx.coroutines.CoroutineScope, onExport: () -> Unit, onDismiss: () -> Unit) {
    val core = core()
    
    val snack = LocalSnack.current
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val gone = m.mirror?.originStatus == OriginStatus.GONE
    ConfirmDialog(
        str(R.string.cred_delete_named, m.name), str(R.string.delete_body),
        if (busy) str(R.string.delete_running) else str(R.string.common_delete), destructive = true, confirmEnabled = typed == m.name && !busy,
        onConfirm = {
            busy = true
            scope.launch {
                runCatching { core.ops.delete(m.name) }.onSuccess { onDismiss(); snack(str(R.string.delete_done, m.name)); nav.home() }.onFailure { busy = false; snack(it.message ?: str(R.string.delete_failed)) }
            }
        }, onDismiss = onDismiss,
    ) {
        if (gone) {
            Spacer(Modifier.height(Space.md))
            Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.delete_origin_gone_warning), str(R.string.delete_export_first), actionLabel = str(R.string.detail_export), onAction = onExport)
        }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = Space.md), singleLine = true, placeholder = { Text(m.name) })
    }
}

