package com.ouor.mobilegit.ui.screens

import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ouor.mobilegit.app.BatteryHelper
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.components.AddressBar
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.PrimaryButton
import com.ouor.mobilegit.ui.components.SecondaryButton
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import kotlinx.coroutines.launch

/** A-01 온보딩 (F-25, F-03 AC2/AC3, F-22 AC5). Also reachable from 설정 > 소개 다시 보기 (F-25 AC2). */
@Composable
fun OnboardingScreen(nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val replay = remember { core.settings.current.onboardingCompleted }
    val password = remember { core.settings.initialPassword() }
    val username = core.settings.current.username
    val pager = rememberPagerState { 4 }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val notifOk = remember(tick) { hasNotificationPermission(ctx) }
    val batteryOk = remember(tick) { BatteryHelper.isIgnoringOptimizations(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun finish() { core.settings.completeOnboarding(); if (replay) nav.back() else nav.home() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = Space.md), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i -> Box(Modifier.size(if (i == pager.currentPage) 20.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) }
            }
            TextButton(onClick = ::finish) { Text(if (replay) str(R.string.common_close) else str(R.string.onboarding_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                when (page) {
                    0 -> Page(null, str(R.string.onboarding_intro_title), str(R.string.onboarding_intro_body)) {
                        Banner(Tone.Info, Icons.Outlined.CheckCircle, str(R.string.onboarding_extra_copy_title), str(R.string.onboarding_extra_copy_body))
                    }
                    1 -> Page(Icons.Outlined.PhonelinkLock, str(R.string.onboarding_on_device_title), str(R.string.onboarding_on_device_body)) {
                        Banner(Tone.Danger, Icons.Outlined.Warning, str(R.string.onboarding_uninstall_title), str(R.string.onboarding_uninstall_body))
                    }
                    2 -> Page(Icons.Outlined.Key, str(R.string.onboarding_account_title), str(R.string.onboarding_account_body)) {
                        SectionCard {
                            AddressBar(str(R.string.bulk_user_label), username)
                            Spacer(Modifier.height(Space.xs))
                            if (password != null) AddressBar(str(R.string.onboarding_password), password)
                            else Text(str(R.string.onboarding_password_once), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (password != null) Banner(Tone.Warning, Icons.Outlined.Warning, str(R.string.onboarding_copy_now_title), str(R.string.onboarding_copy_now_body))
                    }
                    else -> Page(Icons.Outlined.BatteryChargingFull, str(R.string.onboarding_keep_alive_title), str(R.string.onboarding_keep_alive_body)) {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.size(Space.md))
                                Column(Modifier.weight(1f)) {
                                    Text(str(R.string.onboarding_notif_permission), style = MaterialTheme.typography.titleSmall)
                                    Text(if (notifOk) str(R.string.onboarding_granted) else str(R.string.onboarding_notif_permission_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!notifOk && Build.VERSION.SDK_INT >= 33) SecondaryButton(str(R.string.onboarding_allow)) { perm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                else Icon(Icons.Outlined.CheckCircle, str(R.string.onboarding_granted), tint = MaterialTheme.colorScheme.tertiary)
                            }
                            Spacer(Modifier.height(Space.md))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.BatteryChargingFull, null); Spacer(Modifier.size(Space.md))
                                Column(Modifier.weight(1f)) {
                                    Text(str(R.string.onboarding_battery_exempt), style = MaterialTheme.typography.titleSmall)
                                    Text(if (batteryOk) str(R.string.bulk_registered) else str(R.string.onboarding_battery_exempt_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!batteryOk) SecondaryButton(str(R.string.onboarding_view_guide)) { nav.go(Routes.BATTERY) }
                                else Icon(Icons.Outlined.CheckCircle, str(R.string.bulk_registered), tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = Space.screen, vertical = Space.md)) {
            val last = pager.currentPage == 3
            PrimaryButton(if (last) (if (replay) str(R.string.common_close) else str(R.string.onboarding_start)) else str(R.string.onboarding_next)) { if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
        }
    }
}

@Composable
/** [icon] null = the app logo (first page). */
private fun Page(icon: ImageVector?, title: String, body: String, extra: @Composable () -> Unit) {
    Spacer(Modifier.height(Space.xl))
    if (icon == null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), "mobilegit", Modifier.size(72.dp))
    else Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(Space.md))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Space.sm))
    extra()
}
