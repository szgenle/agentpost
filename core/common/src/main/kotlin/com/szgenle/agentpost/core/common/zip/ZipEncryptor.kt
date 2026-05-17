package com.szgenle.agentpost.core.common.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/**
 * 加密 zip 打包工具，与 [ZipDecryptor] 配套。
 *
 * 取向：
 * - 纯 IO 逻辑，无 Android 依赖；
 * - 全部 IO 切到 [Dispatchers.IO]；
 * - 结果用 sealed class 区分成功/失败，调用方按需展示反馈。
 *
 * 默认 AES-256 加密；调用方需传非空密码（与 ZipDecryptor 对称）。
 */
object ZipEncryptor {

    /**
     * 把 [srcFiles] 打包并加密输出到 [outputZip]。
     *
     * - [outputZip] 已存在会被覆盖；父目录不存在会自动创建；
     * - 密码为空直接返回 [EncryptResult.Failure]，不生成空密码 zip；
     * - 任何 IO 异常统一走 [EncryptResult.Failure]，不抛给调用方。
     */
    suspend fun encrypt(
        srcFiles: List<File>,
        outputZip: File,
        password: String,
    ): EncryptResult = withContext(Dispatchers.IO) {
        if (password.isEmpty()) {
            return@withContext EncryptResult.Failure(
                IllegalArgumentException("password must not be empty"),
            )
        }
        if (srcFiles.isEmpty()) {
            return@withContext EncryptResult.Failure(
                IllegalArgumentException("srcFiles must not be empty"),
            )
        }
        runCatching {
            outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (outputZip.exists()) outputZip.delete()
            val params = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            ZipFile(outputZip, password.toCharArray()).use { zf ->
                srcFiles.forEach { f ->
                    if (!f.exists() || !f.isFile) {
                        throw IllegalArgumentException("source not a regular file: ${f.absolutePath}")
                    }
                    zf.addFile(f, params)
                }
            }
            EncryptResult.Success(outputZip)
        }.getOrElse { e ->
            EncryptResult.Failure(e)
        }
    }
}

/** 加密打包结果。 */
sealed class EncryptResult {
    data class Success(val output: File) : EncryptResult()
    data class Failure(val error: Throwable) : EncryptResult()
}
