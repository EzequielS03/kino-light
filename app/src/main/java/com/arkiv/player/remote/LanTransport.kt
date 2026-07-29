package com.arkiv.player.remote

import com.arkiv.player.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Transporte LAN: envuelve el SyncManager (descubrimiento multicast + HTTP /play y /key). */
class LanTransport(private val sync: SyncManager) : RemoteTransport {

    override suspend fun reachable(): Boolean = sync.connectRemote()

    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean =
        sync.playOnTv(PlayPayloadCodec.encode(p))

    override suspend fun sendKey(key: String, seq: Long): Boolean = sync.sendKey(key)

    override fun incoming(): Flow<RemoteCommand> = merge(
        sync.remotePlay.map {
            android.util.Log.i("ArkivRemote", "<- recibido PLAY por LAN: $it")
            RemoteCommand("play", PlayPayloadCodec.decode(it), null, 0)
        },
        sync.remoteKeys.map { RemoteCommand("key", null, it.toString(), 0) },
    )
}
