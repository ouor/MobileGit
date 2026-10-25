package com.ouor.mobilegit.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.BuildConfig
import com.ouor.mobilegit.app.ServerState
import com.ouor.mobilegit.core.SettingsStore
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.CommandBlock
import com.ouor.mobilegit.ui.components.ConfirmDialog
import com.ouor.mobilegit.ui.components.Divider
import com.ouor.mobilegit.ui.components.LocalSnack
import com.ouor.mobilegit.ui.components.NavRow
import com.ouor.mobilegit.ui.components.RowGroup
import com.ouor.mobilegit.ui.components.ScreenColumn
import com.ouor.mobilegit.ui.components.SectionHeader
import com.ouor.mobilegit.ui.components.SwitchRow
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone

/** A-10 설정 (F-01 포트, F-03, F-04, F-07, F-10, F-12 AC4, F-19, F-22, F-23, F-25 AC2). */
@Composable
fun SettingsScreen(nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val s by core.settings.state.collectAsStateWithLifecycle()
    val tunnel by core.tunnel.state.collectAsStateWithLifecycle()
    val server by core.server.state.collectAsStateWithLifecycle()
    val creds by core.credentials.items.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    val running = server is ServerState.Running

    AppScaffold("설정", onBack = nav::back) { padding ->
        ScreenColumn(padding) {
            SectionHeader("서버")
            RowGroup {
                NavRow("포트", { dialog = "port" }, value = "${s.port}")
                Divider()
                NavRow("사용자 이름", { dialog = "user" }, value = s.username)
                Divider()
                NavRow("비밀번호 재설정", { dialog = "password" }, subtitle = "비밀번호는 보여 주지 않고 새로 정하기만 해요")
                Divider()
                SwitchRow("인증 끄기", !s.authEnabled, { off -> if (off) dialog = "authOff" else core.settings.update { it.copy(authEnabled = true) } },
                    subtitle = if (s.ngrokEnabled) "외부 공개(ngrok) 중에는 인증이 강제돼요" else "끄면 같은 네트워크의 누구나 읽고 push할 수 있어요", enabled = !s.ngrokEnabled)
                Divider()
                SwitchRow("같은 네트워크에서 읽기는 인증 없이", s.anonymousReadOnLan, { v -> core.settings.update { it.copy(anonymousReadOnLan = v) } },
                    subtitle = if (s.ngrokEnabled) "외부 공개(ngrok) 중에는 쓸 수 없어요" else "clone·fetch와 웹 화면은 익명 허용, push는 계속 인증. 수업할 때 유용해요",
                    enabled = !s.ngrokEnabled && s.authEnabled)
                Divider()
                SwitchRow("부팅 후 서버 자동 시작", s.startOnBoot, { v -> core.settings.update { it.copy(startOnBoot = v) } }, subtitle = "재부팅 전에 서버가 켜져 있었다면 다시 켜요")
            }
            Banner(Tone.Neutral, Icons.Outlined.Shield, "연결 경로별 보안",
                "와이파이·핫스팟: 평문 HTTP라 같은 네트워크의 다른 기기가 접속 계정을 엿볼 수 있어요.\nTailscale: WireGuard로 암호화돼요.\nngrok: 인터넷 구간은 HTTPS예요. 대신 누구나 접근을 시도할 수 있어요.")

            SectionHeader("외부 접속 (ngrok)")
            RowGroup {
                SwitchRow("외부 접속", s.ngrokEnabled, { on ->
                    when {
                        !on -> core.settings.update { it.copy(ngrokEnabled = false) }
                        !s.hasNgrokToken -> { snack("먼저 authtoken을 입력하세요"); dialog = "token" }
                        s.ngrokWarningAccepted -> core.settings.update { it.copy(ngrokEnabled = true) }
                        else -> dialog = "ngrokWarn"
                    }
                }, subtitle = if (s.ngrokEnabled) tunnelText(tunnel, running) else "Tailscale을 쓸 수 없는 PC에서 인터넷으로 접속할 때만 켜세요")
                Divider()
                NavRow("authtoken", { dialog = "token" }, value = if (s.hasNgrokToken) "설정됨" else "없음", subtitle = "암호화해 저장하고 내보내기에 넣지 않아요")
            }

            SectionHeader("동기화")
            RowGroup {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Text("변경 확인 주기", style = MaterialTheme.typography.bodyLarge)
                    Text("원격 refs만 조회해 데이터가 적어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Space.sm))
                    val opts = listOf(1, 6, 12, 24)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        opts.forEachIndexed { i, h ->
                            SegmentedButton(s.checkIntervalHours == h, { core.settings.setCheckIntervalHours(h) }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text("${h}시간") }
                        }
                    }
                }
                Divider()
                SwitchRow("자동 업데이트", s.autoUpdate, { v -> core.settings.update { it.copy(autoUpdate = v) } }, subtitle = "변경이 있으면 이어서 받아요. 끄면 배지만 붙어요")
                Divider()
                SwitchRow("와이파이에서만 업데이트", s.updateWifiOnly, { v -> core.settings.update { it.copy(updateWifiOnly = v) } }, enabled = s.autoUpdate)
                Divider()
                SwitchRow("충전 중에만 업데이트", s.updateChargingOnly, { v -> core.settings.update { it.copy(updateChargingOnly = v) } }, enabled = s.autoUpdate)
                Divider()
                SwitchRow("확인도 와이파이에서만", s.checkWifiOnly, { v -> core.settings.update { it.copy(checkWifiOnly = v) } }, subtitle = "모바일 데이터에서는 확인도 미뤄요")
            }

            SectionHeader("알림")
            RowGroup {
                SwitchRow("업데이트 완료 알림", s.notifyOnSuccess, { v -> core.settings.update { it.copy(notifyOnSuccess = v) } }, subtitle = "실패·원본 없음·보존 발생 알림은 항상 보내요")
                Divider()
                NavRow("시스템 알림 설정", {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                }, icon = Icons.Outlined.Notifications, subtitle = "채널별로 끄거나 켤 수 있어요")
            }

            SectionHeader("관리")
            RowGroup {
                NavRow("원격 자격 증명", { nav.go(Routes.CREDENTIALS) }, icon = Icons.Outlined.Key, value = "${creds.size}개")
                Divider()
                NavRow("접속 기록", { nav.go(Routes.ACCESS_LOG) }, icon = Icons.Outlined.ListAlt)
                Divider()
                NavRow("저장 공간", { nav.go(Routes.STORAGE) }, icon = Icons.Outlined.Storage)
                Divider()
                NavRow("전체 백업", { nav.go(Routes.BACKUP) }, icon = Icons.Outlined.Upload)
                Divider()
                NavRow("백그라운드 안내", { nav.go(Routes.BATTERY) }, icon = Icons.Outlined.BatteryChargingFull, subtitle = "배터리 최적화, 제조사별 설정, 서랍 폰 팁")
            }

            SectionHeader("정보")
            RowGroup {
                NavRow("버전", {}, icon = Icons.Outlined.Info, value = BuildConfig.VERSION_NAME, enabled = false)
                Divider()
                NavRow("소개 다시 보기", { nav.go(Routes.ONBOARDING) }, icon = Icons.Outlined.School)
                Divider()
                NavRow("오픈소스 라이선스", { dialog = "licenses" }, icon = Icons.Outlined.Public)
            }
        }
    }

    when (dialog) {
        "port" -> InputDialog("포트", "${s.port}", "1024~65535", KeyboardType.Number, note = if (running) "서버를 껐다 켜야 새 포트가 적용돼요" else null, onDismiss = { dialog = null }) {
            core.settings.setPort(it.trim().toIntOrNull() ?: 0); snack("포트를 바꿨어요")
        }
        "user" -> InputDialog("사용자 이름", s.username, "git", onDismiss = { dialog = null }) { core.settings.setUsername(it); snack("사용자 이름을 바꿨어요") }
        "password" -> PasswordDialog(onDismiss = { dialog = null })
        "token" -> InputDialog("ngrok authtoken", "", "dashboard.ngrok.com에서 복사", password = true, note = "비워 두고 저장하면 토큰을 지워요", onDismiss = { dialog = null }) {
            core.settings.setNgrokAuthtoken(it.ifBlank { null })
            if (it.isBlank()) core.settings.update { st -> st.copy(ngrokEnabled = false) }
            snack(if (it.isBlank()) "토큰을 지웠어요" else "토큰을 저장했어요")
        }
        "authOff" -> ConfirmDialog("인증을 끌까요?", "같은 네트워크의 누구나 레포를 읽고, push로 레포를 만들거나 덮어쓸 수 있어요. 꺼진 동안 메인 화면에 경고가 보여요.", "인증 끄기",
            destructive = true, onConfirm = { core.settings.update { it.copy(authEnabled = false) }; dialog = null }, onDismiss = { dialog = null })
        "ngrokWarn" -> ConfirmDialog(
            "외부 접속을 켤까요?", "인터넷의 누구나 이 주소에 접근을 시도할 수 있습니다. 인증이 강제로 켜집니다. 모바일 데이터 사용량이 늘 수 있습니다.", "확인하고 켜기",
            onConfirm = { core.settings.update { it.copy(ngrokEnabled = true, ngrokWarningAccepted = true) }; dialog = null }, onDismiss = { dialog = null })
        "licenses" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("오픈소스 라이선스") }, text = {
            Text(LICENSES, style = MaterialTheme.typography.bodySmall)
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("닫기") } })
    }
}

private const val LICENSES = """AndroidX, Jetpack Compose, Material 3, Navigation, WorkManager: Apache-2.0
Kotlin, kotlinx.coroutines, kotlinx.serialization: Apache-2.0
Eclipse JGit: EDL-1.0 (BSD-3-Clause)
Ktor: Apache-2.0
commonmark-java: BSD-2-Clause
ZXing: Apache-2.0
ngrok-go: MIT
SLF4J: MIT
Material Symbols / Icons: Apache-2.0"""

/** Single-field dialog; [onSave] may throw (e.g. SettingsException) and the message is shown inline. */
@Composable
fun InputDialog(
    title: String, initial: String, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, note: String? = null,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(v, { v = it; err = null }, singleLine = true, placeholder = { Text(placeholder) }, isError = err != null,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard), supportingText = (err ?: note)?.let { { Text(it) } })
        }
    }, confirmButton = {
        TextButton(onClick = { try { onSave(v); onDismiss() } catch (e: Exception) { err = e.message } }) { Text("저장") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** F-03 AC2/AC3: reset only. Generated passwords are shown once with a copy action. */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit) {
    val core = core()
    var v by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    if (done != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("새 비밀번호") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("지금만 보여요. 복사해 두세요. PC의 git 자격 증명 관리자에 저장된 이전 비밀번호도 바꿔야 해요.", style = MaterialTheme.typography.bodyMedium)
                CommandBlock(done!!)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("비밀번호 재설정") }, text = {
        Column {
            OutlinedTextField(v, { v = it; err = null }, singleLine = true, label = { Text("새 비밀번호 (8자 이상)") }, isError = err != null,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), supportingText = err?.let { { Text(it) } })
            TextButton(onClick = { val p = SettingsStore.generatePassword(16); runCatching { core.settings.setPassword(p) }.onSuccess { done = p }.onFailure { err = it.message } }) {
                Text("무작위 16자로 만들기")
            }
        }
    }, confirmButton = {
        TextButton(onClick = { runCatching { core.settings.setPassword(v) }.onSuccess { onDismiss() }.onFailure { err = it.message } }) { Text("저장") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

