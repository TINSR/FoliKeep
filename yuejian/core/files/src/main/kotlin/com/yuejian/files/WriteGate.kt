package com.yuejian.files

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 数据写入协调：备份/恢复在准备阶段短暂冻结写入以取得一致快照，
 * 常规写入（导入、入队、草稿、回答更新）逐次经过这把锁。
 * 打包/解压等长 IO 不持锁；资产文件写入后不被物理删除，锁外流式复制是安全的。
 */
object WriteGate {
    val mutex = Mutex()
    suspend fun <T> write(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun <T> frozen(block: suspend () -> T): T = mutex.withLock { block() }
}
