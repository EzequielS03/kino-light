package com.arkiv.player.data.magis

internal object Sign2 {
    private val SALT: ByteArray = "salt3333=4".toByteArray(Charsets.UTF_8) +
        byteArrayOf(
            0x98.toByte(), 0x0d, 0x0a, 0x15, 0x32, 0xc9.toByte(), 0xc3.toByte(),
            0x82.toByte(), 0x17, 0x08, 0xc0.toByte(),
        )

    fun signO3(token: String, startMomentMs: Long): String {
        val msg = "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMomentMs"
            .toByteArray(Charsets.UTF_8) + SALT
        return TweakedMd5.digestHex(msg)
    }
}
