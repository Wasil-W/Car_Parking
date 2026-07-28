package dev.wasil.permit.ui

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ScriptedApi : PermitApi {
    var active: String? = "RH950F"
    var failNextGet = false
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse {
        if (failNextGet) { failNextGet = false; throw IOException("offline") }
        return ClientProductResponse(
            listOf(
                VrnEntry("RH950F", active == "RH950F"),
                VrnEntry("XX123Y", active == "XX123Y"),
            )
        )
    }
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        active = body.vrn
        return ActivateResponse(1L)
    }
}

class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ScriptedApi
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")

    @Before fun setUp() { Dispatchers.setMain(dispatcher); api = ScriptedApi() }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(store: FakeCredentialStore = FakeCredentialStore(config)) =
        MainViewModel(PermitRepository(api), store)

    @Test
    fun `unconfigured store shows setup screen`() = runTest(dispatcher) {
        val vm = vm(FakeCredentialStore(null))
        assertTrue(vm.state.value.needsSetup)
    }

    @Test
    fun `configured store loads active plate and both options on init`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("RH950F", s.activeVrn)
        assertEquals(listOf("Wasil", "Walid"), s.options.map { it.label })
        assertEquals(listOf("RH950F", "XX123Y"), s.options.map { it.vrn })
    }

    @Test
    fun `switchTo updates active plate after confirmed switch`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        vm.switchTo(PlateOption("Walid", "XX123Y"))
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("XX123Y", s.activeVrn)
        assertNull(s.switching)
    }

    @Test
    fun `network failure surfaces message instead of crashing`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.failNextGet = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.message != null)
    }

    @Test
    fun `saveSetup normalizes plates and leaves setup mode`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        val vm = vm(store)
        vm.saveSetup("u", "p", "rh-950-f", "xx 123 y")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("RH950F", store.config!!.wasilPlate)
        assertEquals("XX123Y", store.config!!.walidPlate)
        assertTrue(!vm.state.value.needsSetup)
    }
}
