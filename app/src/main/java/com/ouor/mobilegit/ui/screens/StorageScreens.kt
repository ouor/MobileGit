package com.ouor.mobilegit.ui.screens

import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
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
    var floorDialog by remember { mutableStateOf(false) }
    val summary by produceState<StorageSummary?>(null, repos, tick) { value = withContext(Dispatchers.IO) { core.storageSummary() } }

    AppScaffold(str(R.string.main_007), onBack = nav::back, actions = {
        IconButton(onClick = { measuring = true; scope.launch { core.refreshSizes(); measuring = false; tick++ } }, enabled = !measuring) { Icon(Icons.Outlined.Refresh, str(R.string.storage_001)) }
    }) { padding ->
        val s = summary
        if (s == null) { Box(Modifier.padding(padding)) { Loading() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                SectionCard {
                    Text(str(R.string.storage_002), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bytes(s.appUsageBytes), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Space.md))
                    val used = (s.totalBytes - s.freeBytes).coerceAtLeast(0)
                    LinearProgressIndicator(progress = { if (s.totalBytes > 0) used.toFloat() / s.totalBytes else 0f }, Modifier.fillMaxWidth().height(8.dp),
                        color = if (s.belowFloor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, drawStopIndicator = {})
                    Spacer(Modifier.height(Space.sm))
                    InfoRow(str(R.string.storage_003), "${bytes(s.freeBytes)} / ${bytes(s.totalBytes)}", valueTone = if (s.belowFloor) Tone.Danger else null)
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = str(R.string.storage_floor_change)) { floorDialog = true }, verticalAlignment = Alignment.CenterVertically) {
                        InfoRow(str(R.string.storage_004), bytes(s.floorBytes), Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                    Text(str(R.string.storage_005, bytes(s.floorBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.reposBySize.mapNotNull { it.sizeMeasuredAt }.minOrNull()?.let {
                        Text(str(R.string.storage_measured_once, ago(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                    }
                    if (measuring) Text(str(R.string.storage_006), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (s.belowFloor) item {
                Banner(Tone.Danger, Icons.Outlined.SdCardAlert, str(R.string.main_013), str(R.string.storage_007))
            }
            item { SecondaryButton(str(R.string.storage_008), Modifier.fillMaxWidth(), icon = Icons.Outlined.Upload) { nav.go(Routes.BACKUP) } }
            item { Text(str(R.string.storage_009), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.md)) }
            if (s.reposBySize.isEmpty()) item { EmptyState(Icons.Outlined.Inventory2, str(R.string.storage_010), str(R.string.storage_011)) }
            val max = s.reposBySize.firstOrNull()?.sizeBytes?.coerceAtLeast(1) ?: 1
            items(s.reposBySize, key = { it.id }) { m ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable { nav.go(Routes.repo(m.name)) }.padding(Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(if (m.sizeMeasuredAt != null) bytes(m.sizeBytes) else str(R.string.detail_012), style = MaterialTheme.typography.titleSmall)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                        }
                        LinearProgressIndicator(progress = { m.sizeBytes.toFloat() / max }, Modifier.fillMaxWidth().padding(top = Space.xs), drawStopIndicator = {})
                    }
                }
            }
        }
    }
    if (floorDialog) AlertDialog(
        onDismissRequest = { floorDialog = false },
        title = { Text(str(R.string.storage_floor_change)) },
        text = {
            Column {
                Text(str(R.string.storage_floor_body), style = MaterialTheme.typography.bodyMedium)
                listOf(512L, 1024L, 2048L, 5120L).map { it * 1024 * 1024 }.forEach { v ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.RadioButton) { core.settings.update { it.copy(storageFloorBytes = v) }; floorDialog = false; tick++ },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = core.settings.current.storageFloorBytes == v, onClick = null, modifier = Modifier.padding(Space.sm))
                        Text(bytes(v), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { floorDialog = false }) { Text(str(R.string.common_005)) } },
    )
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

    AppScaffold(str(R.string.main_008), onBack = nav::back, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.md)) {
                Text(str(R.string.storage_012, chosen.size, bytes(est)) + (free?.let { str(R.string.add_051, bytes(it.first)) } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.sm))
                val p = export.progress
                if (export.running) {
                    if (p != null) LinearProgressIndicator(progress = { p.first.toFloat() / p.second.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(bottom = Space.xs))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = Space.xs))
                    Text(p?.let { str(R.string.storage_013, it.first, it.second) } ?: str(R.string.storage_014), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = Space.sm))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SecondaryButton(str(R.string.detail_090), Modifier.weight(1f).height(54.dp), enabled = chosen.isNotEmpty() && !export.running) { export.start(ExportKind.FULL_BACKUP, chosen.map { it.name }, toFile = false) }
                    PrimaryButton(if (export.running) str(R.string.storage_015) else str(R.string.detail_091), Modifier.weight(1f), enabled = chosen.isNotEmpty() && !export.running) {
                        export.start(ExportKind.FULL_BACKUP, chosen.map { it.name }, toFile = true)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item {
                Text(str(R.string.storage_016),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.sm)) {
                    Text(str(R.string.storage_017, repos.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val all = repos.isNotEmpty() && selected.size == repos.size
                    TextButton(onClick = { selected.clear(); if (!all) selected.addAll(repos.map { it.name }) }) { Text(if (all) str(R.string.add_063) else str(R.string.add_064)) }
                }
            }
            if (repos.isEmpty()) item { EmptyState(Icons.Outlined.Inventory2, str(R.string.storage_018), str(R.string.storage_019)) }
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
                SectionCard(Modifier.padding(top = Space.md), title = str(R.string.storage_020)) {
                    Text(str(R.string.storage_021), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Space.sm))
                    val base = addrs.firstOrNull()?.baseUrl ?: str(R.string.main_031, core.settings.current.port)
                    CommandBlock(str(R.string.storage_022)); Spacer(Modifier.height(Space.xs))
                    CommandBlock(str(R.string.storage_023)); Spacer(Modifier.height(Space.xs))
                    CommandBlock(str(R.string.storage_024, base)); Spacer(Modifier.height(Space.xs))
                    CommandBlock(str(R.string.storage_025, base))
                    Text(str(R.string.storage_026), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm))
                }
            }
        }
    }
}

