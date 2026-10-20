package io.github.v_inn.foxloop.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRulesTest {
    private fun ok(name: String, port: String, path: String, existing: List<Dashboard> = emptyList()): Dashboard {
        val r = DashboardRules.add(name, port, path, existing)
        assertTrue("expected Ok, got ${(r as? DashboardRules.Added.Invalid)?.reason}", r is DashboardRules.Added.Ok)
        return (r as DashboardRules.Added.Ok).dashboard
    }

    private fun invalid(port: String, path: String) =
        assertTrue(DashboardRules.add("x", port, path, emptyList()) is DashboardRules.Added.Invalid)

    @Test
    fun uptimeKumaKeepsItsOwnPort() {
        val d = ok("Uptime Kuma", "3001", "/dashboard")
        assertEquals(3001, d.localPort)
        assertEquals("http://127.0.0.1:3001/dashboard", d.url)
    }

    @Test
    fun privilegedPortsMoveUpBy8000() {
        assertEquals(8080, DashboardRules.localPortFor(80, emptySet()))
        assertEquals(8443, DashboardRules.localPortFor(443, emptySet()))
    }

    @Test
    fun aTakenPortFallsBackToTheSpareRange() {
        assertEquals(20_000, DashboardRules.localPortFor(3001, setOf(3001)))
        assertEquals(20_001, DashboardRules.localPortFor(80, setOf(8080, 20_000)))
    }

    @Test
    fun theSameRemotePortTwiceGetsTwoLocalPorts() {
        val first = ok("a", "8123", "/")
        val second = ok("b", "8123", "/lovelace", listOf(first))
        assertEquals(8123, first.localPort)
        assertEquals(20_000, second.localPort)
    }

    @Test
    fun pathIsNormalised() {
        assertEquals("/", ok("x", "80", "").path)
        assertEquals("/admin", ok("x", "80", "admin").path)
        assertEquals("/admin/", ok("x", "80", " /admin/ ").path)
    }

    @Test
    fun aBlankNameIsNamedAfterThePort() {
        assertEquals("Port 3001", ok("  ", "3001", "/").name)
    }

    @Test
    fun badInputIsRefusedWithAReason() {
        invalid("", "/")
        invalid("0", "/")
        invalid("70000", "/")
        invalid("http", "/")
        invalid("3001", "http://pi.local:3001/dashboard")
        invalid("3001", "/two words")
    }

    @Test
    fun storageRoundTrips() {
        val list = listOf(ok("Uptime Kuma", "3001", "/dashboard"), ok("Pi-hole", "80", "/admin"))
        assertEquals(list, DashboardRules.decode(DashboardRules.encode(list)))
    }

    @Test
    fun separatorsInANameCannotBreakStorage() {
        val d = ok("evil\tname\nwith breaks", "3001", "/")
        assertEquals("evil name with breaks", d.name)
        assertEquals(listOf(d), DashboardRules.decode(DashboardRules.encode(listOf(d))))
    }

    @Test
    fun corruptLinesAreSkipped() {
        assertEquals(1, DashboardRules.decode("ok\t3001\t/\t3001\nbroken\nalso\tx\t/\t1").size)
        assertEquals(emptyList<Dashboard>(), DashboardRules.decode(""))
    }
}
