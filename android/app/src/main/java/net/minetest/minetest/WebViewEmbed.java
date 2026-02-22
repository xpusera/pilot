/*
Luanti WebView Embed System
SPDX-License-Identifier: LGPL-2.1-or-later
Copyright (C) 2024 Luanti Contributors

Provides HTML/CSS/JS embedding capabilities for mods via WebView.
Supports overlay display and texture rendering for entities/blocks.
Includes a local HTTP server for CORS-free content hosting.
*/

package net.minetest.minetest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Keep;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Keep
@SuppressWarnings("unused")
public class WebViewEmbed {
    private static final String TAG = "WebViewEmbed";

    // Use -1 for width or height to get MATCH_PARENT (fullscreen on that axis)
    public static final int SIZE_FULLSCREEN = -1;

    private static WebViewEmbed instance;
    private final Context context;
    private final Handler mainHandler;
    private final ConcurrentHashMap<Integer, WebViewInstance> webViews;
    private final AtomicInteger nextId;
    private final CopyOnWriteArrayList<LuaMessage> pendingMessages;
    private FrameLayout containerView;
    private boolean initialized = false;
    private boolean prewarmed = false;
    private WebView prewarmWebView;
    private Activity activity;

    // Pending WebView PermissionRequests waiting for Android OS permission grant
    // Key = request code passed to ActivityCompat.requestPermissions()
    private static final int WV_PERM_BASE_CODE = 2001;
    private final ConcurrentHashMap<Integer, PermissionRequest> pendingWebViewPermissions = new ConcurrentHashMap<>();
    private final AtomicInteger wvPermCodeCounter = new AtomicInteger(WV_PERM_BASE_CODE);

    // Local HTTP server for CORS-free content hosting
    private LocalContentServer localServer;
    private int localServerPort = 0;

    // Runnables for debouncing position/size updates per webview
    private final ConcurrentHashMap<Integer, Runnable> pendingPositionUpdates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Runnable> pendingSizeUpdates = new ConcurrentHashMap<>();

    public static class LuaMessage {
        public final int webViewId;
        public final String eventType;
        public final String data;
        public final long timestamp;

        public LuaMessage(int webViewId, String eventType, String data) {
            this.webViewId = webViewId;
            this.eventType = eventType;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class WebViewInstance {
        public final int id;
        public WebView webView;
        public int x, y, width, height;
        public boolean visible;
        public boolean isTextureMode;
        public Bitmap textureBitmap;
        public ByteBuffer textureBuffer;
        public volatile boolean textureNeedsUpdate;
        public final AtomicLong lastContentChange;

        public WebViewInstance(int id) {
            this.id = id;
            this.visible = true;
            this.isTextureMode = false;
            this.textureNeedsUpdate = false;
            this.lastContentChange = new AtomicLong(0);
        }
    }

    @Keep
    public static class JsBridge {
        private final int webViewId;
        private final WebViewEmbed parent;

        public JsBridge(int webViewId, WebViewEmbed parent) {
            this.webViewId = webViewId;
            this.parent = parent;
        }

        @JavascriptInterface
        public void sendToLua(String eventType, String data) {
            parent.queueMessage(webViewId, eventType, data);
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, "WebView[" + webViewId + "] JS: " + message);
        }

        @JavascriptInterface
        public void requestTextureUpdate() {
            parent.markTextureNeedsUpdate(webViewId);
        }

        @JavascriptInterface
        public void close() {
            parent.queueMessage(webViewId, "close", "");
            parent.mainHandler.post(() -> parent.destroy(webViewId));
        }

        @JavascriptInterface
        public int getLocalServerPort() {
            return parent.localServerPort;
        }

        @JavascriptInterface
        public String getLocalServerUrl() {
            if (parent.localServerPort > 0) {
                return "http://127.0.0.1:" + parent.localServerPort;
            }
            return "";
        }
    }

    // Minimal single-threaded local HTTP server for CORS-free content hosting
    private static class LocalContentServer {
        private final ServerSocket serverSocket;
        private final ConcurrentHashMap<String, byte[]> content = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> contentTypes = new ConcurrentHashMap<>();
        private volatile boolean running = true;

        LocalContentServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            Thread t = new Thread(this::acceptLoop, "LuantiLocalHTTPServer");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void registerContent(String path, byte[] data, String mimeType) {
            content.put(path, data);
            contentTypes.put(path, mimeType);
        }

        void registerHtml(String path, String html) {
            registerContent(path, html.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/html; charset=utf-8");
        }

        void unregisterContent(String path) {
            content.remove(path);
            contentTypes.remove(path);
        }

        void shutdown() {
            running = false;
            try { serverSocket.close(); } catch (IOException ignored) {}
        }

        private void acceptLoop() {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "LuantiHTTPClient").start();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "HTTP server accept error", e);
                }
            }
        }

        private void handleClient(Socket client) {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                // Read HTTP request line
                StringBuilder requestLine = new StringBuilder();
                int c;
                while ((c = in.read()) != -1) {
                    char ch = (char) c;
                    if (ch == '\n') break;
                    if (ch != '\r') requestLine.append(ch);
                }
                // Drain headers
                StringBuilder headers = new StringBuilder();
                String lastLine = "";
                while (true) {
                    StringBuilder line = new StringBuilder();
                    while ((c = in.read()) != -1) {
                        char ch = (char) c;
                        if (ch == '\n') break;
                        if (ch != '\r') line.append(ch);
                    }
                    if (line.length() == 0) break;
                    headers.append(line).append("\n");
                }

                String req = requestLine.toString().trim();
                String path = "/";
                if (req.startsWith("GET ") || req.startsWith("POST ")) {
                    String[] parts = req.split(" ");
                    if (parts.length >= 2) {
                        path = parts[1];
                        int q = path.indexOf('?');
                        if (q >= 0) path = path.substring(0, q);
                    }
                }

                PrintWriter pw = new PrintWriter(out, false);
                byte[] body = content.get(path);
                if (body != null) {
                    String mime = contentTypes.getOrDefault(path, "application/octet-stream");
                    pw.print("HTTP/1.1 200 OK\r\n");
                    pw.print("Content-Type: " + mime + "\r\n");
                    pw.print("Content-Length: " + body.length + "\r\n");
                    pw.print("Access-Control-Allow-Origin: *\r\n");
                    pw.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
                    pw.print("Access-Control-Allow-Headers: *\r\n");
                    pw.print("Cache-Control: no-cache\r\n");
                    pw.print("\r\n");
                    pw.flush();
                    out.write(body);
                } else {
                    String notFound = "Not Found";
                    pw.print("HTTP/1.1 404 Not Found\r\n");
                    pw.print("Content-Type: text/plain\r\n");
                    pw.print("Content-Length: " + notFound.length() + "\r\n");
                    pw.print("Access-Control-Allow-Origin: *\r\n");
                    pw.print("\r\n");
                    pw.print(notFound);
                }
                pw.flush();
                out.flush();
                client.close();
            } catch (IOException e) {
                Log.w(TAG, "HTTP client error: " + e.getMessage());
            }
        }
    }

    private WebViewEmbed(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.webViews = new ConcurrentHashMap<>();
        this.nextId = new AtomicInteger(1);
        this.pendingMessages = new CopyOnWriteArrayList<>();
    }

    public static synchronized WebViewEmbed getInstance(Context context) {
        if (instance == null) {
            instance = new WebViewEmbed(context);
        }
        return instance;
    }

    public static WebViewEmbed getInstance() {
        return instance;
    }

    public void setActivity(Activity act) {
        this.activity = act;
    }

    public void initialize(FrameLayout container) {
        this.containerView = container;
        this.initialized = true;

        // Start local HTTP server for CORS-free content hosting
        try {
            localServer = new LocalContentServer();
            localServerPort = localServer.getPort();
            Log.i(TAG, "Local content server started on port " + localServerPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local server", e);
        }

        // Prewarm WebView on main thread immediately (no sleep)
        if (!prewarmed) {
            mainHandler.post(() -> {
                try {
                    prewarmWebView = new WebView(context);
                    WebSettings ps = prewarmWebView.getSettings();
                    ps.setJavaScriptEnabled(true);
                    prewarmWebView.loadDataWithBaseURL(null, "<html><head></head><body></body></html>", "text/html", "UTF-8", null);
                    prewarmed = true;
                    Log.i(TAG, "WebView prewarmed");
                } catch (Exception e) {
                    Log.e(TAG, "Prewarm failed", e);
                }
            });
        }
    }

    public int getLocalServerPort() {
        return localServerPort;
    }

    public void registerServerContent(String path, byte[] data, String mimeType) {
        if (localServer != null) {
            localServer.registerContent(path, data, mimeType);
        }
    }

    public void registerServerHtml(String path, String html) {
        if (localServer != null) {
            localServer.registerHtml(path, html);
        }
    }

    public void unregisterServerContent(String path) {
        if (localServer != null) {
            localServer.unregisterContent(path);
        }
    }

    public int createWebView(int x, int y, int width, int height, boolean textureMode) {
        final int id = nextId.getAndIncrement();
        final WebViewInstance wvi = new WebViewInstance(id);
        wvi.x = x;
        wvi.y = y;
        wvi.width = width;
        wvi.height = height;
        wvi.isTextureMode = textureMode;

        webViews.put(id, wvi);

        if (textureMode && width > 0 && height > 0) {
            wvi.textureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            wvi.textureBuffer = ByteBuffer.allocateDirect(width * height * 4);
        }

        mainHandler.post(() -> {
            try {
                createWebViewOnUI(wvi);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create WebView", e);
                queueMessage(id, "error", "Failed to create: " + e.getMessage());
            }
        });

        return id;
    }

    @SuppressLint({"SetJavaScriptEnabled", "SetJavaScriptEnabled"})
    private void createWebViewOnUI(WebViewInstance wvi) {
        WebView webView;

        if (prewarmWebView != null) {
            webView = prewarmWebView;
            prewarmWebView = null;
            Log.i(TAG, "Using prewarmed WebView");
        } else {
            webView = new WebView(context);
        }

        wvi.webView = webView;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Allow cross-origin file access
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        webView.setBackgroundColor(Color.TRANSPARENT);

        // Hardware acceleration for overlay (smooth rendering), software for texture capture
        if (wvi.isTextureMode) {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        webView.addJavascriptInterface(new JsBridge(wvi.id, this), "LuantiBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                wvi.lastContentChange.set(System.currentTimeMillis());
                if (wvi.isTextureMode) {
                    wvi.textureNeedsUpdate = true;
                }
                queueMessage(wvi.id, "pageLoaded", url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Allow all requests — return null to proceed normally
                return null;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "WebView[" + wvi.id + "] Console[" +
                        consoleMessage.messageLevel().name() + "]: " + consoleMessage.message() +
                        " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }

            // Bridge Android OS-level permissions to WebView media permissions.
            // Calling request.grant() alone is NOT enough — the underlying media
            // stack checks the Android OS permission (RECORD_AUDIO / CAMERA) and
            // silently fails if the app doesn't hold it. So we must:
            //   1. Check whether each required Android permission is already granted.
            //   2a. If yes → grant the WebView request immediately.
            //   2b. If no  → store the pending WebView request and ask Android for
            //                the permission; onAndroidPermissionResult() finishes it.
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                mainHandler.post(() -> {
                    if (activity == null) {
                        // No Activity reference yet — optimistically grant and hope
                        // the OS permission was already given at install/first-run.
                        request.grant(request.getResources());
                        return;
                    }
                    List<String> androidPermsNeeded = new ArrayList<>();
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                            if (ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                                if (!androidPermsNeeded.contains(Manifest.permission.RECORD_AUDIO))
                                    androidPermsNeeded.add(Manifest.permission.RECORD_AUDIO);
                            }
                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                            if (ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.CAMERA)
                                    != PackageManager.PERMISSION_GRANTED) {
                                if (!androidPermsNeeded.contains(Manifest.permission.CAMERA))
                                    androidPermsNeeded.add(Manifest.permission.CAMERA);
                            }
                        }
                    }
                    if (androidPermsNeeded.isEmpty()) {
                        // All required Android perms are already granted.
                        request.grant(request.getResources());
                        Log.d(TAG, "WebView permission granted immediately (OS perms ok)");
                    } else {
                        // Store request and ask Android; result comes back via
                        // onAndroidPermissionResult() → called by GameActivity.
                        int code = wvPermCodeCounter.getAndIncrement();
                        pendingWebViewPermissions.put(code, request);
                        Log.d(TAG, "WebView permission deferred, requesting Android perms code=" + code
                                + " perms=" + androidPermsNeeded);
                        ActivityCompat.requestPermissions(activity,
                                androidPermsNeeded.toArray(new String[0]), code);
                    }
                });
            }

            // Grant geolocation permissions
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() {
                // no-op
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100 && wvi.isTextureMode) {
                    wvi.textureNeedsUpdate = true;
                }
            }
        });

        if (wvi.isTextureMode) {
            webView.setVisibility(View.INVISIBLE);
            int w = wvi.width > 0 ? wvi.width : 1;
            int h = wvi.height > 0 ? wvi.height : 1;
            webView.layout(0, 0, w, h);
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            );
        }

        if (containerView != null && !wvi.isTextureMode) {
            int resolvedWidth = resolveSize(wvi.width, containerView.getWidth());
            int resolvedHeight = resolveSize(wvi.height, containerView.getHeight());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(resolvedWidth, resolvedHeight);
            params.leftMargin = wvi.x;
            params.topMargin = wvi.y;
            containerView.addView(webView, params);
            // Use INVISIBLE instead of GONE: GONE removes the view from layout which
        // can suppress onPermissionRequest callbacks on some Android WebView versions.
        webView.setVisibility(wvi.visible ? View.VISIBLE : View.INVISIBLE);
        } else if (wvi.isTextureMode) {
            // No container needed for texture mode; already laid out above
        }
    }

    // Resolve SIZE_FULLSCREEN (-1) → MATCH_PARENT, otherwise use pixels directly
    private int resolveSize(int size, int containerSize) {
        if (size == SIZE_FULLSCREEN || size <= 0) {
            return FrameLayout.LayoutParams.MATCH_PARENT;
        }
        return size;
    }

    private String wrapHtmlWithBridge(String html) {
        String serverUrl = localServerPort > 0 ? "http://127.0.0.1:" + localServerPort : "";
        String bridgeScript = "<script>\n" +
            "window.luanti = {\n" +
            "  send: function(eventType, data) {\n" +
            "    if (typeof data !== 'string') data = JSON.stringify(data);\n" +
            "    LuantiBridge.sendToLua(eventType, data);\n" +
            "  },\n" +
            "  log: function(msg) { LuantiBridge.log(String(msg)); },\n" +
            "  requestTextureUpdate: function() { LuantiBridge.requestTextureUpdate(); },\n" +
            "  close: function() { LuantiBridge.close(); },\n" +
            "  getServerPort: function() { return LuantiBridge.getLocalServerPort(); },\n" +
            "  getServerUrl: function() { return LuantiBridge.getLocalServerUrl(); },\n" +
            "  serverUrl: '" + serverUrl + "'\n" +
            "};\n" +
            "window.addEventListener('error', function(e) {\n" +
            "  LuantiBridge.log('JS Error: ' + e.message + ' at ' + e.filename + ':' + e.lineno);\n" +
            "});\n" +
            "</script>\n";

        if (html.toLowerCase().contains("<head>")) {
            return html.replaceFirst("(?i)<head>", "<head>\n" + bridgeScript);
        } else if (html.toLowerCase().contains("<html>")) {
            return html.replaceFirst("(?i)<html>", "<html>\n<head>" + bridgeScript + "</head>\n");
        } else {
            return "<html><head>" + bridgeScript + "</head><body>" + html + "</body></html>";
        }
    }

    public void loadHtml(int id, String html) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        final String wrappedHtml = wrapHtmlWithBridge(html);
        // Use local server base URL so inline fetches work without CORS
        final String baseUrl = localServerPort > 0
            ? "http://127.0.0.1:" + localServerPort + "/"
            : "file:///android_asset/";

        mainHandler.post(() -> {
            if (wvi.webView != null) {
                wvi.webView.loadDataWithBaseURL(baseUrl, wrappedHtml, "text/html", "UTF-8", null);
            } else {
                mainHandler.postDelayed(() -> {
                    if (wvi.webView != null) {
                        wvi.webView.loadDataWithBaseURL(baseUrl, wrappedHtml, "text/html", "UTF-8", null);
                    }
                }, 50);
            }
        });
    }

    public void loadFile(int id, String filePath) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        final int webViewId = id;
        new Thread(() -> {
            try {
                File file = new File(filePath);
                StringBuilder content = new StringBuilder();
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();

                String baseUrl = "file://" + file.getParent() + "/";
                String html = content.toString();
                String wrappedHtml = wrapHtmlWithBridge(html);

                mainHandler.post(() -> {
                    if (wvi.webView != null) {
                        wvi.webView.loadDataWithBaseURL(baseUrl, wrappedHtml, "text/html", "UTF-8", null);
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to load file: " + filePath, e);
                queueMessage(webViewId, "error", "Failed to load file: " + e.getMessage());
            }
        }).start();
    }

    public void loadUrl(int id, String url) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        mainHandler.post(() -> {
            if (wvi.webView != null) {
                wvi.webView.loadUrl(url);
            }
        });
    }

    public void executeJavaScript(int id, String script) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        mainHandler.post(() -> {
            if (wvi.webView != null) {
                wvi.webView.evaluateJavascript(script, null);
            }
        });
    }

    public void setPosition(int id, int x, int y) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        wvi.x = x;
        wvi.y = y;

        if (wvi.isTextureMode || containerView == null) return;

        // Debounce: cancel pending update for this id, post new one
        Runnable prev = pendingPositionUpdates.get(id);
        if (prev != null) mainHandler.removeCallbacks(prev);

        Runnable update = () -> {
            pendingPositionUpdates.remove(id);
            if (wvi.webView == null) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wvi.webView.getLayoutParams();
            if (params != null) {
                params.leftMargin = wvi.x;
                params.topMargin = wvi.y;
                wvi.webView.requestLayout();
            }
        };
        pendingPositionUpdates.put(id, update);
        mainHandler.post(update);
    }

    public void setSize(int id, int width, int height) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        wvi.width = width;
        wvi.height = height;

        if (wvi.isTextureMode) {
            int w = width > 0 ? width : 1;
            int h = height > 0 ? height : 1;
            Bitmap newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(w * h * 4);
            if (wvi.textureBitmap != null) wvi.textureBitmap.recycle();
            wvi.textureBitmap = newBitmap;
            wvi.textureBuffer = newBuffer;
            wvi.textureNeedsUpdate = true;

            mainHandler.post(() -> {
                if (wvi.webView == null) return;
                wvi.webView.layout(0, 0, w, h);
                wvi.webView.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                );
            });
            return;
        }

        if (containerView == null) return;

        // Debounce size updates
        Runnable prev = pendingSizeUpdates.get(id);
        if (prev != null) mainHandler.removeCallbacks(prev);

        Runnable update = () -> {
            pendingSizeUpdates.remove(id);
            if (wvi.webView == null) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wvi.webView.getLayoutParams();
            if (params != null) {
                params.width = resolveSize(wvi.width, containerView.getWidth());
                params.height = resolveSize(wvi.height, containerView.getHeight());
                wvi.webView.requestLayout();
            }
        };
        pendingSizeUpdates.put(id, update);
        mainHandler.post(update);
    }

    public void setVisible(int id, boolean visible) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;

        wvi.visible = visible;

        mainHandler.post(() -> {
            if (wvi.webView != null && !wvi.isTextureMode) {
                wvi.webView.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    public void closeWebView(int id) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi != null) {
            queueMessage(id, "closed", "");
        }
        destroy(id);
    }

    public void destroy(int id) {
        WebViewInstance wvi = webViews.remove(id);
        pendingPositionUpdates.remove(id);
        pendingSizeUpdates.remove(id);
        if (wvi == null) return;

        mainHandler.post(() -> {
            if (wvi.webView != null) {
                wvi.webView.stopLoading();
                wvi.webView.loadUrl("about:blank");
                if (containerView != null) {
                    containerView.removeView(wvi.webView);
                }
                wvi.webView.destroy();
                wvi.webView = null;
            }
            if (wvi.textureBitmap != null) {
                wvi.textureBitmap.recycle();
                wvi.textureBitmap = null;
            }
        });
    }

    public void destroyAll() {
        for (Integer id : webViews.keySet()) {
            destroy(id);
        }
        webViews.clear();
        pendingMessages.clear();
        if (localServer != null) {
            localServer.shutdown();
            localServer = null;
        }
    }

    /**
     * Capture WebView content as ARGB pixel bytes for texture mode.
     * Uses CountDownLatch for proper synchronization (no Thread.sleep).
     */
    public byte[] captureTexture(int id) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null || !wvi.isTextureMode || wvi.textureBitmap == null) {
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                if (wvi.webView != null && wvi.textureBitmap != null) {
                    Canvas canvas = new Canvas(wvi.textureBitmap);
                    wvi.webView.draw(canvas);
                    wvi.textureNeedsUpdate = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Texture capture draw failed", e);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        Bitmap bmp = wvi.textureBitmap;
        ByteBuffer buf = wvi.textureBuffer;
        if (bmp == null || buf == null) return null;

        try {
            buf.rewind();
            bmp.copyPixelsToBuffer(buf);
            buf.rewind();
            byte[] pixels = new byte[bmp.getWidth() * bmp.getHeight() * 4];
            buf.get(pixels);
            return pixels;
        } catch (Exception e) {
            Log.e(TAG, "Texture buffer copy failed", e);
            return null;
        }
    }

    public boolean needsTextureUpdate(int id) {
        WebViewInstance wvi = webViews.get(id);
        return wvi != null && wvi.textureNeedsUpdate;
    }

    public void markTextureNeedsUpdate(int id) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi != null) {
            wvi.textureNeedsUpdate = true;
            wvi.lastContentChange.set(System.currentTimeMillis());
        }
    }

    private void queueMessage(int webViewId, String eventType, String data) {
        pendingMessages.add(new LuaMessage(webViewId, eventType, data));
    }

    public boolean hasMessages() {
        return !pendingMessages.isEmpty();
    }

    public LuaMessage popMessage() {
        if (pendingMessages.isEmpty()) return null;
        return pendingMessages.remove(0);
    }

    /**
     * Called by GameActivity.onRequestPermissionsResult() for ALL request codes.
     * Matches the code against pending WebView PermissionRequests and resolves them.
     */
    public void onAndroidPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        PermissionRequest pending = pendingWebViewPermissions.remove(requestCode);
        if (pending == null) return; // not ours
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        final PermissionRequest req = pending;
        final boolean grant = allGranted;
        mainHandler.post(() -> {
            if (grant) {
                Log.d(TAG, "WebView deferred permission granted after Android prompt");
                req.grant(req.getResources());
            } else {
                Log.w(TAG, "WebView deferred permission denied by user");
                req.deny();
            }
        });
    }

    public int[] getWebViewIds() {
        Integer[] ids = webViews.keySet().toArray(new Integer[0]);
        int[] result = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = ids[i];
        }
        return result;
    }

    public int getTextureWidth(int id) {
        WebViewInstance wvi = webViews.get(id);
        return wvi != null ? (wvi.width > 0 ? wvi.width : 0) : 0;
    }

    public int getTextureHeight(int id) {
        WebViewInstance wvi = webViews.get(id);
        return wvi != null ? (wvi.height > 0 ? wvi.height : 0) : 0;
    }
}
