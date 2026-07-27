package dev.wasil.permit.data.store

class FakeCredentialStore(var config: PermitConfig? = null) : CredentialStore {
    override fun load(): PermitConfig? = config
    override fun save(config: PermitConfig) { this.config = config }
    override fun clear() { config = null }
}
