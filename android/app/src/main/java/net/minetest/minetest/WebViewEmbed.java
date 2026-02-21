/*
Luanti WebView Embed System
SPDX-License-Identifier: LGPL-2.1-or-later
Copyright (C) 2024 Luanti Contributors

Provides HTML/CSS/JS embedding capabilities for mods via WebView.
Supports overlay display and texture rendering for entities/blocks.
*/

package net.minetest.minetest;

import android.annotation.SuppressLint;
import android.content.Context;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Keep
@SuppressWarnings("unused")
public class WebViewEmbed {
    private static final String TAG = "WebViewEmbed";
    
    private static WebViewEmbed instance;
    private final Context context;
    private final Handler mainHandler;
    private final ConcurrentHashMap<Integer, WebViewInstance> webViews;
    private final AtomicInteger nextId;
    private final CopyOnWriteArrayList<LuaMessage> pendingMessages;
    private FrameLayout containerView;
    private boolean initialized = false;
    
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
        public boolean textureNeedsUpdate;
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
    
    public void initialize(FrameLayout container) {
        this.containerView = container;
        this.initialized = true;
    }
    
    public int createWebView(int x, int y, int width, int height, boolean textureMode) {
        final int id = nextId.getAndIncrement();
        final WebViewInstance wvi = new WebViewInstance(id);
        wvi.x = x;
        wvi.y = y;
        wvi.width = width;
        wvi.height = height;
        wvi.isTextureMode = textureMode;
        
        if (textureMode) {
            wvi.textureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            wvi.textureBuffer = ByteBuffer.allocateDirect(width * height * 4);
        }
        
        mainHandler.post(() -> createWebViewOnUI(wvi));
        webViews.put(id, wvi);
        return id;
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private void createWebViewOnUI(WebViewInstance wvi) {
        WebView webView = new WebView(context);
        wvi.webView = webView;
        
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
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
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "WebView[" + wvi.id + "] Console: " + consoleMessage.message());
                return true;
            }
        });
        
        if (wvi.isTextureMode) {
            webView.setVisibility(View.INVISIBLE);
        }
        
        if (containerView != null && !wvi.isTextureMode) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(wvi.width, wvi.height);
            params.leftMargin = wvi.x;
            params.topMargin = wvi.y;
            containerView.addView(webView, params);
        } else if (wvi.isTextureMode) {
            webView.layout(0, 0, wvi.width, wvi.height);
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(wvi.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(wvi.height, View.MeasureSpec.EXACTLY)
            );
        }
    }
    
    public void loadHtml(int id, String html) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;
        
        mainHandler.post(() -> {
            if (wvi.webView != null) {
                String wrappedHtml = wrapHtmlWithBridge(html);
                wvi.webView.loadDataWithBaseURL("file:///android_asset/", wrappedHtml, "text/html", "UTF-8", null);
            }
        });
    }
    
    public void loadFile(int id, String filePath) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;
        
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
            
            mainHandler.post(() -> {
                if (wvi.webView != null) {
                    String wrappedHtml = wrapHtmlWithBridge(html);
                    wvi.webView.loadDataWithBaseURL(baseUrl, wrappedHtml, "text/html", "UTF-8", null);
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Failed to load file: " + filePath, e);
            queueMessage(id, "error", "Failed to load file: " + e.getMessage());
        }
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
    
    private String wrapHtmlWithBridge(String html) {
        String bridgeScript = "<script>\n" +
            "window.luanti = {\n" +
            "  send: function(eventType, data) {\n" +
            "    if (typeof data !== 'string') data = JSON.stringify(data);\n" +
            "    LuantiBridge.sendToLua(eventType, data);\n" +
            "  },\n" +
            "  log: function(msg) { LuantiBridge.log(msg); },\n" +
            "  requestTextureUpdate: function() { LuantiBridge.requestTextureUpdate(); }\n" +
            "};\n" +
            "</script>\n";
        
        if (html.toLowerCase().contains("<head>")) {
            return html.replaceFirst("(?i)<head>", "<head>\n" + bridgeScript);
        } else if (html.toLowerCase().contains("<html>")) {
            return html.replaceFirst("(?i)<html>", "<html>\n<head>" + bridgeScript + "</head>\n");
        } else {
            return bridgeScript + html;
        }
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
        
        if (!wvi.isTextureMode && containerView != null) {
            mainHandler.post(() -> {
                if (wvi.webView != null) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wvi.webView.getLayoutParams();
                    if (params != null) {
                        params.leftMargin = x;
                        params.topMargin = y;
                        wvi.webView.setLayoutParams(params);
                    }
                }
            });
        }
    }
    
    public void setSize(int id, int width, int height) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null) return;
        
        wvi.width = width;
        wvi.height = height;
        
        if (wvi.isTextureMode) {
            wvi.textureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            wvi.textureBuffer = ByteBuffer.allocateDirect(width * height * 4);
            wvi.textureNeedsUpdate = true;
        }
        
        mainHandler.post(() -> {
            if (wvi.webView != null) {
                if (wvi.isTextureMode) {
                    wvi.webView.layout(0, 0, width, height);
                    wvi.webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    );
                } else if (containerView != null) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wvi.webView.getLayoutParams();
                    if (params != null) {
                        params.width = width;
                        params.height = height;
                        wvi.webView.setLayoutParams(params);
                    }
                }
            }
        });
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
    
    public void destroy(int id) {
        WebViewInstance wvi = webViews.remove(id);
        if (wvi == null) return;
        
        mainHandler.post(() -> {
            if (wvi.webView != null) {
                if (containerView != null) {
                    containerView.removeView(wvi.webView);
                }
                wvi.webView.destroy();
            }
            if (wvi.textureBitmap != null) {
                wvi.textureBitmap.recycle();
            }
        });
    }
    
    public void destroyAll() {
        for (Integer id : webViews.keySet()) {
            destroy(id);
        }
        webViews.clear();
        pendingMessages.clear();
    }
    
    public byte[] captureTexture(int id) {
        WebViewInstance wvi = webViews.get(id);
        if (wvi == null || !wvi.isTextureMode || wvi.textureBitmap == null) {
            return null;
        }
        
        mainHandler.post(() -> {
            if (wvi.webView != null && wvi.textureBitmap != null) {
                Canvas canvas = new Canvas(wvi.textureBitmap);
                wvi.webView.draw(canvas);
                wvi.textureNeedsUpdate = false;
            }
        });
        
        try {
            Thread.sleep(16);
        } catch (InterruptedException ignored) {}
        
        if (wvi.textureBitmap != null && wvi.textureBuffer != null) {
            wvi.textureBuffer.rewind();
            wvi.textureBitmap.copyPixelsToBuffer(wvi.textureBuffer);
            wvi.textureBuffer.rewind();
            byte[] pixels = new byte[wvi.width * wvi.height * 4];
            wvi.textureBuffer.get(pixels);
            return pixels;
        }
        
        return null;
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
        if (pendingMessages.isEmpty()) {
            return null;
        }
        return pendingMessages.remove(0);
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
        return wvi != null ? wvi.width : 0;
    }
    
    public int getTextureHeight(int id) {
        WebViewInstance wvi = webViews.get(id);
        return wvi != null ? wvi.height : 0;
    }
}
