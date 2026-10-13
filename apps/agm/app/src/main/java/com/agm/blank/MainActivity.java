package com.agm.blank;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.webkit.*;
import android.hardware.*;
import org.json.*;

public class MainActivity extends Activity implements SensorEventListener {
    private WebView web;
    private SensorManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] types = {Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PRESSURE, Sensor.TYPE_ROTATION_VECTOR};
    private final float[][] values = new float[5][];
    private final long[] last = new long[5];
    private final int[] count = new int[5], accuracy = new int[5];
    private final float[] hz = new float[5];
    private final boolean[] enabled = new boolean[5];
    private long rateStart;
    private float pressure = Float.NaN, ground = Float.NaN;
    private boolean ready;
    private final GyroHeading gyroHeading = new GyroHeading();
    // Independent from magnetic heading and system fusion, including its initial state.
    private final GyroHeading aircraftGyro = new GyroHeading();
    private final float[] gravity = new float[3], magnetic = new float[3];
    private long gyroTime;
    private int alignCount;
    private boolean alignRequested;
    private final Runnable render = new Runnable() { public void run() { publish(); handler.postDelayed(this, 33); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (SensorManager)getSystemService(SENSOR_SERVICE);
        ground = getPreferences(0).getFloat("groundPressure", Float.NaN);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(6,11,17));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void zeroGyro() { handler.post(new Runnable() { public void run() {
                aircraftGyro.reset(); publish();
            }}); }
            @JavascriptInterface public void alignHeading() { handler.post(new Runnable() { public void run() {
                alignRequested=true; publish();
            }}); }
            @JavascriptInterface public void zeroGround() { handler.post(new Runnable() { public void run() {
                if (fresh(3)) { ground=pressure; getPreferences(0).edit().putFloat("groundPressure",ground).apply(); publish(); }
            }}); }
        }, "Sensors");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
            @Override public void onPageFinished(WebView v,String url) { ready=true; publish(); }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/instrument.html");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onResume() {
        super.onResume(); rateStart=SystemClock.elapsedRealtime();
        gyroHeading.reset(); gyroTime=0; alignRequested=false;
        aircraftGyro.reset();
        for(int i=0;i<types.length;i++) { last[i]=0; count[i]=0; hz[i]=0; Sensor s=manager.getDefaultSensor(types[i]); enabled[i]=s!=null && manager.registerListener(this,s,i==3?40000:10000); }
        handler.post(render);
    }
    @Override protected void onPause() { super.onPause(); manager.unregisterListener(this); handler.removeCallbacks(render); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); web.removeJavascriptInterface("Sensors"); web.destroy(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if(focus)getWindow().getDecorView().setSystemUiVisibility(5894); }
    @Override public void onAccuracyChanged(Sensor s,int a) { for(int i=0;i<types.length;i++)if(types[i]==s.getType())accuracy[i]=a; }
    @Override public void onSensorChanged(SensorEvent e) {
        for(int i=0;i<types.length;i++)if(types[i]==e.sensor.getType()) {
            boolean first=last[i]==0;
            if(i==0||i==2) { float[] filtered=i==0?gravity:magnetic; for(int k=0;k<3;k++)filtered[k]=first?e.values[k]:filtered[k]+.18f*(e.values[k]-filtered[k]); }
            values[i]=e.values.clone(); last[i]=SystemClock.elapsedRealtime(); count[i]++; accuracy[i]=e.accuracy;
            if(i==1) {
                gyroTime=e.timestamp; gyroHeading.update(e.values[0],e.values[1],e.values[2],e.timestamp);
                aircraftGyro.update(e.values[0],e.values[1],e.values[2],e.timestamp);
                if(!aircraftGyro.initialized) aircraftGyro.zero(e.timestamp);
            }
            if(i==3 && e.values[0]>0) { pressure=Float.isNaN(pressure)?e.values[0]:pressure+.12f*(e.values[0]-pressure); if(Float.isNaN(ground))ground=pressure; }
        }
    }
    private boolean fresh(int i) { return enabled[i] && last[i]>0 && SystemClock.elapsedRealtime()-last[i]<2000; }
    private float[] screenMatrix(float[] r) {
        int rotation=getWindowManager().getDefaultDisplay().getRotation(),x=SensorManager.AXIS_X,y=SensorManager.AXIS_Y;
        if(rotation==Surface.ROTATION_90){x=SensorManager.AXIS_Y;y=SensorManager.AXIS_MINUS_X;}
        else if(rotation==Surface.ROTATION_270){x=SensorManager.AXIS_MINUS_Y;y=SensorManager.AXIS_X;}
        else if(rotation==Surface.ROTATION_180){x=SensorManager.AXIS_MINUS_X;y=SensorManager.AXIS_MINUS_Y;}
        float[] out=new float[9]; SensorManager.remapCoordinateSystem(r,x,y,out); return out;
    }
    private Double heading(float[] r) {
        float[] s=screenMatrix(r);
        // Screen top is forward. Its bearing is undefined when pointing nearly vertically.
        if(Math.hypot(s[1],s[4])<.15)return null;
        return (Math.toDegrees(Math.atan2(s[1],s[4]))+360)%360;
    }
    private JSONArray vector(int i,float divisor) throws JSONException {
        if(!fresh(i))return null;
        float[] v=values[i]; float x=v[0],y=v[1];
        int rotation=getWindowManager().getDefaultDisplay().getRotation();
        if(rotation==Surface.ROTATION_90) { x=-v[1]; y=v[0]; }
        else if(rotation==Surface.ROTATION_270) { x=v[1]; y=-v[0]; }
        else if(rotation==Surface.ROTATION_180) { x=-v[0]; y=-v[1]; }
        return new JSONArray().put(x/divisor).put(y/divisor).put(v[2]/divisor);
    }
    private void publish() {
        if(!ready)return;
        try {
            long now=SystemClock.elapsedRealtime(),elapsed=now-rateStart;
            if(elapsed>=1000) { for(int i=0;i<5;i++){hz[i]=count[i]*1000f/elapsed;count[i]=0;}rateStart=now; }
            JSONObject o=new JSONObject();
            float[] magMatrix=new float[9];
            boolean magValid=fresh(0)&&fresh(2)&&SensorManager.getRotationMatrix(magMatrix,null,gravity,magnetic);
            Double magHeading=magValid?heading(magMatrix):null;
            if((!gyroHeading.initialized||alignRequested)&&magHeading!=null&&fresh(1)) {
                gyroHeading.align(magMatrix,gyroTime); alignRequested=false; alignCount++;
            }
            Double gyroBearing=gyroHeading.initialized&&fresh(1)?heading(gyroHeading.matrix()):null;
            boolean gyroAttitude=aircraftGyro.initialized&&fresh(1);
            o.put("gyroAttitude",gyroAttitude);
            if(gyroAttitude) {
                float[] gyroAngles=new float[3];
                SensorManager.getOrientation(screenMatrix(aircraftGyro.matrix()),gyroAngles);
                o.put("gyroPitch",-Math.toDegrees(gyroAngles[1]));
                o.put("gyroRoll",Math.toDegrees(gyroAngles[2]));
            }
            o.put("magHeading",magHeading==null?JSONObject.NULL:magHeading);
            o.put("gyroHeading",gyroBearing==null?JSONObject.NULL:gyroBearing);
            o.put("headingDelta",magHeading!=null&&gyroBearing!=null?(Object)((gyroBearing-magHeading+540)%360-180):JSONObject.NULL);
            o.put("canAlign",magHeading!=null&&fresh(1));o.put("alignCount",alignCount);
            o.put("acc",vector(0,1));o.put("gyro",vector(1,(float)(Math.PI/180)));o.put("mag",vector(2,1));
            o.put("pressure",fresh(3)?(Object)pressure:JSONObject.NULL);
            o.put("alt",fresh(3)?(Object)SensorManager.getAltitude(ground,pressure):JSONObject.NULL);
            float[] r=new float[9],screen=new float[9],angles=new float[3]; boolean attitude=false;
            if(fresh(4)) { SensorManager.getRotationMatrixFromVector(r,values[4]);attitude=true; }
            if(attitude) {
                int rotation=getWindowManager().getDefaultDisplay().getRotation(),x=SensorManager.AXIS_X,y=SensorManager.AXIS_Y;
                if(rotation==Surface.ROTATION_90){x=SensorManager.AXIS_Y;y=SensorManager.AXIS_MINUS_X;}
                else if(rotation==Surface.ROTATION_270){x=SensorManager.AXIS_MINUS_Y;y=SensorManager.AXIS_X;}
                else if(rotation==Surface.ROTATION_180){x=SensorManager.AXIS_MINUS_X;y=SensorManager.AXIS_MINUS_Y;}
                SensorManager.remapCoordinateSystem(r,x,y,screen);SensorManager.getOrientation(screen,angles);
                o.put("heading",(Math.toDegrees(angles[0])+360)%360);o.put("pitch",-Math.toDegrees(angles[1]));o.put("roll",Math.toDegrees(angles[2]));
            }
            o.put("attitude",attitude);o.put("cal",fresh(2)?accuracy[2]:-1);
            JSONArray rates=new JSONArray(),states=new JSONArray();for(int i=0;i<5;i++){rates.put(fresh(i)?hz[i]:0);states.put(!enabled[i]?"N/A":fresh(i)?"OK":"WAIT");}
            o.put("hz",rates);o.put("states",states);
            web.evaluateJavascript("window.updateSensors("+o.toString()+")",null);
        } catch(JSONException ignored) { }
    }
}
