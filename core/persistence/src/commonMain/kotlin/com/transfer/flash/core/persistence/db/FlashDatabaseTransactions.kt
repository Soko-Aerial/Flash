package com.transfer.flash.core.persistence.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] as one immediate write transaction. DAO calls made inside [block] join it (Room binds
 * the writer connection to the coroutine), so they commit together or roll back together when
 * [block] throws.
 */
public suspend fun FlashDatabase.runInWriteTransaction(block: suspend () -> Unit) {
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
}
