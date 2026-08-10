package top.weixiansen574.hybridfilexfer.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Android network helpers shared by direct phone transfer and browser sharing. */
public final class NetworkRouteResolver {

    private NetworkRouteResolver() {
    }

    /**
     * Pins an unconnected socket to the physical network which owns the requested
     * source address or route. This prevents a full-tunnel VPN from swallowing a
     * hotspot/Wi-Fi Direct connection.
     */
    public static void bindSocketToBestNetwork(Context context, Socket socket,
                                               InetAddress remoteAddress,
                                               InetAddress bindAddress) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return;
        }
        Network best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Network network : manager.getAllNetworks()) {
            LinkProperties properties = manager.getLinkProperties(network);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (properties == null) {
                continue;
            }
            int score = networkScore(properties, capabilities, remoteAddress, bindAddress);
            if (score > bestScore) {
                best = network;
                bestScore = score;
            }
        }
        if (best != null && bestScore >= 0) {
            best.bindSocket(socket);
        }
    }

    private static int networkScore(LinkProperties properties,
                                    NetworkCapabilities capabilities,
                                    InetAddress remoteAddress,
                                    InetAddress bindAddress) {
        boolean ownsBindAddress = bindAddress == null;
        if (bindAddress != null) {
            ownsBindAddress = false;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (bindAddress.equals(address.getAddress())) {
                    ownsBindAddress = true;
                    break;
                }
            }
            if (!ownsBindAddress) {
                return Integer.MIN_VALUE;
            }
        }

        boolean routeMatches = remoteAddress == null;
        int routePrefix = -1;
        if (remoteAddress != null) {
            for (RouteInfo route : properties.getRoutes()) {
                if (route.matches(remoteAddress)) {
                    routeMatches = true;
                    routePrefix = Math.max(routePrefix, route.getDestination().getPrefixLength());
                }
            }
        }
        if (!routeMatches && bindAddress == null) {
            return Integer.MIN_VALUE;
        }

        return calculateRouteScore(
                routePrefix,
                bindAddress != null && ownsBindAddress,
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    static int calculateRouteScore(int routePrefix, boolean explicitAddress,
                                   boolean vpn, boolean wifi,
                                   boolean ethernet, boolean cellular) {
        // Route specificity is authoritative. Transport preferences only break ties:
        // Wi-Fi /24 beats a full-tunnel VPN /0, while a Tailscale /10 beats a
        // physical default route /0.
        int score = 100 + Math.max(-1, routePrefix) * 1_000;
        if (explicitAddress) {
            score += 1_000_000;
        }
        if (vpn) {
            score -= 200;
        }
        if (wifi) {
            score += 100;
        }
        if (ethernet) {
            score += 80;
        }
        if (cellular) {
            score -= 80;
        }
        return score;
    }

    public static List<LocalAddress> getLocalIpv4Addresses(Context context) throws SocketException {
        Set<String> vpnInterfaces = getVpnInterfaceNames(context);
        ArrayList<LocalAddress> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return result;
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
            } catch (SocketException ignored) {
                // Interfaces can disappear while a VPN or hotspot is changing state.
                continue;
            }
            String name = networkInterface.getName();
            boolean vpn = vpnInterfaces.contains(name) || isVpnName(name);
            boolean cellular = isCellularName(name);
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                        || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                    continue;
                }
                result.add(new LocalAddress(name, address, vpn, cellular));
            }
        }
        Collections.sort(result, Comparator.comparingInt(LocalAddress::score).reversed()
                .thenComparing(item -> item.interfaceName)
                .thenComparing(item -> item.address.getHostAddress()));
        return result;
    }

    private static Set<String> getVpnInterfaceNames(Context context) {
        Set<String> names = new HashSet<>();
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return names;
        }
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties != null && capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                names.add(properties.getInterfaceName());
            }
        }
        return names;
    }

    private static boolean isVpnName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("tun") || lower.startsWith("tap")
                || lower.startsWith("ppp") || lower.startsWith("wg")
                || lower.startsWith("ipsec");
    }

    private static boolean isCellularName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("rmnet") || lower.startsWith("ccmni")
                || lower.startsWith("pdp") || lower.startsWith("wwan");
    }

    public static final class LocalAddress {
        public final String interfaceName;
        public final InetAddress address;
        public final boolean vpn;
        public final boolean cellular;

        public LocalAddress(String interfaceName, InetAddress address,
                            boolean vpn, boolean cellular) {
            this.interfaceName = interfaceName;
            this.address = address;
            this.vpn = vpn;
            this.cellular = cellular;
        }

        public boolean isDefaultEnabled() {
            return !vpn && !cellular;
        }

        private int score() {
            int value = address.isSiteLocalAddress() ? 100 : 20;
            if (address.isLinkLocalAddress()) {
                value += 30;
            }
            if (vpn) {
                value -= 200;
            }
            if (cellular) {
                value -= 100;
            }
            String lower = interfaceName.toLowerCase(Locale.ROOT);
            if (lower.startsWith("wlan") || lower.startsWith("ap")
                    || lower.startsWith("p2p")) {
                value += 50;
            } else if (lower.startsWith("eth") || lower.startsWith("usb")) {
                value += 30;
            }
            return value;
        }

        public String displayLabel() {
            return interfaceName + " · " + address.getHostAddress();
        }
    }
}
