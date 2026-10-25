package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launches an app on another device on the network with DIAL (Discovery and Launch) — the protocol a
 * phone uses to start Netflix on a TV.
 *
 * <p>This is how a dock tile reaches the Fire Stick. The alternative was ADB, which needs debugging left
 * on, a plaintext port open on the LAN and an RSA handshake; DIAL needs none of that. It is a supported
 * feature of the device, already running for casting, and survives updates. The catch is coverage: a device
 * only answers for apps that register a DIAL name, which on Fire OS is Netflix and little else.
 *
 * <p>Everything here runs off the main thread. A launch is fire-and-forget: the input switch has already
 * happened by the time this is called, so a failure leaves the owner on the right HDMI port rather than
 * nowhere.
 */
final class DialClient {

    private static final String TAG = "TvHome.Dial";
    private static final String ST = "urn:dial-multiscreen-org:service:dial:1";
    private static final String PREFS = "dial";
    private static final String KEY_APP_URL = "app_url";
    private static final int SSDP_TIMEOUT_MS = 2500;
    private static final int HTTP_TIMEOUT_MS = 4000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dial");
        t.setDaemon(true);
        return t;
    });

    private DialClient() { }

    /**
     * Starts {@code appName} (a DIAL name such as "Netflix") on the device this launcher is paired with.
     *
     * @param configured the application URL from config.xml, or empty to discover one.
     */
    static void launch(Context ctx, String appName, String configured) {
        if (appName == null || appName.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            String url = cachedAppUrl(app, configured);
            if (url != null && offers(url, appName) && post(url, appName)) return;
            // Either nothing cached, or the device moved: rediscover once and try again.
            String found = discover(app, appName);
            if (found == null) {
                Log.w(TAG, "no DIAL device answered; the input switch still happened");
                return;
            }
            cacheAppUrl(app, found);
            if (!post(found, appName)) Log.w(TAG, "DIAL refused " + appName);
        });
    }

    /** The configured URL, else the one discovery found last time. */
    private static String cachedAppUrl(Context ctx, String configured) {
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        return prefs(ctx).getString(KEY_APP_URL, null);
    }

    private static void cacheAppUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_APP_URL, url).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** POSTs to {@code <appUrl>/<appName>}. DIAL answers 200 or 201 when it has started the app. */
    private static boolean post(String appUrl, String appName) {
        HttpURLConnection c = null;
        try {
            String base = appUrl.endsWith("/") ? appUrl : appUrl + "/";
            c = (HttpURLConnection) new URL(base + appName).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.getOutputStream().close();
            int code = c.getResponseCode();
            Log.i(TAG, "DIAL " + appName + " -> " + code);
            return code == 200 || code == 201;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "DIAL post failed: " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * SSDP M-SEARCH for a DIAL device, then reads the Application-URL header off its description. Needs a
     * multicast lock: Wi-Fi hardware drops multicast otherwise, and the search would simply time out.
     */
    private static String discover(Context ctx, String appName) {
        WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        MulticastSocket sock = null;
        Set<String> local = localAddresses();
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("tvhome-dial");
                lock.setReferenceCounted(true);
                lock.acquire();
            }
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: " + ST + "\r\n\r\n";
            byte[] out = msg.getBytes(StandardCharsets.UTF_8);
            sock = new MulticastSocket();
            sock.setSoTimeout(SSDP_TIMEOUT_MS);
            sock.send(new DatagramPacket(out, out.length, InetAddress.getByName("239.255.255.250"), 1900));

            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + SSDP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                // This television answers DIAL searches too, and on its own service it offers whatever is
                // installed locally — so "has Netflix" cannot tell the two apart. Only its address can:
                // anything answering from one of our own interfaces is us, and is not what the tile meant.
                if (local.contains(in.getAddress().getHostAddress())) continue;
                String location = header(new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8),
                        "location");
                if (location == null) continue;
                String appUrl = applicationUrl(location);
                // Of the remaining devices, take one that actually offers the app; the rest 404 it.
                if (appUrl != null && offers(appUrl, appName)) return appUrl;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "SSDP discovery failed: " + e);
        } finally {
            if (sock != null) sock.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
        return null;
    }

    /** Every address this device holds, so a DIAL answer from itself can be told apart from a real one. */
    private static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    out.add(a.getHostAddress());
                }
            }
        } catch (SocketException | RuntimeException ignored) {
            // With no list we simply cannot exclude ourselves; the app check below still filters most of it.
        }
        return out;
    }

    /** True when this DIAL device knows the app: a GET on it answers 200 rather than 404. */
    private static boolean offers(String appUrl, String appName) {
        HttpURLConnection c = null;
        try {
            String base = appUrl.endsWith("/") ? appUrl : appUrl + "/";
            c = (HttpURLConnection) new URL(base + appName).openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            int code = c.getResponseCode();
            Log.i(TAG, "DIAL " + base + appName + " offers? " + code);
            return code == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The Application-URL response header on the device description — where DIAL launches are POSTed. */
    private static String applicationUrl(String descriptionUrl) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(descriptionUrl).openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.getInputStream().close();
            return c.getHeaderField("Application-URL");
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Case-insensitive lookup of an SSDP response header. */
    private static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
