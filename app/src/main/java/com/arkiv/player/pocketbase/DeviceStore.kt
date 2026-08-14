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

    /**
     * De quien es la base local: el `accountId` que la genero. Ver [DuenoDeLaBase] para el porque.
     *
     * NO se borra en [clearPersonEmail] ni en [clear]: es justamente el dato que tiene que
     * sobrevivir a un cierre de sesion para que la proxima persona que entre no herede lo ajeno.
     */
    fun saveDuenoDeLaBase(accountId: String)
    fun duenoDeLaBase(): String?

    /** Token de sesión de la PERSONA (no del aparato): lo que autoriza pedidos al gateway en su
     *  nombre. Antes se autenticaba y se tiraba; sin persistirlo no hay forma de hablarle al
     *  gateway como esa persona, así que va cifrado igual que el token del device. */
    fun savePersonToken(token: String)
    fun personToken(): String?
    fun clearPersonToken()
}
