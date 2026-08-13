package com.arkiv.player.pocketbase

class FakeDeviceStore(private var identity: DeviceIdentity? = null) : DeviceStore {
    private var token: String? = null
    private var personEmail: String? = null
    private var personToken: String? = null
    override fun save(identity: DeviceIdentity) { this.identity = identity }
    override fun load(): DeviceIdentity? = identity
    override fun saveToken(token: String) { this.token = token }
    override fun token(): String? = token
    override fun clear() { identity = null; token = null; personEmail = null; personToken = null }
    override fun savePersonEmail(email: String) { personEmail = email }
    override fun personEmail(): String? = personEmail
    override fun clearPersonEmail() { personEmail = null }
    override fun savePersonToken(token: String) { personToken = token }
    override fun personToken(): String? = personToken
    override fun clearPersonToken() { personToken = null }
}
