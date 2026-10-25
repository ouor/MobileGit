package com.ouor.mobilegit.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import com.ouor.mobilegit.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ouor.mobilegit.app.ExportKind
import com.ouor.mobilegit.app.ExportWorker
import com.ouor.mobilegit.app.MobileGitApp
import com.ouor.mobilegit.core.Exporter
import com.ouor.mobilegit.ui.components.LocalSnack
import java.io.File
import java.util.UUID

/**
 * F-19 export flow shared by A-07, A-09 and A-14: pick a SAF location (AC4) or share, run [ExportWorker] in the
 * background (AC5) and report the result. Observe [running] / [progress] for inline progress UI.
 */
class ExportRunner internal constructor(private val savedId: MutableState<String?>, private val savedPending: MutableState<ArrayList<String>?>) {
    /** Waiting for the SAF picker: kind, repos, ref. Saved as [kind, ref or "", repos...] so it survives process death (AND-09). */
    internal var pending: Triple<ExportKind, List<String>, String?>?
        get() = savedPending.value?.let { Triple(ExportKind.valueOf(it[0]), it.drop(2), it[1].ifEmpty { null }) }
        set(v) { savedPending.value = v?.let { arrayListOf(it.first.name, it.third.orEmpty()).apply { addAll(it.second) } } }
    internal var launch: (String) -> Unit = {}
    internal var enqueue: (ExportKind, List<String>, String?, Uri?) -> Unit = { _, _, _, _ -> }
    var workId: UUID?
        get() = savedId.value?.let(UUID::fromString)
        internal set(v) { savedId.value = v?.toString() }
    var progress by mutableStateOf<Pair<Int, Int>?>(null); internal set
    val running get() = workId != null

    /** [toFile] = choose a location with the system picker; otherwise the result is shared via the share sheet. [ref]: SOURCE_ZIP branch (null = HEAD). */
    fun start(kind: ExportKind, repos: List<String>, toFile: Boolean, ref: String? = null) {
        if (running) return
        if (toFile) {
            pending = Triple(kind, repos, ref)
            val ext = when (kind) { ExportKind.BUNDLE -> "bundle"; ExportKind.SOURCE_ZIP -> "zip"; ExportKind.FULL_BACKUP -> "backup" }
            launch(Exporter.suggestedFileName(repos.firstOrNull() ?: "", ext, System.currentTimeMillis()))
        } else enqueue(kind, repos, ref, null)
    }
}

@Composable
fun rememberExportRunner(): ExportRunner {
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val id0 = rememberSaveable { mutableStateOf<String?>(null) }
    val pending0 = rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val runner = remember { ExportRunner(id0, pending0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = runner.pending; runner.pending = null
        if (uri != null && p != null) runner.enqueue(p.first, p.second, p.third, uri)
    }
    runner.launch = { picker.launch(it) }
    runner.enqueue = { kind, repos, ref, uri ->
        runner.progress = null
        runner.workId = MobileGitApp.core(ctx).scheduler.enqueueExport(kind, repos, ref, uri)
        snack(str(R.string.export_001))
    }
    val id = runner.workId
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { info ->
            if (info == null) return@collect
            val d = info.progress.getInt("done", -1); val t = info.progress.getInt("total", -1)
            if (t > 0) runner.progress = d to t
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    runner.workId = null
                    val file = info.outputData.getString(ExportWorker.K_OUT_FILE)
                    if (file != null) share(ctx, File(file)) else snack(str(R.string.export_002))
                }
                WorkInfo.State.FAILED -> { runner.workId = null; snack(str(R.string.export_004, info.outputData.getString(ExportWorker.K_ERROR) ?: str(R.string.export_003))) }
                WorkInfo.State.CANCELLED -> { runner.workId = null; snack(str(R.string.export_005)) }
                else -> Unit
            }
        }
    }
    return runner
}

private fun share(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
