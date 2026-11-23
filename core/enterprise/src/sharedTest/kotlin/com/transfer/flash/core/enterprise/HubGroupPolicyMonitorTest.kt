@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HubGroupPolicyMonitorTest {
    @Test
    fun directoryClientReadsGroupPolicyAndUsesSafeDefaultsForOlderHubs() = runTest {
        val hub = FakeHub()
        hub.client().use { http ->
            val client = EnterpriseDirectoryClient(httpClient = http, expectedServerId = { "test-hub" })
            hub.respond(
                "GET", HubContract.PATH_HUB_INFO, HttpStatusCode.OK,
                """{"serverId":"test-hub","allowPersonalHubLink":true,"groupE2eeMode":"HUB_INSPECTABLE"}""",
            )
            assertEquals(
                FlashResult.Success(HubGroupPolicy(true, HubGroupPolicy.GROUP_E2EE_MODE_HUB_INSPECTABLE)),
                client.fetchHubGroupPolicy("https://hub.example.test"),
            )

            hub.respond("GET", HubContract.PATH_HUB_INFO, HttpStatusCode.OK, """{"serverId":"test-hub"}""")
            assertEquals(
                FlashResult.Success(HubGroupPolicyMonitor.SAFE_DEFAULT),
                client.fetchHubGroupPolicy("https://hub.example.test"),
            )
        }
    }

    @Test
    fun invalidationAndEnrollmentChangesFailClosedBeforeARefresh() = runTest {
        val credentials = MutableStateFlow<EnrollmentCredentials?>(enrolled("old"))
        val connected = MutableStateFlow(true)
        val inspectable = HubGroupPolicy(groupE2eeMode = HubGroupPolicy.GROUP_E2EE_MODE_HUB_INSPECTABLE)
        var reply: FlashResult<HubGroupPolicy> = FlashResult.Success(inspectable)
        val monitor = HubGroupPolicyMonitor(credentials, { connected.value }) { reply }
        backgroundScope.launch { monitor.watch(connected) {} }
        runCurrent()
        assertEquals(inspectable, monitor.currentPolicy())

        reply = FlashResult.Failure(FlashError.Unknown("unavailable"))
        monitor.invalidate()
        assertEquals(HubGroupPolicyMonitor.SAFE_DEFAULT, monitor.currentPolicy())
        runCurrent()
        assertEquals(HubGroupPolicyMonitor.SAFE_DEFAULT, monitor.currentPolicy())

        reply = FlashResult.Success(inspectable)
        monitor.invalidate()
        runCurrent()
        assertEquals(inspectable, monitor.currentPolicy())
        credentials.value = enrolled("new")
        assertEquals(HubGroupPolicyMonitor.SAFE_DEFAULT, monitor.currentPolicy())
        connected.value = false
        runCurrent()
        assertEquals(HubGroupPolicyMonitor.SAFE_DEFAULT, monitor.currentPolicy())
    }

    @Test
    fun transientDisconnectDuringFetchKeepsRefreshRunning() = runTest {
        val credentials = MutableStateFlow<EnrollmentCredentials?>(enrolled("hub"))
        val onlineEvents = MutableStateFlow(true)
        val policy = HubGroupPolicy(allowPersonalHubLink = true)
        var online = true
        var requests = 0
        val monitor = HubGroupPolicyMonitor(credentials, { online }) {
            if (++requests == 1) online = false
            FlashResult.Success(policy)
        }
        backgroundScope.launch { monitor.watch(onlineEvents) {} }
        runCurrent()
        assertEquals(HubGroupPolicyMonitor.SAFE_DEFAULT, monitor.currentPolicy())

        online = true
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(policy, monitor.currentPolicy())
    }

    private fun enrolled(name: String): EnrollmentCredentials = EnrollmentCredentials(
        hubUrl = "https://$name.example",
        rootFingerprint = name,
        rootPublicKey = "root",
        deviceCert = DeviceCert(
            deviceId = "device",
            subjectPublicKey = "key",
            role = DeviceCert.ROLE_MEMBER,
            validFrom = 1L,
            validTo = 10_000L,
            serial = 1L,
            issuerPublicKey = "root",
            issuerSignature = "signature",
        ),
    )
}
