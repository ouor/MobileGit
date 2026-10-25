package com.ouor.mobilegit.ui.screens

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
import com.ouor.mobilegit.ui.ago
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

    AppScaffold(item?.meta?.name ?: name, onBack = nav::back, actions = {
        if (item != null) OverflowMenu {
            item(str(R.string.detail_001)) { sheet = "export" }
            if (item.meta.mirror != null) item(str(R.string.detail_002)) { sheet = "fork" }
            item(str(R.string.manage_012)) { sheet = "delete" }
        }
    }) { padding ->
        when {
            items == null -> Box(Modifier.padding(padding)) { Loading() }
            item == null -> Box(Modifier.padding(padding)) { EmptyState(Icons.Outlined.Search, str(R.string.detail_003), str(R.string.detail_004)) }
            else -> {
                val meta = item.meta
                val events by remember(meta.name) { core.repos.eventsFlow(meta.name) }.collectAsStateWithLifecycle(emptyList())
                val showHistory = meta.mirror != null || events.isNotEmpty()
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    item(key = "header") { Header(item) }
                    if (showHistory) item(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                            Tab(tab == 0, { tab = 0 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(str(R.string.detail_005)) })
                            Tab(tab == 1, { tab = 1 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(str(R.string.detail_006, events.size)) })
                        }
                    }
                    if (tab == 0 || !showHistory) {
                        item(key = "info") { InfoTab(item, export, onSheet = { sheet = it }, nav = nav) }
                    } else {
                        if (events.isEmpty()) item { EmptyState(Icons.Outlined.History, str(R.string.detail_007), str(R.string.detail_008)) }
                        items(events, key = { it.startedAt.toString() + it.kind }) { EventRow(it) }
                    }
                }
                when (sheet) {
                    "export" -> ExportSheet(meta, export) { sheet = null }
                    "fork" -> ForkDialog(meta, nav) { sheet = null }
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
            Icon(if (m.mirror != null) Icons.Outlined.SyncAlt else Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs + Space.xxs))
            Text(if (m.mirror != null) str(R.string.detail_009) else str(R.string.detail_010), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs))
        Text("${bytes(m.sizeBytes)} · ${m.sizeMeasuredAt?.let { str(R.string.detail_011, ago(it)) } ?: str(R.string.detail_012)}" + (m.defaultBranch?.let { str(R.string.add_033, it) } ?: ""),
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
        if (export.running) Banner(Tone.Info, Icons.Outlined.Upload, str(R.string.detail_013), str(R.string.detail_014))
        val mir = m.mirror
        if (mir != null) {
            if (mir.originStatus == OriginStatus.GONE) Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.detail_015),
                str(R.string.detail_016), actionLabel = str(R.string.detail_017), onAction = { onSheet("export") })
            OriginCard(item, nav)
        } else LocalInfoCard(m)

        if (m.mirror == null && m.lastPushAt == null && m.forkedFromUrl == null) SectionCard(title = str(R.string.detail_018)) {
            Text(str(R.string.detail_019), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.sm))
            core.ops.pushCommands(urls.firstOrNull()?.second ?: str(R.string.detail_020, core.settings.current.port, m.name)).forEach {
                CommandBlock(it); Spacer(Modifier.height(Space.xs))
            }
        }

        // clone card (F-16)
        SectionCard(title = str(R.string.detail_021), trailing = {
            if (urls.isNotEmpty()) IconButton(onClick = { nav.go(Routes.qr(m.name)) }) { Icon(Icons.Outlined.QrCode2, str(R.string.detail_022)) }
        }) {
            if (server !is ServerState.Running) {
                Banner(Tone.Warning, Icons.Outlined.WifiOff, str(R.string.detail_023), str(R.string.detail_024), actionLabel = str(R.string.detail_025), onAction = { core.server.start() })
                Spacer(Modifier.height(Space.sm))
            }
            if (urls.isEmpty()) Text(str(R.string.detail_026), style = MaterialTheme.typography.bodyMedium)
            urls.forEach { (a, u) -> AddressBar(a.kind.label(), u); Spacer(Modifier.height(Space.xs)) }
            urls.firstOrNull()?.let {
                Spacer(Modifier.height(Space.xs))
                Text(str(R.string.detail_027), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommandBlock("git clone ${it.second}")
            }
            Text(str(R.string.detail_028), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        }

        if (m.hasLfs) {
            Banner(Tone.Warning, Icons.Outlined.Inventory2, str(R.string.detail_029), str(R.string.detail_030) + " " + str(R.string.detail_lfs_push_hint))
            CommandBlock("GIT_LFS_SKIP_PUSH=1 git push")
        }
        if (m.submoduleUrls.isNotEmpty()) SectionCard(title = str(R.string.detail_031, m.submoduleUrls.size)) {
            Text(str(R.string.detail_032), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.submoduleUrls.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
                    Text(u, style = CodeText.small, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { nav.go(Routes.add(u)) }) { Text(str(R.string.add_006)) }
                }
            }
        }
        if (m.archivedRefCount > 0) SectionCard(title = str(R.string.detail_033), trailing = { Pill(str(R.string.add_032, m.archivedRefCount), Tone.Info, Icons.Outlined.Archive) }) {
            Text(str(R.string.detail_034),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.sm))
            val u = urls.firstOrNull()?.second ?: str(R.string.detail_035)
            CommandBlock("git clone --mirror ${u}"); Spacer(Modifier.height(Space.xs))
            CommandBlock("git fetch ${u} 'refs/archive/*:refs/archive/*'")
        }

        Text(str(R.string.detail_036), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        RowGroup {
            NavRow(str(R.string.detail_001), { onSheet("export") }, subtitle = str(R.string.detail_037), icon = Icons.Outlined.Upload)
            if (m.mirror != null) { Divider(); NavRow(str(R.string.detail_002), { onSheet("fork") }, subtitle = str(R.string.detail_038), icon = Icons.Outlined.CallSplit) }
            Divider()
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSheet("delete") }.padding(horizontal = Space.lg, vertical = Space.md + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(Space.md + Space.xs))
                Text(str(R.string.detail_039), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OriginCard(item: RepoListItem, nav: Nav) {
    val core = core()
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val m = item.meta
    val mir = m.mirror!!
    val creds by core.credentials.items.collectAsStateWithLifecycle()
    val host = CredentialStore.hostOf(mir.remoteUrl)
    val (statusText, statusTone) = mir.originStatus.style()
    SectionCard(title = str(R.string.add_022), trailing = { Pill(statusText, statusTone) }) {
        AddressBar(str(R.string.detail_040), mir.remoteUrl)
        Spacer(Modifier.height(Space.sm))
        InfoRow(str(R.string.detail_041), mir.lastCheckAt?.let { "${ago(it)} (${dateTime(it)})" } ?: str(R.string.detail_042))
        InfoRow(str(R.string.detail_043), mir.lastSuccessUpdateAt?.let { "${ago(it)} (${dateTime(it)})" } ?: str(R.string.detail_042))
        if (mir.pendingChanges > 0) InfoRow(str(R.string.detail_044), str(R.string.detail_045, mir.pendingChanges), valueTone = Tone.Info)
        mir.waitingReason.label()?.let { InfoRow(str(R.string.add_084), it, valueTone = Tone.Warning) }
        mir.lastError?.let { InfoRow(str(R.string.detail_046), Notifier.categoryText(it) + if (mir.consecutiveFailures > 1) str(R.string.detail_047, mir.consecutiveFailures) else "", valueTone = Tone.Danger) }
        mir.nextRetryAt?.takeIf { it > System.currentTimeMillis() }?.let { InfoRow(str(R.string.detail_048), dateTime(it)) }
        if (mir.originStatus == OriginStatus.AUTH_REQUIRED) {
            Spacer(Modifier.height(Space.sm))
            Banner(Tone.Warning, Icons.Outlined.Key, str(R.string.detail_049), str(R.string.detail_050), actionLabel = str(R.string.detail_051), onAction = { nav.go(Routes.credentials(host)) })
        }
        item.progress?.let { p ->
            Spacer(Modifier.height(Space.md))
            Text(if (p.kind == SyncKind.CHECK) str(R.string.detail_052) else (p.progress?.task ?: str(R.string.detail_053)), style = MaterialTheme.typography.bodySmall)
            p.progress?.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = Space.xs)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.md))
        PrimaryButton(if (item.progress?.kind == SyncKind.CHECK) str(R.string.detail_052) else if (item.progress != null) str(R.string.detail_054) else str(R.string.common_021), enabled = item.progress == null) {
            core.mirrors.updateNow(m.name); snack(str(R.string.detail_055))
        }
        TextButton(onClick = { runCatching { core.mirrors.checkNow(m.name) }.onSuccess { snack(str(R.string.main_006)) } },
            enabled = item.progress == null, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.detail_056)) }
        Text(str(R.string.detail_057), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
    }
    RowGroup {
        SwitchRow(str(R.string.detail_058), mir.autoUpdateExcluded, { v -> scope.launch(Dispatchers.IO) { core.mirrors.setAutoUpdateExcluded(m.name, v) } },
            subtitle = str(R.string.detail_059))
        PullRefsRow(m.name, mir.includePullRefs)
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
        Text(str(R.string.detail_060), style = MaterialTheme.typography.bodyLarge)
        Text(str(R.string.detail_063, if (effective) str(R.string.detail_061) else str(R.string.detail_062)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.sm))
        val opts = listOf(null to str(R.string.detail_064), true to str(R.string.detail_065), false to str(R.string.detail_066))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (v, label) ->
                SegmentedButton(current == v, {
                    if (effective && !(v ?: global.mirrorPullRefs)) ask = listOf(v) else set(v)
                }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label, maxLines = 1) }
            }
        }
    }
    ask?.let { pending ->
        ConfirmDialog(str(R.string.detail_067), PULL_OFF_WARNING, str(R.string.detail_066), destructive = true,
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
    SectionCard(title = str(R.string.detail_005)) {
        if (branches.isNotEmpty()) {
            ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(m.defaultBranch ?: "-", {}, readOnly = true, label = { Text(str(R.string.detail_068)) }, shape = MaterialTheme.shapes.medium,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(open, { open = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(text = { Text(b) }, onClick = {
                            open = false
                            scope.launch { runCatching { core.ops.setDefaultBranch(m.name, b) }.onSuccess { snack(str(R.string.detail_069, b)) }.onFailure { snack(it.message ?: str(R.string.detail_070)) } }
                        })
                    }
                }
            }
            Spacer(Modifier.height(Space.sm))
        }
        InfoRow(str(R.string.detail_071), m.lastPushAt?.let { "${ago(it)} · ${m.lastPushIp ?: str(R.string.detail_072)}" } ?: str(R.string.detail_042))
        InfoRow(str(R.string.detail_073), when (m.createdBy) { CreatedBy.PUSH -> str(R.string.detail_074); CreatedBy.APP_EMPTY -> str(R.string.detail_075); CreatedBy.FORK -> str(R.string.detail_076); else -> str(R.string.common_014) } + " · ${dateTime(m.createdAt)}")
        m.forkedFromUrl?.let { InfoRow(str(R.string.detail_077), "${it}\n${dateTime(m.forkedAt)}") }
    }
}

@Composable
private fun EventRow(e: SyncEvent) {
    var open by remember { mutableStateOf(false) }
    val (rl, rt) = e.result.style()
    val expandable = e.refChanges.isNotEmpty() || e.errorDetail != null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(enabled = expandable) { open = !open }.padding(Space.md).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.kind == SyncKind.CHECK) str(R.string.main_046) else str(R.string.detail_078), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(Space.sm)); Pill(rl, rt); Spacer(Modifier.width(Space.xs)); Pill(e.trigger.label(), Tone.Neutral)
                Spacer(Modifier.weight(1f))
                Text(ago(e.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val summary = buildList {
                if (e.refChanges.isNotEmpty()) add(str(R.string.detail_079, e.refChanges.size))
                if (e.newCommitCount > 0) add(str(R.string.detail_080, e.newCommitCount))
                if (e.archivedRefCount > 0) add(str(R.string.detail_081, e.archivedRefCount))
                e.errorCategory?.let { add(Notifier.categoryText(it)) }
                e.waitingReason?.label()?.let { add(it) }
                add(str(R.string.detail_082, dateTime(e.startedAt), "%.1f".format(e.durationMs / 1000.0)))
            }
            Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (e.result == SyncResult.FAILED) tone(Tone.Danger).content else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs))
            if (expandable) Text(if (open) str(R.string.detail_083) else str(R.string.detail_084), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Space.xs))
            if (open) {
                e.refChanges.forEach { c ->
                    Text("${c.ref.removePrefix("refs/")}  ${shortSha(c.oldSha)} → ${shortSha(c.newSha)}" + (c.archivedAs?.let { str(R.string.detail_085, it.removePrefix("refs/")) } ?: ""),
                        style = CodeText.small, modifier = Modifier.padding(top = Space.xs))
                }
                e.errorDetail?.let {
                    Text(it, style = CodeText.small, modifier = Modifier.padding(top = Space.sm).fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.background).padding(Space.sm))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(m: RepoMeta, export: ExportRunner, onDismiss: () -> Unit) {
    val core = core()
    var kind by remember { mutableStateOf(ExportKind.BUNDLE) }
    var branch by remember { mutableStateOf(m.defaultBranch) }
    var open by remember { mutableStateOf(false) }
    val branches by produceState(emptyList<String>(), m.name) { value = withContext(Dispatchers.IO) { runCatching { core.ops.branches(m.name) }.getOrDefault(emptyList()) } }
    val ref = branch.takeIf { kind == ExportKind.SOURCE_ZIP }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(str(R.string.detail_001), style = MaterialTheme.typography.titleLarge)
            Choice(kind == ExportKind.BUNDLE, "git bundle", str(R.string.detail_086, m.name)) { kind = ExportKind.BUNDLE }
            Choice(kind == ExportKind.SOURCE_ZIP, str(R.string.detail_087), str(R.string.detail_088)) { kind = ExportKind.SOURCE_ZIP }
            if (kind == ExportKind.SOURCE_ZIP && branches.isNotEmpty()) ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(branch ?: "HEAD", {}, readOnly = true, label = { Text(str(R.string.detail_089)) }, shape = MaterialTheme.shapes.medium,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(open, { open = false }) { branches.forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { branch = b; open = false }) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(top = Space.sm)) {
                com.ouor.mobilegit.ui.components.SecondaryButton(str(R.string.detail_090), Modifier.weight(1f)) { export.start(kind, listOf(m.name), toFile = false, ref = ref); onDismiss() }
                androidx.compose.material3.Button(onClick = { export.start(kind, listOf(m.name), toFile = true, ref = ref); onDismiss() }, Modifier.weight(1f).height(44.dp), shape = MaterialTheme.shapes.small) { Text(str(R.string.detail_091)) }
            }
            Text(str(R.string.detail_092),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

@Composable
private fun ForkDialog(m: RepoMeta, nav: Nav, onDismiss: () -> Unit) {
    val core = core()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ForkMode.COPY) }
    var newName by remember { mutableStateOf("${m.name}-local") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameErr = if (mode == ForkMode.COPY) RepoNames.validate(newName) ?: if (core.repos.exists(newName)) str(R.string.add_005) else null else null
    ConfirmDialog(
        str(R.string.detail_002), str(R.string.detail_093), if (busy) str(R.string.detail_094) else str(R.string.detail_095),
        onConfirm = {
            busy = true; error = null
            scope.launch {
                try { val r = core.ops.fork(m.name, mode, newName.trim().takeIf { mode == ForkMode.COPY }); onDismiss(); nav.replace(Routes.repo(r.name)) }
                catch (e: Exception) { error = e.message } finally { busy = false }
            }
        },
        onDismiss = onDismiss, confirmEnabled = !busy && nameErr == null,
    ) {
        Spacer(Modifier.height(Space.md))
        Choice(mode == ForkMode.COPY, str(R.string.detail_096), str(R.string.detail_097)) { mode = ForkMode.COPY }
        if (mode == ForkMode.COPY) OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth().padding(vertical = Space.sm), label = { Text(str(R.string.detail_098)) },
            singleLine = true, isError = nameErr != null, supportingText = nameErr?.let { { Text(it) } })
        Spacer(Modifier.height(Space.xs))
        Choice(mode == ForkMode.CONVERT, str(R.string.detail_099), str(R.string.detail_100)) { mode = ForkMode.CONVERT }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.sm)) }
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
        str(R.string.manage_008, m.name), str(R.string.detail_101),
        if (busy) str(R.string.detail_102) else str(R.string.manage_012), destructive = true, confirmEnabled = typed == m.name && !busy,
        onConfirm = {
            busy = true
            scope.launch {
                runCatching { core.ops.delete(m.name) }.onSuccess { onDismiss(); snack(str(R.string.detail_103, m.name)); nav.home() }.onFailure { busy = false; snack(it.message ?: str(R.string.detail_104)) }
            }
        }, onDismiss = onDismiss,
    ) {
        if (gone) {
            Spacer(Modifier.height(Space.md))
            Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.detail_105), str(R.string.detail_106), actionLabel = str(R.string.detail_001), onAction = onExport)
        }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = Space.md), singleLine = true, placeholder = { Text(m.name) })
    }
}

