package com.szgenle.agentpost.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.AccountType
import kotlinx.coroutines.flow.Flow

/**
 * 账户 DAO。MVP 阶段 SELF / AGENT 各 1 条，但表结构按多账户预留。
 */
@Dao
interface AccountDao {

    @Upsert
    suspend fun upsert(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): Account?

    @Query("SELECT * FROM accounts WHERE type = :type LIMIT 1")
    fun observeFirstByType(type: AccountType): Flow<Account?>

    @Query("SELECT * FROM accounts WHERE type = :type LIMIT 1")
    suspend fun getFirstByType(type: AccountType): Account?

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Account>>
}
