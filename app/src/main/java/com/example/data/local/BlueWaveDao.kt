package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlueWaveDao {
    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY timestamp ASC, id ASC")
    fun observeMessagesForRoom(roomId: String): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT roomId FROM messages WHERE roomId LIKE 'dm:%'")
    fun observeDmRoomIds(): Flow<List<String>>

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun observeKnownPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeerById(peerId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun clearRoomMessages(roomId: String)

    @Query("DELETE FROM messages")
    suspend fun wipeAllMessages()

    @Query("DELETE FROM peers")
    suspend fun wipeAllPeers()
}
