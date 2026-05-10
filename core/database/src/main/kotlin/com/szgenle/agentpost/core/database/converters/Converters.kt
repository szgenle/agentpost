package com.szgenle.agentpost.core.database.converters

import androidx.room.TypeConverter
import com.szgenle.agentpost.core.model.AccountType
import com.szgenle.agentpost.core.model.Attachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room TypeConverter 集合。
 *
 * - [AccountType] ↔ String（存枚举名，SELF/AGENT）
 * - List<[Attachment]> ↔ JSON 字符串（MVP 阶段不单独建附件表）
 *
 * 该类必须能无参实例化（Room 限制）。内部持有的 [Json] 实例开启
 * `ignoreUnknownKeys = true`，方便未来 Attachment 加字段时平滑升级。
 */
class Converters {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- AccountType ----

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType = AccountType.valueOf(value)

    // ---- List<Attachment> ----

    @TypeConverter
    fun fromAttachmentList(list: List<Attachment>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    @TypeConverter
    fun toAttachmentList(value: String): List<Attachment> =
        if (value.isEmpty()) emptyList() else json.decodeFromString(value)
}
