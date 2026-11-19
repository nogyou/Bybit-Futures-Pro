package com.btc.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent, testable calculations. All prices are quote-currency prices, no orders placed. */
public final class PlanEngine {
    private PlanEngine() {}
    public static final class Bar {
        public final long start;
        public final double open, high, low, close, volume;
        public Bar(long start, double open, double high, double low, double close, double volume) {
            this.start=start;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;
        }
    }
    public static final class Plan {
        public final boolean longSide;
        public final double entry, atr, support, resistance, stop, tp1, tp2, rr1, rr2;
        public final String warning;
        public Plan(boolean longSide,double entry,double atr,double support,double resistance,double stop,double tp1,double tp2,double rr1,double rr2,String warning){
            this.longSide=longSide;this.entry=entry;this.atr=atr;this.support=support;this.resistance=resistance;
            this.stop=stop;this.tp1=tp1;this.tp2=tp2;this.rr1=rr1;this.rr2=rr2;this.warning=warning;
        }
        public boolean hasTarget(){return Double.isFinite(tp1) && rr1>0;}
    }
    public static double atr(List<Bar> data,int period) {
        if(data.size()<period+2) throw new IllegalArgumentException("캔들 부족");
        double sum=0; int start=data.size()-period;
        for(int i=start;i<data.size();i++){
            Bar b=data.get(i); double prev=data.get(i-1).close;
            sum+=Math.max(b.high-b.low,Math.max(Math.abs(b.high-prev),Math.abs(b.low-prev)));
        }
        return sum/period;
    }
    /** Pivot highs/lows of completed candles, excluding latest two to avoid edge confirmation bias. */
    public static Plan calculate(List<Bar> data, boolean longSide, double entry,double feePerSide) {
        if(data.size()<65 || !Double.isFinite(entry) || entry<=0 || feePerSide<0 || feePerSide>=0.02)
            throw new IllegalArgumentException("캔들·진입가·수수료를 확인하세요");
        for(Bar b:data) if(!Double.isFinite(b.high)||!Double.isFinite(b.low)||b.low<=0||b.high<b.low)
            throw new IllegalArgumentException("잘못된 캔들 데이터");
        double atr=atr(data,14);
        if(!(atr>0))throw new IllegalArgumentException("변동성 산출 불가");
        List<Double> lows=new ArrayList<>(), highs=new ArrayList<>();
        int from=Math.max(2,data.size()-120), end=data.size()-2;
        for(int i=from;i<end;i++){
            Bar b=data.get(i);
            if(b.low<=data.get(i-1).low && b.low<=data.get(i-2).low && b.low<=data.get(i+1).low && b.low<=data.get(i+2).low)lows.add(b.low);
            if(b.high>=data.get(i-1).high && b.high>=data.get(i-2).high && b.high>=data.get(i+1).high && b.high>=data.get(i+2).high)highs.add(b.high);
        }
        Collections.sort(lows);Collections.sort(highs);
        double support=Double.NaN,resistance=Double.NaN;
        for(double v:lows)if(v<entry-0.15*atr)support=v;
        for(double v:highs)if(v>entry+0.15*atr){resistance=v;break;}
        double structure=longSide?support:resistance;
        // Require a real swing level to set a structure-based stop; otherwise no fabricated prices.
        double stop=Double.NaN;
        if(Double.isFinite(structure))stop=longSide?Math.min(entry-1.2*atr,structure-0.25*atr):Math.max(entry+1.2*atr,structure+0.25*atr);
        double risk=Double.isFinite(stop)?Math.abs(entry-stop):Double.NaN;
        List<Double> targets=new ArrayList<>();
        List<Double> levels=longSide?highs:lows;
        for(double v:levels)if(longSide?v>entry+0.4*atr:v<entry-0.4*atr) {
            boolean duplicate=false;for(double t:targets)if(Math.abs(v-t)<0.35*atr){duplicate=true;break;}
            if(!duplicate)targets.add(v);
        }
        targets.sort(longSide?Double::compare:(a,b)->Double.compare(b,a));
        double tp1=targets.size()>0?targets.get(0):Double.NaN;
        double tp2=targets.size()>1?targets.get(1):Double.NaN;
        double rr1=rr(longSide,entry,stop,tp1,feePerSide),rr2=rr(longSide,entry,stop,tp2,feePerSide);
        String warning;
        if(!Double.isFinite(stop))warning="구조적 손절선 확인 불가 · 거래 보류";
        else if(!Double.isFinite(tp1))warning="유효한 차트 목표 구간 없음 · 거래 보류";
        else if(rr1<1.5)warning="1차 목표 순손익비 1:1.5 미만 · 진입 재검토";
        else warning="차트 기준 후보선 · 실시간 유동성/포지션 위험 추가 확인";
        return new Plan(longSide,entry,atr,support,resistance,stop,tp1,tp2,rr1,rr2,warning);
    }
    public static double rr(boolean longSide,double entry,double stop,double target,double feePerSide){
        if(!Double.isFinite(stop)||!Double.isFinite(target))return Double.NaN;
        double reward=(longSide?target-entry:entry-target)-feePerSide*(entry+target);
        double risk=(longSide?entry-stop:stop-entry)+feePerSide*(entry+stop);
        return risk>0?Math.max(0,reward)/risk:Double.NaN;
    }
}
