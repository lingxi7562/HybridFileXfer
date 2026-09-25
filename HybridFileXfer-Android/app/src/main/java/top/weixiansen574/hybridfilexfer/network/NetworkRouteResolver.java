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
                result.add(new LocalAddress(name, address, vpn, cellular, legacyScope(address)));
            }
        }
        Collections.sort(result, Comparator.comparingInt(LocalAddress::score).reversed()
                .thenComparing(item -> item.interfaceName)
                .thenComparing(item -> item.address.getHostAddress()));
        return result;
    }

    /**
     * Addresses that can be advertised in a browser share URL.
     *
     * <p>Unlike {@link #getLocalIpv4Addresses} this includes IPv6, because a
     * globally routable IPv6 address is what lets a share work from a different
     * network. Addresses that can never appear in a browser URL are dropped:
     * link-local IPv6 needs a zone id ({@code fe80::1%wlan0}) which browsers
     * cannot express, and IPv4 link-local is never routable.
     *
     * <p>Local addresses sort first, so a public address is never preselected.
     */
    public static List<LocalAddress> getShareAddresses(Context context) throws SocketException {
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
                continue;
            }
            String name = networkInterface.getName();
            boolean vpn = vpnInterfaces.contains(name) || isVpnName(name);
            boolean cellular = isCellularName(name);
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                Scope scope = classify(address);
                if (scope == null) {
                    continue;
                }
                result.add(new LocalAddress(name, address, vpn, cellular, scope));
            }
        }
        Collections.sort(result, Comparator.comparingInt(LocalAddress::score).reversed()
                .thenComparing(item -> item.interfaceName)
                .thenComparing(item -> item.address.getHostAddress()));
        return result;
    }

    /**
     * Classifies an address for sharing, or returns {@code null} when it can
     * never be reached from a browser.
     */
    static Scope classify(InetAddress address) {
        if (address.isLoopbackAddress() || address.isMulticastAddress()
                || address.isAnyLocalAddress()) {
            return null;
        }
        if (address instanceof Inet4Address) {
            if (address.isLinkLocalAddress()) {
                return null;
            }
            return address.isSiteLocalAddress() ? Scope.LAN : Scope.PUBLIC;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return null;
        }
        // Compare on masked ints: a byte of 0xfe sign extends to -2, so a plain
        // "bytes[0] == 0xfe" would never match.
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if ((first & 0xfe) == 0xfc) {
            // fc00::/7 unique local. Inet6Address.isSiteLocalAddress() only covers
            // the deprecated fec0::/10, so ULA has to be matched on the bytes or a
            // local-only address would be advertised as globally reachable.
            return Scope.PRIVATE_IPV6;
        }
        if (first == 0xfe && (second & 0xc0) == 0x80) {
            // fe80::/10 link local needs a zone id (fe80::1%wlan0) which a browser
            // URL cannot carry.
            return null;
        }
        if (first == 0xfe && (second & 0xc0) == 0xc0) {
            // Deprecated fec0::/10 site local, mirroring Inet6Address.isSiteLocalAddress().
            return Scope.PRIVATE_IPV6;
        }
        return Scope.PUBLIC;
    }

    private static Scope legacyScope(InetAddress address) {
        return address.isSiteLocalAddress() ? Scope.LAN : Scope.PUBLIC;
    }

    /**
     * Formats an address as the host part of a URL. IPv6 literals must be
     * bracketed, and a zone id is not valid in a URL host.
     */
    public static String formatUrlHost(InetAddress address) {
        String host = address.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
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

    /** How far an address can reach, which decides whether a share URL is usable. */
    public enum Scope {
        /** IPv4 RFC1918: reachable inside the current local network. */
        LAN,
        /** IPv6 unique local (fc00::/7): local only, never routed on the internet. */
        PRIVATE_IPV6,
        /** Globally routable: the only scope that can work from another network. */
        PUBLIC
    }

    public static final class LocalAddress {
        public final String interfaceName;
        public final InetAddress address;
        public final boolean vpn;
        public final boolean cellular;
        public final Scope scope;

        public LocalAddress(String interfaceName, InetAddress address,
                            boolean vpn, boolean cellular, Scope scope) {
            this.interfaceName = interfaceName;
            this.address = address;
            this.vpn = vpn;
            this.cellular = cellular;
            this.scope = scope;
        }

        public boolean isDefaultEnabled() {
            return !vpn && !cellular;
        }

        private int score() {
            int value = address.isSiteLocalAddress() ? 100 : 20;
            if (scope == Scope.PRIVATE_IPV6) {
                // Unique local IPv6 is not internet routable, but it still behaves
                // like a local address, so it ranks above a public one.
                value = 60;
            }
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
