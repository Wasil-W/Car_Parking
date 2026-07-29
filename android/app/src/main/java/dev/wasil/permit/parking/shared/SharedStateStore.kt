package dev.wasil.permit.parking.shared

/**
 * Two-phone shared state. Implementations throw IOException on network
 * trouble and return null when a node simply doesn't exist yet.
 */
interface SharedStateStore {
    val configured: Boolean
    suspend fun readOther(): PhoneState?
    /** heartbeatAtMs in [state] is replaced by the RTDB server timestamp. */
    suspend fun writeMine(state: PhoneState)
    suspend fun heartbeat()
    suspend fun readPermit(): PermitClaim?
    /** claimedAtMs is set to the RTDB server timestamp. */
    suspend fun writePermit(holder: String, vrn: String, forced: Boolean)
}

/** Used until the user enters a database URL: nothing to read, writes vanish. */
object UnconfiguredSharedStateStore : SharedStateStore {
    override val configured = false
    override suspend fun readOther(): PhoneState? = null
    override suspend fun writeMine(state: PhoneState) {}
    override suspend fun heartbeat() {}
    override suspend fun readPermit(): PermitClaim? = null
    override suspend fun writePermit(holder: String, vrn: String, forced: Boolean) {}
}
