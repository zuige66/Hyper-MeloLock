package io.github.hyperisland.compose.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

internal object RestartScopeService {
    /**
     * 探测是否真能拿到 root：直接开一个 `su` 并跑 `id`。
     *
     * 之所以要在打开作用域列表**之前**先探测一次：列表里每条都对应一条 root 命令，
     * 没有 root 时把它们列出来只会让用户在点「确定」之后才看到失败。提前探测可以
     * 在点击图标时就直接给出「需要 root」的提示。
     * 注意这可能触发 root 管理器的授权弹窗。
     */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { writer ->
                writer.writeBytes("id\nexit\n")
                writer.flush()
            }
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    suspend fun restart(commands: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { writer ->
                commands.forEach { command -> writer.writeBytes("$command\n") }
                writer.writeBytes("exit\n")
                writer.flush()
            }
            val exitCode = process.waitFor()
            check(exitCode == 0) { "Root permission denied (exit $exitCode)" }
        }
    }
}
