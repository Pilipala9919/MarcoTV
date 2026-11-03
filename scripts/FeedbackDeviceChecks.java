// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Uses synthetic reports for the only cloud write; never opts the user's TV into uploads. */
public final class FeedbackDeviceChecks extends Instrumentation {
    MainActivity host;FeedbackPanel panel;int passed;
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");sendStatus(0,b);}
    void ui(Runnable task){final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MainActivity)host=(MainActivity)activity;}
    public void onStart(){
        Bundle result=new Bundle();Map<String,?> before=null;
        try{
            host=(MainActivity)startActivitySync(new Intent().setClassName("com.pilipala.tvhome","com.pilipala.tvhome.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            before=new HashMap<>(FeedbackManager.prefs(host).getAll());
            check("automatic_off_by_default",!FeedbackManager.automatic(host));
            JSONObject report=FeedbackManager.snapshot(host,"manual");
            check("bounded_allowed_payload",report.length()==13&&report.toString().getBytes("UTF-8").length<12000);
            check("no_network_identity_fields",!report.has("ip")&&!report.has("serial")&&!report.has("packages"));
            ui(()->{host.exitMenus();panel=new FeedbackPanel(host);panel.show();});
            for(int i=0;i<40;i++){
                final boolean[] loaded={false};ui(()->loaded[0]=host.isPage("feedback"));
                if(loaded[0])break;Thread.sleep(200);
            }
            ui(()->{
                check("feedback_page_loaded foreground="+host.foreground+" epoch="+host.menuEpoch+" loading="+host.isPage("feedback_loading"),host.isPage("feedback"));
                check("opening_does_not_enable_uploads",!FeedbackManager.automatic(host));
                panel.consent(true);check("automatic_requires_confirmation",host.isPage("feedback_consent")&&!FeedbackManager.automatic(host));
                host.panelWindow().onBackPressed();check("cancel_returns_to_feedback",host.isPage("feedback"));
                panel.qr();check("qr_page_opens",host.isPage("feedback_qr"));
                check("qr_does_not_enable_uploads",!FeedbackManager.automatic(host));
                host.panelWindow().onBackPressed();check("qr_back_restores_parent",host.isPage("feedback"));
            });
            JSONObject synthetic=new JSONObject().put("schema",1).put("id",UUID.randomUUID().toString().replace("-",""))
                    .put("version",ProjectInfo.VERSION).put("edition","standard").put("manufacturer","QA Fixture")
                    .put("model","Android transport test only").put("firmware","synthetic-feedback-check").put("api",28)
                    .put("reason","manual").put("home",false).put("service",false).put("events",new JSONArray()).put("frames",new JSONArray());
            CountDownLatch done=new CountDownLatch(1);String[] message={null};
            FeedbackManager.send(host,synthetic,text->{message[0]=text;done.countDown();});
            check("upload_callback_completes",done.await(30,TimeUnit.SECONDS));
            check("cloud_receipt_confirmed",message[0]!=null&&message[0].startsWith("已收到"));
            check("receipt_id_preserved",synthetic.getString("id").equals(FeedbackManager.prefs(host).getString("last_id","")));
            check("acknowledged_report_not_pending",!FeedbackManager.pending(host).exists());
            JSONObject state=FeedbackManager.request("/api/feedback/status",new JSONObject().put("id",synthetic.getString("id")));
            check("cloud_status_query_works","received".equals(state.optString("state")));
            check("automatic_still_off",!FeedbackManager.automatic(host));
            result.putString("stream","OK "+passed+" feedback device checks\n");
        }catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e)+"\n");}
        finally{
            if(host!=null&&before!=null){
                final Map<String,?> original=before;
                ui(()->{host.exitMenus();SharedPreferences.Editor editor=FeedbackManager.prefs(host).edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){
                    Object value=entry.getValue();if(value instanceof Boolean)editor.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Long)editor.putLong(entry.getKey(),(Long)value);else if(value instanceof String)editor.putString(entry.getKey(),(String)value);
                }editor.commit();});
            }
        }
        finish(-1,result);
    }
}
