package com.arkiv.player.pocketbase

/** Persistencia de la identidad del device + estado de cuenta de persona. */
interface DeviceStore {
    fun save(identity: DeviceIdentity)
    fun load(): DeviceIdentity?
    fun saveToken(token: String)
    fun token(): String?
    fun clear()

    /** Email de la persona logueada (solo para UI/estado; la clave NO se guarda). */
    fun savePersonEmail(email: String)
    fun personEmail(): String?
    fun clearPersonEmail()
}
