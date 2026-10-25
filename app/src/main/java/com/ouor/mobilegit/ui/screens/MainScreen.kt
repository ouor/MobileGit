package com.ouor.mobilegit.ui.screens

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
            ExtendedFloatingActionButton(onClick = { addSheet = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("레포 추가") },
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
                    IconButton(onClick = { nav.go(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "설정") }
                    OverflowMenu {
                        item("모든 미러 업데이트") { core.mirrors.updateAll(); snack("모든 미러 업데이트를 시작했어요") }
                        item("저장 공간") { nav.go(Routes.STORAGE) }
                        item("전체 백업") { nav.go(Routes.BACKUP) }
                        item("접속 기록") { nav.go(Routes.ACCESS_LOG) }
                        item("설정") { nav.go(Routes.SETTINGS) }
                    }
                }
            }
            if (settings.ngrokEnabled) item(key = "ngrok") {
                Banner(Tone.Danger, Icons.Outlined.Public, "외부 공개 중 (ngrok)", tunnelText(tunnel, server is ServerState.Running),
                    actionLabel = "외부 공개 끄기", onAction = { core.settings.update { it.copy(ngrokEnabled = false) }; snack("외부 공개를 껐어요") })
            }
            if (lowStorage) item(key = "storage") {
                Banner(Tone.Danger, Icons.Outlined.SdCardAlert, "저장 공간이 부족해요", "자동 업데이트와 새 미러 받기를 멈췄어요. push는 계속 받아요.",
                    actionLabel = "저장 공간 보기", onAction = { nav.go(Routes.STORAGE) })
            }
            item(key = "server") {
                ServerCard(server, addrs, settings.port, settings.effectiveAuthEnabled, settings.effectiveAnonymousRead,
                    onToggle = { on -> if (on) core.server.start() else core.server.stop() },
                    onQr = { nav.go(Routes.qr(null)) }, onPort = { nav.go(Routes.SETTINGS) }, onTailscale = { dialog = "tailscale" })
            }
            val chips = !settings.effectiveAuthEnabled || batteryHint || delayed > 0
            if (chips) item(key = "chips") {
                FlowRow(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (!settings.effectiveAuthEnabled) WarningChip("인증 꺼짐", Icons.Outlined.LockOpen, Tone.Danger) { nav.go(Routes.SETTINGS) }
                    if (delayed > 0) WarningChip("확인 지연 ${delayed}개", Icons.Outlined.HourglassBottom) { dialog = "delayed" }
                    if (batteryHint) WarningChip("배터리 최적화 해제 필요", Icons.Outlined.BatteryAlert,
                        onDismiss = { core.settings.update { it.copy(batteryHintDismissedAt = System.currentTimeMillis()) } }) { nav.go(Routes.BATTERY) }
                }
            }
            item(key = "repoHeader") {
                Column(Modifier.padding(top = Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("레포", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(Space.sm))
                        Text("${all.size}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { sort = if (sort == RepoSort.RECENT) RepoSort.NAME else RepoSort.RECENT }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs))
                            Text(if (sort == RepoSort.RECENT) "최근 활동순" else "이름순")
                        }
                    }
                    if (all.isNotEmpty()) {
                        SearchField(query) { query = it }
                        LazyRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            val opts = listOf(RepoFilter.ALL to "전체", RepoFilter.MIRROR to "미러 ${all.count { it.meta.type == RepoType.MIRROR }}",
                                RepoFilter.LOCAL to "로컬 ${all.count { it.meta.type == RepoType.LOCAL }}", RepoFilter.PROBLEM to "문제 있음 $problems")
                            items(opts) { (f, label) -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) }) }
                        }
                    }
                }
            }
            when {
                items == null -> item(key = "loading") { Loading() }
                all.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Outlined.Inventory2, "아직 레포가 없어요", "원격 레포를 미러로 보존하거나, PC에서 바로 push해 새 레포를 만들 수 있어요.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SecondaryButton("미러 추가", icon = Icons.Outlined.SyncAlt) { nav.go(Routes.add()) }
                            SecondaryButton("빈 레포", icon = Icons.Outlined.CreateNewFolder) { nav.go(Routes.CREATE) }
                        }
                        Spacer(Modifier.height(Space.sm))
                        SecondaryButton("계정 단위 일괄 미러", icon = Icons.Outlined.Groups) { nav.go(Routes.BULK) }
                        Spacer(Modifier.height(Space.lg))
                        Text("PC에서 push로 만들기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.xs))
                        CommandBlock("git push ${addrs.firstOrNull()?.baseUrl ?: "http://<폰 주소>:${settings.port}"}/my-project.git main")
                    }
                }
                shown.isEmpty() -> item(key = "noMatch") {
                    EmptyState(Icons.Outlined.Search, "조건에 맞는 레포가 없어요", "검색어나 필터를 바꿔 보세요.") {
                        TextButton(onClick = { query = ""; filter = RepoFilter.ALL }) { Text("필터 지우기") }
                    }
                }
                else -> items(shown, key = { it.meta.id }) { item ->
                    RepoRow(item, onClick = { nav.go(Routes.repo(item.meta.name)) },
                        onUpdate = if (item.meta.mirror != null) ({ core.mirrors.updateNow(item.meta.name); snack("${item.meta.name} 업데이트를 시작했어요") }) else null)
                }
            }
        }
    }

    if (addSheet) ModalBottomSheet(onDismissRequest = { addSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Space.lg).padding(bottom = Space.xl).navigationBarsPadding()) {
            Text("레포 추가", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.sm))
            AddOption(Icons.Outlined.SyncAlt, "미러 추가", "GitHub·GitLab 등의 주소를 붙여넣어 보존해요. 여러 줄도 돼요.") { addSheet = false; nav.go(Routes.add()) }
            AddOption(Icons.Outlined.Groups, "계정·조직 일괄 미러", "한 계정의 레포를 골라 한 번에 등록해요.") { addSheet = false; nav.go(Routes.BULK) }
            AddOption(Icons.Outlined.CreateNewFolder, "빈 레포 만들기", "PC에서 push할 새 로컬 레포를 만들어요.") { addSheet = false; nav.go(Routes.CREATE) }
        }
    }

    when (dialog) {
        "delayed" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("확인이 지연되고 있어요") },
            text = { Text("백그라운드 작업이 제한되어 확인이 지연되었습니다. 배터리 최적화에서 mobilegit을 예외로 두면 나아질 수 있어요.") },
            confirmButton = { TextButton(onClick = { dialog = null; nav.go(Routes.BATTERY) }) { Text("배터리 안내") } },
            dismissButton = { TextButton(onClick = { dialog = null; filter = RepoFilter.PROBLEM }) { Text("해당 레포 보기") } },
        )
        "tailscale" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("밖에서도 접속하려면") },
            text = {
                Text("Tailscale 앱을 설치하고 연결하면 모바일 데이터에서도 접속할 수 있습니다. mobilegit은 Tailscale IP를 감지해 주소로 보여 줄 뿐 Tailscale을 제어하지 않아요.\n\n" +
                    "안드로이드는 VPN을 하나만 쓸 수 있어서 다른 VPN과 동시에 쓸 수 없어요.\n\nTailscale을 쓸 수 없는 PC라면 설정에서 외부 접속(ngrok)을 켤 수 있어요.")
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { dialog = null; nav.go(Routes.SETTINGS) }) { Text("ngrok 설정") } },
        )
    }
}

fun tunnelText(t: TunnelState, serverRunning: Boolean): String = when {
    !serverRunning -> "서버를 켜면 터널이 열려요. 인증이 강제로 켜져요."
    else -> when (t) {
        TunnelState.Off -> "터널 준비 중"
        is TunnelState.Connecting -> "연결 중 (${t.attempt}번째 시도)"
        is TunnelState.Connected -> "${t.publicUrl} · 인터넷의 누구나 접근을 시도할 수 있어요"
        is TunnelState.Retrying -> "끊김, ${com.ouor.mobilegit.ui.time(t.atMillis)}에 재연결: ${t.reason}"
        is TunnelState.TokenRejected -> "토큰 확인 필요: ${t.reason}"
    }
}

@Composable
private fun ServerCard(
    server: ServerState, addrs: List<com.ouor.mobilegit.core.ServerAddress>, port: Int, auth: Boolean, anon: Boolean,
    onToggle: (Boolean) -> Unit, onQr: () -> Unit, onPort: () -> Unit, onTailscale: () -> Unit,
) {
    val running = server is ServerState.Running
    val on = running || server is ServerState.Starting
    val (title, sub, t) = when (server) {
        is ServerState.Running -> Triple("서버 켜짐", "포트 ${server.port} · " + when { !auth -> "인증 없음"; anon -> "읽기는 익명 허용"; else -> "인증 켜짐" }, Tone.Success)
        ServerState.Starting -> Triple("켜는 중…", "포트 $port", Tone.Info)
        ServerState.Stopping -> Triple("끄는 중…", "진행 중인 전송을 마무리하고 있어요", Tone.Neutral)
        ServerState.Stopped -> Triple("서버 꺼짐", "켜면 PC에서 clone·push할 수 있어요", Tone.Neutral)
        is ServerState.Failed -> Triple("서버 꺼짐", "켤 수 없었어요", Tone.Danger)
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
            Banner(Tone.Danger, Icons.Outlined.Close, server.message, if (server.portInUse) "다른 앱이 이 포트를 쓰고 있어요. 설정에서 포트를 바꿔 보세요." else null,
                actionLabel = if (server.portInUse) "포트 바꾸기" else null, onAction = onPort)
        }
        Spacer(Modifier.height(Space.md))
        if (addrs.isEmpty()) {
            Banner(Tone.Warning, Icons.Outlined.WifiOff, "접속 가능한 네트워크 없음", "와이파이에 연결하거나, 핫스팟을 켜거나, Tailscale 앱을 설치해 연결하세요.",
                actionLabel = "Tailscale 안내", onAction = onTailscale)
        } else {
            val primary = addrs.first()
            AddressBar("${primary.kind.label()} 주소", primary.baseUrl, large = true, enabled = running, onQr = onQr)
            addrs.drop(1).forEach { a -> Spacer(Modifier.height(Space.xs)); AddressBar(a.kind.label(), a.baseUrl, enabled = running) }
            Text(
                if (running) "탭하면 복사돼요. 브라우저로 열면 웹 화면, git으로는 <주소>/<레포>.git" else "서버를 켜면 이 주소로 접속할 수 있어요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm, start = Space.xs),
            )
            if (addrs.none { it.kind == AddressKind.TAILSCALE }) {
                Text("모바일 데이터에서도 접속하려면? Tailscale 안내", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Space.xs).clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onTailscale).padding(Space.xs))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value, onChange, Modifier.fillMaxWidth().padding(top = Space.xs), singleLine = true,
        placeholder = { Text("레포 이름 검색") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "검색어 지우기") } },
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
