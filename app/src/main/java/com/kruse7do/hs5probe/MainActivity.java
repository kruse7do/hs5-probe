package com.kruse7do.hs5probe;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 1001;
    private static final int HTTP_PORT = 18080;
    private static final ComponentName HQ_VEHICLE_SERVICE = new ComponentName(
            "com.faw.hqzl3.hqvehicleservice",
            "com.faw.hqzl3.hqvehicleservice.HQVehicleService");
    private final StringBuilder report = new StringBuilder();
    private TextView output;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private volatile ServerSocket httpServer;
    private volatile Thread httpThread;
    private boolean vendorServiceBound;
    private boolean vendorBindRequested;
    private final ServiceConnection vendorConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            vendorServiceBound = true;
            String descriptor;
            try { descriptor = binder.getInterfaceDescriptor(); }
            catch (Throwable t) { descriptor = "ERROR: " + describe(t); }
            appendActive("T-BOX BIND RESULT: connected component=" + name.flattenToShortString());
            appendActive("T-BOX binder class=" + binder.getClass().getName());
            appendActive("T-BOX binder descriptor=" + descriptor);
            appendActive("T-BOX binder alive=" + binder.isBinderAlive() + ", ping=" + binder.pingBinder());
            appendActive("No openAP transaction was sent; descriptor must be analysed first.");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> unbindVendorService("descriptor captured"), 3000);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vendorServiceBound = false;
            vendorBindRequested = false;
            appendActive("T-BOX service disconnected: " + name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            vendorServiceBound = false;
            vendorBindRequested = false;
            appendActive("T-BOX binding died: " + name.flattenToShortString());
        }

        @Override
        public void onNullBinding(ComponentName name) {
            vendorServiceBound = true;
            appendActive("T-BOX BIND RESULT: null binding from " + name.flattenToShortString());
            unbindVendorService("null binding");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        runReadOnlyProbe();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 18);

        LinearLayout bar = new LinearLayout(this);
        Button refresh = button("重新检测");
        Button save = button("保存报告");
        bar.addView(refresh);
        bar.addView(save);

        LinearLayout tboxBar = new LinearLayout(this);
        Button bindTbox = button("探测T-BOX原厂服务");
        Button http = button("启动/停止HTTP自检");
        Button hotspot = button("标准Android热点（对照）");
        tboxBar.addView(bindTbox);
        tboxBar.addView(http);
        tboxBar.addView(hotspot);

        TextView warning = new TextView(this);
        warning.setText("先手动打开原车热点，再启动HTTP自检并用iPhone访问。标准Android热点仅是对照项，失败不代表T-BOX方案失败。服务探测只绑定并读取接口描述符，不调用openAP。");
        warning.setTextSize(16);
        warning.setPadding(0, 12, 0, 12);

        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(bar);
        root.addView(tboxBar);
        root.addView(warning);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        refresh.setOnClickListener(v -> runReadOnlyProbe());
        hotspot.setOnClickListener(v -> requestAndTestHotspot());
        save.setOnClickListener(v -> saveReport());
        bindTbox.setOnClickListener(v -> probeAndBindVendorService());
        http.setOnClickListener(v -> toggleHttpServer());
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void runReadOnlyProbe() {
        report.setLength(0);
        line("HS5 Android Environment Probe 0.2.1");
        line("Time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        section("Android / device");
        line("SDK_INT", Build.VERSION.SDK_INT);
        line("RELEASE", Build.VERSION.RELEASE);
        line("MODEL", Build.MODEL);
        line("MANUFACTURER", Build.MANUFACTURER);
        line("BRAND", Build.BRAND);
        line("PRODUCT", Build.PRODUCT);
        line("DEVICE", Build.DEVICE);
        line("HARDWARE", Build.HARDWARE);
        line("BOARD", Build.BOARD);
        line("FINGERPRINT", Build.FINGERPRINT);
        line("SUPPORTED_ABIS", join(Build.SUPPORTED_ABIS));
        line("UID", Process.myUid());
        line("Android ID", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));

        section("System features");
        PackageManager pm = getPackageManager();
        feature(pm, PackageManager.FEATURE_WIFI);
        feature(pm, PackageManager.FEATURE_WIFI_DIRECT);
        feature(pm, PackageManager.FEATURE_USB_HOST);
        feature(pm, PackageManager.FEATURE_BLUETOOTH);
        feature(pm, PackageManager.FEATURE_BLUETOOTH_LE);
        feature(pm, PackageManager.FEATURE_LOCATION);

        section("Permissions");
        permission(Manifest.permission.ACCESS_WIFI_STATE);
        permission(Manifest.permission.CHANGE_WIFI_STATE);
        permission(Manifest.permission.ACCESS_NETWORK_STATE);
        permission(Manifest.permission.ACCESS_FINE_LOCATION);

        section("Android services");
        Object rawWifi = safeService(Context.WIFI_SERVICE);
        line("WIFI_SERVICE class", className(rawWifi));
        line("WifiManager unavailable", rawWifi == null);
        Object connectivity = safeService(Context.CONNECTIVITY_SERVICE);
        line("CONNECTIVITY_SERVICE class", className(connectivity));
        Object p2p = safeService(Context.WIFI_P2P_SERVICE);
        line("WIFI_P2P_SERVICE class", className(p2p));
        line("WifiP2pManager available", p2p instanceof WifiP2pManager);

        if (rawWifi instanceof WifiManager) {
            inspectWifi((WifiManager) rawWifi);
        }
        if (connectivity instanceof ConnectivityManager) {
            inspectConnectivity((ConnectivityManager) connectivity);
        }
        inspectNetworkInterfaces();
        inspectVendorService();

        section("Interpretation hints");
        if (rawWifi == null) {
            line("RESULT", "Context.WIFI_SERVICE returned null. DiPlay cannot fix this with a permission request alone; it needs a non-WifiManager network path or a vehicle-specific bridge.");
        } else {
            line("RESULT", "WifiManager exists. Use the active hotspot test to learn whether LocalOnlyHotspot is blocked.");
        }
        render();
    }

    private Object safeService(String name) {
        try {
            return getSystemService(name);
        } catch (Throwable t) {
            line(name + " exception", describe(t));
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void inspectWifi(WifiManager wifi) {
        section("WifiManager details");
        try { line("isWifiEnabled", wifi.isWifiEnabled()); } catch (Throwable t) { line("isWifiEnabled exception", describe(t)); }
        try { line("wifiState", wifi.getWifiState()); } catch (Throwable t) { line("wifiState exception", describe(t)); }
        try { line("connectionInfo", String.valueOf(wifi.getConnectionInfo())); } catch (Throwable t) { line("connectionInfo exception", describe(t)); }
        try {
            DhcpInfo d = wifi.getDhcpInfo();
            line("dhcpInfo", String.valueOf(d));
        } catch (Throwable t) { line("dhcpInfo exception", describe(t)); }
        line("LocalOnlyHotspot API present", Build.VERSION.SDK_INT >= 26);
    }

    @SuppressWarnings("deprecation")
    private void inspectConnectivity(ConnectivityManager cm) {
        section("ConnectivityManager details");
        try {
            NetworkInfo info = cm.getActiveNetworkInfo();
            line("activeNetwork", String.valueOf(info));
            line("activeConnected", info != null && info.isConnected());
        } catch (Throwable t) { line("activeNetwork exception", describe(t)); }
        if (Build.VERSION.SDK_INT >= 23) {
            try { line("activeNetworkHandle", String.valueOf(cm.getActiveNetwork())); }
            catch (Throwable t) { line("activeNetworkHandle exception", describe(t)); }
        }
    }

    private void inspectNetworkInterfaces() {
        section("Network interfaces");
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                StringBuilder addresses = new StringBuilder();
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (addresses.length() > 0) addresses.append(", ");
                    addresses.append(a.getHostAddress());
                }
                line(nif.getName(), "up=" + nif.isUp() + ", loopback=" + nif.isLoopback() + ", addresses=" + addresses);
            }
            if (interfaces.isEmpty()) line("interfaces", "none");
        } catch (Throwable t) {
            line("interfaces exception", describe(t));
        }
    }

    private void inspectVendorService() {
        section("FAW T-BOX candidate service");
        line("component", HQ_VEHICLE_SERVICE.flattenToString());
        try {
            ServiceInfo info = getPackageManager().getServiceInfo(HQ_VEHICLE_SERVICE, PackageManager.GET_META_DATA);
            line("service exists", true);
            line("enabled", info.enabled);
            line("exported", info.exported);
            line("required permission", info.permission == null ? "none" : info.permission);
            line("process", info.processName);
            line("service application UID", info.applicationInfo == null ? "unknown" : info.applicationInfo.uid);
            line("our UID", Process.myUid());
        } catch (PackageManager.NameNotFoundException e) {
            line("service exists", false);
            line("lookup result", "NameNotFoundException");
        } catch (Throwable t) {
            line("service lookup exception", describe(t));
        }
    }

    private void probeAndBindVendorService() {
        inspectVendorService();
        render();
        if (vendorBindRequested || vendorServiceBound) {
            appendActive("T-BOX bind is already active or pending.");
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(HQ_VEHICLE_SERVICE);
        try {
            vendorBindRequested = bindService(intent, vendorConnection, Context.BIND_AUTO_CREATE);
            appendActive("T-BOX bindService returned=" + vendorBindRequested);
            if (vendorBindRequested) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (vendorBindRequested && !vendorServiceBound) {
                        appendActive("T-BOX BIND RESULT: no callback within 8 seconds (timeout).");
                        unbindVendorService("timeout");
                    }
                }, 8000);
            }
        } catch (SecurityException e) {
            vendorBindRequested = false;
            appendActive("T-BOX BIND RESULT: SecurityException=" + describe(e));
        } catch (Throwable t) {
            vendorBindRequested = false;
            appendActive("T-BOX BIND RESULT: exception=" + describe(t));
        }
    }

    private void unbindVendorService(String reason) {
        if (!vendorBindRequested && !vendorServiceBound) return;
        try {
            unbindService(vendorConnection);
            appendActive("T-BOX service unbound: " + reason);
        } catch (Throwable t) {
            appendActive("T-BOX unbind exception: " + describe(t));
        } finally {
            vendorBindRequested = false;
            vendorServiceBound = false;
        }
    }

    private void toggleHttpServer() {
        if (httpServer == null || httpServer.isClosed()) startHttpServer();
        else stopHttpServer("stopped by user");
    }

    private void startHttpServer() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
            httpServer = server;
            appendActive("HTTP SELF-TEST: listening on 0.0.0.0:" + HTTP_PORT);
            appendHttpUrls();
            httpThread = new Thread(() -> httpLoop(server), "hs5-http-probe");
            httpThread.start();
        } catch (Throwable t) {
            httpServer = null;
            appendActive("HTTP SELF-TEST: start failed=" + describe(t));
        }
    }

    private void appendHttpUrls() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        appendActive("Open on iPhone: http://" + address.getHostAddress() + ":" + HTTP_PORT + "/  (interface " + nif.getName() + ")");
                    }
                }
            }
        } catch (Throwable t) {
            appendActive("HTTP URL enumeration failed=" + describe(t));
        }
    }

    private void httpLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(3000);
                serveHttpClient(client);
            } catch (Throwable t) {
                if (!server.isClosed()) postActive("HTTP SELF-TEST: server exception=" + describe(t));
            }
        }
    }

    private void serveHttpClient(Socket client) {
        String remote = String.valueOf(client.getRemoteSocketAddress());
        String requestLine = "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            requestLine = reader.readLine();
            String body = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>"
                    + "<body><h1>HS5 T-BOX link works</h1><p>iPhone can reach the Android head unit.</p>"
                    + "<p>Client: " + escapeHtml(remote) + "</p><p>Time: "
                    + escapeHtml(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    + "</p></body></html>";
            byte[] bodyBytes = body.getBytes("UTF-8");
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + bodyBytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(headers.getBytes("US-ASCII"));
            out.write(bodyBytes);
            out.flush();
            postActive("HTTP HIT: client=" + remote + ", request=" + requestLine);
        } catch (Throwable t) {
            postActive("HTTP client error from " + remote + "=" + describe(t));
        } finally {
            try { client.close(); } catch (Throwable ignored) { }
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void stopHttpServer(String reason) {
        ServerSocket server = httpServer;
        httpServer = null;
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) { }
            appendActive("HTTP SELF-TEST: stopped — " + reason);
        }
    }

    private void postActive(String text) {
        runOnUiThread(() -> appendActive(text));
    }

    private void requestAndTestHotspot() {
        if (Build.VERSION.SDK_INT < 26) {
            appendActive("LocalOnlyHotspot unavailable: Android API below 26.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        testHotspot();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) testHotspot();
            else appendActive("Location permission denied; Android 9 requires it for LocalOnlyHotspot.");
        }
    }

    private void testHotspot() {
        Object service = safeService(Context.WIFI_SERVICE);
        if (!(service instanceof WifiManager)) {
            appendActive("ACTIVE RESULT: WifiManager is null/unavailable; startLocalOnlyHotspot was not called.");
            return;
        }
        appendActive("ACTIVE TEST: calling WifiManager.startLocalOnlyHotspot...");
        try {
            ((WifiManager) service).startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    appendActive("ACTIVE RESULT: onStarted — LocalOnlyHotspot works.");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> closeHotspot("8-second test complete"), 8000);
                }

                @Override
                public void onStopped() {
                    hotspotReservation = null;
                    appendActive("ACTIVE RESULT: onStopped callback received.");
                }

                @Override
                public void onFailed(int reason) {
                    appendActive("ACTIVE RESULT: onFailed reason=" + reason + " (1=NO_CHANNEL, 2=GENERIC, 3=INCOMPATIBLE_MODE, 4=TETHERING_DISALLOWED)");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            appendActive("ACTIVE RESULT: exception=" + describe(t));
        }
    }

    private void closeHotspot(String reason) {
        if (hotspotReservation != null) {
            try { hotspotReservation.close(); } catch (Throwable ignored) { }
            hotspotReservation = null;
            appendActive("Hotspot reservation closed: " + reason);
        }
    }

    private void saveReport() {
        try {
            File dir = getExternalFilesDir("reports");
            if (dir == null) dir = new File(getFilesDir(), "reports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create report directory");
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "hs5-probe-" + stamp + ".txt");
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(report.toString().getBytes("UTF-8"));
            stream.close();
            Toast.makeText(this, "报告已保存：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            appendActive("Saved report: " + file.getAbsolutePath());
        } catch (Throwable t) {
            appendActive("Save failed: " + describe(t));
        }
    }

    private void feature(PackageManager pm, String name) { line(name, pm.hasSystemFeature(name)); }
    private void permission(String name) { line(name, checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"); }
    private String className(Object value) { return value == null ? "null" : value.getClass().getName(); }
    private String join(String[] values) {
        if (values == null) return "null";
        StringBuilder b = new StringBuilder();
        for (String value : values) { if (b.length() > 0) b.append(", "); b.append(value); }
        return b.toString();
    }
    private String describe(Throwable t) { return t.getClass().getName() + ": " + String.valueOf(t.getMessage()); }
    private void section(String title) { report.append("\n== ").append(title).append(" ==\n"); }
    private void line(String key, Object value) { report.append(key).append(": ").append(String.valueOf(value)).append('\n'); }
    private void line(String text) { report.append(text).append('\n'); }
    private void render() { output.setText(report.toString()); }
    private void appendActive(String text) { line(text); render(); }

    @Override
    protected void onDestroy() {
        closeHotspot("activity destroyed");
        stopHttpServer("activity destroyed");
        unbindVendorService("activity destroyed");
        super.onDestroy();
    }
}
