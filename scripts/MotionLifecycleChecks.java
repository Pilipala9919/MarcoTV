// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.*;
import android.view.*;

/** Tests callbacks, attachment and interrupted transitions using production motion code. */
public final class MotionLifecycleChecks {
    static int passed;
    static void check(String label,boolean ok){if(!ok)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
    public static void main(String[] args){
        MainActivity host=new MainActivity();View owner=new View(host);
        Choreographer clock=Choreographer.getInstance();
        float[] value={0};int[] closed={0};
        UiMotion motion=new UiMotion(owner,0,26,v->value[0]=v);
        motion.to(1);
        check("detached_request_waits",clock.pending()==0);
        owner.attach();clock.frames(5);
        check("attachment_starts_pending_transition",value[0]>0&&value[0]<1);
        float position=motion.spring.value,velocity=motion.spring.velocity;
        motion.to(0,()->closed[0]++);
        check("exit_preserves_position_and_velocity",position==motion.spring.value&&velocity==motion.spring.velocity);
        clock.frames(3);position=value[0];
        motion.to(1);
        check("reopen_keeps_current_position",value[0]==position);
        clock.frames(160);
        check("reopened_transition_settles",value[0]==1);
        check("abandoned_exit_cannot_close_reopened_page",closed[0]==0);
        check("settled_transition_stops_frames",clock.pending()==0);
        motion.to(0,()->closed[0]++);clock.frames(4);owner.detach();position=value[0];clock.frames(20);
        check("detach_stops_callbacks",clock.pending()==0&&position==value[0]);
        owner.attach();clock.frames(160);
        check("reattachment_finishes_once",value[0]==0&&closed[0]==1);
        motion.to(1,()->closed[0]++);motion.cancel();clock.frames(160);
        check("cancel_discards_completion",closed[0]==1&&clock.pending()==0);
        host.duration=500;motion.snap(0);motion.speedOverride=Float.NaN;motion.to(1);clock.frames(8);float global=value[0];
        motion.snap(0);motion.speedOverride=1;motion.to(1);clock.frames(8);
        check("local_override_can_preserve_existing_task_speed",value[0]>global);
        motion.dispose();clock.frames(160);
        check("dispose_removes_frames_and_listener",clock.pending()==0);
        System.out.println("OK "+passed+" motion lifecycle checks (deterministic frame clock, not device proof)");
    }
}
class MainActivity extends Context {
    int duration=260;
    SharedPreferences appearance(){
        return (SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},
                (proxy,method,args)->method.getName().equals("getInt")?duration:null);
    }
}
