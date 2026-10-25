package com.ouor.mobilegit.ui.screens

import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.Notifier
import com.ouor.mobilegit.core.AddPreview
import com.ouor.mobilegit.core.BulkItemStatus
import com.ouor.mobilegit.core.BulkSourceKind
import com.ouor.mobilegit.core.CatalogException
import com.ouor.mobilegit.core.CatalogRepo
import com.ouor.mobilegit.core.CredentialStore
import com.ouor.mobilegit.core.ProbeResult
import com.ouor.mobilegit.core.RemoteCredential
import com.ouor.mobilegit.core.RemoteUrls
import com.ouor.mobilegit.core.RepoNames
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.bytes
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.BottomAction
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.ErrorState
import com.ouor.mobilegit.ui.components.Pill
import com.ouor.mobilegit.ui.components.ScreenColumn
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.theme.CodeText
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import com.ouor.mobilegit.ui.time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Free space vs floor, measured off the main thread. */
@Composable
fun rememberFreeSpace(): Pair<Long, Long>? {
    val core = core()
    val v by produceState<Pair<Long, Long>?>(null) { value = withContext(Dispatchers.IO) { core.env.freeBytes() to core.settings.current.storageFloorBytes } }
    return v
}

/** Credential dropdown. `null` = 자동 (해당 호스트의 첫 번째 자격 증명, F-07 AC1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPicker(creds: List<RemoteCredential>, selected: String?, onSelect: (String?) -> Unit, host: String?, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sel = creds.firstOrNull { it.id == selected }
    fun label(c: RemoteCredential) = (c.label.ifBlank { c.host }) + " · ${c.tokenPrefix}••••"
    val auto = if (host != null && creds.any { it.host == host }) str(R.string.add_001, host) else str(R.string.add_002)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = sel?.let(::label) ?: auto, onValueChange = {}, readOnly = true, label = { Text(str(R.string.add_003)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(auto) }, onClick = { open = false; onSelect(null) })
            creds.forEach { c -> DropdownMenuItem(text = { Text(label(c)) }, onClick = { open = false; onSelect(c.id) }) }
            DropdownMenuItem(text = { Text(str(R.string.add_004), color = MaterialTheme.colorScheme.primary) }, onClick = { open = false; onAdd() })
        }
    }
}

// ---------------- A-03 미러 추가 ----------------

/** A-03 (F-06, F-07). Single URL: preview → probe → add. Multiple lines: bulk add (AC7). */
@Composable
fun AddMirrorScreen(initialText: String?, nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val creds by core.credentials.items.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(initialText?.let { RemoteUrls.extractCandidates(it).joinToString("\n") } ?: "") }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var credId by rememberSaveable { mutableStateOf<String?>(null) }
    val settings by core.settings.state.collectAsStateWithLifecycle()
    var includePull by rememberSaveable { mutableStateOf<Boolean?>(null) } // null = 전역 설정 따름 (CR-2)
    val pullOn = includePull ?: settings.mirrorPullRefs
    var preview by remember { mutableStateOf<AddPreview?>(null) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var probing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val free = rememberFreeSpace()

    val candidates = remember(text) { RemoteUrls.extractCandidates(text) }
    val single = candidates.singleOrNull()

    LaunchedEffect(single) {
        preview = null; probe = null; error = null
        if (single == null) return@LaunchedEffect
        delay(250)
        val p = withContext(Dispatchers.IO) { core.mirrors.preview(single) }
        preview = p
        if (!nameEdited) name = p.suggestedName.orEmpty()
    }
    val url = preview?.normalized?.url
    val host = preview?.normalized?.host
    LaunchedEffect(url, credId) {
        probe = null
        if (url == null || preview?.existing != null) return@LaunchedEffect
        probing = true
        probe = withContext(Dispatchers.IO) { runCatching { core.mirrors.probe(url, credId) }.getOrElse { ProbeResult.Failed(com.ouor.mobilegit.core.ErrorCategory.NETWORK, it.message.orEmpty()) } }
        probing = false
    }

    val nameError = if (name.isBlank()) null else RepoNames.validate(name) ?: if (core.repos.exists(name)) str(R.string.add_005) else null
    val lowSpace = free != null && free.first < free.second
    val multi = candidates.size > 1
    val canAdd = !busy && !lowSpace && when {
        multi -> true
        else -> url != null && preview?.existing == null && name.isNotBlank() && nameError == null
    }

    AppScaffold(str(R.string.add_006), onBack = nav::back, bottomBar = {
        BottomAction(if (multi) str(R.string.add_007, candidates.size) else str(R.string.add_008), enabled = canAdd, loading = busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    if (multi) {
                        val job = withContext(Dispatchers.IO) { core.mirrors.addMany(text, credId) }
                        nav.replace(Routes.bulkProgress(job.id))
                    } else {
                        val meta = withContext(Dispatchers.IO) { core.mirrors.add(url!!, name.trim(), credId, includePullRefs = includePull) }
                        nav.replace(Routes.repo(meta.name))
                    }
                } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
            }
        })
    }) { padding ->
        ScreenColumn(padding) {
            Text(str(R.string.add_009), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(str(R.string.add_010)) }, placeholder = { Text("https://github.com/owner/repo") },
                textStyle = CodeText.body, shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    if (text.isEmpty()) androidx.compose.material3.IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.let { text = it.toString() }
                    }) { Icon(Icons.Outlined.ContentPaste, str(R.string.add_011)) }
                },
            )
            if (lowSpace) Banner(Tone.Danger, Icons.Outlined.SdCardAlert, str(R.string.add_012), str(R.string.add_013, bytes(free!!.first), bytes(free.second)))
            error?.let { ErrorState(it) }

            if (multi) {
                SectionCard(title = str(R.string.add_014, candidates.size)) {
                    candidates.take(50).forEach { Text(it, style = CodeText.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp)) }
                    if (candidates.size > 50) Text(str(R.string.add_015, candidates.size - 50), style = MaterialTheme.typography.bodySmall)
                    Text(str(R.string.add_016), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
                }
                CredentialPicker(creds, credId, { credId = it }, null) { nav.go(Routes.CREDENTIALS) }
            } else if (single != null) {
                val p = preview
                when {
                    p == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(Space.sm)); Text(str(R.string.add_017)) }
                    p.error != null -> Banner(Tone.Danger, Icons.Outlined.ErrorOutline, str(R.string.add_018), p.error)
                    p.existing != null -> Banner(Tone.Info, Icons.Outlined.Info, str(R.string.add_019), p.existing!!.name, actionLabel = str(R.string.add_020), onAction = { nav.replace(Routes.repo(p.existing!!.name)) })
                    else -> {
                        SectionCard(title = str(R.string.add_021)) {
                            Text(str(R.string.add_022), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.normalized!!.url, style = CodeText.body)
                            Spacer(Modifier.size(Space.md))
                            OutlinedTextField(name, { name = it; nameEdited = true }, Modifier.fillMaxWidth(), label = { Text(str(R.string.add_023)) }, singleLine = true,
                                isError = nameError != null, supportingText = { Text(nameError ?: str(R.string.add_025, name.ifBlank { str(R.string.add_024) })) }, shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                            CredentialPicker(creds.filter { it.host == host }, credId, { credId = it }, host) { nav.go(Routes.credentials(host)) }
                        }
                        ProbeView(probing, probe, pullOn) { nav.go(Routes.credentials(host)) }
                        if ((probe as? ProbeResult.Reachable)?.pullRefCount?.let { it > 0 } == true || includePull != null) com.ouor.mobilegit.ui.components.RowGroup {
                            com.ouor.mobilegit.ui.components.SwitchRow(str(R.string.add_026), pullOn, { v -> includePull = if (v == settings.mirrorPullRefs) null else v },
                                subtitle = str(R.string.add_027))
                        }
                    }
                }
            }
            free?.let { Text(str(R.string.add_028, bytes(it.first)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ProbeView(probing: Boolean, probe: ProbeResult?, includePull: Boolean, onAddToken: () -> Unit) {
    when {
        probing || probe == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(Space.sm))
            Text(str(R.string.add_029), style = MaterialTheme.typography.bodyMedium)
        }
        probe is ProbeResult.Reachable -> Banner(Tone.Success, Icons.Outlined.CheckCircle, str(R.string.add_030),
            (if (!includePull && probe.pullRefCount > 0) str(R.string.add_031, probe.refCount - probe.pullRefCount, probe.pullRefCount) else str(R.string.add_032, probe.refCount)) +
                (probe.defaultBranch?.let { str(R.string.add_033, it) } ?: ""))
        probe is ProbeResult.AuthRequired -> Banner(Tone.Warning, Icons.Outlined.Key, str(R.string.add_034), str(R.string.add_035),
            actionLabel = str(R.string.add_036), onAction = onAddToken)
        probe is ProbeResult.NotFound -> Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.add_037), str(R.string.add_038),
            actionLabel = str(R.string.add_036), onAction = onAddToken)
        probe is ProbeResult.Failed -> Banner(Tone.Danger, Icons.Outlined.ErrorOutline, Notifier.categoryText(probe.category), probe.detail.take(300) + str(R.string.add_039))
    }
}

// ---------------- A-04 일괄 미러 ----------------

private val sourceLabels get() = listOf(BulkSourceKind.GITHUB_USER to str(R.string.add_040), BulkSourceKind.GITHUB_ORG to str(R.string.add_041),
    BulkSourceKind.GITLAB_USER to str(R.string.add_042), BulkSourceKind.GITLAB_GROUP to str(R.string.add_043))

/** A-04 (F-15 AC1~AC4). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkMirrorScreen(nav: Nav) {
    val core = core()
    val scope = rememberCoroutineScope()
    val creds by core.credentials.items.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(BulkSourceKind.GITHUB_USER) }
    var account by rememberSaveable { mutableStateOf("") }
    var gitlabHost by rememberSaveable { mutableStateOf("") }
    var credId by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<CatalogRepo>?>(null) }
    var mirrored by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rateUntil by remember { mutableStateOf<Long?>(null) }
    var noForks by rememberSaveable { mutableStateOf(true) }
    var noArchived by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val free = rememberFreeSpace()

    val gitlab = kind == BulkSourceKind.GITLAB_USER || kind == BulkSourceKind.GITLAB_GROUP
    val host = CredentialStore.normalizeHost(if (gitlab) gitlabHost.ifBlank { "gitlab.com" } else "github.com")
    val hostCreds = creds.filter { it.host == host }
    val visible = list.orEmpty().filter { (!noForks || !it.fork) && (!noArchived || !it.archived) }
    val selectable = visible.filter { it.cloneUrl !in mirrored }
    val chosen = list.orEmpty().filter { it.cloneUrl in selected }
    val knownSize = chosen.sumOf { it.sizeBytes ?: 0 }
    val unknown = chosen.count { it.sizeBytes == null }

    fun load() {
        loading = true; error = null; list = null; selected.clear(); rateUntil = null
        scope.launch {
            try {
                val cred = creds.firstOrNull { it.id == credId } ?: hostCreds.firstOrNull()
                val token = cred?.let { withContext(Dispatchers.IO) { core.credentials.token(it) } }
                val l = core.catalog.list(kind, account, if (gitlab) gitlabHost else "", token) { rateUntil = it }
                mirrored = withContext(Dispatchers.IO) { core.catalog.alreadyMirrored(l, core.repos) }
                list = l
            } catch (e: CatalogException) {
                error = when (e.status) { 404 -> str(R.string.add_044); 401, 403 -> str(R.string.add_045); else -> e.message ?: str(R.string.add_046) }
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { loading = false; rateUntil = null }
        }
    }

    AppScaffold(str(R.string.add_047), onBack = nav::back, bottomBar = {
        if (list != null) BottomAction(str(R.string.add_048, chosen.size), enabled = chosen.isNotEmpty(), loading = busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val job = withContext(Dispatchers.IO) { core.mirrors.addFromCatalog(kind, account.trim(), if (gitlab) gitlabHost else "", chosen, credId) }
                    nav.replace(Routes.bulkProgress(job.id))
                } catch (e: Exception) { error = e.message } finally { busy = false }
            }
        }) {
            val f = free
            val over = f != null && knownSize > f.first - f.second
            Text(str(R.string.add_049, bytes(knownSize)) + (if (unknown > 0) str(R.string.add_050, unknown) else "") + (f?.let { str(R.string.add_051, bytes(it.first)) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.sm))
            if (over) Text(str(R.string.add_052, bytes(f!!.second)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Space.sm))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        sourceLabels.forEach { (k, l) -> FilterChip(selected = kind == k, onClick = { kind = k; credId = null }, label = { Text(l) }) }
                    }
                    OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                        label = { Text(if (kind == BulkSourceKind.GITHUB_ORG || kind == BulkSourceKind.GITLAB_GROUP) str(R.string.add_053) else str(R.string.add_054)) })
                    if (gitlab) OutlinedTextField(gitlabHost, { gitlabHost = it }, Modifier.fillMaxWidth().padding(top = Space.sm), singleLine = true, shape = MaterialTheme.shapes.medium,
                        label = { Text(str(R.string.add_055)) }, placeholder = { Text("gitlab.com") })
                    Spacer(Modifier.size(Space.sm))
                    CredentialPicker(hostCreds, credId, { credId = it }, host) { nav.go(Routes.credentials(host)) }
                    Text(str(R.string.add_056), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                    Spacer(Modifier.size(Space.md))
                    com.ouor.mobilegit.ui.components.SecondaryButton(if (loading) str(R.string.add_057) else str(R.string.add_058), Modifier.fillMaxWidth(), enabled = account.isNotBlank() && !loading,
                        icon = Icons.Outlined.Search) { load() }
                }
            }
            rateUntil?.let { item { Banner(Tone.Warning, Icons.Outlined.Timer, str(R.string.add_059), str(R.string.add_060, time(it))) } }
            error?.let { item { ErrorState(it, onRetry = ::load) } }
            if (loading && rateUntil == null) item { com.ouor.mobilegit.ui.components.Loading() }
            list?.let { l ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.sm)) {
                        FilterChip(noForks, { noForks = !noForks }, { Text(str(R.string.add_061)) })
                        Spacer(Modifier.width(Space.sm))
                        FilterChip(noArchived, { noArchived = !noArchived }, { Text(str(R.string.add_062)) })
                        Spacer(Modifier.weight(1f))
                        val allOn = selectable.isNotEmpty() && selectable.all { it.cloneUrl in selected }
                        TextButton(onClick = { if (allOn) selected.removeAll(selectable.map { it.cloneUrl }) else selectable.forEach { if (it.cloneUrl !in selected) selected += it.cloneUrl } }) {
                            Text(if (allOn) str(R.string.add_063) else str(R.string.add_064))
                        }
                    }
                    Text(str(R.string.add_065, l.size, visible.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (visible.isEmpty()) item { EmptyState(Icons.Outlined.Search, str(R.string.add_066), str(R.string.add_067)) }
                items(visible, key = { it.cloneUrl }) { r ->
                    val done = r.cloneUrl in mirrored
                    val on = r.cloneUrl in selected
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.clickable(enabled = !done) { if (on) selected.remove(r.cloneUrl) else selected.add(r.cloneUrl) }.padding(end = Space.md, top = Space.xs, bottom = Space.xs),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on || done, onCheckedChange = null, enabled = !done, modifier = Modifier.padding(Space.md))
                            Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall)
                                r.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                FlowRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    if (done) Pill(str(R.string.add_068), Tone.Success)
                                    r.sizeBytes?.let { Pill(bytes(it), Tone.Neutral) }
                                    if (r.fork) Pill("fork", Tone.Neutral)
                                    if (r.archived) Pill("archived", Tone.Warning)
                                    if (r.isPrivate) Pill(str(R.string.add_069), Tone.Info)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- A-05 일괄 진행 ----------------

/** A-05 (F-15 AC5~AC7, F-06 AC7). */
@Composable
fun BulkProgressScreen(id: String, nav: Nav) {
    val core = core()
    val scope = rememberCoroutineScope()
    val jobs by core.bulkJobs.jobs.collectAsStateWithLifecycle()
    val running by core.coordinator.running.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == id }
    AppScaffold(str(R.string.add_070), onBack = nav::back, bottomBar = { BottomAction(str(R.string.add_071), onClick = nav::home) }) { padding ->
        if (job == null) { Box(Modifier.padding(padding)) { EmptyState(Icons.Outlined.Search, str(R.string.add_072), str(R.string.add_073)) }; return@AppScaffold }
        val total = job.items.size
        val done = job.count(BulkItemStatus.DONE); val failed = job.count(BulkItemStatus.FAILED)
        val waiting = job.count(BulkItemStatus.QUEUED) + job.count(BulkItemStatus.RUNNING)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    Text(if (job.source == BulkSourceKind.URL_LIST) str(R.string.add_074) else (sourceLabels.firstOrNull { it.first == job.source }?.second ?: "") + " ${job.account}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (waiting == 0) str(R.string.add_075, done, total) else str(R.string.add_076, done + failed, total), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(Space.md))
                    LinearProgressIndicator(progress = { if (total == 0) 1f else (done + failed).toFloat() / total }, Modifier.fillMaxWidth())
                    Spacer(Modifier.size(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Pill(str(R.string.add_077, done), Tone.Success); Pill(str(R.string.add_078, failed), if (failed > 0) Tone.Danger else Tone.Neutral); Pill(str(R.string.add_079, waiting), Tone.Neutral)
                    }
                    Text(str(R.string.add_080), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.md))
                }
            }
            job.rateLimitedUntil?.takeIf { it > System.currentTimeMillis() }?.let {
                item { Banner(Tone.Warning, Icons.Outlined.Timer, str(R.string.add_081), str(R.string.add_082, time(it))) }
            }
            if (failed > 0) item {
                com.ouor.mobilegit.ui.components.SecondaryButton(str(R.string.add_083, failed), Modifier.fillMaxWidth()) {
                    scope.launch(Dispatchers.IO) { core.mirrors.retryFailed(job.id) }
                }
            }
            items(job.items, key = { it.repoName }) { it ->
                val p = running[it.repoName.lowercase()]?.progress
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable(enabled = it.status == BulkItemStatus.DONE) { nav.go(Routes.repo(it.repoName)) }.padding(Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.repoName, style = MaterialTheme.typography.titleSmall)
                                Text(it.remoteUrl, style = CodeText.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val (l, t) = when (it.status) {
                                BulkItemStatus.QUEUED -> str(R.string.add_084) to Tone.Neutral; BulkItemStatus.RUNNING -> str(R.string.add_085) to Tone.Info
                                BulkItemStatus.DONE -> str(R.string.add_086) to Tone.Success; BulkItemStatus.FAILED -> str(R.string.fmt_008) to Tone.Danger
                            }
                            Pill(l, t)
                        }
                        if (it.status == BulkItemStatus.RUNNING) {
                            Spacer(Modifier.size(Space.sm))
                            p?.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        it.error?.let { e -> Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.xs)) }
                    }
                }
            }
        }
    }
}

// ---------------- A-06 빈 레포 만들기 ----------------

/** A-06 (F-14). */
@Composable
fun CreateRepoScreen(nav: Nav) {
    val core = core()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameError = if (name.isEmpty()) null else RepoNames.validate(name) ?: if (core.repos.exists(name)) str(R.string.add_005) else null
    AppScaffold(str(R.string.add_087), onBack = nav::back, bottomBar = {
        BottomAction(str(R.string.add_088), enabled = name.isNotBlank() && nameError == null, loading = busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val m = withContext(Dispatchers.IO) { core.ops.createEmpty(name.trim(), desc.trim()) }
                    nav.replace(Routes.repo(m.name))
                } catch (e: Exception) { error = e.message } finally { busy = false }
            }
        })
    }) { padding ->
        ScreenColumn(padding) {
            Text(str(R.string.add_089), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.add_024)) }, singleLine = true, isError = nameError != null, shape = MaterialTheme.shapes.medium,
                supportingText = { Text(nameError ?: str(R.string.add_090)) })
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.add_091)) }, shape = MaterialTheme.shapes.medium)
            error?.let { ErrorState(it) }
        }
    }
}
