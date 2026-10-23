package app.tiviate.data.sync

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4FirstDnsTest {

    private val v6a = InetAddress.getByName("2a04:4e42:200::656")
    private val v4a = InetAddress.getByName("151.101.2.144")
    private val v6b = InetAddress.getByName("2a04:4e42::656")
    private val v4b = InetAddress.getByName("151.101.194.144")

    @Test
    fun ipv4AddressesComeFirstKeepingOrderWithinFamilies() {
        val dns = Ipv4FirstDns(Dns { listOf(v6a, v4a, v6b, v4b) })
        val result = dns.lookup("jiotv.catchup.cdn.jio.com")
        assertEquals(listOf(v4a, v4b, v6a, v6b), result)
        assertTrue(result.first() is Inet4Address)
    }

    @Test
    fun ipv6OnlyHostsStillResolve() {
        val dns = Ipv4FirstDns(Dns { listOf(v6a) })
        assertEquals(listOf(v6a), dns.lookup("v6.only"))
    }
}
