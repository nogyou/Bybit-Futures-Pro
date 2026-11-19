package com.btc.analyzer;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class TradePlanActivity extends Activity {
    private final OkHttpClient client=new OkHttpClient();
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private EditText entry,fee;
    private Spinner side,frame;
    private TextView status,levels;
    private PlanChartView chart;
    private String symbol,category;
    private PlanEngine.Plan current;
    private boolean active=true;
    private String loadedFrame="";
    private String analyzedEntryInput="", analyzedFeeInput="";
    @Override public void onCreate(Bundle b){super.onCreate(b);
        symbol=getIntent().getStringExtra("symbol");category=getIntent().getStringExtra("category");
        if(symbol==null||!symbol.matches("[A-Z0-9]{2,40}")||!("linear".equals(category)||"inverse".equals(category))){finish();return;}
        ScrollView sc=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(26,24,26,28);root.setBackgroundColor(Color.rgb(12,19,31));sc.addView(root);
        add(root,"스마트 TP/SL · "+symbol,23,Color.WHITE);
        add(root,"실제 캔들에서 피벗 고점/저점과 ATR을 계산합니다. 자동 주문하지 않습니다.",14,Color.LTGRAY);
        side=new Spinner(this);side.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"LONG","SHORT"}));root.addView(side);
        frame=new Spinner(this);frame.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"5분봉","15분봉","1시간봉"}));frame.setSelection(1);root.addView(frame);
        entry=number(root,"진입가 · 비워두면 최근 완료 캔들 종가 사용");
        fee=number(root,"편도 수수료 (%) 예: 0.055");fee.setText("0.055");
        Button calc=new Button(this);calc.setText("차트 분석 · 목표/손절 계산");root.addView(calc);calc.setOnClickListener(v->analyze());
        status=add(root,"분석을 눌러주세요",15,Color.YELLOW);
        chart=new PlanChartView(this);root.addView(chart,new LinearLayout.LayoutParams(-1,(int)(getResources().getDisplayMetrics().density*360)));
        levels=add(root,"분석 전",17,Color.WHITE);
        Button watch=new Button(this);watch.setText("이 계획으로 포지션 가디언 시작");root.addView(watch);watch.setOnClickListener(v->savePlan());
        add(root,"손익비는 수수료 가정 반영·슬리피지/펀딩비 미포함. 지지·저항은 후행적 가격구조 추정치입니다. 실거래 전 거래소 손절 주문을 따로 등록하세요.",13,Color.LTGRAY);
        setContentView(sc);
    }
    private TextView add(LinearLayout root,String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setPadding(0,12,0,12);root.addView(t);return t;}
    private EditText number(LinearLayout root,String hint){EditText x=new EditText(this);x.setHint(hint);x.setHintTextColor(Color.LTGRAY);x.setTextColor(Color.WHITE);x.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);root.addView(x);return x;}
    private static String fmt(double n){return Double.isFinite(n)?String.format(Locale.US,"%,.5f",n):"데이터 부족";}
    private static String rr(double n){return Double.isFinite(n)?String.format(Locale.US,"1 : %.2f",n):"계산 불가";}
    private void analyze(){
        final boolean isLong=side.getSelectedItemPosition()==0;
        final String interval=new String[]{"5","15","60"}[frame.getSelectedItemPosition()];
        final String inputEntry=entry.getText().toString().trim(),inputFee=fee.getText().toString().trim();
        final double feeVal;
        try{feeVal=Double.parseDouble(inputFee)/100;if(!Double.isFinite(feeVal)||feeVal<0||feeVal>=0.02)throw new Exception();}
        catch(Exception ex){status.setText("편도 수수료를 올바르게 입력하세요");return;}
        status.setText("바이비트 완료 캔들 분석 중…");current=null;
        io.execute(()->{
            try(Response response=client.newCall(new Request.Builder().url("https://api.bybit.com/v5/market/kline?category="+category+"&symbol="+symbol+"&interval="+interval+"&limit=200").build()).execute()){
                if(!response.isSuccessful()||response.body()==null)throw new Exception("HTTP "+response.code());
                JSONObject data=new JSONObject(response.body().string());
                if(data.optInt("retCode",-1)!=0)throw new Exception(data.optString("retMsg","API error"));
                JSONArray arr=data.getJSONObject("result").getJSONArray("list");
                ArrayList<PlanEngine.Bar> candles=new ArrayList<>();
                // Reverse newest-first response; exclude newest possibly incomplete candle.
                for(int i=arr.length()-1;i>=1;i--){JSONArray c=arr.getJSONArray(i);candles.add(new PlanEngine.Bar(c.getLong(0),c.getDouble(1),c.getDouble(2),c.getDouble(3),c.getDouble(4),c.getDouble(5)));}
                if(candles.isEmpty())throw new Exception("완료된 캔들 부족");
                double e=inputEntry.isEmpty()?candles.get(candles.size()-1).close:Double.parseDouble(inputEntry);
                PlanEngine.Plan plan=PlanEngine.calculate(candles,isLong,e,feeVal);
                ui.post(()->{
                    if(!active)return;
                    current=plan;loadedFrame=interval;analyzedEntryInput=inputEntry;analyzedFeeInput=inputFee;
                    status.setText("분석 완료 · "+(interval.equals("5")?"5분":interval.equals("15")?"15분":"1시간")+" 완료 캔들 기준 (실시간 자동재계산 아님)");
                    chart.setData(candles,plan);
                    levels.setText("진입: "+fmt(plan.entry)+"\n지지: "+fmt(plan.support)+" / 저항: "+fmt(plan.resistance)+
                       "\n1차 익절 TP1: "+fmt(plan.tp1)+" · 순손익비 "+rr(plan.rr1)+
                       "\n2차 익절 TP2: "+fmt(plan.tp2)+" · 순손익비 "+rr(plan.rr2)+
                       "\n구조적 손절 SL: "+fmt(plan.stop)+"\nATR(14): "+fmt(plan.atr)+"\n\n"+plan.warning);
                });
            }catch(Exception ex){ui.post(()->{if(active){status.setText("분석 실패: "+ex.getMessage());levels.setText("데이터 확인 후 다시 시도하세요");}});}
        });
    }
    private void savePlan(){
        if(current==null||!Double.isFinite(current.stop)||!current.hasTarget()){
            status.setText("유효한 손절/1차 목표가 없어 감시 계획을 저장할 수 없습니다");return;
        }
        if(!loadedFrame.equals(new String[]{"5","15","60"}[frame.getSelectedItemPosition()]) || (current.longSide != (side.getSelectedItemPosition()==0)) || !analyzedEntryInput.equals(entry.getText().toString().trim()) || !analyzedFeeInput.equals(fee.getText().toString().trim())){
            status.setText("설정이 변경됐습니다. 다시 분석해주세요");return;
        }
        SharedPreferences.Editor p=getSharedPreferences("guardian",MODE_PRIVATE).edit();
        p.putBoolean("enabled",true).putString("symbol",symbol).putString("category",category)
          .putString("side",current.longSide?"LONG":"SHORT").putString("entry",Double.toString(current.entry))
          .putString("stop",Double.toString(current.stop)).putString("tp1",Double.toString(current.tp1))
          .putString("tp2",Double.toString(current.tp2)).apply();
        try{android.content.Intent intent=new android.content.Intent(this,PositionGuardianService.class);
            if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            status.setText("포지션 감시 시작 · 알림 권한 및 배터리 설정을 확인하세요");
        }catch(Exception ex){status.setText("감시 실행 실패: "+ex.getMessage());}
    }
    @Override protected void onDestroy(){active=false;io.shutdownNow();client.dispatcher().executorService().shutdown();super.onDestroy();}
}
