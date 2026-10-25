package com.ouor.mobilegit.ui.screens

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
            TextButton(onClick = ::finish) { Text(if (replay) "닫기" else "건너뛰기") }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                when (page) {
                    0 -> Page(Icons.Outlined.Inventory2, "주머니 속 두 번째 사본", "원격 레포를 미러로 보존하고, PC에서는 평소처럼 git으로 clone·push해요. 원본이 force-push하거나 사라져도 이 폰의 기록은 남아요.") {
                        Banner(Tone.Info, Icons.Outlined.CheckCircle, "폰은 추가 사본이에요", "폰이 유일한 사본이 되지 않도록 정기적으로 내보내기(백업)를 권장해요.")
                    }
                    1 -> Page(Icons.Outlined.PhonelinkLock, "레포는 이 폰 안에만 있어요", "레포는 앱 내부 저장소에 저장돼요. 클라우드 백업에도 올라가지 않아요.") {
                        Banner(Tone.Danger, Icons.Outlined.Warning, "앱을 지우면 모든 레포가 사라져요", "앱 삭제나 '앱 데이터 삭제'를 하면 레포와 이력이 모두 지워져요. 중요한 레포는 내보내기로 SD 카드나 클라우드에 따로 보관하세요.")
                    }
                    2 -> Page(Icons.Outlined.Key, "접속 계정", "PC에서 clone·push하거나 웹 화면을 열 때 이 계정을 입력해요.") {
                        SectionCard {
                            AddressBar("사용자 이름", username)
                            Spacer(Modifier.height(Space.xs))
                            if (password != null) AddressBar("비밀번호", password)
                            else Text("비밀번호는 처음 한 번만 보여 드려요. 잊었다면 설정 > 비밀번호 재설정에서 새로 정하세요.", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (password != null) Banner(Tone.Warning, Icons.Outlined.Warning, "지금 복사해 두세요", "비밀번호는 이 화면에서만 보여요. 설정에서는 다시 볼 수 없고 재설정만 할 수 있어요.")
                    }
                    else -> Page(Icons.Outlined.BatteryChargingFull, "꺼지지 않게 준비하기", "서버를 켜 두면 상단 알림이 떠요. 화면이 꺼져도 접속을 받으려면 배터리 최적화 예외가 필요해요.") {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.size(Space.md))
                                Column(Modifier.weight(1f)) {
                                    Text("알림 권한", style = MaterialTheme.typography.titleSmall)
                                    Text(if (notifOk) "허용됨" else "서버 상태와 동기화 실패를 알려 드려요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!notifOk && Build.VERSION.SDK_INT >= 33) SecondaryButton("허용") { perm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                else Icon(Icons.Outlined.CheckCircle, "허용됨", tint = MaterialTheme.colorScheme.tertiary)
                            }
                            Spacer(Modifier.height(Space.md))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.BatteryChargingFull, null); Spacer(Modifier.size(Space.md))
                                Column(Modifier.weight(1f)) {
                                    Text("배터리 최적화 예외", style = MaterialTheme.typography.titleSmall)
                                    Text(if (batteryOk) "등록됨" else "제조사별 설정도 안내해 드려요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!batteryOk) SecondaryButton("안내 보기") { nav.go(Routes.BATTERY) }
                                else Icon(Icons.Outlined.CheckCircle, "등록됨", tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = Space.screen, vertical = Space.md)) {
            val last = pager.currentPage == 3
            PrimaryButton(if (last) (if (replay) "닫기" else "시작하기") else "다음") { if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
        }
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, body: String, extra: @Composable () -> Unit) {
    Spacer(Modifier.height(Space.xl))
    Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(Space.md))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Space.sm))
    extra()
}
