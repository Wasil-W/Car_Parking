package dev.wasil.permit.data.store

data class PermitConfig(
    val username: String,
    val password: String,
    val wasilPlate: String,
    val walidPlate: String,
)

interface CredentialStore {
    fun load(): PermitConfig?
    fun save(config: PermitConfig)
    fun clear()
}
