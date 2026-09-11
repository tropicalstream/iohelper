// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;


import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Objects;

/**
 * Automatic discovery of ADB daemons.
 */
// Copyright 2020 南宫雪珊
// Copyright 2022 Muntashir Al-Islam
// Based on https://android.googlesource.com/platform/packages/modules/adb/+/eddd2d3a386a83f5d1e14f87a318adef4c2f1a9d/adb_mdns.cpp
public class AdbMdns {
    public static final String SERVICE_TYPE_ADB = "adb";
    public static final String SERVICE_TYPE_TLS_PAIRING = "adb-tls-pairing";
    public static final String SERVICE_TYPE_TLS_CONNECT = "adb-tls-connect";

    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceType {
    }

    public interface OnAdbDaemonDiscoveredListener {
        void onPortChanged(InetAddress hostAddress, int port);
    }

    private final Context mContext;
    private final String mServiceType;
    private final OnAdbDaemonDiscoveredListener mAdbDaemonDiscoveredListener;
    private final NsdManager.DiscoveryListener mDiscoveryListener;
    private final NsdManager mNsdManager;

    private boolean mRegistered;
    private boolean mRunning;
    private String mServiceName;

    public AdbMdns(Context context, @ServiceType String serviceType,
                   OnAdbDaemonDiscoveredListener portChangeListener) {
        mContext = Objects.requireNonNull(context);
        mServiceType = String.format("_%s._tcp", Objects.requireNonNull(serviceType));
        mAdbDaemonDiscoveredListener = Objects.requireNonNull(portChangeListener);
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mDiscoveryListener = new DiscoveryListener(this);
    }

    public void start() {
        if (mRunning) return;
        mRunning = true;
        if (!mRegistered) {
            mNsdManager.discoverServices(mServiceType, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        }
    }

    public void stop() {
        if (!mRunning) return;
        mRunning = false;
        if (mRegistered) {
            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
        }
    }

    public boolean isRunning() {
        return mRunning;
    }

    private void onDiscoveryStart() {
        mRegistered = true;
    }

    private void onDiscoverStop() {
        mRegistered = false;
    }

    private void onServiceFound(NsdServiceInfo serviceInfo) {
        mNsdManager.resolveService(serviceInfo, new ResolveListener(this));
    }

    private void onServiceLost(NsdServiceInfo serviceInfo) {
        if (mServiceName != null && mServiceName.equals(serviceInfo.getServiceName())) {
            mAdbDaemonDiscoveredListener.onPortChanged(serviceInfo.getHost(), -1);
        }
    }

    private void onServiceResolved(NsdServiceInfo serviceInfo) {
        if (!mRunning) return;
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress inetAddress : Collections.list(networkInterface.getInetAddresses())) {
                    String inetHost = inetAddress.getHostAddress();
                    if (Objects.equals(inetHost, serviceInfo.getHost().getHostAddress())
                            && isPortAvailable(serviceInfo.getPort())) {
                        mServiceName = serviceInfo.getServiceName();
                        mAdbDaemonDiscoveredListener.onPortChanged(serviceInfo.getHost(), serviceInfo.getPort());
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(AndroidUtils.getHostIpAddress(mContext), port), 1);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static class DiscoveryListener implements NsdManager.DiscoveryListener {
        private final AdbMdns mAdbMdns;

        private DiscoveryListener(AdbMdns adbMdns) {
            mAdbMdns = adbMdns;
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            mAdbMdns.onDiscoveryStart();
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            mAdbMdns.onDiscoverStop();
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            mAdbMdns.onServiceFound(serviceInfo);
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            mAdbMdns.onServiceLost(serviceInfo);
        }
    }

    private static class ResolveListener implements NsdManager.ResolveListener {
        private final AdbMdns mAdbMdns;

        private ResolveListener(AdbMdns adbMdns) {
            mAdbMdns = adbMdns;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            mAdbMdns.onServiceResolved(serviceInfo);
        }
    }
}
