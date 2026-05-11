package com.szgenle.agentpost.core.common.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

/**
 * 加密 zip 解压工具。
 *
 * 设计取向：
 * - 纯 IO 逻辑，无 Android 依赖，便于单测；
 * - 结果用 sealed class 封装，区分密码错 vs 其他失败（让 UI 决定是否弹框重输）；
 * - 所有 IO 切到 [Dispatchers.IO]，不会阻塞调用方线程。
 *
 * zip4j 选型：Apache 2.0，支持 ZipCrypto + AES-128/256，主流加密 zip 都能解。
 */
object ZipDecryptor {

    /**
     * 判断文件是否为「加密 zip」。
     *
     * 两道门：
     * 1. 文件扩展名为 `.zip`（不硬性必须，但可加速排除绝大多数非 zip 文件）；
     * 2. zip4j 可解析且 [ZipFile.isEncrypted] 为真。
     *
     * 任何异常（非 zip / 读取失败）统一视为 false，不抛给调用方。
     */
    fun isEncryptedZip(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        // 扩展名粗筛：非 .zip 直接放行给现有流程，避免误走解密路径。
        if (!file.name.endsWith(".zip", ignoreCase = true)) return false
        return runCatching {
            ZipFile(file).use { it.isEncrypted }
        }.getOrDefault(false)
    }

    /**
     * 使用 [password] 解压 [src] 到 [outputDir]。
     *
     * - [outputDir] 必须可写，已存在的同名文件会被 zip4j 覆盖；
     * - 解压前调用方应保证 outputDir 已清空（避免和历史残留混在一起）；
     * - 捕 [ZipException.Type.WRONG_PASSWORD] 返回 [DecryptResult.WrongPassword]；
     *   其他异常（损坏、不支持的算法、IO 错）统一走 [DecryptResult.Failure]。
     */
    suspend fun decrypt(
        src: File,
        outputDir: File,
        password: String,
    ): DecryptResult = withContext(Dispatchers.IO) {
        if (!src.exists() || !src.isFile) {
            return@withContext DecryptResult.Failure(
                IllegalArgumentException("Source zip not found: ${src.absolutePath}"),
            )
        }
        if (password.isEmpty()) {
            // zip4j 对空密码会直接报 WRONG_PASSWORD，但显式判一下减少歧义。
            return@withContext DecryptResult.WrongPassword
        }
        runCatching {
            if (!outputDir.exists()) outputDir.mkdirs()
            ZipFile(src, password.toCharArray()).use { zf ->
                zf.extractAll(outputDir.absolutePath)
            }
            val files = outputDir.walkTopDown()
                .filter { it.isFile }
                .toList()
            DecryptResult.Success(outputDir = outputDir, files = files)
        }.getOrElse { e ->
            if (e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD) {
                DecryptResult.WrongPassword
            } else {
                DecryptResult.Failure(e)
            }
        }
    }
}

/** 解密结果。调用方据此分发 UI 反馈：成功→打开；密码错→弹框；其他失败→Snackbar。 */
sealed class DecryptResult {
    /** 解压成功。[files] 为 [outputDir] 下所有产物文件（递归）。 */
    data class Success(val outputDir: File, val files: List<File>) : DecryptResult()

    /** 密码错误。UI 应提示用户手输一次性密码后重试。 */
    data object WrongPassword : DecryptResult()

    /** 其他失败（zip 损坏、不支持的算法、IO 错等）。 */
    data class Failure(val error: Throwable) : DecryptResult()
}
