package com.ouor.mobilegit.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.mobilegit.app.ExportKind
import com.ouor.mobilegit.app.StorageSummary
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.ago
import com.ouor.mobilegit.ui.bytes
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.Banner
import com.ouor.mobilegit.ui.components.CommandBlock
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.InfoRow
import com.ouor.mobilegit.ui.components.Loading
import com.ouor.mobilegit.ui.components.PrimaryButton
import com.ouor.mobilegit.ui.components.SecondaryButton
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.rememberExportRunner
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A-09 저장 공간 (F-17, F-19 진입). */
@Composable
fun StorageScreen(nav: Nav) {
    val core = core()
    val scope = rememberCoroutineScope()
    val repos by core.repos.repos.collectAsStateWithLifecycle()
    var measuring by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val summary by produceState<StorageSummary?>(null, repos, tick) { value = withContext(Dispatchers.IO) { core.storageSummary() } }

    AppScaffold("저장 공간", onBack = nav::back, actions = {
        IconButton(onClick = { measuring = true; scope.launch { core.refreshSizes(); measuring = false; tick++ } }, enabled = !measuring) { Icon(Icons.Outlined.Refresh, "용량 다시 측정") }
    }) { padding ->
        val s = summary
        if (s == null) { Box(Modifier.padding(padding)) { Loading() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    Text("mobilegit이 쓰는 공간", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bytes(s.appUsageBytes), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Space.md))
                    val used = (s.totalBytes - s.freeBytes).coerceAtLeast(0)
                    LinearProgressIndicator(progress = { if (s.totalBytes > 0) used.toFloat() / s.totalBytes else 0f }, Modifier.fillMaxWidth().height(8.dp),
                        color = if (s.belowFloor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, drawStopIndicator = {})
                    Spacer(Modifier.height(Space.sm))
                    InfoRow("기기 남은 공간", "${bytes(s.freeBytes)} / ${bytes(s.totalBytes)}", valueTone = if (s.belowFloor) Tone.Danger else null)
                    InfoRow("하한", "${bytes(s.floorBytes)} 미만이면 자동 업데이트와 새 미러 받기를 멈춰요")
                    if (measuring) Text("다시 측정하는 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (s.belowFloor) item {
                Banner(Tone.Danger, Icons.Outlined.SdCardAlert, "저장 공간이 부족해요", "자동 업데이트와 새 미러 받기를 멈췄어요. push는 받을 데이터보다 공간이 적으면 실패해요. 레포를 정리하거나 기기 공간을 확보하세요.")
            }
            item { SecondaryButton("전체 백업 (zip)", Modifier.fillMaxWidth(), icon = Icons.Outlined.Upload) { nav.go(Routes.BACKUP) } }
            item { Text("레포별 용량 (큰 순)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.md)) }
            if (s.reposBySize.isEmpty()) item { EmptyState(Icons.Outlined.Inventory2, "레포가 없어요", "레포를 추가하면 여기에 용량이 표시돼요.") }
            val max = s.reposBySize.firstOrNull()?.sizeBytes?.coerceAtLeast(1) ?: 1
            items(s.reposBySize, key = { it.id }) { m ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable { nav.go(Routes.repo(m.name)) }.padding(Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(bytes(m.sizeBytes), style = MaterialTheme.typography.titleSmall)
                        }
                        LinearProgressIndicator(progress = { m.sizeBytes.toFloat() / max }, Modifier.fillMaxWidth().padding(vertical = Space.xs), drawStopIndicator = {})
                        Text(m.sizeMeasuredAt?.let { "${ago(it)} 측정" } ?: "측정 전", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A-14 전체 백업 (F-19 AC2, AC4~AC7). */
@Composable
fun BackupScreen(nav: Nav) {
    val core = core()
    val repos by core.repos.repos.collectAsStateWithLifecycle()
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<String>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(repos) { if (!initialized && repos.isNotEmpty()) { selected.addAll(repos.map { it.name }); initialized = true } }
    val export = rememberExportRunner()
    val free = rememberFreeSpace()
    val chosen = repos.filter { it.name in selected }
    val est = chosen.sumOf { it.sizeBytes }

    AppScaffold("전체 백업", onBack = nav::back, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.md)) {
                Text("${chosen.size}개 · 예상 ${bytes(est)} 이하" + (free?.let { " · 남은 공간 ${bytes(it.first)}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.sm))
                val p = export.progress
                if (export.running) {
                    if (p != null) LinearProgressIndicator(progress = { p.first.toFloat() / p.second.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(bottom = Space.xs))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = Space.xs))
                    Text(p?.let { "${it.first} / ${it.second} 레포 처리 중" } ?: "백업 준비 중…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = Space.sm))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SecondaryButton("공유하기", Modifier.weight(1f).height(54.dp), enabled = chosen.isNotEmpty() && !export.running) { export.start(ExportKind.FULL_BACKUP, chosen.map { it.name }, toFile = false) }
                    PrimaryButton(if (export.running) "백업 중…" else "파일로 저장", Modifier.weight(1f), enabled = chosen.isNotEmpty() && !export.running) {
                        export.start(ExportKind.FULL_BACKUP, chosen.map { it.name }, toFile = true)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                Text("선택한 레포를 zip 하나로 만들어요. 레포별 .bundle(보존 ref 포함)과 manifest.json(이름, 원본, refs SHA 등)이 들어가요. 자격 증명과 비밀번호는 넣지 않아요. SD 카드나 클라우드 드라이브에 저장할 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.sm)) {
                    Text("레포 ${repos.size}개", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val all = repos.isNotEmpty() && selected.size == repos.size
                    TextButton(onClick = { selected.clear(); if (!all) selected.addAll(repos.map { it.name }) }) { Text(if (all) "선택 해제" else "전체 선택") }
                }
            }
            if (repos.isEmpty()) item { EmptyState(Icons.Outlined.Inventory2, "백업할 레포가 없어요", "레포를 추가하면 백업할 수 있어요.") }
            items(repos, key = { it.id }) { m ->
                val on = m.name in selected
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.clickable { if (on) selected.remove(m.name) else selected.add(m.name) }.padding(end = Space.md), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null, Modifier.padding(Space.md))
                        Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(Space.sm))
                        Text(bytes(m.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "restore") {
                SectionCard(Modifier.padding(top = Space.md), title = "복원 방법") {
                    Text("앱 안에서 가져오기는 없어요. zip을 풀고 PC에서:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Space.sm))
                    val base = addrs.firstOrNull()?.baseUrl ?: "http://<폰 주소>:${core.settings.current.port}"
                    CommandBlock("git clone <이름>.bundle <이름>"); Spacer(Modifier.height(Space.xs))
                    CommandBlock("cd <이름>"); Spacer(Modifier.height(Space.xs))
                    CommandBlock("git push $base/<이름>.git --all"); Spacer(Modifier.height(Space.xs))
                    CommandBlock("git push $base/<이름>.git --tags")
                    Text("이 폰에 push하면 레포가 새로 만들어져요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
                }
            }
        }
    }
}

