package com.szgenle.agentpost.core.data

import android.content.Context
import androidx.room.Room
import com.szgenle.agentpost.core.common.security.CredentialsVault
import com.szgenle.agentpost.core.database.AgentPostDatabase
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.mail.MailProviders

/**
 * 轻量 ServiceLocator。
 *
 * MVP 阶段的依赖装配入口，由 [android.app.Application.onCreate] 调用 [init]。
 * 之后 ViewModel / Worker 通过 [mailRepository] 直接取。
 *
 * 后续如果迁到 Hilt：只需改装配点 + 给 Repository 加 @Inject，
 * 调用方 API 不变。
 */
object AppServiceLocator {

    @Volatile
    private var initialized = false

    private lateinit var _mailRepository: MailRepository
    val mailRepository: MailRepository
        get() {
            check(initialized) { "AppServiceLocator not initialized. Call init(context) in Application.onCreate." }
            return _mailRepository
        }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val db = Room.databaseBuilder(
                app,
                AgentPostDatabase::class.java,
                AgentPostDatabase.DATABASE_NAME,
            )
                // MVP 阶段没有历史版本，fallbackToDestructiveMigration 防止 schema 变更时崩溃
                .fallbackToDestructiveMigration()
                .build()
            val vault = CredentialsVault.create(app)
            val prefs = AppPreferences(app)
            _mailRepository = MailRepository(
                accountDao = db.accountDao(),
                taskDao = db.taskDao(),
                messageDao = db.taskMessageDao(),
                vault = vault,
                prefs = prefs,
                sender = MailProviders.sender(),
                fetcher = MailProviders.fetcher(),
            )
            initialized = true
        }
    }
}
