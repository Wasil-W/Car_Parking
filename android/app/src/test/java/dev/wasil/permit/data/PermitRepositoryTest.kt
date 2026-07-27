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

    @Test
    fun `switchTo activates then re-reads and confirms`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", true), VrnEntry("XX123Y", false))))
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
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true))))
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertTrue(result is PermitRepository.SwitchResult.Mismatch)
        assertEquals("XX123Y", (result as PermitRepository.SwitchResult.Mismatch).serverActiveVrn)
    }
}
