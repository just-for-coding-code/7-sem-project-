package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val nickname: String,
    val publicKeyBase64: String,
    val customAlias: String? = null,
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = customAlias?.takeIf { it.isNotBlank() } ?: nickname.ifBlank { peerId }
}
