package com.ouor.mobilegit.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.minimumInteractiveComponentSize
import com.ouor.mobilegit.ui.text
import com.ouor.mobilegit.ui.tone
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import com.ouor.mobilegit.ui.breakable
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import com.ouor.mobilegit.R
import com.ouor.mobilegit.ui.str
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ouor.mobilegit.core.RepoListItem
import com.ouor.mobilegit.core.RepoType
import com.ouor.mobilegit.ui.ago
import com.ouor.mobilegit.ui.badgeOrder
import com.ouor.mobilegit.ui.bytes
import com.ouor.mobilegit.ui.style
import com.ouor.mobilegit.ui.theme.CodeText
import com.ouor.mobilegit.ui.theme.Space
import com.ouor.mobilegit.ui.theme.Tone
import com.ouor.mobilegit.ui.theme.tone

/** App-wide snackbar: `LocalSnack.current("복사했어요")`. Provided by the root in MainActivity. */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Returns a copy action that also confirms with a snackbar. */
@Composable
fun rememberCopy(): (String) -> Unit {
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    return remember(ctx, snack) { { text: String -> ctx.copyText("mobilegit", text); snack(str(R.string.common_copied)) } }
}

// ---------- Scaffolding ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, str(R.string.common_back)) } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** Overflow menu: `OverflowMenu { item("설정") { … } }`. */
class MenuScope { val items = mutableListOf<Triple<String, Boolean, () -> Unit>>(); fun item(label: String, enabled: Boolean = true, onClick: () -> Unit) { items += Triple(label, enabled, onClick) } }

@Composable
fun OverflowMenu(description: String = str(R.string.common_more), build: MenuScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, description) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuScope().apply(build).items.forEach { (label, enabled, onClick) ->
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; onClick() })
            }
        }
    }
}

/** Bottom action bar with one primary button (full width, 56dp). */
@Composable
fun BottomAction(label: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit, above: @Composable ColumnScope.() -> Unit = {}) {
    // a hairline so a half-scrolled row reads as "continues under the bar", not as covered (RV2-05)
    Surface(color = MaterialTheme.colorScheme.background) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.md)) {
            above()
            PrimaryButton(label, enabled = enabled && !loading, onClick = onClick, loading = loading)
        }
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(54.dp), shape = MaterialTheme.shapes.medium) {
        Text(if (loading) str(R.string.common_working) else label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp), shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        contentPadding = PaddingValues(horizontal = Space.lg),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs + Space.xxs)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------- Cards & sections ----------

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, trailing: @Composable (() -> Unit)? = null, padding: PaddingValues = PaddingValues(Space.lg), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(padding)) {
            if (title != null || trailing != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ---------- Status ----------

@Composable
fun Pill(text: String, tone: Tone, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val c = tone(tone)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.container).padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(13.dp), tint = c.content); Spacer(Modifier.width(3.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.content, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(badges: Set<com.ouor.mobilegit.core.Badge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        badgeOrder.filter { it in badges }.forEach { b -> val s = b.style(); Pill(s.label, s.tone, s.icon) }
    }
}

/** Full-width message block. Use for persistent state (ngrok 공개 중, 저장 공간 부족, 원본 없음). */
@Composable
fun Banner(
    tone: Tone, icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null, detail: String? = null,
) {
    val c = tone(tone)
    var open by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = c.container) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = c.content, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(Modifier.width(Space.sm + Space.xxs))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.content)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = c.content, modifier = Modifier.padding(top = 2.dp))
                val hasDetail = !detail.isNullOrBlank()
                if ((actionLabel != null && onAction != null) || hasDetail) Row(Modifier.padding(top = Space.xs).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                    val colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = c.content)
                    if (actionLabel != null && onAction != null) androidx.compose.material3.TextButton(onClick = onAction, colors = colors) { Text(actionLabel, fontWeight = FontWeight.Bold) }
                    if (hasDetail) androidx.compose.material3.TextButton(onClick = { open = !open }, colors = colors) { Text(if (open) str(R.string.common_details_hide) else str(R.string.common_details)) }
                }
                if (hasDetail) {
                    if (open) Text(detail!!.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"), style = CodeText.small,
                        color = c.content, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.offset(x = Space.sm, y = (-Space.sm))) { Icon(Icons.Outlined.Close, str(R.string.common_close), tint = c.content, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Small tappable warning chip for the main-screen status strip. */
@Composable
fun WarningChip(text: String, icon: ImageVector, tone: Tone = Tone.Warning, onDismiss: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = tone(tone)
    Row(
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(50)).background(c.container).clickable(role = Role.Button, onClick = onClick)
            .padding(start = Space.md, end = if (onDismiss != null) Space.xs else Space.md, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.content, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs + Space.xxs))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.content)
        if (onDismiss != null) {
            Spacer(Modifier.width(Space.xs))
            Icon(Icons.Outlined.Close, str(R.string.common_close_named, text), tint = c.content, modifier = Modifier.size(18.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Space.lg))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(Space.lg))
        content()
    }
}

/** Load/operation error with retry. */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Banner(Tone.Danger, Icons.Outlined.Refresh, str(R.string.common_error_title), message, modifier, actionLabel = onRetry?.let { str(R.string.common_retry) }, onAction = onRetry)
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
}

// ---------- Addresses & code ----------

/**
 * "Address bar": a monospace URL that copies on tap, with optional QR action.
 * [large] for the main-screen primary address.
 */
@Composable
fun AddressBar(label: String, url: String, modifier: Modifier = Modifier, large: Boolean = false, enabled: Boolean = true, onQr: (() -> Unit)? = null) {
    val copy = rememberCopy()
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = str(R.string.common_copy)) { copy(url) }.padding(start = Space.md, top = if (large) Space.md else Space.sm, bottom = if (large) Space.md else Space.sm, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // large: host:port only so the primary address fits one line; copy/QR still use the full URL.
            Text(breakable(if (large) url.removePrefix("http://") else url), style = if (large) CodeText.large else CodeText.body, // RV2-10: wrap in full at large font sizes, never "…"
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!large) IconButton(onClick = { copy(url) }) { Icon(Icons.Outlined.ContentCopy, str(R.string.common_copy_named, label), Modifier.size(20.dp)) }
        if (onQr != null) IconButton(onClick = onQr) { Icon(Icons.Outlined.QrCode2, str(R.string.common_qr_large), Modifier.size(22.dp)) }
    }
}

/** Copyable command / code block. */
@Composable
fun CommandBlock(command: String, modifier: Modifier = Modifier) {
    val copy = rememberCopy()
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = str(R.string.common_copy_command)) { copy(command) }.padding(start = Space.md, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(command, style = CodeText.small, softWrap = false, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = Space.xs))
        IconButton(onClick = { copy(command) }) { Icon(Icons.Outlined.ContentCopy, str(R.string.common_copy_command), Modifier.size(18.dp)) }
    }
}

// ---------- Rows ----------

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueTone: Tone? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = valueTone?.let { tone(it).content } ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Space.md))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = appSwitchColors())
    }
}

/** White thumb in both themes so "on" reads at a glance (review #13: the dark-mode thumb was navy on light blue). */
@Composable
fun appSwitchColors() = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary, checkedBorderColor = MaterialTheme.colorScheme.primary)

@Composable
fun NavRow(title: String, onClick: () -> Unit, subtitle: String? = null, icon: ImageVector? = null, value: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(Space.md + Space.xs)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** A white rounded group of rows (settings style). */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = Space.xs), content = content)
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(horizontal = Space.lg), color = MaterialTheme.colorScheme.outlineVariant)

// ---------- Repo row ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RepoRow(item: RepoListItem, intervalHours: Int, onClick: () -> Unit, onUpdate: (() -> Unit)?) {
    val m = item.meta
    var menu by remember { mutableStateOf(false) }
    val mirror = m.type == RepoType.MIRROR
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (onUpdate != null) menu = true }, onLongClickLabel = str(R.string.common_menu))
                    .padding(start = Space.lg, top = Space.md + Space.xxs, bottom = Space.md + Space.xxs, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(if (mirror) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (mirror) Icons.Outlined.SyncAlt else Icons.Outlined.Folder, if (mirror) str(R.string.common_kind_mirror) else str(R.string.common_kind_local_repo),
                        tint = if (mirror) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val whenText = if (mirror && m.mirror?.waitingForForeground == true) str(R.string.detail_waiting_foreground_short) else if (mirror) m.mirror?.lastSuccessUpdateAt?.let { str(R.string.common_updated_ago, ago(it)) } ?: str(R.string.common_before_first_fetch) else m.lastPushAt?.let { "push ${ago(it)}" } ?: str(R.string.common_before_push)
                    val kind = if (mirror) str(R.string.common_kind_mirror) else str(R.string.common_kind_local)
                    val issue = item.issue
                    // R2-01: the reason replaces "업데이트 N분 전"; the icon already says 미러, and "언제부터" must not be cut (RV2-05)
                    if (issue != null) Text(issue.text(intervalHours), style = MaterialTheme.typography.bodySmall, color = tone(issue.tone).content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else Text("$kind · ${whenText} · ${bytes(m.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    if (item.badges.isNotEmpty()) BadgeRow(item.badges, Modifier.padding(top = Space.xs + Space.xxs))
                    item.progress?.let { p ->
                        val f = p.progress?.fraction
                        Spacer(Modifier.height(Space.sm))
                        if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        p.progress?.task?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = Space.sm))
                if (onUpdate != null) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(str(R.string.common_update_now)) }, enabled = item.progress == null, onClick = { menu = false; onUpdate() })
                    DropdownMenuItem(text = { Text(str(R.string.common_view_details)) }, onClick = { menu = false; onClick() })
                }
            }
        }
    }
}

// ---------- Dialogs ----------

@Composable
fun ConfirmDialog(
    title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = false, confirmEnabled: Boolean = true, extra: @Composable ColumnScope.() -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { Text(text, style = MaterialTheme.typography.bodyMedium); extra() } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (destructive && confirmEnabled) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.common_cancel)) } },
    )
}

@Composable
fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.md), content = content,
    )
}

/** "고급" fold for expert options (PR branches, default branch): closed by default (review #3). */
@Composable
fun AdvancedSection(title: String = str(R.string.common_advanced), content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = title) { open = !open }.padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(Space.md), content = content)
    }
}
