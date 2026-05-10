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
    /**
     * 原邮件在 IMAP 上的 UID，配合 [partIndex] 用于懒下载时重新定位。
     * 本机发出的附件没有 UID，始终为 null；旧数据升级后也可能为 null。
     */
    val imapUid: Long? = null,
    /**
     * 附件在来信 walkParts 顺序里的 0-based 序号（字符串形式保留扩展空间）。
     * 和 [imapUid] 搭配，MailRepository 下载时根据它到 fetcher 重新拉字节流。
     * 本机发出的附件无此值。
     */
    val partIndex: String? = null,
)
