package com.transfer.flash.server

import com.transfer.flash.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The first-run banner must name the management listener, where the console is served (see [setupUrlFor]). */
class SetupUrlTest {
    private fun config(
        tlsMode: String = "proxy",
        adminHost: String = "127.0.0.1",
        adminPort: Int = 8444,
        adminOnPublicPort: Boolean = false,
    ) = ServerConfig(
        port = 8443,
        adminPort = adminPort,
        host = "127.0.0.1",
        dataDir = "./test-data",
        tlsMode = tlsMode,
        adminHost = adminHost,
        adminOnPublicPort = adminOnPublicPort,
        externalUrl = "https://hub.example.org:8443",
        trustedProxies = listOf("10.0.0.1"),
    )

    @Test
    fun theDefaultNamesTheLoopbackManagementListenerOverPlainHttp() {
        assertEquals("http://127.0.0.1:8444/admin/setup", setupUrlFor(config()))
    }

    @Test
    fun aWildcardBindIsShownAsLocalhost() {
        assertEquals("http://localhost:8444/admin/setup", setupUrlFor(config(adminHost = "0.0.0.0")))
        assertEquals("http://localhost:8444/admin/setup", setupUrlFor(config(adminHost = "::")))
    }

    @Test
    fun anIpv6BindIsBracketed() {
        assertEquals("http://[::1]:9000/admin/setup", setupUrlFor(config(adminHost = "::1", adminPort = 9000)))
    }

    @Test
    fun aHubThatTerminatesTlsItselfUsesHttps() {
        assertEquals("https://127.0.0.1:8444/admin/setup", setupUrlFor(config(tlsMode = "self")))
    }

    @Test
    fun whenTheConsoleIsOnThePublicPortTheExternalUrlIsUsed() {
        assertEquals("https://hub.example.org:8443/admin/setup", setupUrlFor(config(adminOnPublicPort = true)))
    }
}
