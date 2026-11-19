package com.btc.analyzer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only chart, confirmed candles and annotated levels. */
public final class PlanChartView extends View {
    private final Paint p=new Paint(3);
    private List<PlanEngine.Bar> bars=new ArrayList<>();
    private PlanEngine.Plan plan;
    public PlanChartView(Context context){super(context);setMinimumHeight(390);}
    public void setData(List<PlanEngine.Bar> bars,PlanEngine.Plan plan){this.bars=new ArrayList<>(bars);this.plan=plan;invalidate();}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.drawColor(Color.rgb(20,30,45));
        if(bars.isEmpty()||plan==null)return;
        int n=Math.min(70,bars.size()),start=bars.size()-n;
        float left=12,top=20,right=getWidth()-15,bottom=getHeight()-30;
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(int i=start;i<bars.size();i++){lo=Math.min(lo,bars.get(i).low);hi=Math.max(hi,bars.get(i).high);}
        double[] levels={plan.entry,plan.support,plan.resistance,plan.stop,plan.tp1,plan.tp2};
        for(double v:levels)if(Double.isFinite(v)){lo=Math.min(lo,v);hi=Math.max(hi,v);}
        double pad=Math.max((hi-lo)*.08,plan.entry*.0001);lo-=pad;hi+=pad;
        float width=(right-left)/n;
        for(int i=0;i<n;i++){
            PlanEngine.Bar bar=bars.get(start+i);float x=left+(i+.5f)*width;
            float yH=y(bar.high,lo,hi,top,bottom),yL=y(bar.low,lo,hi,top,bottom);
            float yO=y(bar.open,lo,hi,top,bottom),yC=y(bar.close,lo,hi,top,bottom);
            p.setColor(bar.close>=bar.open?Color.rgb(71,210,144):Color.rgb(244,105,122));p.setStrokeWidth(2);c.drawLine(x,yH,x,yL,p);
            c.drawRect(x-Math.max(1,width*.28f),Math.min(yO,yC),x+Math.max(1,width*.28f),Math.max(yO+1,yC),p);
        }
        line(c,plan.support,lo,hi,top,bottom,"지지",Color.rgb(248,199,89));
        line(c,plan.resistance,lo,hi,top,bottom,"저항",Color.rgb(248,199,89));
        line(c,plan.tp2,lo,hi,top,bottom,"TP2",Color.rgb(55,213,131));
        line(c,plan.tp1,lo,hi,top,bottom,"TP1",Color.rgb(92,245,155));
        line(c,plan.stop,lo,hi,top,bottom,"SL",Color.rgb(255,107,127));
        line(c,plan.entry,lo,hi,top,bottom,"진입",Color.WHITE);
    }
    private float y(double val,double lo,double hi,float top,float bottom){return (float)(bottom-(val-lo)/(hi-lo)*(bottom-top));}
    private void line(Canvas c,double value,double lo,double hi,float top,float bottom,String label,int color){
        if(!Double.isFinite(value))return;
        float yy=y(value,lo,hi,top,bottom);
        p.setColor(color);p.setStrokeWidth(2);c.drawLine(5,yy,getWidth()-5,yy,p);
        p.setTextSize(24);p.setStyle(Paint.Style.FILL);
        float labelY=Math.max(24,Math.min(getHeight()-8,yy-5));
        c.drawText(label+" "+String.format(Locale.US,"%.4f",value),13,labelY,p);
    }
}
