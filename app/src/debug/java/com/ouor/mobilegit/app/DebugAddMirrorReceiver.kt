package com.ouor.mobilegit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WM-00, debug builds only: `adb shell am broadcast -a com.ouor.mobilegit.DEBUG_ADD_MIRROR -n com.ouor.mobilegit/.app.DebugAddMirrorReceiver
 * --es url <https url> --es name <name> [--ei delaySec 20]`; `--es delete <name>` removes a test repo again. Calls [com.ouor.mobilegit.core.MirrorController.add] after the
 * delay, so any short background allowance from receiving the broadcast has expired (like a web request would).
 */
class DebugAddMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = MobileGitApp.core(context)
        intent.getStringExtra("delete")?.let { n ->
            core.scope.launch { Log.i("mobilegit-wm00", "delete $n: ${runCatching { core.ops.delete(n) }.exceptionOrNull() ?: "ok"}") }
            return
        }
        val url = intent.getStringExtra("url") ?: return
        val name = intent.getStringExtra("name") ?: return
        val delaySec = intent.getIntExtra("delaySec", 20)
        core.scope.launch {
            delay(delaySec * 1000L)
            val r = runCatching { core.mirrors.add(url, name) }
            Log.i("mobilegit-wm00", "add $name at ${System.currentTimeMillis()}: ${r.exceptionOrNull() ?: "ok"}")
        }
    }
}
