package com.winlator.star.cast;

import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * Dead-simple single-file HTTP/1.1 server for casting: a Chromecast fetches the media over the LAN, so
 * we host one file on a background thread with basic HTTP Range support (the receiver seeks). Serves
 * only the one file it was started with; not a general server.
 */
public class HttpFileServer {
    private static final String TAG = "HttpFileServer";
    private final File file;
    private final String contentType;
    private ServerSocket server;
    private volatile boolean running = false;
    private int port = -1;

    public HttpFileServer(File file, String contentType) { this.file = file; this.contentType = contentType; }

    public int start() throws Exception {
        server = new ServerSocket(0);           // any free port
        port = server.getLocalPort();
        running = true;
        new Thread(this::acceptLoop, "cast-http").start();
        return port;
    }

    public int getPort() { return port; }

    /** The phone's Wi-Fi IPv4 address, for building the URL the TV fetches from. */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address
                            && a.getHostAddress() != null && a.getHostAddress().startsWith("192.168")) {
                        return a.getHostAddress();
                    }
                }
            }
            // fall back to any non-loopback IPv4
            ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs = ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) { Log.w(TAG, "localIpv4 failed", e); }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                new Thread(() -> serve(client), "cast-http-conn").start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept failed", e);
                break;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket c = client; RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()));
            String line = r.readLine();             // request line
            long start = 0, end = file.length() - 1;
            boolean partial = false;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("range:")) {
                    partial = true;
                    String rng = h.substring(h.indexOf('=') + 1).trim();
                    String[] parts = rng.split("-");
                    try {
                        start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
            }
            long total = file.length();
            long length = end - start + 1;
            OutputStream os = c.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            os.write(head.toString().getBytes("UTF-8"));

            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                os.write(buf, 0, n);
                remaining -= n;
            }
            os.flush();
        } catch (Exception e) {
            // client hang-ups are normal; keep quiet
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }
}
