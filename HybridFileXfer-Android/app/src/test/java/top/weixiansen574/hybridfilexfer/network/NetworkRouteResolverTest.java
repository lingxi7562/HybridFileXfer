package top.weixiansen574.hybridfilexfer.network;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetworkRouteResolverTest {
    @Test
    public void localWifiRouteBeatsFullTunnelVpn() {
        int wifi = NetworkRouteResolver.calculateRouteScore(24,
                false, false, true, false, false);
        int vpn = NetworkRouteResolver.calculateRouteScore(0,
                false, true, false, false, false);
        assertTrue(wifi > vpn);
    }

    @Test
    public void specificVpnRouteBeatsPhysicalDefaultRoute() {
        int vpn = NetworkRouteResolver.calculateRouteScore(10,
                false, true, false, false, false);
        int wifiDefault = NetworkRouteResolver.calculateRouteScore(0,
                false, false, true, false, false);
        assertTrue(vpn > wifiDefault);
    }

    @Test
    public void explicitSourceAddressWins() {
        int explicit = NetworkRouteResolver.calculateRouteScore(-1,
                true, false, false, true, false);
        int automatic = NetworkRouteResolver.calculateRouteScore(32,
                false, false, true, false, false);
        assertTrue(explicit > automatic);
    }

    @Test
    public void privateIpv4IsLocal() throws UnknownHostException {
        assertEquals(NetworkRouteResolver.Scope.LAN, NetworkRouteResolver.classify(
                InetAddress.getByAddress(new byte[]{10, 0, 0, 7})));
        assertEquals(NetworkRouteResolver.Scope.LAN, NetworkRouteResolver.classify(
                InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 5})));
    }

    @Test
    public void publicAddressesAreFlaggedPublic() throws UnknownHostException {
        assertEquals(NetworkRouteResolver.Scope.PUBLIC, NetworkRouteResolver.classify(
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8})));
        assertEquals(NetworkRouteResolver.Scope.PUBLIC, NetworkRouteResolver.classify(
                ipv6(0x24, 0x0e)));
    }

    /**
     * Unique local IPv6 (fc00::/7) is not routed on the internet. Java's
     * Inet6Address.isSiteLocalAddress() only covers the deprecated fec0::/10, so a
     * naive classification would label ULA as globally reachable and hand the user
     * a URL that can never work from another network.
     */
    @Test
    public void uniqueLocalIpv6IsNotReportedAsPublic() throws UnknownHostException {
        assertTrue("precondition: Java does not treat ULA as site local",
                !ipv6(0xfd, 0x00).isSiteLocalAddress());
        assertEquals(NetworkRouteResolver.Scope.PRIVATE_IPV6,
                NetworkRouteResolver.classify(ipv6(0xfd, 0x00)));
        assertEquals(NetworkRouteResolver.Scope.PRIVATE_IPV6,
                NetworkRouteResolver.classify(ipv6(0xfc, 0x00)));
        assertEquals(NetworkRouteResolver.Scope.PRIVATE_IPV6,
                NetworkRouteResolver.classify(ipv6(0xfe, 0xc0)));
    }

    /**
     * A link local IPv6 address only works with a zone id (fe80::1%wlan0), which a
     * browser cannot put in a URL, so it must never become a share address.
     */
    @Test
    public void linkLocalAndLoopbackAreNeverOfferable() throws UnknownHostException {
        assertNull(NetworkRouteResolver.classify(ipv6(0xfe, 0x80)));
        assertNull(NetworkRouteResolver.classify(ipv6(0xfe, 0xbf)));
        assertNull(NetworkRouteResolver.classify(
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 1})));
        assertNull(NetworkRouteResolver.classify(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})));
    }

    @Test
    public void urlHostBracketsIpv6Only() throws UnknownHostException {
        assertEquals("192.168.1.5", NetworkRouteResolver.formatUrlHost(
                InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 5})));
        assertEquals("[240e:1:2:3:4:5:6:7]", NetworkRouteResolver.formatUrlHost(
                InetAddress.getByAddress(new byte[]{
                        0x24, 0x0e, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7})));
    }

    private static InetAddress ipv6(int first, int second) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{
                (byte) first, (byte) second, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    }
}
