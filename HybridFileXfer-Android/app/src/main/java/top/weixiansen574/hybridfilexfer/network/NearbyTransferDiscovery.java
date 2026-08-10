package top.weixiansen574.hybridfilexfer.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.InetAddress;
import java.util.ArrayDeque;

/** DNS-SD discovery for app-to-app transfers on Wi-Fi and phone hotspots. */
public final class NearbyTransferDiscovery {
    private static final String SERVICE_TYPE = "_hybridfilexfer._tcp.";

    private final Context context;
    private final NsdManager nsdManager;
    private final ArrayDeque<NsdServiceInfo> resolutionQueue = new ArrayDeque<>();
    private volatile boolean resolving;
    private volatile long discoveryGeneration;
    private volatile long advertisingGeneration;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private volatile String registeredName;

    public NearbyTransferDiscovery(Context context) {
        this.context = context.getApplicationContext();
        nsdManager = this.context.getSystemService(NsdManager.class);
    }

    public synchronized void advertise(int port) {
        stopAdvertising();
        long generation = advertisingGeneration;
        if (nsdManager == null) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(Build.MANUFACTURER + " " + Build.MODEL);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (NearbyTransferDiscovery.this) {
                    if (generation == advertisingGeneration) {
                        registrationListener = null;
                    }
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                synchronized (NearbyTransferDiscovery.this) {
                    if (generation == advertisingGeneration) {
                        registeredName = serviceInfo.getServiceName();
                    }
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                synchronized (NearbyTransferDiscovery.this) {
                    if (generation == advertisingGeneration) {
                        registeredName = null;
                    }
                }
            }
        };
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (RuntimeException e) {
            registrationListener = null;
        }
    }

    public synchronized void discover(Callback callback) {
        stopDiscovery();
        long generation = discoveryGeneration;
        if (nsdManager == null) {
            callback.onError();
            return;
        }
        acquireMulticastLock();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                if (generation == discoveryGeneration) {
                    callback.onStarted();
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (generation != discoveryGeneration
                        || !sameServiceType(serviceInfo.getServiceType())
                        || serviceInfo.getServiceName().equals(registeredName)) {
                    return;
                }
                resolve(serviceInfo, callback, generation);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (generation == discoveryGeneration) {
                    callback.onLost(serviceInfo.getServiceName());
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                if (generation == discoveryGeneration) {
                    releaseMulticastLock();
                }
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (generation == discoveryGeneration) {
                    callback.onError();
                    safeStopDiscovery();
                    releaseMulticastLock();
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                if (generation == discoveryGeneration) {
                    releaseMulticastLock();
                }
            }
        };
        try {
            nsdManager.discoverServices(
                    SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            discoveryListener = null;
            releaseMulticastLock();
            callback.onError();
        }
    }

    private synchronized void resolve(NsdServiceInfo serviceInfo, Callback callback,
                                      long generation) {
        if (generation != discoveryGeneration) {
            return;
        }
        for (NsdServiceInfo queued : resolutionQueue) {
            if (queued.getServiceName().equals(serviceInfo.getServiceName())) {
                return;
            }
        }
        resolutionQueue.add(serviceInfo);
        resolveNext(callback, generation);
    }

    private synchronized void resolveNext(Callback callback, long generation) {
        if (generation != discoveryGeneration || resolving
                || resolutionQueue.isEmpty() || nsdManager == null) {
            return;
        }
        NsdServiceInfo serviceInfo = resolutionQueue.removeFirst();
        resolving = true;
        NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo failed, int errorCode) {
                synchronized (NearbyTransferDiscovery.this) {
                    if (generation != discoveryGeneration) {
                        return;
                    }
                    resolving = false;
                    resolveNext(callback, generation);
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolved) {
                synchronized (NearbyTransferDiscovery.this) {
                    if (generation != discoveryGeneration) {
                        return;
                    }
                    resolving = false;
                    InetAddress host = resolved.getHost();
                    if (host != null && resolved.getPort() > 0) {
                        callback.onFound(new Device(resolved.getServiceName(),
                                host.getHostAddress(), resolved.getPort()));
                    }
                    resolveNext(callback, generation);
                }
            }
        };
        try {
            nsdManager.resolveService(serviceInfo, listener);
        } catch (RuntimeException ignored) {
            resolving = false;
            resolveNext(callback, generation);
        }
    }

    private static boolean sameServiceType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.endsWith(".") ? type : type + ".";
        return SERVICE_TYPE.equalsIgnoreCase(normalized);
    }

    public synchronized void stopAdvertising() {
        advertisingGeneration++;
        NsdManager.RegistrationListener current = registrationListener;
        registrationListener = null;
        registeredName = null;
        if (nsdManager != null && current != null) {
            try {
                nsdManager.unregisterService(current);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public synchronized void stopDiscovery() {
        discoveryGeneration++;
        safeStopDiscovery();
        releaseMulticastLock();
        resolutionQueue.clear();
        resolving = false;
    }

    private synchronized void safeStopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) {
            }
        }
        discoveryListener = null;
    }

    private synchronized void acquireMulticastLock() {
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager != null && multicastLock == null) {
            try {
                multicastLock = wifiManager.createMulticastLock("hybrid-file-xfer-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
                multicastLock = null;
            }
        }
    }

    private synchronized void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) {
                    multicastLock.release();
                }
            } catch (RuntimeException ignored) {
            }
            multicastLock = null;
        }
    }

    public synchronized void close() {
        stopDiscovery();
        stopAdvertising();
    }

    public interface Callback {
        void onStarted();
        void onFound(Device device);
        void onLost(String name);
        void onError();
    }

    public static final class Device {
        public final String name;
        public final String address;
        public final int port;

        Device(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        public String displayLabel() {
            return name + " · " + address;
        }
    }
}
