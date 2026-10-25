package com.ouor.mobilegit.ui.screens

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.NotificationsOff
import com.ouor.mobilegit.core.TunnelState
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import com.ouor.mobilegit.ui.components.AdvancedSection
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.theme.tone
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
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
    var resumeTick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val notifOk = remember(resumeTick) { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingLevel by remember { mutableIntStateOf(0) }
    val running = server is ServerState.Running
    fun setAuthLevel(l: Int) = core.settings.update { it.copy(authEnabled = l != 2, anonymousReadOnLan = l == 1) }
    fun turnNgrokOn() = when {
        !s.hasNgrokToken -> { snack(str(R.string.settings_ngrok_need_token)); dialog = "token" }
        authLevel(s) != 0 -> dialog = "riskFromNgrok" // R2-06 AC1
        s.ngrokWarningAccepted -> core.settings.update { it.copy(ngrokEnabled = true) }
        else -> dialog = "ngrokWarn"
    }

    AppScaffold(str(R.string.main_settings), onBack = nav::back) { padding ->
        ScreenColumn(padding) {
            SectionHeader(str(R.string.settings_server))
            RowGroup {
                NavRow(str(R.string.settings_port), { dialog = "port" }, value = "${s.port}")
                Divider()
                NavRow(str(R.string.bulk_user_label), { dialog = "user" }, value = s.username)
                Divider()
                NavRow(str(R.string.settings_reset_password), { dialog = "password" }, subtitle = str(R.string.settings_reset_password_sub))
                Divider()
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Text(str(R.string.settings_auth), style = MaterialTheme.typography.bodyLarge)
                    val level = authLevel(s)
                    Text(listOf(str(R.string.settings_auth_all_sub), str(R.string.settings_auth_push_sub), str(R.string.settings_auth_off_sub))[level],
                        style = MaterialTheme.typography.bodySmall, color = if (level == 2) tone(Tone.Danger).content else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.exposedWithoutPassword) ExposedMark(running && tunnel is TunnelState.Connected)
                    Spacer(Modifier.height(Space.sm))
                    val opts = listOf(str(R.string.settings_auth_all), str(R.string.settings_auth_push), str(R.string.settings_auth_off))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        opts.forEachIndexed { i, label ->
                            SegmentedButton(level == i, {
                                when {
                                    i == level -> Unit
                                    i == 0 -> setAuthLevel(0)
                                    s.ngrokEnabled -> { pendingLevel = i; dialog = "riskFromAuth" } // R2-06 AC2
                                    i == 1 -> setAuthLevel(1)
                                    else -> dialog = "authOff"
                                }
                            }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label, maxLines = 1) }
                        }
                    }
                }
                Divider()
                SwitchRow(str(R.string.settings_start_on_boot), s.startOnBoot, { v -> core.settings.update { it.copy(startOnBoot = v) } }, subtitle = str(R.string.settings_start_on_boot_sub))
            }
            SectionCard(title = str(R.string.settings_security_title)) {
                listOf(
                    Triple(Tone.Warning, str(R.string.settings_sec_wifi), str(R.string.settings_sec_wifi_body)),
                    Triple(Tone.Success, "Tailscale", str(R.string.settings_sec_ts_body)),
                    Triple(Tone.Danger, "ngrok", str(R.string.settings_sec_ngrok_body)),
                ).forEach { (t, name, body) ->
                    Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(tone(t).content))
                        Spacer(Modifier.width(Space.md))
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp))
                        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }

            SectionCard(title = str(R.string.settings_lost_title)) {
                listOf(R.string.settings_lost_storage, R.string.settings_lost_no_lock, R.string.settings_lost_tokens, R.string.settings_lost_revoke).forEach {
                    Text("• " + str(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Space.xxs))
                }
            }

            SectionHeader(str(R.string.settings_sync))
            RowGroup {
                NavRow(str(R.string.settings_sync_summary, s.checkIntervalHours, autoUpdateText(s)), { dialog = "interval" }, icon = Icons.Outlined.Schedule,
                    subtitle = str(R.string.settings_check_interval_sub))
                Divider()
                SwitchRow(str(R.string.settings_auto_update), s.autoUpdate, { v -> core.settings.update { it.copy(autoUpdate = v) } }, subtitle = str(R.string.settings_auto_update_sub))
                Divider()
                SwitchRow(str(R.string.settings_wifi_only), s.updateWifiOnly, { v -> core.settings.update { it.copy(updateWifiOnly = v) } }, enabled = s.autoUpdate)
                Divider()
                SwitchRow(str(R.string.settings_charging_only), s.updateChargingOnly, { v -> core.settings.update { it.copy(updateChargingOnly = v) } }, enabled = s.autoUpdate)
                Divider()
                SwitchRow(str(R.string.settings_check_wifi_only), s.checkWifiOnly, { v -> core.settings.update { it.copy(checkWifiOnly = v) } }, subtitle = str(R.string.settings_check_wifi_only_sub))
            }
            AdvancedSection {
                RowGroup {
                    SwitchRow(str(R.string.settings_pull_refs), s.mirrorPullRefs, { v -> if (v) core.settings.update { it.copy(mirrorPullRefs = true) } else dialog = "pullOff" },
                        subtitle = str(R.string.settings_pull_refs_sub))
                }
            }

            SectionHeader(str(R.string.settings_notifications))
            if (!notifOk) Banner(Tone.Warning, Icons.Outlined.NotificationsOff, str(R.string.notif_off_title), str(R.string.notif_off_body),
                actionLabel = str(R.string.notif_off_action), onAction = { openNotificationSettings(ctx) })
            SectionCard(title = str(R.string.settings_notify_list_title)) {
                listOf(R.string.settings_notify_failed, R.string.settings_notify_origin_gone, R.string.settings_notify_token, R.string.settings_notify_history, R.string.settings_notify_recovered).forEach {
                    Text("• " + str(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Space.xxs))
                }
            }
            RowGroup {
                SwitchRow(str(R.string.settings_notify_success), s.notifyOnSuccess, { v -> core.settings.update { it.copy(notifyOnSuccess = v) } }, subtitle = str(R.string.settings_notify_success_sub))
                Divider()
                NavRow(str(R.string.settings_system_notifications), {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                }, icon = Icons.Outlined.Notifications, subtitle = str(R.string.settings_system_notifications_sub))
            }

            SectionHeader(str(R.string.detail_manage))
            RowGroup {
                NavRow(str(R.string.cred_picker_label), { nav.go(Routes.CREDENTIALS) }, icon = Icons.Outlined.Key, value = str(R.string.settings_count, creds.size))
                Divider()
                NavRow(str(R.string.main_access_log), { nav.go(Routes.ACCESS_LOG) }, icon = Icons.Outlined.ListAlt)
                Divider()
                NavRow(str(R.string.main_storage), { nav.go(Routes.STORAGE) }, icon = Icons.Outlined.Storage)
                Divider()
                NavRow(str(R.string.main_full_backup), { nav.go(Routes.BACKUP) }, icon = Icons.Outlined.Upload)
                Divider()
                NavRow(str(R.string.battery_title), { nav.go(Routes.BATTERY) }, icon = Icons.Outlined.BatteryChargingFull, subtitle = str(R.string.settings_background_sub))
            }

            SectionHeader(str(R.string.detail_tab_info))
            RowGroup {
                NavRow(str(R.string.settings_version), {}, icon = Icons.Outlined.Info, value = BuildConfig.VERSION_NAME, enabled = false)
                Divider()
                NavRow(str(R.string.settings_replay_intro), { nav.go(Routes.ONBOARDING) }, icon = Icons.Outlined.School)
                Divider()
                NavRow(str(R.string.settings_licenses), { dialog = "licenses" }, icon = Icons.Outlined.Public)
            }
            Text(str(R.string.unsupported_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.sm))

            AdvancedSection(title = str(R.string.settings_ngrok_title) + " · " + ngrokStatus(s.ngrokEnabled, tunnel, running)) {
                if (s.exposedWithoutPassword) ExposedMark(running && tunnel is TunnelState.Connected)
                RowGroup {
                    SwitchRow(str(R.string.settings_ngrok_switch), s.ngrokEnabled, { on -> if (on) turnNgrokOn() else core.settings.update { it.copy(ngrokEnabled = false) } },
                        subtitle = if (s.ngrokEnabled) tunnelText(tunnel, running) else str(R.string.settings_ngrok_sub))
                    Divider()
                    NavRow("authtoken", { dialog = "token" }, value = if (s.hasNgrokToken) str(R.string.settings_ngrok_token_set) else str(R.string.fmt_none), subtitle = str(R.string.settings_ngrok_token_note))
                }
            }
        }
    }

    when (dialog) {
        "port" -> InputDialog(str(R.string.settings_port), "${s.port}", "1024~65535", KeyboardType.Number, note = if (running) str(R.string.settings_port_restart_note) else null, onDismiss = { dialog = null }) {
            val p = it.trim().toIntOrNull() ?: 0
            if (p != s.port) { core.settings.setPort(p); snack(if (running) str(R.string.settings_port_changed_restart) else str(R.string.settings_port_changed)) }
        }
        "user" -> InputDialog(str(R.string.bulk_user_label), s.username, "git", onDismiss = { dialog = null }) { core.settings.setUsername(it); snack(str(R.string.settings_username_changed)) }
        "password" -> PasswordDialog(onDismiss = { dialog = null })
        "token" -> InputDialog("ngrok authtoken", "", str(R.string.settings_ngrok_token_hint), password = true, note = str(R.string.settings_ngrok_token_clear_hint), onDismiss = { dialog = null }) {
            core.settings.setNgrokAuthtoken(it.ifBlank { null })
            if (it.isBlank()) core.settings.update { st -> st.copy(ngrokEnabled = false) }
            snack(if (it.isBlank()) str(R.string.cred_deleted) else str(R.string.settings_ngrok_token_saved))
        }
        "interval" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(str(R.string.settings_check_interval)) }, text = {
            Column {
                listOf(1, 6, 12, 24).forEach { h ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { core.settings.setCheckIntervalHours(h); dialog = null },
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(s.checkIntervalHours == h, null, Modifier.padding(Space.sm)); Text(str(R.string.settings_every_hours, h))
                    }
                }
                Text(str(R.string.settings_auto_update_where), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(str(R.string.common_close)) } })
        "riskFromNgrok" -> RiskDialog(level = authLevel(s), safeLabel = str(R.string.risk_safe_auth_on), onDismiss = { dialog = null },
            onSafe = { core.settings.update { it.copy(authEnabled = true, anonymousReadOnLan = false, ngrokEnabled = true, ngrokWarningAccepted = true) }; dialog = null },
            onProceed = { core.settings.update { it.copy(ngrokEnabled = true, ngrokWarningAccepted = true) }; dialog = null })
        "riskFromAuth" -> RiskDialog(level = pendingLevel, safeLabel = str(R.string.risk_safe_ngrok_off), onDismiss = { dialog = null },
            onSafe = { val l = pendingLevel; core.settings.update { it.copy(ngrokEnabled = false, authEnabled = l != 2, anonymousReadOnLan = l == 1) }; dialog = null },
            onProceed = { setAuthLevel(pendingLevel); dialog = null })
        "authOff" -> ConfirmDialog(str(R.string.settings_auth_off_title), str(R.string.settings_auth_off_body), str(R.string.settings_auth_off_confirm),
            destructive = true, onConfirm = { core.settings.update { it.copy(authEnabled = false) }; dialog = null }, onDismiss = { dialog = null })
        "ngrokWarn" -> ConfirmDialog(
            str(R.string.settings_ngrok_on_title), str(R.string.settings_ngrok_on_body), str(R.string.settings_ngrok_on_confirm),
            onConfirm = { core.settings.update { it.copy(ngrokEnabled = true, ngrokWarningAccepted = true) }; dialog = null }, onDismiss = { dialog = null })
        "pullOff" -> ConfirmDialog(str(R.string.settings_pull_refs_off_title), PULL_OFF_WARNING, str(R.string.settings_turn_off), destructive = true,
            onConfirm = { core.settings.update { it.copy(mirrorPullRefs = false) }; dialog = null }, onDismiss = { dialog = null })
        "licenses" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(str(R.string.settings_licenses)) }, text = {
            Text(LICENSES, style = MaterialTheme.typography.bodySmall)
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(str(R.string.common_close)) } })
    }
}

private fun authLevel(s: com.ouor.mobilegit.core.Settings) = when { !s.authEnabled -> 2; s.anonymousReadOnLan -> 1; else -> 0 }

private fun autoUpdateText(s: com.ouor.mobilegit.core.Settings) = when {
    !s.autoUpdate -> str(R.string.settings_auto_off)
    s.updateWifiOnly && s.updateChargingOnly -> str(R.string.settings_auto_wifi_charging)
    s.updateWifiOnly -> str(R.string.settings_auto_wifi)
    s.updateChargingOnly -> str(R.string.settings_auto_charging)
    else -> str(R.string.settings_auto_always)
}

private fun ngrokStatus(on: Boolean, t: TunnelState, running: Boolean) = when {
    !on -> str(R.string.settings_ngrok_status_off)
    running && (t is TunnelState.TokenRejected || t is TunnelState.Retrying) -> str(R.string.settings_ngrok_status_failed)
    else -> str(R.string.settings_ngrok_status_on)
}

/** R2-06 AC3: shown next to both settings while the combination is saved. QA2-06: "공개 중" only while the tunnel is up. */
@Composable
private fun ExposedMark(live: Boolean) {
    Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LockOpen, null, Modifier.size(16.dp), tint = tone(Tone.Danger).content); Spacer(Modifier.width(Space.xs))
        Text(str(if (live) R.string.main_exposed_no_password else R.string.main_exposed_pending_body), style = MaterialTheme.typography.labelLarge, color = tone(Tone.Danger).content)
    }
}

/**
 * R2-06: password-less + ngrok. Cancel is the default (filled), the safe alternative is next, "그래도 진행" last and
 * in the danger color. Only that last button saves the risky combination.
 */
@Composable
private fun RiskDialog(level: Int, safeLabel: String, onDismiss: () -> Unit, onSafe: () -> Unit, onProceed: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.LockOpen, null, tint = tone(Tone.Danger).content) },
        title = { Text(str(R.string.risk_title)) },
        text = { Text(str(if (level == 2) R.string.risk_body_off else R.string.risk_body_push_only)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                androidx.compose.material3.Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(str(R.string.common_cancel)) }
                androidx.compose.material3.OutlinedButton(onClick = onSafe, Modifier.fillMaxWidth()) { Text(safeLabel) }
                TextButton(onClick = onProceed, Modifier.fillMaxWidth()) { Text(str(R.string.risk_proceed), color = tone(Tone.Danger).content) }
            }
        })
}

/** CR-2: turning PR refs off deletes already-fetched ones on the next update, without archiving. */
val PULL_OFF_WARNING: String get() = str(R.string.settings_pull_refs_off_body)

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
        TextButton(onClick = { try { onSave(v); onDismiss() } catch (e: Exception) { err = e.message } }) { Text(str(R.string.common_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.common_cancel)) } })
}

/** F-03 AC2/AC3: reset only. Generated passwords are shown once with a copy action. */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit) {
    val core = core()
    var v by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    if (done != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(str(R.string.settings_new_password_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(str(R.string.settings_new_password_body), style = MaterialTheme.typography.bodyMedium)
                CommandBlock(done!!)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.main_check)) } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(str(R.string.settings_reset_password)) }, text = {
        Column {
            OutlinedTextField(v, { v = it; err = null }, singleLine = true, label = { Text(str(R.string.settings_new_password_label)) }, isError = err != null,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), supportingText = err?.let { { Text(it) } })
            TextButton(onClick = { val p = SettingsStore.generatePassword(16); runCatching { core.settings.setPassword(p) }.onSuccess { done = p }.onFailure { err = it.message } }) {
                Text(str(R.string.settings_new_password_random))
            }
        }
    }, confirmButton = {
        TextButton(onClick = { runCatching { core.settings.setPassword(v) }.onSuccess { onDismiss() }.onFailure { err = it.message } }) { Text(str(R.string.common_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.common_cancel)) } })
}

