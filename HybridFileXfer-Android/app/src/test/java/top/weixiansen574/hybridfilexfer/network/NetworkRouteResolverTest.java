package top.weixiansen574.hybridfilexfer.network;

import org.junit.Test;

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
}
