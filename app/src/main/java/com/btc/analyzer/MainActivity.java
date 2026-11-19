package com.btc.analyzer;

import android.app.Activity;
import android.os.Bundle;
import android.app.AlertDialog;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebSocket socket;
    private String symbol = "BTCUSDT", category = "linear";
    private TextView symbolView;
    private final ArrayList<Instrument> instruments = new ArrayList<>();
    private int generation = 0;
    private static class Instrument {
        final String symbol, category;
        Instrument(String s, String c) { symbol=s; category=c; }
        String label() { return symbol + "  (" + category + ")"; }
    }
    private TextView priceView, statusView, signalView, metricsView, riskView, bookView, timeView;
    private final Map<String, ArrayList<Candle>> bars = new HashMap<>();
    private double lastPrice = Double.NaN, bidTotal = Double.NaN, askTotal = Double.NaN;
    private long lastFeedAt = 0;
    private boolean closed = false;
    private static final String[] FRAMES = {"5", "15", "60"};
    private static final int GREEN = Color.rgb(68, 220, 157), RED = Color.rgb(248, 104, 116);
    private static class Candle {
        long start;
        double close, volume;
        boolean confirmed;
        Candle(long s, double c, double v, boolean f) { start=s; close=c; volume=v; confirmed=f; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences selected = getSharedPreferences("market", MODE_PRIVATE);
        symbol = selected.getString("symbol", "BTCUSDT");
        category = selected.getString("category", "linear");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 42, 30, 30);
        root.setBackgroundColor(Color.rgb(14, 19, 30));
        scroll.addView(root);
        add(root, "BYBIT · FUTURES ANALYZER", 21, Color.WHITE);
        symbolView = add(root, "선택 종목: " + symbol + " (" + category + ")", 19, Color.WHITE);
        Button choose = new Button(this);
        choose.setText("선물 종목 검색 / 변경");
        root.addView(choose);
        choose.setOnClickListener(v -> showSymbolPicker());
        statusView = add(root, "연결 준비 중", 15, Color.LTGRAY);
        priceView = add(root, "가격 수신 대기", 30, Color.WHITE);
        signalView = add(root, "분석 준비 중", 26, Color.YELLOW);
        metricsView = add(root, "5분 / 15분 / 1시간 캔들 수신 대기", 17, Color.LTGRAY);
        bookView = add(root, "호가 데이터 수신 대기", 17, Color.LTGRAY);
        riskView = add(root, "투자 참고용 분석이며 승률을 의미하지 않습니다. 자동매매와 주문은 실행하지 않습니다.", 14, Color.rgb(180,185,196));
        timeView = add(root, "", 13, Color.LTGRAY);
        Button reconnect = new Button(this);
        reconnect.setText("연결 다시 시작 / 데이터 새로고침");
        root.addView(reconnect);
        Button updateButton = new Button(this);
        updateButton.setText("앱 업데이트 확인 (GitHub Releases)");
        root.addView(updateButton);
        updateButton.setOnClickListener(v -> checkUpdate());
        reconnect.setOnClickListener(v -> reconnect());
        Button tradePlan = new Button(this);
        tradePlan.setText("차트 분석 · 지지/저항 · TP1/TP2 · 손절 · 손익비");
        root.addView(tradePlan);
        tradePlan.setOnClickListener(v -> {
            Intent intent = new Intent(this, TradePlanActivity.class);
            intent.putExtra("symbol", symbol);
            intent.putExtra("category", category);
            startActivity(intent);
        });
        Button guardian = new Button(this);
        guardian.setText("포지션 가디언 · 시작 / 설정");
        root.addView(guardian);
        guardian.setOnClickListener(v -> showGuardianSetup());
        Button guardianStop = new Button(this);
        guardianStop.setText("포지션 감시 중지");
        root.addView(guardianStop);
        guardianStop.setOnClickListener(v -> {
            stopService(new Intent(this, PositionGuardianService.class));
            getSharedPreferences("guardian", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            Toast.makeText(this, "포지션 감시 종료", Toast.LENGTH_SHORT).show();
        });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        setContentView(scroll);
        reconnect();
        loadInstruments();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (closed) return;
                if (lastFeedAt > 0 && System.currentTimeMillis() - lastFeedAt > 25000) {
                    statusView.setText("데이터 지연 · 연결을 확인하거나 새로고침하세요");
                }
                ui.postDelayed(this, 5000);
            }
        }, 5000);
    }
    private TextView add(LinearLayout root, String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, 12, 0, 17); t.setGravity(Gravity.START);
        root.addView(t); return t;
    }
    private void showGuardianSetup() {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(35, 14, 35, 8);
        panel.setOrientation(LinearLayout.VERTICAL);
        TextView explain = new TextView(this);
        explain.setText("현재 선택한 종목만 수동 감시합니다. 실제 바이비트 보유 포지션을 자동 조회하지 않으며, 알림은 손절 주문이 아닙니다.");
        panel.addView(explain);
        EditText entry = new EditText(this);
        entry.setHint("진입가 (선택, 양수)");
        entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        panel.addView(entry);
        new AlertDialog.Builder(this).setTitle("포지션 가디언 · " + symbol)
            .setView(panel).setNegativeButton("취소", null)
            .setNeutralButton("SHORT 감시", (d,w) -> startGuardian("SHORT", entry.getText().toString()))
            .setPositiveButton("LONG 감시", (d,w) -> startGuardian("LONG", entry.getText().toString()))
            .show();
    }
    private void startGuardian(String side, String entryString) {
        double entry=0;
        if (!entryString.trim().isEmpty()) {
            try { entry=Double.parseDouble(entryString.trim()); }
            catch (Exception e) { Toast.makeText(this,"진입가 형식을 확인하세요",Toast.LENGTH_LONG).show(); return; }
            if (!Double.isFinite(entry) || entry<=0) { Toast.makeText(this,"진입가는 양수여야 합니다",Toast.LENGTH_LONG).show(); return; }
        }
        getSharedPreferences("guardian",MODE_PRIVATE).edit()
            .remove("stop").remove("tp1").remove("tp2")
            .putBoolean("enabled",true).putString("symbol",symbol)
            .putString("category",category).putString("side",side)
            .putString("entry",Double.toString(entry)).apply();
        try {
            Intent intent = new Intent(this,PositionGuardianService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            Toast.makeText(this,"가디언 시작: " + side + " " + symbol,Toast.LENGTH_LONG).show();
        } catch (Exception ex) { Toast.makeText(this,"감시 시작 오류: " + ex.getMessage(),Toast.LENGTH_LONG).show(); }
    }
    private void loadInstruments() {
        worker.execute(() -> {
            ArrayList<Instrument> all = new ArrayList<>();
            try {
                for (String market : new String[]{"linear", "inverse"}) {
                    String cursor = "";
                    do {
                        String url = "https://api.bybit.com/v5/market/instruments-info?category="
                            + market + "&status=Trading&limit=1000"
                            + (cursor.isEmpty() ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8"));
                        try (Response res = client.newCall(new Request.Builder().url(url).build()).execute()) {
                            if (!res.isSuccessful() || res.body() == null) throw new IOException("HTTP " + res.code());
                            JSONObject json = new JSONObject(res.body().string());
                            if (json.optInt("retCode", -1) != 0) throw new IOException(json.optString("retMsg"));
                            JSONObject result = json.getJSONObject("result");
                            JSONArray list = result.getJSONArray("list");
                            for (int i=0; i<list.length(); i++) {
                                JSONObject item = list.getJSONObject(i);
                                if ("Trading".equals(item.optString("status"))) {
                                    all.add(new Instrument(item.getString("symbol"), market));
                                }
                            }
                            cursor = result.optString("nextPageCursor", "");
                        }
                    } while (!cursor.isEmpty());
                }
                all.sort(Comparator.comparing(a -> a.symbol));
                ui.post(() -> {
                    if (closed) return;
                    instruments.clear(); instruments.addAll(all);
                    symbolView.setText("선택 종목: " + symbol + " (" + category + ") · 검색 가능 " + all.size() + "종목");
                });
            } catch (Exception e) {
                ui.post(() -> { if (!closed) symbolView.setText("선택 종목: " + symbol + " · 목록 조회 실패: " + e.getMessage()); });
            }
        });
    }
    private void showSymbolPicker() {
        if (instruments.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("종목 목록을 불러오는 중이거나 API 접속에 실패했습니다.")
                .setPositiveButton("다시 조회", (d,w) -> loadInstruments()).setNegativeButton("닫기", null).show();
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(24, 12, 24, 12);
        panel.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("BTC, ETH, XRP, USDT 등 검색");
        panel.addView(search);
        ListView listView = new ListView(this);
        panel.addView(listView, new LinearLayout.LayoutParams(-1, (int)(getResources().getDisplayMetrics().heightPixels*0.53)));
        ArrayList<Instrument> filtered = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().trim().toUpperCase(Locale.US);
            filtered.clear(); labels.clear();
            for (Instrument instrument : instruments) {
                if (instrument.symbol.contains(q) && filtered.size() < 250) {
                    filtered.add(instrument); labels.add(instrument.label());
                }
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { filter.run(); }
            public void afterTextChanged(Editable e) {}
        });
        filter.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("바이비트 선물 종목 선택")
            .setView(panel).setNegativeButton("닫기",null).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Instrument selected = filtered.get(position);
            symbol = selected.symbol; category = selected.category;
            getSharedPreferences("market", MODE_PRIVATE).edit()
                .putString("symbol",symbol).putString("category",category).apply();
            symbolView.setText("선택 종목: " + symbol + " (" + category + ")");
            dialog.dismiss();
            reconnect();
        });
        dialog.show();
    }
    private int compareVersion(String a, String b) {
        try {
            String[] aa = a.split("\\."), bb = b.split("\\.");
            for (int i=0; i<Math.max(aa.length, bb.length); i++) {
                int av = i<aa.length ? Integer.parseInt(aa[i]) : 0;
                int bv = i<bb.length ? Integer.parseInt(bb[i]) : 0;
                if (av != bv) return Integer.compare(av, bv);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }
    private void checkUpdate() {
        SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        EditText repoInput = new EditText(this);
        repoInput.setSingleLine(true);
        repoInput.setHint("GitHub 계정명/저장소명");
        repoInput.setText(prefs.getString("repo", ""));
        new AlertDialog.Builder(this).setTitle("업데이트 확인")
            .setMessage("APK를 GitHub Releases에 게시할 저장소를 입력하세요. 예: myname/Bybit-Futures-Analyzer")
            .setView(repoInput).setNegativeButton("취소",null)
            .setPositiveButton("확인", (d,w) -> {
                String repo = repoInput.getText().toString().trim();
                if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                    statusView.setText("저장소 형식은 계정명/저장소명입니다."); return;
                }
                prefs.edit().putString("repo",repo).apply();
                statusView.setText("최신 버전 확인 중…");
                worker.execute(() -> {
                    try {
                        String url = "https://api.github.com/repos/" + repo + "/releases/latest";
                        Request req = new Request.Builder().url(url).header("Accept","application/vnd.github+json").build();
                        try (Response res = client.newCall(req).execute()) {
                            if (!res.isSuccessful() || res.body()==null) throw new IOException("릴리스 조회 HTTP " + res.code());
                            JSONObject release = new JSONObject(res.body().string());
                            String tag = release.optString("tag_name", "");
                            String page = release.optString("html_url", "");
                            String installed = "2.1.0-beta";
                            String remote = tag.startsWith("v") ? tag.substring(1) : tag;
                            boolean newer = compareVersion(remote, installed) > 0;
                            ui.post(() -> new AlertDialog.Builder(this).setTitle("배포 버전: " + tag)
                                .setMessage("설치 버전: " + installed + "\n" + (newer ? "업데이트가 있습니다. " : "현재보다 새로운 릴리스는 확인되지 않았습니다. ")
                                    + "GitHub 릴리스에 서명된 APK를 게시해야 설치할 수 있습니다. 같은 서명키로 빌드해야 덮어쓰기가 가능합니다.")
                                .setNegativeButton("닫기", null)
                                .setPositiveButton("릴리스 열기", (d2,w2) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page))))
                                .show());
                        }
                    } catch (Exception e) {
                        ui.post(() -> statusView.setText("업데이트 조회 실패: " + e.getMessage() + " · GitHub Releases 게시 여부 확인"));
                    }
                });
            }).show();
    }
    private void reconnect() {
        final int myGeneration = ++generation;
        lastFeedAt = 0;
        lastPrice = Double.NaN;
        bidTotal = askTotal = Double.NaN;
        priceView.setText("가격 수신 대기");
        signalView.setText("분석 준비 중");
        metricsView.setText("캔들 불러오는 중…");
        bookView.setText("호가 수신 대기");
        final String selectedSymbol = symbol, selectedCategory = category;
        if (socket != null) { socket.close(1000, "refresh"); socket = null; }
        statusView.setText("Bybit 캔들 불러오는 중…");
        worker.execute(() -> {
            try {
                Map<String, ArrayList<Candle>> loaded = new HashMap<>();
                for (String interval : FRAMES) loaded.put(interval, fetch(selectedCategory, selectedSymbol, interval));
                ui.post(() -> {
                    if (closed || myGeneration != generation) return;
                    bars.clear(); bars.putAll(loaded);
                    render();
                    startSocket(selectedCategory, selectedSymbol);
                });
            } catch (Exception e) {
                ui.post(() -> { if (myGeneration == generation) statusView.setText("초기 데이터 실패: " + e.getMessage() + " · 재시도하세요"); });
            }
        });
    }
    private ArrayList<Candle> fetch(String marketCategory, String marketSymbol, String interval) throws Exception {
        String url = "https://api.bybit.com/v5/market/kline?category=" + marketCategory + "&symbol=" + marketSymbol + "&interval=" + interval + "&limit=200";
        Request request = new Request.Builder().url(url).build();
        try (Response res = client.newCall(request).execute()) {
            if (!res.isSuccessful() || res.body()==null) throw new IOException("HTTP " + res.code());
            JSONObject obj = new JSONObject(res.body().string());
            if (obj.optInt("retCode", -1) != 0) throw new IOException(obj.optString("retMsg", "API 응답 오류"));
            JSONArray list = obj.getJSONObject("result").getJSONArray("list");
            ArrayList<Candle> result = new ArrayList<>();
            for (int i=list.length()-1; i>=0; i--) {
                JSONArray c = list.getJSONArray(i);
                result.add(new Candle(c.getLong(0), c.getDouble(4), c.getDouble(5), false));
            }
            return result;
        }
    }
    private void startSocket(String marketCategory, String marketSymbol) {
        if (closed) return;
        statusView.setText("실시간 스트림 연결 중…");
        Request request = new Request.Builder().url("wss://stream.bybit.com/v5/public/" + marketCategory).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send("{\"op\":\"subscribe\",\"args\":[\"tickers." + marketSymbol
                    + "\",\"orderbook.1." + marketSymbol + "\",\"kline.5." + marketSymbol
                    + "\",\"kline.15." + marketSymbol + "\",\"kline.60." + marketSymbol + "\"]}");
                ui.post(() -> { if (!closed) statusView.setText("Bybit " + marketSymbol + " 실시간 연결됨"); });
            }
            @Override public void onMessage(WebSocket ws, String text) {
                ui.post(() -> { if (!closed && ws == socket) handle(text); });
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                ui.post(() -> { if (!closed && ws == socket) statusView.setText("연결 오류: " + error.getMessage() + " · 재연결 버튼을 누르세요"); });
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                ui.post(() -> { if (!closed && ws == socket) statusView.setText("연결 종료 · 재연결 버튼을 누르세요"); });
            }
        });
    }
    private void handle(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String topic = event.optString("topic", "");
            if (topic.isEmpty()) return;
            lastFeedAt = System.currentTimeMillis();
            if (topic.startsWith("tickers.")) {
                JSONObject data = event.getJSONObject("data");
                if (data.has("lastPrice")) lastPrice = Double.parseDouble(data.getString("lastPrice"));
            } else if (topic.startsWith("orderbook.")) {
                JSONObject data = event.getJSONObject("data");
                JSONArray bids = data.optJSONArray("b"), asks = data.optJSONArray("a");
                if (bids!=null && bids.length()>0) bidTotal = bids.getJSONArray(0).getDouble(1);
                if (asks!=null && asks.length()>0) askTotal = asks.getJSONArray(0).getDouble(1);
            } else if (topic.startsWith("kline.")) {
                String interval = topic.split("\\.")[1];
                JSONArray updates = event.getJSONArray("data");
                ArrayList<Candle> list = bars.get(interval);
                if (list != null) {
                    for (int i=0;i<updates.length();i++) {
                        JSONObject c = updates.getJSONObject(i);
                        Candle candle = new Candle(c.getLong("start"), c.getDouble("close"), c.getDouble("volume"), c.optBoolean("confirm", false));
                        boolean found = false;
                        for (int j=list.size()-1;j>=Math.max(0,list.size()-3);j--) {
                            if (list.get(j).start == candle.start) { list.set(j,candle); found=true; break; }
                        }
                        if (!found && (list.isEmpty() || candle.start > list.get(list.size()-1).start)) list.add(candle);
                        while (list.size()>200) list.remove(0);
                    }
                }
            }
            render();
        } catch (Exception ignore) { /* malformed individual update: leave latest valid data visible */ }
    }
    private static double ema(double[] a, int period) {
        double k = 2.0 / (period+1); double e=a[0];
        for (int i=1;i<a.length;i++) e = k*a[i]+(1-k)*e;
        return e;
    }
    private static double rsi(double[] a) {
        if (a.length < 16) return Double.NaN;
        double gain=0,loss=0;
        for (int i=1;i<=14;i++) { double d=a[i]-a[i-1]; gain+=Math.max(0,d); loss+=Math.max(0,-d); }
        gain/=14; loss/=14;
        for (int i=15;i<a.length;i++) { double d=a[i]-a[i-1]; gain=(gain*13+Math.max(0,d))/14; loss=(loss*13+Math.max(0,-d))/14; }
        return loss==0 ? (gain==0 ? 50 : 100) : 100-100/(1+gain/loss);
    }
    private static double[] macd(double[] a) {
        double e12=a[0], e26=a[0], signal=0, macd=0;
        for (int i=0;i<a.length;i++) {
            e12=e12+(2.0/13)*(a[i]-e12);
            e26=e26+(2.0/27)*(a[i]-e26);
            macd=e12-e26;
            if (i==0) signal=macd; else signal+=2.0/10*(macd-signal);
        }
        return new double[]{macd,signal};
    }
    private static class Snapshot {
        double ema9, ema21, rsi, macd, macdSignal, last;
        int bias;
        String text;
    }
    private Snapshot snapshot(String frame) {
        ArrayList<Candle> list=bars.get(frame);
        if (list==null || list.size()<40) return null;
        // REST can include the current unfinished candle. Use completed candles only.
        int len=list.size()-1;
        if (len<35) return null;
        double[] a = new double[len];
        for (int i=0;i<len;i++) a[i]=list.get(i).close;
        Snapshot s=new Snapshot();
        s.last=a[len-1]; s.ema9=ema(a,9); s.ema21=ema(a,21); s.rsi=rsi(a);
        double[] m=macd(a); s.macd=m[0]; s.macdSignal=m[1];
        s.bias=(s.ema9>s.ema21 ? 1:-1)+(s.macd>s.macdSignal ? 1:-1)+(s.rsi>55?1:(s.rsi<45?-1:0));
        s.text=(s.bias>0?"상승":(s.bias<0?"하락":"중립"));
        return s;
    }
    private void render() {
        if (!Double.isNaN(lastPrice)) priceView.setText(String.format(Locale.US,"$%,.2f",lastPrice));
        Snapshot s5=snapshot("5"), s15=snapshot("15"), s60=snapshot("60");
        if (s5!=null && s15!=null && s60!=null) {
            int score = s5.bias*5+s15.bias*4+s60.bias*3;
            String direction = score>=12 ? "LONG 우위" : score<=-12 ? "SHORT 우위" : "관망 / 혼조";
            signalView.setText(direction + "  (지표점수 " + score + ")");
            signalView.setTextColor(score>=12 ? GREEN : score<=-12 ? RED : Color.YELLOW);
            metricsView.setText(String.format(Locale.US,
                "5분봉: %s · RSI %.1f · EMA9 %.0f / EMA21 %.0f\n15분봉: %s · RSI %.1f\n1시간봉: %s · RSI %.1f\n5분봉 MACD %.2f / Signal %.2f",
                s5.text,s5.rsi,s5.ema9,s5.ema21,s15.text,s15.rsi,s60.text,s60.rsi,s5.macd,s5.macdSignal));
        }
        if (!Double.isNaN(bidTotal) && !Double.isNaN(askTotal) && bidTotal+askTotal>0) {
            double share=100*bidTotal/(bidTotal+askTotal);
            bookView.setText(String.format(Locale.US,"최우선 호가 잔량 비중: 매수 %.1f%% / 매도 %.1f%%\n(1호가 잔량이며 전체 호가·실제 체결 방향은 아님)", share,100-share));
        }
        if (lastFeedAt>0) timeView.setText("최종 수신: " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new java.util.Date(lastFeedAt)) + " · 인터넷 연결 필요");
    }
    @Override protected void onDestroy() {
        closed=true;
        if (socket!=null) socket.close(1000,"Activity closed");
        worker.shutdownNow();
        client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
}
