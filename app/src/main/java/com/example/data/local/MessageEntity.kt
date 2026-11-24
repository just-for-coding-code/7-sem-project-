package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["roomId", "timestamp"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packetId: String,
    val roomId: String, // "#mesh", "#general", or "dm:<peerId>"
    val senderId: String,
    val senderNick: String,
    val isOutgoing: Boolean,
    val isEncrypted: Boolean = false,
    val isSystem: Boolean = false,
    val hopCount: Int = 0,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)
