package io.github.hyperisland.compose.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

internal object RestartScopeService {
    /**
     * `su` 可能根本不存在（例如 SukiSU 环境 shell 侧 `su: inaccessible or not found`），
     * 也可能启动后卡在等 root 管理器授权。这两种情况都不能让 `waitFor()` 无限期阻塞——
     * 那会让点击「看起来毫无反应」（既没有弹列表也没有提示）。统一按超时判定失败。
     */
    private const val SU_TIMEOUT_MS = 3000L

    private fun runSu(command: String): Boolean {
        val process = runCatching { Runtime.getRuntime().exec("su") }
            .onFailure { Log.w("MeloLock", "RestartScope: exec su failed: $it") }
            .getOrNull() ?: return false
        return runCatching {
            DataOutputStream(process.outputStream).use { writer ->
                writer.writeBytes("$command\nexit\n")
                writer.flush()
            }
            process.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) &&
                    process.exitValue() == 0
        }.onFailure { Log.w("MeloLock", "RestartScope: su run failed: $it") }
            .also { runCatching { process.destroy() } }
            .getOrDefault(false)
    }

    /**
     * 探测是否真能拿到 root：直接开一个 `su` 并跑 `id`。
     *
     * 之所以要在打开作用域列表**之前**先探测一次：列表里每条都对应一条 root 命令，
     * 没有 root 时把它们列出来只会让用户在点「确定」之后才看到失败。提前探测可以
     * 在点击图标时就直接决定走哪条路。注意这可能触发 root 管理器的授权弹窗。
     */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runSu("id").also { Log.i("MeloLock", "RestartScope: hasRoot=$it") }
    }

    suspend fun restart(commands: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { writer ->
                commands.forEach { command -> writer.writeBytes("$command\n") }
                writer.writeBytes("exit\n")
                writer.flush()
            }
            val finished = process.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            check(finished && process.exitValue() == 0) { "Root permission denied or timed out" }
        }
    }
}
