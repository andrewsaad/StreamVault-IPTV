package com.streamvault.data.local

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

interface DatabaseTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** Runs the block as is; for hand-built test objects that have no database. */
object DirectTransactionRunner : DatabaseTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

@Singleton
class RoomDatabaseTransactionRunner @Inject constructor(
    private val database: StreamVaultDatabase
) : DatabaseTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        return database.withTransaction {
            block()
        }
    }
}
