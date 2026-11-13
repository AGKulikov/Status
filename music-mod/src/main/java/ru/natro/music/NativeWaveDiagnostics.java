/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.music;
import android.os.SystemClock;
import android.os.Process;
import android.util.Log;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/** Observes native decisions; never calls the player, network or native analytics. */
public final class NativeWaveDiagnostics {
    private static final AtomicLong sequence=new AtomicLong();
    private static volatile Field groupedName, groupedData;
    private NativeWaveDiagnostics() {}
    public static void onTelemetry(String event, Map<?,?> data) {
        try { String text=telemetry(event,data); if(text!=null) emit(text); } catch(Throwable ignored) { }
    }
    /** nz0.b is a separate route: prefetch decisions do not call nz0.a. */
    public static void onGroupedTelemetry(Object event) {
        try {
            if(event==null || !"lc0".equals(event.getClass().getName()))return;
            Field name=groupedName, data=groupedData;
            if(name==null || data==null) {
                name=event.getClass().getField("a"); data=event.getClass().getField("b");
                if(name.getType()!=String.class || !Map.class.isAssignableFrom(data.getType()))return;
                groupedName=name; groupedData=data;
            }
            Object attrs=data.get(event);
            if(attrs instanceof Map)onTelemetry((String)name.get(event),(Map<?,?>)attrs);
        } catch(Throwable ignored) { }
    }
    static String telemetry(String event,Map<?,?> data) {
        if(data==null || !("OFFLINEWAVE_START".equals(event)
                || "OFFLINEWAVE_PREFETCH_TRIGGER".equals(event)
                || "OFFLINEWAVE_ERROR".equals(event))) return null;
        // Do not copy track/account/session IDs, URLs, seeds, messages or arbitrary map values.
        String result="event="+event;
        if("OFFLINEWAVE_ERROR".equals(event))return result+" reason=UNSPECIFIED_NATIVE_ERROR";
        result+=" reason="+allowed(data.get("reason"),
                "Prolongation|SkipToOffline|CreateQueueWithSeeds|CreateQueueWithPassiveSession|LOW_RATIO|PREFETCH_FAILED|ABOVE_THRESHOLD|OFFLINE_UNAVAILABLE|RECOMMENDER_ERROR|TIMEOUT");
        if("OFFLINEWAVE_PREFETCH_TRIGGER".equals(event)) {
            result+=" decision="+allowed(data.get("decision"),"SWITCHED|KEPT_ONLINE");
            result+=" thresholdType="+allowed(data.get("thresholdType"),"RATIO|PREFETCHED_DURATION_MS");
            for(String key:new String[]{"prefetchRatio","threshold","offlineBatchWaitMs"}) {
                Object value=data.get(key);
                if(value instanceof Integer || value instanceof Long) {
                    long number=((Number)value).longValue();
                    if(number>=0)result+=" "+key+"="+number;
                }
            }
        }
        return result;
    }
    private static String allowed(Object value,String choices) {
        if(!(value instanceof String))return "UNKNOWN";
        for(String choice:choices.split("\\|"))if(choice.equals(value))return choice;
        return "UNKNOWN";
    }
    public static void onNativeLog(String tag,String message,Throwable failure) {
        try {
            String event=nativeLog(tag,message,failure);
            if(event!=null)emit(event);
        } catch(Throwable ignored) { }
    }
    static String nativeLog(String tag,String message,Throwable failure) {
            String event=null;
            if("WaveProlongationOperation".equals(tag)) {
                if("GetProlongationResult: online prolongation failed after offline, keeping offline result".equals(message))
                    event="event=ONLINE_RETRY_FAILED reason=KEEP_OFFLINE_RESULT";
                else if("GetProlongationResult: online prolongation completed after offline, take online result".equals(message))
                    event="event=ONLINE_RESULT_SELECTED reason=ONLINE_PROLONGATION_COMPLETED";
                else if("Perform migration from offline to online session".equals(message))
                    event="event=ONLINE_MIGRATION_STARTED reason=ONLINE_SESSION_AVAILABLE";
                else if("Online prolongation is failed, fallback to offline prolongation".equals(message))
                    event="event=OFFLINE_FALLBACK reason=ONLINE_PROLONGATION_FAILED";
                else if("Offline prolongation is failed, wait for online prolongation".equals(message))
                    event="event=ONLINE_WAIT reason=OFFLINE_PROLONGATION_FAILED";
            } else if("OfflineWavePrefetchTrigger".equals(tag)) {
                if("canStartOfflineWave failed".equals(message))
                    event="event=PREFETCH_PROBE_FAILED reason=RECOMMENDER_ERROR";
                else if("Timeout canStartOfflineWave".equals(message))
                    event="event=PREFETCH_PROBE_FAILED reason=TIMEOUT";
            }
            return event==null?null:event+" error_kind="+errorKind(failure);
    }
    static String errorKind(Throwable failure) {
        for(int i=0;failure!=null && i<5;i++) {
            if(failure instanceof java.net.UnknownHostException)return "DNS";
            if(failure instanceof java.net.SocketTimeoutException)return "TIMEOUT";
            if(failure instanceof java.net.ConnectException)return "CONNECT";
            if(failure instanceof javax.net.ssl.SSLException)return "TLS";
            if(failure instanceof java.util.concurrent.CancellationException)return "CANCELLED";
            Throwable next=failure.getCause();if(next==failure)break;failure=next;
        }
        return "UNKNOWN";
    }
    private static synchronized void emit(String text) {
        Log.i("NatroMusicWave","v=1 pid="+Process.myPid()+" elapsed="+SystemClock.elapsedRealtime()
                +" seq="+sequence.incrementAndGet()+" wall="+System.currentTimeMillis()+" "+text);
    }
}
