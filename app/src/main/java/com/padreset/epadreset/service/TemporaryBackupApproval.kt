package com.padreset.epadreset.service

import com.padreset.epadreset.data.BackupOrigin

/** Explicit approval never survives a changed session, operation or pre-write state. */
internal class TemporaryBackupApproval {
    private data class Request(val session: Any, val origin: BackupOrigin, val plan: String, val bytes: Map<Int, Byte>)
    private var pending: Request? = null
    private var approved: Request? = null

    fun request(session: Any, origin: BackupOrigin, plan: String, bytes: Map<Int, Byte>) {
        pending = Request(session, origin, plan, bytes.toMap())
        approved = null
    }

    fun approve(): Boolean {
        approved = pending ?: return false
        pending = null
        return true
    }

    fun consume(session: Any, origin: BackupOrigin, plan: String, bytes: Map<Int, Byte>): Boolean {
        val grant = approved
        approved = null
        return grant != null && grant.session === session && grant.origin == origin && grant.plan == plan && grant.bytes == bytes
    }

    fun clear() {
        pending = null
        approved = null
    }
}

internal class BackupConfirmationRequired : Exception("Backup is only in memory; explicit approval required")
