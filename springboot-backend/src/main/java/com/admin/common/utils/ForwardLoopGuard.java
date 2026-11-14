package com.admin.common.utils;

import com.admin.entity.Node;
import org.apache.http.conn.util.InetAddressUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Finds destination ports which must not become listeners on the same node. */
public final class ForwardLoopGuard {
    private ForwardLoopGuard() {}

    public static Set<Integer> forbiddenPorts(String remoteAddr, Node listenerNode,
                                               Long destinationNodeId, String... listenAddresses) {
        if (remoteAddr == null || listenerNode == null) return Collections.emptySet();
        Set<String> addresses = new LinkedHashSet<>();
        addAddresses(addresses, listenerNode.getIp());
        addAddresses(addresses, listenerNode.getServerIp());
        for (String address : listenAddresses) addAddresses(addresses, address);

        Set<Integer> ports = new LinkedHashSet<>();
        for (String target : remoteAddr.split(",")) {
            target = target.trim();
            int separator = target.lastIndexOf(':');
            if (separator <= 0) continue;
            String host = normalizeHost(target.substring(0, separator));
            int port;
            try {
                port = Integer.parseInt(target.substring(separator + 1).trim());
            } catch (NumberFormatException e) {
                continue; // Invalid destinations are handled by the existing node validation.
            }
            if (port < 1 || port > 65535) continue;
            // Loopback/unspecified destinations are local to the node making the final dial,
            // not necessarily the ingress node of a two-node tunnel.
            boolean local = isLocalAlias(host)
                    ? Objects.equals(listenerNode.getId(), destinationNodeId)
                    : addresses.contains(host);
            if (local) ports.add(port);
        }
        return ports;
    }

    private static void addAddresses(Set<String> addresses, String raw) {
        if (raw == null) return;
        for (String address : raw.split("[,\\s]+")) {
            String host = normalizeHost(address);
            if (!host.isEmpty() && !isLocalAlias(host)) addresses.add(host);
        }
    }

    private static String normalizeHost(String host) {
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        InetAddress address = literalAddress(host);
        return address == null ? host : address.getHostAddress();
    }

    private static boolean isLocalAlias(String host) {
        if ("localhost".equals(host)) return true;
        InetAddress address = literalAddress(host);
        return address != null && (address.isAnyLocalAddress() || address.isLoopbackAddress());
    }

    private static InetAddress literalAddress(String host) {
        // Do not resolve arbitrary hostnames on the panel: its DNS/network may differ
        // from the node's. Configured node hostnames are compared directly instead.
        if (!"0.0.0.0".equals(host) && !InetAddressUtils.isIPv4Address(host)
                && !(host.contains(":") && host.matches("[0-9a-fA-F:.]+"))) return null;
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
