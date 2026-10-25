package com.ouor.mobilegit.ui.screens

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

    AppScaffold("원격 자격 증명", onBack = nav::back, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("토큰 추가") }, shape = MaterialTheme.shapes.large)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.sm, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                Text("비공개 레포를 미러할 때 쓰는 토큰이에요. Android Keystore로 암호화해 저장하고, 화면에는 앞 4자리만 보여요. 백업에는 넣지 않아요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.isEmpty()) item { EmptyState(Icons.Outlined.Key, "저장된 토큰이 없어요", "공개 레포는 토큰 없이도 미러할 수 있어요.") }
            items(items, key = { it.id }) { c ->
                val used = core.credentials.usageCount(c.id, repos)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = Space.lg, top = Space.md, bottom = Space.md, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label.ifBlank { c.host }, style = MaterialTheme.typography.titleSmall)
                            Text("${c.host} · ${c.username ?: "${CredentialStore.defaultUser(c.host)} (기본)"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Pill("${c.tokenPrefix}••••", Tone.Neutral)
                                Pill(if (used > 0) "미러 ${used}개에서 사용" else "사용 안 함", if (used > 0) Tone.Info else Tone.Neutral)
                            }
                        }
                        IconButton(onClick = { editing = c }) { Icon(Icons.Outlined.Edit, "${c.host} 편집") }
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Outlined.DeleteOutline, "${c.host} 삭제") }
                    }
                }
            }
        }
    }
    if (adding || editing != null) CredentialDialog(editing, prefillHost.takeIf { editing == null }, onDismiss = { adding = false; editing = null })
    deleting?.let { c ->
        val used = core.credentials.usageCount(c.id, repos)
        ConfirmDialog("토큰 삭제", if (used > 0) "이 토큰을 쓰는 미러가 ${used}개 있어요. 지우면 비공개 원본은 '인증 필요'가 될 수 있어요." else "이 토큰을 지울까요?", "삭제", destructive = true,
            onConfirm = { core.credentials.delete(c.id); deleting = null; snack("토큰을 지웠어요") }, onDismiss = { deleting = null })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "토큰 추가" else "토큰 편집") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("호스트") }, placeholder = { Text("github.com") }, enabled = existing == null)
            OutlinedTextField(token, { token = it; err = null }, singleLine = true, label = { Text(if (existing == null) "토큰" else "새 토큰 (비우면 유지)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("사용자 이름 (선택)") },
                placeholder = { Text(if (host.isBlank()) "비우면 호스트 기본값" else CredentialStore.defaultUser(CredentialStore.normalizeHost(host))) })
            OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("메모 (선택)") }, placeholder = { Text("예: 회사 GitLab") })
            Text("읽기 권한만 있는 토큰을 권장해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = ok, onClick = {
            runCatching {
                if (existing == null) core.credentials.add(host, token, user, label.trim())
                else core.credentials.update(existing.id, token.ifBlank { null }, user, label.trim())
            }.onSuccess { snack("저장했어요"); onDismiss() }.onFailure { err = it.message }
        }) { Text("저장") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

// ---------------- A-12 백그라운드 안내 ----------------

private val makerGuides = listOf(
    BatteryHelper.Maker.SAMSUNG to ("삼성" to "설정 > 배터리 > 백그라운드 사용 한도에서 '사용하지 않는 앱을 절전 상태로 전환'을 끄고, mobilegit을 '절전 예외 앱'에 추가하세요."),
    BatteryHelper.Maker.XIAOMI to ("샤오미" to "설정 > 앱 > 앱 관리 > mobilegit에서 '자동 시작'을 켜고, 배터리 세이버를 '제한 없음'으로 바꾸세요. 최근 앱 화면에서 mobilegit을 길게 눌러 잠그면 정리되지 않아요."),
    BatteryHelper.Maker.ONEPLUS to ("원플러스" to "설정 > 배터리 > 배터리 최적화에서 mobilegit을 '최적화 안 함'으로 두고, '고급 최적화(딥 최적화)'를 끄세요. 최근 앱에서 잠금도 해 두세요."),
    BatteryHelper.Maker.GOOGLE to ("구글 픽셀" to "설정 > 앱 > mobilegit > 앱 배터리 사용량을 '제한 없음'으로 바꾸세요. 보통 이것만으로 충분해요."),
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

    AppScaffold("백그라운드 안내", onBack = nav::back) { padding ->
        ScreenColumn(padding) {
            if (ignoring) Banner(Tone.Success, Icons.Outlined.CheckCircle, "배터리 최적화 예외로 등록됐어요", "화면이 꺼져도 서버와 자동 확인이 잘 유지돼요. 아래 제조사 설정도 확인해 보세요.")
            else Banner(Tone.Warning, Icons.Outlined.BatteryAlert, "배터리 최적화가 켜져 있어요", "화면이 꺼지면 서버가 멈추거나 자동 확인이 늦어질 수 있어요.")
            if (!ignoring) PrimaryButton("최적화 예외 요청") { runCatching { ctx.startActivity(BatteryHelper.requestIntent(ctx)) }.onFailure { ctx.startActivity(BatteryHelper.settingsIntent()) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SecondaryButton("배터리 최적화 목록", Modifier.weight(1f)) { ctx.startActivity(BatteryHelper.settingsIntent()) }
                SecondaryButton("앱 정보", Modifier.weight(1f)) { ctx.startActivity(BatteryHelper.appDetailsIntent(ctx)) }
            }

            SectionHeader("제조사별 추가 설정")
            ordered.forEach { (m, g) ->
                SectionCard(title = g.first + if (m == maker) " (이 기기)" else "") { Text(g.second, style = MaterialTheme.typography.bodyMedium) }
            }
            if (maker == BatteryHelper.Maker.OTHER) SectionCard(title = "그 밖의 기기") {
                Text("dontkillmyapp.com에서 기기별 방법을 찾아보세요. 대부분 '자동 시작 허용'과 '배터리 제한 없음'이 필요해요.", style = MaterialTheme.typography.bodyMedium)
            }

            SectionHeader("서랍 폰 운영 팁")
            SectionCard {
                listOf(
                    "충전기에 꽂아 두세요. 자동 업데이트를 '충전 중에만'으로 두면 배터리 부담이 없어요.",
                    "오래 꽂아 둘 거라면 배터리 보호(충전 상한 80% 등) 기능을 켜세요.",
                    "재부팅해도 서버가 켜지도록 아래 '부팅 후 서버 자동 시작'을 켜세요.",
                    "같은 와이파이인데 접속이 안 되면 공유기가 기기 간 통신을 막는 것일 수 있어요(AP 격리). 폰 핫스팟을 쓰세요.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Space.xs)) }
            }
            RowGroup { SwitchRow("부팅 후 서버 자동 시작", s.startOnBoot, { v -> core.settings.update { it.copy(startOnBoot = v) } }) }
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

    AppScaffold("접속 기록", onBack = nav::back, actions = { OverflowMenu { item("기록 지우기", enabled = entries.isNotEmpty()) { confirm = true } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(bottom = Space.sm)) {
                    items(listOf("전체 ${entries.size}", "ngrok 경로", "인증 실패").withIndex().toList()) { (i, l) -> FilterChip(filter == i, { filter = i }, { Text(l) }) }
                }
                Text("최근 2,000건을 보관해요. 비밀번호와 인증 헤더는 기록하지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.sm))
            }
            if (shown.isEmpty()) item { EmptyState(Icons.Outlined.ListAlt, if (entries.isEmpty()) "아직 접속 기록이 없어요" else "조건에 맞는 기록이 없어요", "서버가 받은 clone·push·웹 요청이 여기에 남아요.") }
            items(shown, key = { "${it.at}-${it.clientIp}-${it.status}-${it.repoName}-${it.action}" }) { e -> AccessRow(e) }
        }
    }
    if (confirm) ConfirmDialog("접속 기록 지우기", "모든 접속 기록을 지울까요?", "지우기", destructive = true, onConfirm = { core.accessLog.clear(); confirm = false }, onDismiss = { confirm = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessRow(e: com.ouor.mobilegit.core.AccessLogEntry) {
    val (al, at) = e.authResult.style()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm + Space.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (e.action) { AccessAction.READ -> "clone·fetch"; AccessAction.PUSH -> "push"; AccessAction.WEB -> "웹" } + (e.repoName?.let { " · $it" } ?: ""),
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

