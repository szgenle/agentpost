package com.szgenle.agentpost.core.model

import kotlinx.serialization.Serializable

/**
 * 附件元数据。
 *
 * MVP 阶段以 JSON 形式嵌入 [TaskMessage] 行（TypeConverter 在 core:database 实现），
 * 不单独建表；等真需要"按附件查询/过滤"再拆表。
 */
@Serializable
data class Attachment(
    /** 服务端原始文件名。 */
    val fileName: String,
    /** MIME 类型，如 "application/zip" / "text/plain"。 */
    val mimeType: String,
    /** 字节数。来自 IMAP SIZE 或 BODYSTRUCTURE；无法取得时为 -1。 */
    val sizeBytes: Long,
    /** 已下载到本地后的缓存路径；未下载为 null。 */
    val localPath: String? = null,
)
