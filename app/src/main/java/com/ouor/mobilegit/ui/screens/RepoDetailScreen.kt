package com.ouor.mobilegit.ui.screens

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
    var sheet by remember { mutableStateOf<String?>(null) } // export | fork | delete
    var tab by rememberSaveable { mutableIntStateOf(0) }

    AppScaffold(item?.meta?.name ?: name, onBack = nav::back, actions = {
        if (item != null) OverflowMenu {
            item("내보내기") { sheet = "export" }
            if (item.meta.mirror != null) item("로컬 레포로 분리") { sheet = "fork" }
            item("삭제") { sheet = "delete" }
        }
    }) { padding ->
        when {
            items == null -> Box(Modifier.padding(padding)) { Loading() }
            item == null -> Box(Modifier.padding(padding)) { EmptyState(Icons.Outlined.Search, "레포를 찾을 수 없어요", "삭제되었거나 이름이 바뀌었을 수 있어요.") }
            else -> {
                val meta = item.meta
                val events by produceState(emptyList<SyncEvent>(), meta) { value = withContext(Dispatchers.IO) { core.repos.events(meta.name) }.sortedByDescending { it.startedAt } }
                val showHistory = meta.mirror != null || events.isNotEmpty()
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    item(key = "header") { Header(item) }
                    if (showHistory) item(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("정보") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("동기화 이력 ${events.size}") })
                        }
                    }
                    if (tab == 0 || !showHistory) {
                        item(key = "info") { InfoTab(item, export, onSheet = { sheet = it }, nav = nav) }
                    } else {
                        if (events.isEmpty()) item { EmptyState(Icons.Outlined.History, "아직 기록이 없어요", "확인이나 업데이트가 실행되면 여기에 남아요. 변경 없는 확인은 마지막 확인 시각만 갱신해요.") }
                        items(events, key = { it.startedAt.toString() + it.kind }) { EventRow(it) }
                    }
                }
                when (sheet) {
                    "export" -> ExportSheet(meta, export) { sheet = null }
                    "fork" -> ForkDialog(meta, nav) { sheet = null }
                    "delete" -> DeleteDialog(meta, nav, onExport = { sheet = "export" }) { sheet = null }
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
            Text(if (m.mirror != null) "읽기 전용 미러" else "로컬 레포 · push 가능", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs))
        Text("${bytes(m.sizeBytes)} · ${m.sizeMeasuredAt?.let { "${ago(it)} 측정" } ?: "측정 전"}" + (m.defaultBranch?.let { " · 기본 브랜치 $it" } ?: ""),
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
        if (export.running) Banner(Tone.Info, Icons.Outlined.Upload, "내보내는 중…", "끝나면 알려 드릴게요.")
        val mir = m.mirror
        if (mir != null) {
            if (mir.originStatus == OriginStatus.GONE) Banner(Tone.Danger, Icons.Outlined.CloudOff, "원본에 접근할 수 없어요",
                "이 미러가 남은 사본일 수 있습니다. 내보내기를 권장합니다.", actionLabel = "지금 내보내기", onAction = { onSheet("export") })
            OriginCard(item, nav)
        } else LocalInfoCard(m)

        if (m.mirror == null && m.lastPushAt == null) SectionCard(title = "PC에서 시작하기") {
            Text("이 레포는 비어 있어요. PC의 기존 프로젝트에서 아래 명령을 실행하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.sm))
            core.ops.pushCommands(urls.firstOrNull()?.second ?: "http://<폰 주소>:${core.settings.current.port}/${m.name}.git").forEach {
                CommandBlock(it); Spacer(Modifier.height(Space.xs))
            }
        }

        // clone card (F-16)
        SectionCard(title = "clone 주소", trailing = {
            if (urls.isNotEmpty()) IconButton(onClick = { nav.go(Routes.qr(m.name)) }) { Icon(Icons.Outlined.QrCode2, "clone 주소 QR 크게 보기") }
        }) {
            if (server !is ServerState.Running) {
                Banner(Tone.Warning, Icons.Outlined.WifiOff, "서버가 꺼져 있어요", "켜야 이 주소로 접속할 수 있어요.", actionLabel = "서버 켜기", onAction = { core.server.start() })
                Spacer(Modifier.height(Space.sm))
            }
            if (urls.isEmpty()) Text("접속 가능한 네트워크가 없어요. 와이파이·핫스팟·Tailscale 중 하나에 연결하세요.", style = MaterialTheme.typography.bodyMedium)
            urls.forEach { (a, u) -> AddressBar(a.kind.label(), u); Spacer(Modifier.height(Space.xs)) }
            urls.firstOrNull()?.let {
                Spacer(Modifier.height(Space.xs))
                Text("clone 명령", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommandBlock("git clone ${it.second}")
            }
            Text("주소에는 사용자 이름과 비밀번호가 들어가지 않아요. git이 물어보면 접속 계정을 입력하세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        }

        if (m.hasLfs) Banner(Tone.Warning, Icons.Outlined.Inventory2, "LFS 파일은 저장되지 않아요", "이 레포는 Git LFS를 쓰지만 LFS 파일은 저장되지 않습니다. 포인터 파일만 보존돼요.")
        if (m.submoduleUrls.isNotEmpty()) SectionCard(title = "서브모듈 ${m.submoduleUrls.size}개") {
            Text("서브모듈은 함께 보존되지 않습니다. 필요하면 따로 미러로 추가하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.submoduleUrls.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
                    Text(u, style = CodeText.small, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { nav.go(Routes.add(u)) }) { Text("미러 추가") }
                }
            }
        }
        if (m.archivedRefCount > 0) SectionCard(title = "보존된 기록", trailing = { Pill("ref ${m.archivedRefCount}개", Tone.Info, Icons.Outlined.Archive) }) {
            Text("원본에서 force-push나 삭제로 사라진 ref를 refs/archive/에 보존했어요. 일반 clone에는 포함되지 않아요. 받으려면:",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.sm))
            val u = urls.firstOrNull()?.second ?: "<clone 주소>"
            CommandBlock("git clone --mirror $u"); Spacer(Modifier.height(Space.xs))
            CommandBlock("git fetch $u 'refs/archive/*:refs/archive/*'")
        }

        Text("관리", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
        RowGroup {
            NavRow("내보내기", { onSheet("export") }, subtitle = "git bundle 또는 소스 zip으로 폰 밖에 보관", icon = Icons.Outlined.Upload)
            if (m.mirror != null) { Divider(); NavRow("로컬 레포로 분리", { onSheet("fork") }, subtitle = "push할 수 있는 로컬 레포로 복제하거나 전환", icon = Icons.Outlined.CallSplit) }
            Divider()
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSheet("delete") }.padding(horizontal = Space.lg, vertical = Space.md + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(Space.md + Space.xs))
                Text("레포 삭제", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
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
    SectionCard(title = "원본", trailing = { Pill(statusText, statusTone) }) {
        AddressBar("원본 URL", mir.remoteUrl)
        Spacer(Modifier.height(Space.sm))
        InfoRow("마지막 확인", mir.lastCheckAt?.let { "${ago(it)} (${dateTime(it)})" } ?: "아직 없음")
        InfoRow("마지막 업데이트", mir.lastSuccessUpdateAt?.let { "${ago(it)} (${dateTime(it)})" } ?: "아직 없음")
        if (mir.pendingChanges > 0) InfoRow("변경", "업데이트 있음: ref ${mir.pendingChanges}개", valueTone = Tone.Info)
        mir.waitingReason.label()?.let { InfoRow("대기", it, valueTone = Tone.Warning) }
        mir.lastError?.let { InfoRow("최근 오류", Notifier.categoryText(it) + if (mir.consecutiveFailures > 1) " (연속 ${mir.consecutiveFailures}회)" else "", valueTone = Tone.Danger) }
        mir.nextRetryAt?.takeIf { it > System.currentTimeMillis() }?.let { InfoRow("자동 재시도", dateTime(it)) }
        if (mir.originStatus == OriginStatus.AUTH_REQUIRED) {
            Spacer(Modifier.height(Space.sm))
            Banner(Tone.Warning, Icons.Outlined.Key, "인증이 필요해요", "토큰이 없거나 만료됐어요. 자격 증명을 추가하거나 고치세요.", actionLabel = "자격 증명 관리", onAction = { nav.go(Routes.credentials(host)) })
        }
        item.progress?.let { p ->
            Spacer(Modifier.height(Space.md))
            Text(if (p.kind == SyncKind.CHECK) "확인 중…" else (p.progress?.task ?: "받는 중…"), style = MaterialTheme.typography.bodySmall)
            p.progress?.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = Space.xs)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.md))
        PrimaryButton(if (item.progress != null) "업데이트 중…" else "지금 업데이트", enabled = item.progress == null) {
            core.mirrors.updateNow(m.name); snack("업데이트를 시작했어요")
        }
        Text("읽기 전용 미러라 push는 거부돼요. 수정하려면 로컬 레포로 분리하세요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
    }
    RowGroup {
        SwitchRow("자동 업데이트에서 제외", mir.autoUpdateExcluded, { v -> scope.launch(Dispatchers.IO) { core.mirrors.setAutoUpdateExcluded(m.name, v) } },
            subtitle = "제외해도 변경 확인은 계속해요")
        Box(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
            CredentialPicker(creds.filter { it.host == host }, mir.credentialId, { id -> scope.launch(Dispatchers.IO) { core.mirrors.setCredential(m.name, id) } }, host) {
                nav.go(Routes.credentials(host))
            }
        }
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
    SectionCard(title = "정보") {
        if (branches.isNotEmpty()) {
            ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(m.defaultBranch ?: "-", {}, readOnly = true, label = { Text("기본 브랜치 (HEAD)") }, shape = MaterialTheme.shapes.medium,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(open, { open = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(text = { Text(b) }, onClick = {
                            open = false
                            scope.launch { runCatching { core.ops.setDefaultBranch(m.name, b) }.onSuccess { snack("기본 브랜치를 $b(으)로 바꿨어요") }.onFailure { snack(it.message ?: "바꾸지 못했어요") } }
                        })
                    }
                }
            }
            Spacer(Modifier.height(Space.sm))
        }
        InfoRow("마지막 push", m.lastPushAt?.let { "${ago(it)} · ${m.lastPushIp ?: "IP 모름"}" } ?: "아직 없음")
        InfoRow("만든 방법", when (m.createdBy) { CreatedBy.PUSH -> "PC에서 push"; CreatedBy.APP_EMPTY -> "앱에서 빈 레포"; CreatedBy.FORK -> "미러에서 분리"; else -> "미러" } + " · ${dateTime(m.createdAt)}")
        m.forkedFromUrl?.let { InfoRow("분리 원본", "$it\n${dateTime(m.forkedAt)}") }
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
                Text(if (e.kind == SyncKind.CHECK) "확인" else "업데이트", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(Space.sm)); Pill(rl, rt); Spacer(Modifier.width(Space.xs)); Pill(e.trigger.label(), Tone.Neutral)
                Spacer(Modifier.weight(1f))
                Text(ago(e.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val summary = buildList {
                if (e.refChanges.isNotEmpty()) add("ref ${e.refChanges.size}개 변경")
                if (e.newCommitCount > 0) add("새 커밋 ${e.newCommitCount}개")
                if (e.archivedRefCount > 0) add("force-push/삭제 감지: ${e.archivedRefCount}개 ref 보존")
                e.errorCategory?.let { add(Notifier.categoryText(it)) }
                e.waitingReason?.label()?.let { add(it) }
                add("${dateTime(e.startedAt)} · ${"%.1f".format(e.durationMs / 1000.0)}초")
            }
            Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (e.result == SyncResult.FAILED) tone(Tone.Danger).content else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs))
            if (expandable) Text(if (open) "접기" else "자세히", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Space.xs))
            if (open) {
                e.refChanges.forEach { c ->
                    Text("${c.ref.removePrefix("refs/")}  ${shortSha(c.oldSha)} → ${shortSha(c.newSha)}" + (c.archivedAs?.let { "\n  보존: ${it.removePrefix("refs/")}" } ?: ""),
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
    var kind by remember { mutableStateOf(ExportKind.BUNDLE) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("내보내기", style = MaterialTheme.typography.titleLarge)
            Choice(kind == ExportKind.BUNDLE, "git bundle", "모든 브랜치·태그와 보존 ref까지 담아요. PC에서 git clone ${m.name}.bundle 로 복원해요.") { kind = ExportKind.BUNDLE }
            Choice(kind == ExportKind.SOURCE_ZIP, "소스 zip", "기본 브랜치(${m.defaultBranch ?: "HEAD"})의 최신 파일만, .git 없이 담아요.") { kind = ExportKind.SOURCE_ZIP }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(top = Space.sm)) {
                com.ouor.mobilegit.ui.components.SecondaryButton("공유하기", Modifier.weight(1f)) { export.start(kind, listOf(m.name), toFile = false); onDismiss() }
                androidx.compose.material3.Button(onClick = { export.start(kind, listOf(m.name), toFile = true); onDismiss() }, Modifier.weight(1f).height(44.dp), shape = MaterialTheme.shapes.small) { Text("파일로 저장") }
            }
            Text("복원은 앱 안에서 하지 않아요. PC에서 git clone x.bundle 후 이 폰으로 push하면 레포가 다시 만들어져요. 자격 증명과 비밀번호는 담기지 않아요.",
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
    val nameErr = if (mode == ForkMode.COPY) RepoNames.validate(newName) ?: if (core.repos.exists(newName)) "이미 같은 이름의 레포가 있어요" else null else null
    ConfirmDialog(
        "로컬 레포로 분리", "원본 추적이 중단되고 되돌릴 수 없습니다. push가 가능해집니다.", if (busy) "분리 중…" else "분리",
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
        Choice(mode == ForkMode.COPY, "새 로컬 레포로 복제 (권장)", "미러는 계속 원본을 따라가고, 복제본에 push해요.") { mode = ForkMode.COPY }
        if (mode == ForkMode.COPY) OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth().padding(vertical = Space.sm), label = { Text("새 이름") },
            singleLine = true, isError = nameErr != null, supportingText = nameErr?.let { { Text(it) } })
        Spacer(Modifier.height(Space.xs))
        Choice(mode == ForkMode.CONVERT, "이 미러를 로컬로 전환", "미러가 사라지고 이 레포가 push 가능한 로컬 레포가 돼요. 이력은 유지돼요.") { mode = ForkMode.CONVERT }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.sm)) }
    }
}

@Composable
private fun DeleteDialog(m: RepoMeta, nav: Nav, onExport: () -> Unit, onDismiss: () -> Unit) {
    val core = core()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val gone = m.mirror?.originStatus == OriginStatus.GONE
    ConfirmDialog(
        "${m.name} 삭제", "레포 데이터와 이력을 모두 지우고 되돌릴 수 없어요. 진행 중인 clone은 끊겨요. 확인하려면 레포 이름을 입력하세요.",
        if (busy) "삭제 중…" else "삭제", destructive = true, confirmEnabled = typed == m.name && !busy,
        onConfirm = {
            busy = true
            scope.launch {
                runCatching { core.ops.delete(m.name) }.onSuccess { onDismiss(); snack("${m.name}을(를) 삭제했어요"); nav.home() }.onFailure { busy = false; snack(it.message ?: "삭제하지 못했어요") }
            }
        }, onDismiss = onDismiss,
    ) {
        if (gone) {
            Spacer(Modifier.height(Space.md))
            Banner(Tone.Danger, Icons.Outlined.CloudOff, "이 레포는 원본이 없어 복구할 수 없을 수 있습니다", "먼저 내보내기를 해 두세요.", actionLabel = "내보내기", onAction = onExport)
        }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = Space.md), singleLine = true, placeholder = { Text(m.name) })
    }
}

