package com.ouor.mobilegit.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.ouor.mobilegit.core.RepoFilter
import com.ouor.mobilegit.core.RepoQuery
import com.ouor.mobilegit.core.RepoSort
import com.ouor.mobilegit.core.RemoteUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Temporary developer screen (to be replaced by the Compose UI): server switch, addresses, add mirror, repo list.
 * Also the share-sheet target (F-06 AC6): shared text pre-fills the URL box.
 */
class MainActivity : AppCompatActivity() {
    private val core get() = MobileGitApp.core(this)
    private lateinit var urlBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 320, 32, 64) }
        fun text(size: Float = 14f) = TextView(this).apply { textSize = size; setTextIsSelectable(true) }.also { col.addView(it) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }.also { col.addView(it) }

        val switch = SwitchCompat(this).apply { text = "서버" }.also { col.addView(it) }
        val status = text()
        val addresses = text(16f)
        val account = text()
        urlBox = EditText(this).apply {
            hint = "미러할 HTTPS URL (여러 줄 가능)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            contentDescription = "미러 URL 입력"
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        button("미러 추가") { addMirrors() }
        button("모든 미러 업데이트") { core.mirrors.updateAll() }
        val list = text()

        setContentView(ScrollView(this).apply { addView(col) })

        val s = core.settings.current
        account.text = core.settings.initialPassword()?.let { "접속 계정: ${s.username} / $it  (온보딩 전용 표시)" } ?: "접속 계정: ${s.username}"

        switch.setOnCheckedChangeListener { _, on -> if (on) core.server.start() else core.server.stop() }
        lifecycleScope.launch {
            core.server.state.collect { st ->
                status.text = "상태: $st"
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = st is ServerState.Running || st is ServerState.Starting
                switch.setOnCheckedChangeListener { _, on -> if (on) core.server.start() else core.server.stop() }
            }
        }
        lifecycleScope.launch {
            core.network.addresses.collect { a ->
                addresses.text = if (a.isEmpty()) "접속 가능한 네트워크 없음 (와이파이 연결, 핫스팟 켜기, Tailscale 연결)"
                else a.joinToString("\n") { "${it.kind}: ${it.baseUrl}" }
            }
        }
        lifecycleScope.launch {
            combine(core.repoItems, core.bulkJobs.jobs) { items, jobs -> items to jobs.lastOrNull() }.collect { (items, job) ->
                val rows = RepoQuery.apply(items, RepoSort.NAME, RepoFilter.ALL, "").joinToString("\n") { i ->
                    val m = i.meta
                    "${m.name} [${m.type}] ${i.badges.joinToString(",")} ${m.sizeBytes / 1024}KB" + (i.progress?.progress?.let { p -> " ${p.task} ${p.done}/${p.total}" } ?: "")
                }
                list.text = (job?.let { "일괄: 완료 ${it.count(com.ouor.mobilegit.core.BulkItemStatus.DONE)} / 실패 ${it.count(com.ouor.mobilegit.core.BulkItemStatus.FAILED)} / 전체 ${it.items.size}\n\n" } ?: "") +
                    rows.ifEmpty { "아직 레포가 없습니다.\nPC에서: git push http://<주소>/<이름>.git main" }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        urlBox.setText(RemoteUrls.extractCandidates(text).joinToString("\n"))
    }

    private fun addMirrors() {
        val text = urlBox.text.toString()
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val lines = RemoteUrls.extractCandidates(text)
                    if (lines.size == 1) {
                        val p = core.mirrors.preview(lines[0])
                        p.error ?: core.mirrors.add(p.normalized!!.url, p.suggestedName!!).let { "등록: ${it.name}" }
                    } else core.mirrors.addMany(text).let { "일괄 등록 ${it.items.size}개" }
                }.getOrElse { it.message ?: it.toString() }
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            urlBox.setText("")
        }
    }
}
