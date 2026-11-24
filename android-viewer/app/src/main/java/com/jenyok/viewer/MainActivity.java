package com.jenyok.viewer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import okhttp3.*;
import okio.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Native Viewer using the existing Saturn relay/Host text protocol. */
public final class MainActivity extends Activity {
    private static final String RELAY = "wss://saturn-pc-remote-relay.onrender.com/connect";
    private static final String ROOM = "saturn-pc-remote-public-room-v1";
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private WebSocket socket;
    private TextView status, hostLabel;
    private Spinner monitorPicker;
    private ImageView screen;
    private Button recoveryButton, remoteButton, blockButton;
    private final Map<Integer,String> monitors = new TreeMap<>();
    private int selectedMonitor = 0, screenWidth = 1, screenHeight = 1;
    private String selectedHost;
    private boolean connected, agentReady, remoteRequested, remoteGranted, blockRequested, blockGranted;
    private final Runnable heartbeat = new Runnable() { @Override public void run() {
        if (socket == null || !connected) return;
        if (remoteGranted) send("REMOTE_CONTROL_HEARTBEAT");
        if (blockGranted) send("BLOCK_HOST_INPUT_HEARTBEAT");
        heartbeatHandler.postDelayed(this, 4000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12,12,12,12); root.setBackgroundColor(0xff070a16);
        status = label("DISCONNECTED"); root.addView(status);
        hostLabel = label("No host selected"); root.addView(hostLabel);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(button("Connect", v -> connect()));
        top.addView(button("Refresh", v -> send("GET_HOST_LIST"))); root.addView(top);
        monitorPicker = new Spinner(this); root.addView(monitorPicker);
        monitorPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                if (position >= 0 && position < monitors.size()) {
                    int next = (Integer) monitors.keySet().toArray()[position];
                    if (next != selectedMonitor) { selectedMonitor = next; send("SCREEN_SELECT|" + next); }
                }
            }
        });
        LinearLayout tools = new LinearLayout(this); tools.setGravity(Gravity.CENTER);
        remoteButton = button("Remote control: OFF", v -> {
            if (remoteGranted || remoteRequested) { remoteRequested = false; remoteGranted = false; send("REMOTE_CONTROL|0"); }
            else { remoteRequested = true; send("REMOTE_CONTROL_REQUEST|1"); }
            refreshButtons();
        });
        blockButton = button("Block input: OFF", v -> {
            if (blockGranted || blockRequested) { blockRequested = false; blockGranted = false; send("BLOCK_HOST_INPUT|0"); }
            else { blockRequested = true; send("BLOCK_HOST_INPUT_REQUEST|1"); }
            refreshButtons();
        });
        recoveryButton = button("Recovery", v -> { if (agentReady) { recoveryButton.setEnabled(false); send("RECOVER_HOST"); status.setText("Recovery request sent…"); }});
        tools.addView(remoteButton); tools.addView(blockButton); tools.addView(recoveryButton); root.addView(tools);
        screen = new ImageView(this); screen.setBackgroundColor(0xff020617); screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        screen.setOnTouchListener(this::onScreenTouch);
        setContentView(root); refreshButtons();
    }
    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(0xffdce7ff); t.setPadding(4,8,4,8); return t; }
    private Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b; }
    private void connect() {
        if (socket != null) socket.cancel();
        connected = false; agentReady = false; remoteGranted = false; blockGranted = false;
        String full = RELAY + "?room=" + ROOM + "&role=viewer";
        status.setText("CONNECTING…"); refreshButtons();
        socket = client.newWebSocket(new Request.Builder().url(full).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                connected = true; ui.post(() -> { status.setText("CONNECTED"); refreshButtons(); });
                ws.send("GET_HOST_LIST"); ws.send("PROCESS_TRIGGERS_GET");
            }
            @Override public void onMessage(WebSocket ws, String text) { handleText(text); }
            @Override public void onMessage(WebSocket ws, ByteString bytes) { handleFrame(bytes.toByteArray()); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                connected = false; ui.post(() -> { status.setText("CONNECTION ERROR: " + t.getMessage()); refreshButtons(); });
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                connected = false; ui.post(() -> { status.setText("DISCONNECTED"); refreshButtons(); });
            }
        });
        heartbeatHandler.removeCallbacks(heartbeat); heartbeatHandler.postDelayed(heartbeat, 4000);
    }
    private void handleText(String message) {
        String[] p = message.split("\\|", -1);
        if (p.length >= 6 && p[0].equals("HOST_INFO")) {
            String name = "Remote PC";
            try { name = new String(Base64.decode(p[2], Base64.URL_SAFE | Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) {}
            final String n = name; final String id = p[1]; final boolean online = p[4].equals("1");
            ui.post(() -> hostLabel.setText(n + (online ? " • ONLINE" : " • OFFLINE") + "\nHost ID: " + id));
            if (selectedHost == null) { selectedHost = id; send("SELECT_HOST|" + id); }
        } else if (p.length == 2 && p[0].equals("HOST_SELECTED")) {
            selectedHost = p[1]; send("GET_AGENT_STATUS"); send("GET_HOST_STATUS");
        } else if (p.length == 2 && p[0].equals("SCREEN_COUNT")) {
            try { int count = Math.max(0, Math.min(32, Integer.parseInt(p[1]))); ui.post(() -> { monitors.clear(); for(int i=0;i<count;i++) monitors.put(i,"Screen " + (i+1)); updateMonitorPicker(); }); } catch(Exception ignored) {}
        } else if (p.length >= 6 && p[0].equals("SCREEN_INFO")) {
            try { int idx=Integer.parseInt(p[1]); String value="Screen "+(idx+1)+"  "+p[4]+"×"+p[5]; ui.post(() -> { monitors.put(idx,value); updateMonitorPicker(); }); } catch(Exception ignored) {}
        } else if (p.length == 6 && p[0].equals("SCREEN_SIZE")) {
            try { selectedMonitor=Integer.parseInt(p[1]); screenWidth=Math.max(1,Integer.parseInt(p[4])); screenHeight=Math.max(1,Integer.parseInt(p[5])); } catch(Exception ignored) {}
        } else if (p.length == 2 && p[0].equals("HOST_AGENT_READY")) {
            agentReady=p[1].equals("1"); ui.post(this::refreshButtons);
        } else if (p.length == 2 && p[0].equals("HOST_SESSION_STATUS")) {
            ui.post(() -> status.setText(p[1].equals("1") ? "HOST ONLINE" : (agentReady ? "HOST OFFLINE • Recovery available" : "HOST OFFLINE")));
        } else if (p.length == 2 && p[0].equals("RECOVERY_ACCEPTED")) {
            ui.post(() -> status.setText("Recovery accepted — Host restarting"));
        } else if (p.length >= 2 && p[0].equals("RECOVERY_ERROR")) {
            String detail; try { detail=new String(Base64.decode(p[1],Base64.URL_SAFE|Base64.NO_WRAP),java.nio.charset.StandardCharsets.UTF_8); } catch(Exception e){detail=p[1];}
            final String d=detail; ui.post(() -> { status.setText("Recovery failed: " + d); refreshButtons(); });
        } else if (p.length == 2 && p[0].equals("REMOTE_CONTROL_STATUS")) {
            remoteGranted=p[1].equals("1") && remoteRequested; if(!remoteGranted) remoteRequested=false; ui.post(this::refreshButtons);
        } else if (p.length == 2 && p[0].equals("BLOCK_HOST_INPUT_STATUS")) {
            blockGranted=p[1].equals("1") && blockRequested; if(!blockGranted) blockRequested=false; ui.post(this::refreshButtons);
        } else if (p.length >= 2 && p[0].equals("BLOCK_HOST_INPUT_ERROR")) {
            blockGranted=false; blockRequested=false; ui.post(() -> { status.setText("Block input failed: " + message.substring(message.indexOf('|')+1)); refreshButtons(); });
        }
    }
    private void updateMonitorPicker() {
        String[] labels=monitors.values().toArray(new String[0]);
        monitorPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int position=0, i=0; for(Integer idx:monitors.keySet()){if(idx==selectedMonitor)position=i;i++;}
        if(labels.length>0) monitorPicker.setSelection(position);
    }
    private void handleFrame(byte[] packet) {
        if(packet.length<=4)return;
        ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN); int monitor=b.getInt();
        if(monitor!=selectedMonitor)return;
        byte[] jpeg=new byte[packet.length-4]; b.get(jpeg); Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        if(bitmap!=null) ui.post(() -> screen.setImageBitmap(bitmap));
    }
    private boolean onScreenTouch(View v, MotionEvent e) {
        if(!remoteGranted || socket==null || screen.getDrawable()==null)return true;
        float viewW=v.getWidth(), viewH=v.getHeight();
        float scale=Math.min(viewW/screenWidth,viewH/screenHeight);
        float drawnW=screenWidth*scale, drawnH=screenHeight*scale;
        float left=(viewW-drawnW)/2f, top=(viewH-drawnH)/2f;
        int x=Math.max(0,Math.min(screenWidth-1,(int)((e.getX()-left)/scale)));
        int y=Math.max(0,Math.min(screenHeight-1,(int)((e.getY()-top)/scale)));
        String prefix="SCREEN_INPUT|"+selectedMonitor+"|INPUT|";
        if(e.getAction()==MotionEvent.ACTION_DOWN || e.getAction()==MotionEvent.ACTION_MOVE)send(prefix+"MOVE|"+x+"|"+y);
        if(e.getAction()==MotionEvent.ACTION_DOWN)send(prefix+"BUTTON|1|1");
        if(e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL)send(prefix+"BUTTON|1|0");
        return true;
    }
    private void refreshButtons() {
        if(recoveryButton!=null)recoveryButton.setEnabled(connected&&agentReady);
        if(remoteButton!=null){remoteButton.setText(remoteGranted?"Remote control: ON":(remoteRequested?"Remote control: WAITING":"Remote control: OFF"));remoteButton.setEnabled(connected);}
        if(blockButton!=null){blockButton.setText(blockGranted?"Block input: ON":(blockRequested?"Block input: WAITING":"Block input: OFF"));blockButton.setEnabled(connected);}
    }
    private void send(String text) { WebSocket ws=socket; if(ws!=null && connected)ws.send(text); }
    @Override protected void onDestroy() { heartbeatHandler.removeCallbacksAndMessages(null); if(socket!=null)socket.close(1000,"App closed"); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
