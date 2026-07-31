package dev.wasil.permit.data

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePermitApi(
    /** Each getClientProduct call pops the next state - lets tests script activate side effects. */
    val states: ArrayDeque<List<VrnEntry>>,
) : PermitApi {
    val activated = mutableListOf<ActivateRequest>()

    override suspend fun login(body: LoginRequest) = LoginResponse("tok")

    override suspend fun getClientProduct(productId: Long) =
        ClientProductResponse(states.removeFirst())

    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        activated += body
        return ActivateResponse(1L)
    }
}

class PermitRepositoryTest {

    @Test
    fun `activePlate returns the vrn with a parking session`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true))))
        )
        assertEquals("XX123Y", PermitRepository(api).activePlate())
    }

    @Test
    fun `activePlate returns null when nothing is active`() = runTest {
        val api = FakePermitApi(ArrayDeque(listOf(listOf(VrnEntry("RH950F", false)))))
        assertEquals(null, PermitRepository(api).activePlate())
    }

    // switchTo now reads BEFORE activating, so every path that actually
    // activates makes two getClientProduct calls: the idempotency check, then
    // the read-after-write verification. Hence two scripted states below.

    @Test
    fun `switchTo activates then re-reads and confirms`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(
                listOf(
                    // Before: the other plate holds it, so activate is needed.
                    listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true)),
                    // After: the switch took.
                    listOf(VrnEntry("RH950F", true), VrnEntry("XX123Y", false)),
                )
            )
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertEquals(PermitRepository.SwitchResult.Confirmed("RH950F"), result)
        assertEquals(listOf("RH950F"), api.activated.map { it.vrn })
        assertEquals(5807976L, api.activated.single().clientProductId)
    }

    @Test
    fun `switchTo reports mismatch when server disagrees after activate`() = runTest {
        // Server said 200 to activate, but re-read shows the OTHER plate still active.
        val api = FakePermitApi(
            ArrayDeque(
                listOf(
                    listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true)),
                    listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true)),
                )
            )
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertTrue(result is PermitRepository.SwitchResult.Mismatch)
        assertEquals("XX123Y", (result as PermitRepository.SwitchResult.Mismatch).serverActiveVrn)
    }

    // --- The retry-loop bug: parking again while already holding the permit ---

    @Test
    fun `switchTo confirms without activating when the plate already holds it`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", true), VrnEntry("XX123Y", false))))
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertEquals(PermitRepository.SwitchResult.Confirmed("RH950F"), result)
        // The whole point: no activate call, so the API cannot reject it, so
        // ClaimPermitWorker never sees SwitchFailed and never retries forever.
        assertTrue("activate must not be called", api.activated.isEmpty())
    }

    @Test
    fun `switchTo still activates when nobody holds the permit`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(
                listOf(
                    listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", false)),
                    listOf(VrnEntry("RH950F", true), VrnEntry("XX123Y", false)),
                )
            )
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertEquals(PermitRepository.SwitchResult.Confirmed("RH950F"), result)
        assertEquals(listOf("RH950F"), api.activated.map { it.vrn })
    }
}
