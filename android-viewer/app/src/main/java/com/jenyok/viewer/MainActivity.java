package com.jenyok.viewer;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import okhttp3.*;
import okio.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String DEFAULT_RELAY = "wss://saturn-pc-remote-relay.onrender.com/connect";
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket socket;
    private EditText endpoint;
    private TextView status, hosts;
    private ImageView screen;
    private String selectedHost = null;
    private int screenWidth = 1, screenHeight = 1;
    private boolean control = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12,12,12,12); root.setBackgroundColor(0xff070a16);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setText(DEFAULT_RELAY);
        endpoint.setTextColor(0xffffffff); endpoint.setHintTextColor(0xff9aacc8);
        Button connect = new Button(this); connect.setText("Connect"); connect.setOnClickListener(v -> connect());
        bar.addView(endpoint, new LinearLayout.LayoutParams(0, -2, 1)); bar.addView(connect);
        status = label("DISCONNECTED"); root.addView(bar); root.addView(status);
        hosts = label("No connected hosts"); root.addView(hosts);
        LinearLayout actions = new LinearLayout(this);
        Button refresh = new Button(this); refresh.setText("Refresh"); refresh.setOnClickListener(v -> send("GET_HOST_LIST"));
        Button remote = new Button(this); remote.setText("Remote control"); remote.setOnClickListener(v -> {
            control = !control; send(control ? "REMOTE_CONTROL_REQUEST|1" : "REMOTE_CONTROL|0");
            remote.setText(control ? "Control: ON" : "Remote control");
        });
        actions.addView(refresh); actions.addView(remote); root.addView(actions);
        screen = new ImageView(this); screen.setBackgroundColor(0xff020617); screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        screen.setOnTouchListener(this::onScreenTouch);
        setContentView(root);
    }
    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(0xffdce7ff); t.setPadding(4,8,4,8); return t; }
    private void connect() {
        if (socket != null) socket.close(1000, "Reconnect");
        String url = endpoint.getText().toString().trim();
        if (url.isEmpty()) return;
        String full = url + (url.contains("?") ? "&" : "?") + "room=saturn-pc-remote-public-room-v1&role=viewer";
        status.setText("CONNECTING…");
        socket = client.newWebSocket(new Request.Builder().url(full).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                runOnUiThread(() -> status.setText("CONNECTED"));
                ws.send("GET_HOST_LIST"); ws.send("PROCESS_TRIGGERS_GET");
            }
            @Override public void onMessage(WebSocket ws, String text) { handleText(text); }
            @Override public void onMessage(WebSocket ws, ByteString bytes) { handleFrame(bytes.toByteArray()); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                runOnUiThread(() -> status.setText("CONNECTION ERROR: " + t.getMessage()));
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                runOnUiThread(() -> status.setText("DISCONNECTED"));
            }
        });
    }
    private void handleText(String message) {
        String[] p = message.split("\\|", -1);
        if (p.length >= 6 && p[0].equals("HOST_INFO")) {
            String name;
            try { name = new String(android.util.Base64.decode(p[2], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP)); }
            catch (Exception e) { name = "Remote PC"; }
            final String hostName = name;
            runOnUiThread(() -> hosts.setText(hostName + "  •  " + (p[4].equals("1") ? "ONLINE" : "OFFLINE") + "\nHost ID: " + p[1]));
            if (selectedHost == null) { selectedHost = p[1]; send("SELECT_HOST|" + selectedHost); }
        } else if (p.length == 6 && p[0].equals("SCREEN_SIZE")) {
            try { screenWidth = Math.max(1, Integer.parseInt(p[4])); screenHeight = Math.max(1, Integer.parseInt(p[5])); } catch (Exception ignored) {}
        } else if (p.length == 2 && p[0].equals("HOST_SELECTED")) {
            selectedHost = p[1];
        } else if (p.length == 2 && p[0].equals("HOST_SESSION_STATUS")) {
            runOnUiThread(() -> status.setText(p[1].equals("1") ? "HOST ONLINE" : "HOST OFFLINE"));
        } else if (p[0].equals("REMOTE_CONTROL_STATUS") || p[0].equals("REMOTE_CONTROL_GRANTED")) {
            runOnUiThread(() -> status.setText(message));
        }
    }
    private void handleFrame(byte[] packet) {
        if (packet.length <= 4) return;
        ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        int monitor = b.getInt(); if (monitor != 0) return;
        byte[] jpeg = new byte[packet.length - 4]; b.get(jpeg);
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap != null) runOnUiThread(() -> screen.setImageBitmap(bitmap));
    }
    private boolean onScreenTouch(View v, MotionEvent e) {
        if (!control || socket == null || screen.getDrawable() == null) return true;
        float nx = Math.max(0, Math.min(1, e.getX() / Math.max(1f, v.getWidth())));
        float ny = Math.max(0, Math.min(1, e.getY() / Math.max(1f, v.getHeight())));
        int x = (int)(nx * Math.max(1, screenWidth - 1)), y = (int)(ny * Math.max(1, screenHeight - 1));
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE)
            send("SCREEN_INPUT|INPUT|MOVE|" + x + "|" + y);
        if (e.getAction() == MotionEvent.ACTION_DOWN) send("SCREEN_INPUT|INPUT|BUTTON|1|1");
        if (e.getAction() == MotionEvent.ACTION_UP) send("SCREEN_INPUT|INPUT|BUTTON|1|0");
        return true;
    }
    private void send(String text) { WebSocket ws = socket; if (ws != null) ws.send(text); }
    @Override protected void onDestroy() { if (socket != null) socket.close(1000, "App closed"); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
