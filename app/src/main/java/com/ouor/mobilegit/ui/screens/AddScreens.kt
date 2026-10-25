package com.ouor.mobilegit.ui.screens

import androidx.compose.material.icons.outlined.Shield
import com.ouor.mobilegit.ui.components.AdvancedSection
import androidx.compose.material.icons.outlined.Check
import com.ouor.mobilegit.ui.theme.tone
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
import com.ouor.mobilegit.core.AddPreviewError
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
    val auto = if (host != null && creds.any { it.host == host }) str(R.string.cred_auto_first, host) else str(R.string.cred_none_public)
    Column {
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = sel?.let(::label) ?: auto, onValueChange = {}, readOnly = true, label = { Text(str(R.string.cred_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(auto) }, onClick = { open = false; onSelect(null) })
            creds.forEach { c -> DropdownMenuItem(text = { Text(label(c)) }, onClick = { open = false; onSelect(c.id) }) }
            DropdownMenuItem(text = { Text(str(R.string.cred_add_menu), color = MaterialTheme.colorScheme.primary) }, onClick = { open = false; onAdd() })
        }
    }
    Text(str(R.string.cred_storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
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
    // SEC-05: text from another app is not contacted until the user says so
    var confirmed by rememberSaveable { mutableStateOf(initialText == null) }
    var credId by rememberSaveable { mutableStateOf<String?>(null) }
    val settings by core.settings.state.collectAsStateWithLifecycle()
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
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
    LaunchedEffect(url, credId, confirmed) {
        probe = null
        if (url == null || preview?.existing != null || !confirmed) return@LaunchedEffect
        probing = true
        probe = withContext(Dispatchers.IO) { runCatching { core.mirrors.probe(url, credId) }.getOrElse { ProbeResult.Failed(com.ouor.mobilegit.core.ErrorCategory.NETWORK, it.message.orEmpty()) } }
        probing = false
    }

    val nameError = if (name.isBlank()) null else RepoNames.validate(name) ?: if (core.repos.exists(name)) str(R.string.repo_name_taken) else if (core.repos.damaged.value.any { it.name.equals(name, true) }) str(R.string.add_name_damaged) else null
    val lowSpace = free != null && free.first < free.second
    val multi = candidates.size > 1
    val canAdd = !busy && !lowSpace && confirmed && when {
        multi -> true
        else -> url != null && preview?.existing == null && name.isNotBlank() && nameError == null
    }

    AppScaffold(str(R.string.mirror_title), onBack = nav::back, bottomBar = {
        BottomAction(if (multi) str(R.string.mirror_add_many, candidates.size) else str(R.string.mirror_add), enabled = canAdd, loading = busy, onClick = {
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
        }, above = { // QA-10: next to the button so it never scrolls under it
            free?.let { Text(str(R.string.space_free, bytes(it.first)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.sm)) }
        })
    }) { padding ->
        ScreenColumn(padding) {
            Text(str(R.string.mirror_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(str(R.string.mirror_url_label)) }, placeholder = { Text("https://github.com/owner/repo") },
                textStyle = CodeText.body, shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    if (text.isEmpty()) androidx.compose.material3.IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.let { text = it.toString() }
                    }) { Icon(Icons.Outlined.ContentPaste, str(R.string.mirror_paste)) }
                },
            )
            if (lowSpace) Banner(Tone.Danger, Icons.Outlined.SdCardAlert, str(R.string.mirror_low_space_title), str(R.string.mirror_low_space_body, bytes(free!!.first), bytes(free.second)))
            error?.let { ErrorState(it) }
            if (!confirmed && candidates.isNotEmpty()) Banner(Tone.Info, Icons.Outlined.Info, str(R.string.add_shared_title), str(R.string.add_shared_body),
                actionLabel = str(R.string.add_shared_confirm), onAction = { confirmed = true })
            if (candidates.any { it.startsWith("http://", ignoreCase = true) }) Banner(Tone.Warning, Icons.Outlined.Shield, str(R.string.add_http_title), str(R.string.add_http_body))

            if (multi) {
                SectionCard(title = str(R.string.mirror_multi_title, candidates.size)) {
                    candidates.take(50).forEach { Text(it, style = CodeText.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp)) }
                    if (candidates.size > 50) Text(str(R.string.mirror_multi_more, candidates.size - 50), style = MaterialTheme.typography.bodySmall)
                    Text(str(R.string.mirror_multi_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
                }
                CredentialPicker(creds, credId, { credId = it }, null) { nav.go(Routes.CREDENTIALS) }
            } else if (single != null) {
                val p = preview
                when {
                    p == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(Space.sm)); Text(str(R.string.mirror_checking_url)) }
                    // QA-03: errorKind first; error is also set for an existing mirror
                    p.errorKind == AddPreviewError.ALREADY_MIRRORED && p.existing != null -> Banner(Tone.Info, Icons.Outlined.Info, str(R.string.mirror_already_title),
                        str(R.string.add_already_mirrored_body, p.existing!!.name), actionLabel = str(R.string.add_go_existing), onAction = { nav.replace(Routes.repo(p.existing!!.name)) })
                    p.errorKind != null || p.error != null -> Banner(Tone.Danger, Icons.Outlined.ErrorOutline, str(R.string.mirror_unsupported_url), p.error)
                    else -> {
                        SectionCard(title = str(R.string.mirror_info_title)) {
                            Text(str(R.string.mirror_origin), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.normalized!!.url, style = CodeText.body)
                            Spacer(Modifier.size(Space.md))
                            OutlinedTextField(name, { name = it; nameEdited = true }, Modifier.fillMaxWidth(), label = { Text(str(R.string.mirror_name_label)) }, singleLine = true,
                                isError = nameError != null, supportingText = { Text(nameError ?: str(R.string.mirror_phone_url, "${addrs.firstOrNull()?.baseUrl ?: str(R.string.main_address_placeholder, settings.port)}/${name.ifBlank { str(R.string.mirror_name_placeholder) }}.git")) }, shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                            Spacer(Modifier.size(Space.sm))
                            CredentialPicker(creds.filter { it.host == host }, credId, { credId = it }, host) { nav.go(Routes.credentials(host)) }
                        }
                        if (confirmed) ProbeView(probing, probe, pullOn) { nav.go(Routes.credentials(host)) }
                        Text(str(if (pullOn) R.string.add_scope_with_pull else R.string.add_scope), style = MaterialTheme.typography.bodyMedium)
                        if ((probe as? ProbeResult.Reachable)?.pullRefCount?.let { it > 0 } == true || includePull != null) AdvancedSection {
                            com.ouor.mobilegit.ui.components.RowGroup {
                                com.ouor.mobilegit.ui.components.SwitchRow(str(R.string.mirror_pull_refs), pullOn, { v -> includePull = if (v == settings.mirrorPullRefs) null else v },
                                    subtitle = str(R.string.mirror_pull_refs_body))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeView(probing: Boolean, probe: ProbeResult?, includePull: Boolean, onAddToken: () -> Unit) {
    when {
        probing || probe == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(Space.sm))
            Text(str(R.string.probe_checking), style = MaterialTheme.typography.bodyMedium)
        }
        probe is ProbeResult.Reachable -> Banner(Tone.Success, Icons.Outlined.CheckCircle, str(R.string.probe_reachable),
            (if (!includePull && probe.pullRefCount > 0) str(R.string.probe_refs_excluding_pull, probe.refCount - probe.pullRefCount, probe.pullRefCount) else str(R.string.add_probe_refs, probe.refCount)) +
                (probe.defaultBranch?.let { str(R.string.default_branch_suffix, it) } ?: ""))
        probe is ProbeResult.AuthRequired -> Banner(Tone.Warning, Icons.Outlined.Key, str(R.string.probe_token_needed), str(R.string.probe_token_needed_body),
            actionLabel = str(R.string.probe_add_token), onAction = onAddToken)
        probe is ProbeResult.NotFound -> Banner(Tone.Danger, Icons.Outlined.CloudOff, str(R.string.probe_not_found), str(R.string.probe_not_found_body),
            actionLabel = str(R.string.probe_add_token), onAction = onAddToken)
        probe is ProbeResult.Failed -> Banner(Tone.Danger, Icons.Outlined.ErrorOutline, str(Notifier.categoryRes(probe.category)), probe.detail.take(300) + str(R.string.probe_add_anyway))
    }
}

// ---------------- A-04 일괄 미러 ----------------

private val sourceLabels get() = listOf(BulkSourceKind.GITHUB_USER to str(R.string.bulk_github_user), BulkSourceKind.GITHUB_ORG to str(R.string.bulk_github_org),
    BulkSourceKind.GITLAB_USER to str(R.string.bulk_gitlab_user), BulkSourceKind.GITLAB_GROUP to str(R.string.bulk_gitlab_group))

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
                error = when (e.status) { 404 -> str(R.string.bulk_account_not_found); 401, 403 -> str(R.string.bulk_token_rejected); else -> e.message ?: str(R.string.bulk_list_failed) }
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { loading = false; rateUntil = null }
        }
    }

    AppScaffold(str(R.string.bulk_title), onBack = nav::back, bottomBar = {
        if (list != null) BottomAction(str(R.string.bulk_add_count, chosen.size), enabled = chosen.isNotEmpty(), loading = busy, onClick = {
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
            Text(str(R.string.bulk_selected, bytes(knownSize)) + (if (unknown > 0) str(R.string.bulk_unknown_size_suffix, unknown) else "") + (f?.let { str(R.string.bulk_free_suffix, bytes(it.first)) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.sm))
            if (over) Text(str(R.string.bulk_too_large, bytes(f!!.second)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Space.sm))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.sm, bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        sourceLabels.forEach { (k, l) -> FilterChip(selected = kind == k, onClick = { kind = k; credId = null }, label = { Text(l) },
                            leadingIcon = if (kind == k) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null) }
                    }
                    OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                        label = { Text(if (kind == BulkSourceKind.GITHUB_ORG || kind == BulkSourceKind.GITLAB_GROUP) str(R.string.bulk_org_label) else str(R.string.bulk_user_label)) })
                    if (gitlab) OutlinedTextField(gitlabHost, { gitlabHost = it }, Modifier.fillMaxWidth().padding(top = Space.sm), singleLine = true, shape = MaterialTheme.shapes.medium,
                        label = { Text(str(R.string.bulk_gitlab_host)) }, placeholder = { Text("gitlab.com") })
                    Spacer(Modifier.size(Space.sm))
                    CredentialPicker(hostCreds, credId, { credId = it }, host) { nav.go(Routes.credentials(host)) }
                    Text(str(R.string.bulk_token_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                    Spacer(Modifier.size(Space.md))
                    com.ouor.mobilegit.ui.components.SecondaryButton(if (loading) str(R.string.bulk_loading) else str(R.string.bulk_load), Modifier.fillMaxWidth(), enabled = account.isNotBlank() && !loading,
                        icon = Icons.Outlined.Search) { load() }
                }
            }
            rateUntil?.let { item { Banner(Tone.Warning, Icons.Outlined.Timer, str(R.string.bulk_rate_limit), str(R.string.bulk_rate_limit_body, time(it))) } }
            error?.let { item { ErrorState(it, onRetry = ::load) } }
            if (loading && rateUntil == null) item { com.ouor.mobilegit.ui.components.Loading() }
            list?.let { l ->
                item {
                    // QA2-05: chips wrap on narrow screens; 전체 선택 sits in the list header with the count
                    FlowRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilterChip(noForks, { noForks = !noForks }, { Text(str(R.string.bulk_hide_forks)) }, leadingIcon = if (noForks) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
                        FilterChip(noArchived, { noArchived = !noArchived }, { Text(str(R.string.bulk_hide_archived)) }, leadingIcon = if (noArchived) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.bulk_shown_count, l.size, visible.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        val allOn = selectable.isNotEmpty() && selectable.all { it.cloneUrl in selected }
                        TextButton(onClick = { if (allOn) selected.removeAll(selectable.map { it.cloneUrl }) else selectable.forEach { if (it.cloneUrl !in selected) selected += it.cloneUrl } }) {
                            Text(if (allOn) str(R.string.bulk_select_none) else str(R.string.bulk_select_all), maxLines = 1, softWrap = false)
                        }
                    }
                }
                if (visible.isEmpty()) item { EmptyState(Icons.Outlined.Search, str(R.string.bulk_empty_title), str(R.string.bulk_empty_body)) }
                items(visible, key = { it.cloneUrl }) { r ->
                    val done = r.cloneUrl in mirrored
                    val on = r.cloneUrl in selected
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.clickable(enabled = !done) { if (on) selected.remove(r.cloneUrl) else selected.add(r.cloneUrl) }.padding(end = Space.md, top = Space.xs, bottom = Space.xs),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (done) Icon(Icons.Outlined.CheckCircle, null, tint = tone(Tone.Success).content, modifier = Modifier.padding(Space.md + 2.dp).size(20.dp))
                            else Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(Space.md))
                            Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall)
                                r.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                FlowRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    if (done) Pill(str(R.string.bulk_registered), Tone.Success)
                                    r.sizeBytes?.let { Pill(bytes(it), Tone.Neutral) }
                                    if (r.fork) Pill(str(R.string.add_fork_pill), Tone.Neutral)
                                    if (r.archived) Pill(str(R.string.add_archived_pill), Tone.Warning)
                                    if (r.isPrivate) Pill(str(R.string.bulk_private), Tone.Info)
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
    AppScaffold(str(R.string.progress_title), onBack = nav::back, bottomBar = { BottomAction(str(R.string.progress_to_list), onClick = nav::home) }) { padding ->
        if (job == null) { Box(Modifier.padding(padding)) { EmptyState(Icons.Outlined.Search, str(R.string.progress_not_found), str(R.string.progress_not_found_body)) }; return@AppScaffold }
        val total = job.items.size
        val done = job.count(BulkItemStatus.DONE); val failed = job.count(BulkItemStatus.FAILED)
        val waiting = job.count(BulkItemStatus.QUEUED) + job.count(BulkItemStatus.RUNNING)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.sm, bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    Text(if (job.source == BulkSourceKind.URL_LIST) str(R.string.progress_multi_lines) else (sourceLabels.firstOrNull { it.first == job.source }?.second ?: "") + " ${job.account}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (waiting == 0) str(R.string.progress_done_total, done, total) else str(R.string.progress_processed, done + failed, total), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(Space.md))
                    LinearProgressIndicator(progress = { if (total == 0) 1f else (done + failed).toFloat() / total }, Modifier.fillMaxWidth())
                    Spacer(Modifier.size(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Pill(str(R.string.progress_done, done), Tone.Success); Pill(str(R.string.progress_failed, failed), if (failed > 0) Tone.Danger else Tone.Neutral); Pill(str(R.string.progress_queued, waiting), Tone.Neutral)
                    }
                    Text(str(R.string.progress_background_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.md))
                }
            }
            job.rateLimitedUntil?.takeIf { it > System.currentTimeMillis() }?.let {
                item { Banner(Tone.Warning, Icons.Outlined.Timer, str(R.string.progress_rate_limit), str(R.string.progress_resume_at, time(it))) }
            }
            if (failed > 0) item {
                com.ouor.mobilegit.ui.components.SecondaryButton(str(R.string.progress_retry_failed, failed), Modifier.fillMaxWidth()) {
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
                                BulkItemStatus.QUEUED -> str(R.string.status_waiting) to Tone.Neutral; BulkItemStatus.RUNNING -> str(R.string.status_fetching) to Tone.Info
                                BulkItemStatus.DONE -> str(R.string.status_done) to Tone.Success; BulkItemStatus.FAILED -> str(R.string.badge_failed) to Tone.Danger
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
    val nameError = if (name.isEmpty()) null else RepoNames.validate(name) ?: if (core.repos.exists(name)) str(R.string.repo_name_taken) else if (core.repos.damaged.value.any { it.name.equals(name, true) }) str(R.string.add_name_damaged) else null
    AppScaffold(str(R.string.empty_repo_title), onBack = nav::back, bottomBar = {
        BottomAction(str(R.string.empty_repo_create), enabled = name.isNotBlank() && nameError == null, loading = busy, onClick = {
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
            Text(str(R.string.empty_repo_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.mirror_name_placeholder)) }, singleLine = true, isError = nameError != null, shape = MaterialTheme.shapes.medium,
                supportingText = { Text(nameError ?: str(R.string.repo_name_rule)) })
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.empty_repo_description)) }, shape = MaterialTheme.shapes.medium)
            error?.let { ErrorState(it) }
        }
    }
}
