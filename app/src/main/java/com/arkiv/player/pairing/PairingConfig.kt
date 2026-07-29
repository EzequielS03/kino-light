package com.arkiv.player.pairing

/** Constantes del emparejamiento por QR. */
object PairingConfig {
    const val COLLECTION = "pair_requests"
    const val QR_SCHEME = "arkiv"
    const val QR_HOST = "pair"
    const val TTL_MS = 300_000L // 5 min de validez del QR
}
