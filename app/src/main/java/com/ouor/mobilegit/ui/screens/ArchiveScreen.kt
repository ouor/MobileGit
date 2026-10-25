package com.ouor.mobilegit.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.ouor.mobilegit.R
import com.ouor.mobilegit.app.ServerState
import com.ouor.mobilegit.core.ArchiveEntryDto
import com.ouor.mobilegit.ui.Nav
import com.ouor.mobilegit.ui.ago
import com.ouor.mobilegit.ui.components.AppScaffold
import com.ouor.mobilegit.ui.components.CommandBlock
import com.ouor.mobilegit.ui.components.EmptyState
import com.ouor.mobilegit.ui.components.ErrorState
import com.ouor.mobilegit.ui.components.Loading
import com.ouor.mobilegit.ui.components.Pill
import com.ouor.mobilegit.ui.components.SecondaryButton
import com.ouor.mobilegit.ui.components.SectionCard
import com.ouor.mobilegit.ui.core
import com.ouor.mobilegit.ui.dateTime
import com.ouor.mobilegit.ui.label
import com.ouor.mobilegit.ui.str
import com.ouor.mobilegit.ui.theme.CodeText
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A-15 보존 기록 (R2-03 AC2, AC3). Opening it clears the "히스토리 바뀜" badge. [group] scrolls to one update's
 * batch (from the sync history, AC6).
 */
@Composable
fun ArchiveScreen(name: String, group: String?, nav: Nav) {
    val core = core()
    val ctx = LocalContext.current
    val server by core.server.state.collectAsStateWithLifecycle()
    val addrs by core.network.addresses.collectAsStateWithLifecycle()
    val entries by produceState<Result<List<ArchiveEntryDto>>?>(null, name) {
        value = withContext(Dispatchers.IO) { runCatching { core.repos.markHistorySeen(name); core.ops.archives(name) } }
    }
    val groups = remember(entries) { entries?.getOrNull()?.groupBy { it.group }?.entries?.toList().orEmpty() }
    val list = rememberLazyListState()
    LaunchedEffect(groups, group) {
        val i = groups.indexOfFirst { it.key == group }
        if (i >= 0) list.scrollToItem(i)
    }
    val base = addrs.firstOrNull()?.baseUrl

    AppScaffold(str(R.string.archive_title, name), onBack = nav::back) { padding ->
        val r = entries
        when {
            r == null -> Box(Modifier.padding(padding)) { Loading() }
            r.isFailure -> Box(Modifier.padding(padding)) { ErrorState(r.exceptionOrNull()?.message ?: str(R.string.export_unknown_error)) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), state = list,
                contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                if (groups.isEmpty()) item { EmptyState(Icons.Outlined.Archive, str(R.string.archive_empty_title), str(R.string.detail_archive_none)) }
                items(groups, key = { it.key }) { (_, batch) ->
                    SectionCard(title = str(R.string.archive_group_title, dateTime(batch.first().at), batch.size)) {
                        batch.forEachIndexed { i, e -> ArchiveEntryRow(e, Modifier.padding(top = if (i == 0) 0.dp else Space.md)) }
                    }
                }
                item(key = "fetch") {
                    SectionCard(title = str(R.string.archive_fetch_title)) {
                        Text(str(R.string.archive_fetch_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.padding(top = Space.sm))
                        CommandBlock("git fetch ${base?.let { "$it/$name.git" } ?: str(R.string.detail_archive_url_placeholder)} 'refs/archive/*:refs/archive/*'")
                        Text(str(R.string.detail_archive_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Space.sm))
                        // AC3: the web page (W-05) shows the same list with commit links
                        if (server is ServerState.Running && base != null) SecondaryButton(str(R.string.archive_open_web), Modifier.padding(top = Space.md), icon = Icons.Outlined.OpenInBrowser) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/r/${Uri.encode(name)}/archives"))) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(e: ArchiveEntryDto, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.kind.label(), e.kind.tone)
            Spacer(Modifier.width(Space.sm))
            Text(e.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.sm))
            Text(e.shortSha, style = CodeText.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs)) }
        Text(listOfNotNull(e.committedAt?.let { str(R.string.archive_committed, ago(it)) },
            e.currentSha?.let { str(R.string.archive_current, it.take(7)) } ?: str(R.string.archive_current_gone)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
