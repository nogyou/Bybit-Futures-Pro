package com.btc.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PositionGuardianService extends Service {
    static final String CHANNEL="guardian_watch";
    static final String ALERT_CHANNEL="guardian_alert";
    private static final long TICK_MS=30000L;
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).build();
    private ScheduledExecutorService timer;
    private NotificationManager notifications;
    private String symbol, category, side;
    private double entry;
    private int previousLevel=0, repeats=0;
    private long lastAlertAt=0;
    private long lastLevelAlertAt=0;
    private int lastTargetStage=0;
    private boolean stopNotified=false;
    private volatile boolean active=false;

    @Override public void onCreate() {
        super.onCreate();
        notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"포지션 감시 상태",NotificationManager.IMPORTANCE_LOW));
            notifications.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL,"포지션 위험 알림",NotificationManager.IMPORTANCE_HIGH));
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        SharedPreferences p=getSharedPreferences("guardian",MODE_PRIVATE);
        if(!p.getBoolean("enabled",false)) { stopSelf(); return START_NOT_STICKY; }
        String s=p.getString("symbol","BTCUSDT"), c=p.getString("category","linear"), d=p.getString("side","LONG");
        if (!s.matches("[A-Z0-9]{2,40}") || !(c.equals("linear") || c.equals("inverse")) || !(d.equals("LONG")||d.equals("SHORT"))) { stopSelf();return START_NOT_STICKY; }
        symbol=s; category=c; side=d;
        try { entry=Double.parseDouble(p.getString("entry","0")); }catch(Exception e){entry=0;}
        try {
            Notification n=notification("감시 중: " + symbol + " " + side,"30초 간격 지표 확인 · 앱에서 중지 가능",false);
            if(Build.VERSION.SDK_INT>=29) startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(10,n);
        } catch (Exception e) { Log.e("Guardian","Foreground start failed",e);stopSelf(); return START_NOT_STICKY; }
        if(timer!=null)timer.shutdownNow();
        previousLevel=0; repeats=0; lastAlertAt=0; lastLevelAlertAt=0; lastTargetStage=0; stopNotified=false; active=true;
        timer=Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(this::check,0,TICK_MS,TimeUnit.MILLISECONDS);
        return START_NOT_STICKY; // Never claim automatic restart after process death
    }
    private JSONObject get(String path) throws Exception {
        try(Response r=client.newCall(new Request.Builder().url("https://api.bybit.com"+path).build()).execute()) {
            if(!r.isSuccessful()||r.body()==null)throw new Exception("HTTP "+r.code());
            JSONObject o=new JSONObject(r.body().string());
            if(o.optInt("retCode",-1)!=0)throw new Exception(o.optString("retMsg","Bybit API error"));
            return o.getJSONObject("result");
        }
    }
    private static double ema(double[] a,int n){double v=a[0],k=2.0/(n+1);for(int i=1;i<a.length;i++)v=k*a[i]+(1-k)*v;return v;}
    private static double rsi(double[] a) {
        double gain=0,loss=0;
        for(int i=1;i<=14;i++){double delta=a[i]-a[i-1];gain+=Math.max(0,delta);loss+=Math.max(0,-delta);}
        gain/=14;loss/=14;
        for(int i=15;i<a.length;i++){double delta=a[i]-a[i-1];gain=(gain*13+Math.max(0,delta))/14;loss=(loss*13+Math.max(0,-delta))/14;}
        return loss==0?(gain==0?50:100):100-100/(1+gain/loss);
    }
    private static int bias(double[] a) {
        int n=a.length; if(n<45)throw new IllegalArgumentException("캔들 데이터 부족");
        double fast=ema(a,9),slow=ema(a,21),r=rsi(a),e12=a[0],e26=a[0],sig=0,macd=0;
        for(int i=0;i<n;i++){
            e12+=(2.0/13)*(a[i]-e12); e26+=(2.0/27)*(a[i]-e26);
            macd=e12-e26; sig+=(2.0/10)*(macd-sig);
        }
        return (fast>slow?1:-1)+(macd>sig?1:-1)+(r>55?1:r<45?-1:0);
    }
    private int frameScore(String interval) throws Exception {
        JSONObject result=get("/v5/market/kline?category="+category+"&symbol="+symbol+"&interval="+interval+"&limit=120");
        JSONArray arr=result.getJSONArray("list");
        if(arr.length()<50)throw new Exception("Kline insufficient");
        // API returns newest first. Exclude the latest *unfinished* candle (index 0).
        double[] closes=new double[arr.length()-1];
        for(int i=arr.length()-1,j=0;i>=1;i--,j++) closes[j]=Double.parseDouble(arr.getJSONArray(i).getString(4));
        return bias(closes);
    }
    private void check() {
        if(!active)return;
        try {
            checkPriceLevels();
            int score=frameScore("5")*5+frameScore("15")*4+frameScore("60")*3;
            // Opposite exposure: long risks negative score, short risks positive score.
            int adverse=side.equals("LONG")?-score:score;
            int level=adverse>=12?2:adverse>=0?1:0;
            if(level==previousLevel)repeats++;else {previousLevel=level;repeats=1;}
            SharedPreferences.Editor ed=getSharedPreferences("guardian",MODE_PRIVATE).edit();
            ed.putInt("lastScore",score).putInt("riskLevel",level).putLong("lastCheckAt",System.currentTimeMillis()).putString("lastError","").apply();
            if(level>0 && repeats>=2 && System.currentTimeMillis()-lastAlertAt>=180000) {
                String message=level==2?"반대 방향 기술 지표 우세 (점수 "+score+")":"기존 방향의 기술 지표 약화 (점수 "+score+")";
                notifications.notify(11,notification("["+(level==2?"위험":"주의")+"] "+symbol+" "+side,message,true));
                lastAlertAt=System.currentTimeMillis();
            }
        }catch(Exception e){
            getSharedPreferences("guardian",MODE_PRIVATE).edit().putString("lastError",String.valueOf(e.getMessage())).apply();
            // No signal on failed/stale data.
            Log.w("Guardian","Data unavailable",e);
        }
    }
    private static double readPositive(SharedPreferences p,String key) {
        try { double v=Double.parseDouble(p.getString(key,""));return Double.isFinite(v)&&v>0?v:Double.NaN; }
        catch(Exception ex){return Double.NaN;}
    }
    private void checkPriceLevels() throws Exception {
        SharedPreferences prefs=getSharedPreferences("guardian",MODE_PRIVATE);
        double stop=readPositive(prefs,"stop"), tp1=readPositive(prefs,"tp1"),tp2=readPositive(prefs,"tp2");
        if(!Double.isFinite(stop)&&!Double.isFinite(tp1)) return; // Manual guardian has no plan.
        JSONObject result=get("/v5/market/tickers?category="+category+"&symbol="+symbol);
        JSONArray list=result.getJSONArray("list");
        if(list.length()==0)throw new Exception("Ticker missing");
        double price=Double.parseDouble(list.getJSONObject(0).getString("lastPrice"));
        if(!Double.isFinite(price)||price<=0)throw new Exception("Invalid ticker");
        boolean longSide=side.equals("LONG");
        boolean stopHit=Double.isFinite(stop)&&(longSide?price<=stop:price>=stop);
        boolean target1=Double.isFinite(tp1)&&(longSide?price>=tp1:price<=tp1);
        boolean target2=Double.isFinite(tp2)&&(longSide?price>=tp2:price<=tp2);
        int stage=target2?2:target1?1:0;
        prefs.edit().putString("lastPrice",Double.toString(price)).putLong("lastPriceAt",System.currentTimeMillis()).apply();
        long now=System.currentTimeMillis();
        if(stopHit&&!stopNotified&&now-lastLevelAlertAt>15000) {
            notifications.notify(12,notification("[손절 참고선 도달] "+symbol,
                String.format(Locale.US,"현재 %.5f / SL %.5f · 거래소 주문 확인",price,stop),true));
            stopNotified=true;lastLevelAlertAt=now;
        }else if(stage>lastTargetStage&&now-lastLevelAlertAt>15000){
            double target=stage==2?tp2:tp1;
            notifications.notify(13,notification("[TP"+stage+" 목표 도달] "+symbol,
                String.format(Locale.US,"현재 %.5f / 목표 %.5f · 익절 검토",price,target),true));
            lastTargetStage=stage;lastLevelAlertAt=now;
        }
        // Do not reset stop alert on oscillation; stop watching or re-register to reset.
    }
    private Notification notification(String title,String body,boolean alert) {
        Intent launch=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,alert?ALERT_CHANNEL:CHANNEL):new Notification.Builder(this);
        b.setContentTitle(title).setContentText(body).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).setAutoCancel(alert).setOnlyAlertOnce(!alert);
        if(!alert)b.setOngoing(true);
        return b.build();
    }
    @Override public void onTimeout(int startId,int fgsType) {
        getSharedPreferences("guardian",MODE_PRIVATE).edit().putString("lastError","Android 백그라운드 감시 시간 제한 도달").apply();
        stopSelf();
    }
    @Override public void onDestroy() {
        active=false;
        if(timer!=null)timer.shutdownNow();
        client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
