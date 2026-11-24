package com.jenyok.viewer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import android.graphics.RectF;
import okhttp3.*;
import okio.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Jenyok mobile Viewer. Uses the existing relay and Host wire protocol. */
public final class MainActivity extends Activity {
    private static final String RELAY = "wss://saturn-pc-remote-relay.onrender.com/connect";
    private static final String ROOM = "saturn-pc-remote-public-room-v1";
    private static final int BG = 0xff070b18, PANEL = 0xff10182b, PANEL_2 = 0xff0b1325;
    private static final int CYAN = 0xff31e8ff, PURPLE = 0xffa879ff, TEXT = 0xffedf5ff, MUTED = 0xff8294b5;
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private WebSocket socket;
    private TextView status, hostName, frameInfo, screenHint;
    private Spinner hostPicker, monitorPicker;
    private ImageView screen;
    private Button connectButton, remoteButton, blockButton, recoveryButton;
    private final Map<String,HostInfo> hosts = new LinkedHashMap<>();
    private final Map<Integer,String> monitors = new LinkedHashMap<>();
    private String selectedHost;
    private int selectedMonitor, screenWidth = 1, screenHeight = 1;
    private boolean connected, connectRequested, agentReady, remoteRequested, remoteGranted;
    private boolean blockRequested, blockGranted, rebuildingHosts, rebuildingMonitors;
    private long receivedFrames;
    private Bitmap lastFrame;

    private static final class HostInfo {
        final String id, name;
        int screens;
        boolean online, active;
        HostInfo(String id, String name, int screens, boolean online, boolean active) {
            this.id=id; this.name=name; this.screens=screens; this.online=online; this.active=active;
        }
        String label() { return (online ? "●  " : "○  ") + name + (online ? "  ONLINE" : "  OFFLINE"); }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (connected) {
                send("GET_AGENT_STATUS");
                send("GET_HOST_STATUS");
                send("GET_SCREEN_SIZE");
                if (remoteGranted) send("REMOTE_CONTROL_HEARTBEAT");
                if (blockGranted) send("BLOCK_HOST_INPUT_HEARTBEAT");
            }
            heartbeatHandler.postDelayed(this, 3000);
        }
    };
    private final Runnable reconnect = new Runnable() {
        @Override public void run() { if (connectRequested && !connected) connectSocket(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        buildUi();
        refreshButtons();
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1.4f), stroke);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }

    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(13), dp(11), dp(13), dp(11));
        c.setBackground(shape(PANEL, 0xff263d5b, 18)); c.setElevation(dp(3));
        return c;
    }

    private Button actionButton(String title, int outline, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title); b.setTextColor(TEXT); b.setTextSize(12);
        b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(5), 0, dp(5), 0); b.setMinHeight(dp(46));
        b.setBackground(shape(0xff121d34, outline, 13));
        b.setOnClickListener(listener);
        return b;
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG); root.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout header = horizontal();
        TextView logo = text("J", 27, CYAN, true);
        logo.setGravity(Gravity.CENTER); logo.setBackground(shape(0xff10283a, CYAN, 13));
        LinearLayout.LayoutParams logoLp = lp(dp(44), dp(44)); header.addView(logo, logoLp);
        LinearLayout brand = column(); brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("JENYOK", 19, TEXT, true));
        brand.addView(text("REMOTE CONTROL  /  MOBILE", 9, MUTED, true));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("● OFFLINE", 10, MUTED, true);
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        status.setBackground(shape(PANEL_2, 0xff34445f, 20));
        header.addView(status);
        root.addView(header, lp(-1, dp(52)));

        LinearLayout hostCard = card();
        TextView hostCaption = text("CONNECTED DEVICE", 9, CYAN, true);
        hostCard.addView(hostCaption);
        LinearLayout hostRow = horizontal(); hostRow.setPadding(0, dp(5), 0, 0);
        hostPicker = new Spinner(this);
        hostPicker.setBackground(shape(PANEL_2, 0xff39506e, 11));
        hostRow.addView(hostPicker, new LinearLayout.LayoutParams(0, dp(46), 1));
        connectButton = actionButton("CONNECT", CYAN, v -> {
            if (connected || connectRequested) {
                connectRequested = false; heartbeatHandler.removeCallbacks(reconnect);
                if (socket != null) socket.close(1000, "User disconnected");
                connected = false; refreshButtons();
            } else { connectRequested = true; connectSocket(); }
        });
        LinearLayout.LayoutParams connectLp = lp(dp(104), dp(46)); connectLp.leftMargin = dp(8);
        hostRow.addView(connectButton, connectLp);
        Button refresh = actionButton("↻", PURPLE, v -> { hosts.clear(); rebuildHostPicker(); send("GET_HOST_LIST"); });
        LinearLayout.LayoutParams refreshLp = lp(dp(48), dp(46)); refreshLp.leftMargin = dp(6);
        hostRow.addView(refresh, refreshLp);
        hostCard.addView(hostRow);
        hostName = text("Choose a PC to start viewing", 10, MUTED, false);
        hostName.setPadding(0, dp(6), 0, 0); hostCard.addView(hostName);
        LinearLayout.LayoutParams hostLp = lp(-1, -2); hostLp.topMargin = dp(9);
        root.addView(hostCard, hostLp);

        LinearLayout monitorRow = horizontal(); monitorRow.setPadding(dp(2), dp(8), dp(2), dp(8));
        TextView monitorLabel = text("DISPLAY", 10, PURPLE, true);
        monitorRow.addView(monitorLabel);
        monitorPicker = new Spinner(this);
        monitorPicker.setBackground(shape(0xff111b30, PURPLE, 10));
        LinearLayout.LayoutParams monitorLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        monitorLp.leftMargin = dp(12); monitorRow.addView(monitorPicker, monitorLp);
        root.addView(monitorRow, lp(-1, dp(56)));

        FrameLayout screenPanel = new FrameLayout(this);
        screenPanel.setBackground(shape(0xff030712, CYAN, 17));
        screenPanel.setPadding(dp(2), dp(2), dp(2), dp(2));
        screen = new ImageView(this);
        screen.setBackgroundColor(0xff030712);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenPanel.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        screenHint = text("WAITING FOR SCREEN STREAM\n\nSelect a PC and connect", 13, MUTED, true);
        screenHint.setGravity(Gravity.CENTER); screenHint.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        screenPanel.addView(screenHint, new FrameLayout.LayoutParams(-1, -1));
        screen.setOnTouchListener(this::onScreenTouch);
        LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(-1, 0, 1);
        screenLp.topMargin = dp(2); screenLp.bottomMargin = dp(8);
        root.addView(screenPanel, screenLp);

        frameInfo = text("NO SIGNAL  •  0 FRAMES", 9, MUTED, true);
        frameInfo.setGravity(Gravity.CENTER);
        root.addView(frameInfo, lp(-1, dp(20)));

        LinearLayout controls = column();
        LinearLayout row1 = horizontal();
        remoteButton = actionButton("REMOTE CONTROL  OFF", CYAN, v -> {
            if (remoteGranted || remoteRequested) {
                remoteRequested=false; remoteGranted=false; send("REMOTE_CONTROL|0");
            } else { remoteRequested=true; send("REMOTE_CONTROL_REQUEST|1"); }
            refreshButtons();
        });
        blockButton = actionButton("BLOCK INPUT  OFF", PURPLE, v -> {
            if (blockGranted || blockRequested) {
                blockRequested=false; blockGranted=false; send("BLOCK_HOST_INPUT|0");
            } else { blockRequested=true; send("BLOCK_HOST_INPUT_REQUEST|1"); }
            refreshButtons();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        half.rightMargin = dp(5); row1.addView(remoteButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        half2.leftMargin = dp(5); row1.addView(blockButton, half2);
        controls.addView(row1);
        recoveryButton = actionButton("⟳   HOST RECOVERY", 0xffffb45e, v -> {
            if (connected) {
                send("RECOVER_HOST");
                screenHint.setVisibility(View.VISIBLE);
                screenHint.setText("RECOVERY REQUEST SENT\nWaiting for Host restart…");
            }
        });
        LinearLayout.LayoutParams recLp = lp(-1, dp(44)); recLp.topMargin = dp(7);
        controls.addView(recoveryButton, recLp);
        root.addView(controls, lp(-1, -2));

        TextView footer = text("ENCRYPTED RELAY  •  LIVE SESSION", 8, 0xff536783, true);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(7), 0, 0);
        root.addView(footer);
        setContentView(root);

        hostPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                if (rebuildingHosts || position < 0 || position >= hosts.size()) return;
                String next = (String) hosts.keySet().toArray()[position];
                if (!next.equals(selectedHost)) selectHost(next);
            }
        });
        monitorPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                if (rebuildingMonitors || position < 0 || position >= monitors.size()) return;
                int next = (Integer) monitors.keySet().toArray()[position];
                if (next != selectedMonitor) {
                    selectedMonitor=next; clearScreen("Switching display…");
                    send("SCREEN_SELECT|" + next); send("GET_SCREEN_SIZE");
                }
            }
        });
    }

    private void connectSocket() {
        if (connected) return;
        if (socket != null) socket.cancel();
        agentReady=false; remoteGranted=false; blockGranted=false;
        status.setText("● CONNECTING"); status.setTextColor(PURPLE);
        refreshButtons();
        String url = RELAY + "?room=" + ROOM + "&role=viewer";
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                if (socket != ws) return;
                connected=true;
                ui.post(() -> { status.setText("● ONLINE"); status.setTextColor(CYAN); refreshButtons(); });
                ws.send("GET_HOST_LIST"); ws.send("PROCESS_TRIGGERS_GET");
                heartbeatHandler.removeCallbacks(poll); heartbeatHandler.post(poll);
            }
            @Override public void onMessage(WebSocket ws, String message) { if(socket==ws) handleText(message); }
            @Override public void onMessage(WebSocket ws, ByteString bytes) { if(socket==ws) handleFrame(bytes.toByteArray()); }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                if(socket!=ws) return;
                connected=false; agentReady=false; remoteGranted=false; blockGranted=false;
                ui.post(() -> { status.setText("● RETRYING"); status.setTextColor(0xffffb45e); refreshButtons(); });
                if(connectRequested) heartbeatHandler.postDelayed(reconnect, 2500);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if(socket!=ws) return;
                connected=false; agentReady=false; remoteGranted=false; blockGranted=false;
                ui.post(() -> { status.setText("● OFFLINE"); status.setTextColor(MUTED); refreshButtons(); });
                if(connectRequested) heartbeatHandler.postDelayed(reconnect, 2500);
            }
        });
    }

    private void selectHost(String id) {
        selectedHost=id; selectedMonitor=0; agentReady=false; remoteGranted=false; blockGranted=false;
        HostInfo info=hosts.get(id);
        hostName.setText(info==null ? "Selected PC" : info.name + (info.online ? "  •  ONLINE" : "  •  OFFLINE"));
        monitors.clear(); monitors.put(0,"Screen 1"); rebuildMonitorPicker();
        clearScreen("Opening remote desktop…");
        send("SELECT_HOST|" + id);
        refreshButtons();
    }

    private void handleText(String message) {
        String[] p=message.split("\\|",-1);
        if (p.length==1 && p[0].equals("HOST_LIST_BEGIN")) { hosts.clear(); return; }
        if (p.length>=6 && p[0].equals("HOST_INFO")) {
            String name="Remote PC";
            try { name=new String(Base64.decode(p[2],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8); } catch(Exception ignored) {}
            int count=0; try { count=Integer.parseInt(p[3]); } catch(Exception ignored) {}
            hosts.put(p[1],new HostInfo(p[1],name,count,p[4].equals("1"),p[5].equals("1")));
            return;
        }
        if (p.length==1 && p[0].equals("HOST_LIST_END")) {
            ui.post(() -> {
                rebuildHostPicker();
                if(selectedHost==null || !hosts.containsKey(selectedHost)) {
                    String choice=null;
                    for(HostInfo h:hosts.values()) if(h.online){choice=h.id;break;}
                    if(choice==null && !hosts.isEmpty()) choice=hosts.keySet().iterator().next();
                    if(choice!=null) { selectedHost=choice; rebuildHostPicker(); selectHost(choice); }
                }
            });
            return;
        }
        if (p.length==2 && p[0].equals("HOST_SELECTED")) {
            selectedHost=p[1];
            send("GET_AGENT_STATUS"); send("GET_HOST_STATUS");
            send("GET_SCREEN_SIZE"); send("START_STREAM"); send("SCREEN_SELECT|"+selectedMonitor);
            ui.post(() -> { HostInfo h=hosts.get(selectedHost); if(h!=null)hostName.setText(h.name+"  •  CONNECTED"); });
            return;
        }
        if (p.length==2 && p[0].equals("SCREEN_COUNT")) {
            int count=0; try{count=Math.max(0,Math.min(32,Integer.parseInt(p[1])));}catch(Exception ignored){}
            final int n=count;
            ui.post(() -> { monitors.clear(); for(int i=0;i<n;i++)monitors.put(i,"Screen "+(i+1)); if(monitors.isEmpty())monitors.put(0,"Screen 1"); rebuildMonitorPicker(); });
            return;
        }
        if (p.length==6 && p[0].equals("SCREEN_INFO")) {
            try {
                int index=Integer.parseInt(p[1]); String label="Screen "+(index+1)+"  •  "+p[4]+"×"+p[5];
                ui.post(() -> { monitors.put(index,label); rebuildMonitorPicker(); });
            } catch(Exception ignored) {}
            return;
        }
        if (p.length==6 && p[0].equals("SCREEN_SIZE")) {
            try {
                int idx=Integer.parseInt(p[1]);
                if(idx==selectedMonitor){screenWidth=Math.max(1,Integer.parseInt(p[4]));screenHeight=Math.max(1,Integer.parseInt(p[5]));}
            } catch(Exception ignored) {}
            return;
        }
        if (p.length==2 && p[0].equals("HOST_AGENT_READY")) {
            agentReady=p[1].equals("1"); ui.post(this::refreshButtons); return;
        }
        if (p.length==3 && p[0].equals("HOST_SESSION_STATUS")) {
            // Host reports two independent status flags; do not interpret the first
            // field as a stopped/running boolean without the original Viewer semantics.
            return;
        }
        if (p.length==2 && p[0].equals("RECOVERY_ACCEPTED")) {
            ui.post(() -> { screenHint.setVisibility(View.VISIBLE); screenHint.setText("RECOVERY ACCEPTED\nHost is restarting…"); }); return;
        }
        if (p.length>=2 && p[0].equals("RECOVERY_ERROR")) {
            String detail=decodeUrlBase64(p[1]);
            ui.post(() -> { screenHint.setVisibility(View.VISIBLE); screenHint.setText("RECOVERY FAILED\n"+detail); refreshButtons(); }); return;
        }
        if (p.length==2 && p[0].equals("REMOTE_CONTROL_STATUS")) {
            remoteGranted=p[1].equals("1")&&remoteRequested;
            if(!remoteGranted)remoteRequested=false;
            ui.post(this::refreshButtons); return;
        }
        if (p.length==2 && p[0].equals("BLOCK_HOST_INPUT_STATUS")) {
            blockGranted=p[1].equals("1")&&blockRequested;
            if(!blockGranted)blockRequested=false;
            ui.post(this::refreshButtons); return;
        }
        if (p.length>=2 && p[0].equals("BLOCK_HOST_INPUT_ERROR")) {
            blockGranted=false; blockRequested=false;
            ui.post(() -> { screenHint.setVisibility(View.VISIBLE); screenHint.setText("INPUT BLOCK FAILED\n"+message.substring(message.indexOf('|')+1)); refreshButtons(); }); return;
        }
    }

    private String decodeUrlBase64(String value) {
        try { return new String(Base64.decode(value,Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8); }
        catch(Exception e){return value;}
    }

    private void rebuildHostPicker() {
        rebuildingHosts=true;
        String[] values=new String[hosts.size()]; int index=0, selected=0;
        for(HostInfo h:hosts.values()){ values[index]=h.label(); if(h.id.equals(selectedHost))selected=index; index++; }
        hostPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));
        if(values.length>0)hostPicker.setSelection(selected,false);
        rebuildingHosts=false;
    }

    private void rebuildMonitorPicker() {
        rebuildingMonitors=true;
        String[] values=monitors.values().toArray(new String[0]);
        monitorPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));
        int selected=0,i=0; for(Integer key:monitors.keySet()){if(key==selectedMonitor)selected=i;i++;}
        if(values.length>0)monitorPicker.setSelection(selected,false);
        rebuildingMonitors=false;
    }

    private void handleFrame(byte[] packet) {
        if(packet==null || packet.length<4)return;
        int monitor=selectedMonitor;
        int jpegStart=-1;

        // Host frames are [4-byte big-endian monitor index][JPEG bytes].
        // Also accept a plain JPEG and tolerate small relay framing prefixes.
        if(packet.length>=6 && (packet[0]!=(byte)0xff || packet[1]!=(byte)0xd8)) {
            int candidate=ByteBuffer.wrap(packet,0,4).order(ByteOrder.BIG_ENDIAN).getInt();
            if(candidate>=0 && candidate<32) monitor=candidate;
        }
        if(monitor!=selectedMonitor)return;
        for(int i=0;i<packet.length-1;i++) {
            if((packet[i]&0xff)==0xff && (packet[i+1]&0xff)==0xd8){jpegStart=i;break;}
        }
        if(jpegStart<0)return;
        if(jpegStart>=4) {
            int be=ByteBuffer.wrap(packet,0,4).order(ByteOrder.BIG_ENDIAN).getInt();
            int le=ByteBuffer.wrap(packet,0,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int candidate=(be>=0 && be<32)?be:((le>=0 && le<32)?le:monitor);
            monitor=candidate;
            if(monitor!=selectedMonitor)return;
        }
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        Bitmap bitmap=BitmapFactory.decodeByteArray(packet,jpegStart,packet.length-jpegStart,options);
        if(bitmap==null)return;
        lastFrame=bitmap; receivedFrames++;
        final Bitmap frame=bitmap; final long count=receivedFrames;
        ui.post(() -> {
            screen.setImageBitmap(frame); screenHint.setVisibility(View.GONE);
            frameInfo.setText("LIVE  •  "+frame.getWidth()+" × "+frame.getHeight()+"  •  "+count+" FRAMES");
            frameInfo.setTextColor(CYAN);
        });
    }

    private void clearScreen(String message) {
        if(screen!=null)screen.setImageDrawable(null);
        if(screenHint!=null){screenHint.setText(message);screenHint.setVisibility(View.VISIBLE);}
        if(frameInfo!=null){frameInfo.setText("NO SIGNAL  •  "+receivedFrames+" FRAMES");frameInfo.setTextColor(MUTED);}
    }

    private boolean onScreenTouch(View view, MotionEvent event) {
        if(!remoteGranted || socket==null || lastFrame==null)return true;
        float vw=view.getWidth(), vh=view.getHeight();
        float scale=Math.min(vw/screenWidth,vh/screenHeight);
        float drawW=screenWidth*scale, drawH=screenHeight*scale;
        float left=(vw-drawW)/2f, top=(vh-drawH)/2f;
        int x=Math.max(0,Math.min(screenWidth-1,(int)((event.getX()-left)/scale)));
        int y=Math.max(0,Math.min(screenHeight-1,(int)((event.getY()-top)/scale)));
        String prefix="SCREEN_INPUT|"+selectedMonitor+"|INPUT|";
        if(event.getAction()==MotionEvent.ACTION_DOWN || event.getAction()==MotionEvent.ACTION_MOVE)send(prefix+"MOVE|"+x+"|"+y);
        if(event.getAction()==MotionEvent.ACTION_DOWN)send(prefix+"BUTTON|1|1");
        if(event.getAction()==MotionEvent.ACTION_UP || event.getAction()==MotionEvent.ACTION_CANCEL)send(prefix+"BUTTON|1|0");
        return true;
    }

    private void refreshButtons() {
        if(connectButton!=null)connectButton.setText(connected?"DISCONNECT":(connectRequested?"RETRY":"CONNECT"));
        if(remoteButton!=null) {
            remoteButton.setText(remoteGranted?"REMOTE CONTROL  ON":(remoteRequested?"REMOTE CONTROL  WAIT":"REMOTE CONTROL  OFF"));
            remoteButton.setEnabled(connected);
        }
        if(blockButton!=null) {
            blockButton.setText(blockGranted?"BLOCK INPUT  ON":(blockRequested?"BLOCK INPUT  WAIT":"BLOCK INPUT  OFF"));
            blockButton.setEnabled(connected);
        }
        if(recoveryButton!=null) {
            recoveryButton.setEnabled(connected);
            recoveryButton.setText(agentReady?"⟳   HOST RECOVERY  READY":"⟳   HOST RECOVERY");
        }
    }

    private void send(String message) {
        WebSocket ws=socket;
        if(ws!=null && connected)ws.send(message);
    }

    @Override protected void onDestroy() {
        connectRequested=false;
        heartbeatHandler.removeCallbacksAndMessages(null);
        if(socket!=null)socket.close(1000,"App closed");
        client.dispatcher().executorService().shutdown();
        if(lastFrame!=null && !lastFrame.isRecycled())lastFrame.recycle();
        super.onDestroy();
    }
}
