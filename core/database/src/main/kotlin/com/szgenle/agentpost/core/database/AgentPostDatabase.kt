package com.szgenle.agentpost.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.szgenle.agentpost.core.database.converters.Converters
import com.szgenle.agentpost.core.database.dao.AccountDao
import com.szgenle.agentpost.core.database.dao.TaskDao
import com.szgenle.agentpost.core.database.dao.TaskMessageDao
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage

/**
 * AgentPost 本地数据库。
 *
 * MVP 阶段 `exportSchema = false`，等到第一个 release 版本再打开，
 * 避免现在 schema JSON 反复变动污染 git 历史。
 */
@Database(
    entities = [
        Account::class,
        Task::class,
        TaskMessage::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AgentPostDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    abstract fun taskDao(): TaskDao

    abstract fun taskMessageDao(): TaskMessageDao

    companion object {
        const val DATABASE_NAME = "agentpost.db"
    }
}
