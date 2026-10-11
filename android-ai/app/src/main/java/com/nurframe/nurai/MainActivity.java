package com.nurframe.nurai;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.view.ViewGroup;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("nur_ai_private_config", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(7, 24, 27));
        getWindow().setNavigationBarColor(Color.rgb(7, 24, 27));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(7, 24, 27));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new NativeBridge(), "AndroidAI");
        setContentView(web, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/index.html");
    }

    private String jsQuote(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private void callback(String id, boolean ok, String text) {
        if (web == null) return;
        final String script = "window.onNativeAIResponse && window.onNativeAIResponse("
            + jsQuote(id) + "," + (ok ? "true" : "false") + "," + jsQuote(text) + ");";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    public final class NativeBridge {
        @JavascriptInterface
        public String getConfig() {
            JSONObject o = new JSONObject();
            try {
                o.put("endpoint", prefs.getString("endpoint",
                    "https://api.groq.com/openai/v1/chat/completions"));
                o.put("model", prefs.getString("model", "openai/gpt-oss-20b"));
                o.put("hasKey", !prefs.getString("api_key", "").isEmpty());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public boolean saveConfig(String endpoint, String model, String apiKey, boolean clearKey) {
            try {
                Uri uri = Uri.parse(endpoint == null ? "" : endpoint.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || model == null || model.trim().isEmpty()) return false;
                SharedPreferences.Editor edit = prefs.edit()
                    .putString("endpoint", endpoint.trim())
                    .putString("model", model.trim());
                if (clearKey) edit.remove("api_key");
                else if (apiKey != null && !apiKey.trim().isEmpty())
                    edit.putString("api_key", apiKey.trim());
                edit.apply();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void sendChat(String requestId, String messagesJson) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String endpoint = prefs.getString("endpoint",
                        "https://api.groq.com/openai/v1/chat/completions");
                    String model = prefs.getString("model", "openai/gpt-oss-20b");
                    String apiKey = prefs.getString("api_key", "");
                    if (apiKey.isEmpty()) {
                        callback(requestId, false, "আগে সেটিংস থেকে একটি AI API key দিন।");
                        return;
                    }
                    Uri uri = Uri.parse(endpoint);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                        callback(requestId, false, "নিরাপত্তার জন্য HTTPS API endpoint ব্যবহার করুন।");
                        return;
                    }

                    JSONArray messages = new JSONArray(messagesJson);
                    JSONObject body = new JSONObject();
                    body.put("model", model);
                    body.put("messages", messages);
                    body.put("temperature", 0.7);
                    body.put("max_tokens", 1200);

                    conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(45000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json");
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

                    int status = conn.getResponseCode();
                    InputStream stream = status >= 200 && status < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder response = new StringBuilder();
                    if (stream != null) {
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) response.append(line);
                        }
                    }
                    JSONObject result = new JSONObject(response.toString());
                    if (status < 200 || status >= 300) {
                        String detail = result.optJSONObject("error") != null
                            ? result.optJSONObject("error").optString("message", "API error")
                            : "HTTP " + status;
                        callback(requestId, false, "AI API সমস্যা (" + status + "): " + detail);
                        return;
                    }
                    JSONArray choices = result.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        callback(requestId, false, "API থেকে কোনো উত্তর পাওয়া যায়নি। মডেল ও endpoint যাচাই করুন।");
                        return;
                    }
                    JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                    String text = message == null ? "" : message.optString("content", "");
                    if (text.isEmpty()) text = "উত্তর ফাঁকা এসেছে। আবার চেষ্টা করুন।";
                    callback(requestId, true, text);
                } catch (java.net.SocketTimeoutException e) {
                    callback(requestId, false, "অনুরোধের সময় শেষ হয়েছে। ইন্টারনেট দেখে আবার চেষ্টা করুন।");
                } catch (Exception e) {
                    String message = e.getMessage() == null ? "অজানা নেটওয়ার্ক সমস্যা" : e.getMessage();
                    callback(requestId, false, "সংযোগ/উত্তর সমস্যা: " + message);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }, "NurAI-Network").start();
        }
    }
}
