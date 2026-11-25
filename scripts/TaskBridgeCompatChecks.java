// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercises real TaskBridgeServer Binder call reflection (API 23-34+) and recent-task list unwrapping. */
public final class TaskBridgeCompatChecks {
    private static int passed;

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    public interface ProviderApi28 {
        Object call(String callingPkg, String method, String arg, Object extras);
    }

    public interface ProviderApi29 {
        Object call(String callingPkg, String authority, String method, String arg, Object extras);
    }

    public interface ProviderApi30 {
        Object call(String callingPkg, String attributionTag, String authority, String method, String arg, Object extras);
    }

    public static final class AttributionSource {
        final int uid;
        final String packageName;

        AttributionSource(int uid, String packageName) {
            this.uid = uid;
            this.packageName = packageName;
        }

        public static final class Builder {
            private final int uid;
            private String packageName;

            public Builder(int uid) {
                this.uid = uid;
            }

            public Builder setPackageName(String value) {
                this.packageName = value;
                return this;
            }

            public AttributionSource build() {
                return new AttributionSource(uid, packageName);
            }
        }
    }

    public interface ProviderApi31 {
        Object call(AttributionSource source, String authority, String method, String arg, Object extras);
    }

    public interface ProviderUnsupported {
        Object call(int unknownSignature);
    }

    public static final class FakeParceledListSlice {
        private final List<String> items;

        FakeParceledListSlice(List<String> items) {
            this.items = items;
        }

        public List<String> getList() {
            return items;
        }
    }

    public static final class FakeActivityTaskManager {
        public static Object getService() {
            return "atm-service";
        }
    }

    public static final class FakeActivityManager {
        public static Object getService() {
            return "am-service";
        }
    }

    public static final class FakeActivityManagerNoGetService {
        // Simulates Android 6.0-7.1 (API 23-25) where ActivityManager.getService() does not exist.
    }

    public static final class FakeActivityManagerNative {
        public static Object getDefault() {
            return "am-native-default";
        }
    }

    public interface RemoveTaskOneArg {
        boolean removeTask(int taskId);
    }

    public interface RemoveTaskTwoArg {
        boolean removeTask(int taskId, int flags);
    }

    public interface GetTasksApi23 {
        List<String> getTasks(int maxNum, int flags);
    }

    public interface GetTasksApi28 {
        List<String> getTasks(int maxNum);
    }

    public interface GetTasksApi31 {
        List<String> getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId);
    }

    public interface AudioApi28 {
        void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller);
        int getStreamVolume(int streamType);
        int getStreamMaxVolume(int streamType);
    }

    public interface AudioApi31 {
        void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller, int uid);
    }

    public interface AudioFallbackOnly {
        void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);
    }

    static final class VirtualScheduler implements VolumeRepeatEngine.Scheduler {
        private static final class Entry {
            final long runAt;
            final long seq;
            final Runnable task;
            boolean cancelled;
            Entry(long runAt, long seq, Runnable task) { this.runAt = runAt; this.seq = seq; this.task = task; }
        }
        long now = 1000L;
        private long seq;
        private final java.util.List<Entry> entries = new java.util.ArrayList<>();
        @Override public VolumeRepeatEngine.TaskHandle schedule(Runnable task, long delayMs) {
            Entry e = new Entry(now + delayMs, ++seq, task);
            entries.add(e);
            return () -> e.cancelled = true;
        }
        void advanceBy(long deltaMs) {
            long target = now + deltaMs;
            while (true) {
                Entry next = null;
                for (Entry e : entries) {
                    if (e.cancelled || e.runAt > target) continue;
                    if (next == null || e.runAt < next.runAt || (e.runAt == next.runAt && e.seq < next.seq)) next = e;
                }
                if (next == null) break;
                entries.remove(next);
                now = next.runAt;
                next.task.run();
            }
            now = target;
        }
    }

    static byte[] encodeInputEvent(boolean is64Bit, int type, int code, int value) {
        byte[] buf = new byte[VolumeRepeatEngine.eventSize(is64Bit)];
        int off = is64Bit ? 16 : 8;
        buf[off] = (byte) (type & 0xFF);
        buf[off + 1] = (byte) ((type >>> 8) & 0xFF);
        buf[off + 2] = (byte) (code & 0xFF);
        buf[off + 3] = (byte) ((code >>> 8) & 0xFF);
        buf[off + 4] = (byte) (value & 0xFF);
        buf[off + 5] = (byte) ((value >>> 8) & 0xFF);
        buf[off + 6] = (byte) ((value >>> 16) & 0xFF);
        buf[off + 7] = (byte) ((value >>> 24) & 0xFF);
        return buf;
    }

    public static void main(String[] args) throws Exception {
        final String atm = FakeActivityTaskManager.class.getName();
        final String am = FakeActivityManager.class.getName();
        final String amNoGet = FakeActivityManagerNoGetService.class.getName();
        final String amNative = FakeActivityManagerNative.class.getName();

        check("service_api23_uses_activity_manager_native",
                "am-native-default".equals(TaskBridgeServer.resolveService(23, true, atm, amNoGet, amNative))
                        && "am-native-default".equals(TaskBridgeServer.resolveService(23, false, atm, amNoGet, amNative)));
        check("service_api24_25_uses_activity_manager_native",
                "am-native-default".equals(TaskBridgeServer.resolveService(24, true, atm, amNoGet, amNative))
                        && "am-native-default".equals(TaskBridgeServer.resolveService(25, false, atm, amNoGet, amNative)));
        check("service_api26_28_uses_activity_manager_get_service",
                "am-service".equals(TaskBridgeServer.resolveService(26, true, atm, am, amNative))
                        && "am-service".equals(TaskBridgeServer.resolveService(28, false, atm, am, amNative)));
        check("service_api26_missing_get_service_falls_back_to_native",
                "am-native-default".equals(TaskBridgeServer.resolveService(26, true, atm, amNoGet, amNative)));
        check("service_api29_plus_distinguishes_atm_and_am",
                "atm-service".equals(TaskBridgeServer.resolveService(29, true, atm, am, amNative))
                        && "am-service".equals(TaskBridgeServer.resolveService(29, false, atm, am, amNative)));

        RemoveTaskOneArg rm1 = id -> id == 42;
        check("remove_task_one_arg", TaskBridgeServer.invokeRemoveTask(rm1, RemoveTaskOneArg.class, 42));
        RemoveTaskTwoArg rm2 = (id, flags) -> id == 42 && flags == 0;
        check("remove_task_two_arg_fallback", TaskBridgeServer.invokeRemoveTask(rm2, RemoveTaskTwoArg.class, 42));

        GetTasksApi23 gt23 = (maxNum, flags) ->
                (maxNum == 1 && flags == 0) ? Collections.singletonList("api23-ok") : Collections.emptyList();
        check("get_tasks_api23_passes_zero_flags",
                Collections.singletonList("api23-ok").equals(TaskBridgeServer.invokeGetTasks(gt23, GetTasksApi23.class)));
        GetTasksApi28 gt28 = maxNum ->
                maxNum == 1 ? Collections.singletonList("api28-ok") : Collections.emptyList();
        check("get_tasks_api28_single_arg",
                Collections.singletonList("api28-ok").equals(TaskBridgeServer.invokeGetTasks(gt28, GetTasksApi28.class)));
        GetTasksApi31 gt31 = (maxNum, filter, keep, displayId) ->
                (maxNum == 1 && !filter && !keep && displayId == -1)
                        ? Collections.singletonList("api31-ok") : Collections.emptyList();
        check("get_tasks_api31_passes_invalid_display",
                Collections.singletonList("api31-ok").equals(TaskBridgeServer.invokeGetTasks(gt31, GetTasksApi31.class)));

        final String auth = "com.pilipala.tvhome.taskbridge";
        final Object markerExtras = new Object();

        ProviderApi28 p28 = (pkg, method, arg, extras) ->
                ("com.android.shell".equals(pkg) && "connect".equals(method) && arg == null && extras == markerExtras)
                        ? "ok-28" : null;
        check("binder_call_api23_to_28_four_arg",
                "ok-28".equals(TaskBridgeServer.invokeProviderCall(
                        p28, ProviderApi28.class, auth, "connect", markerExtras, 2000)));

        ProviderApi29 p29 = (pkg, authority, method, arg, extras) ->
                ("com.android.shell".equals(pkg) && auth.equals(authority) && "connect".equals(method) && extras == markerExtras)
                        ? "ok-29" : null;
        check("binder_call_api29_five_arg",
                "ok-29".equals(TaskBridgeServer.invokeProviderCall(
                        p29, ProviderApi29.class, auth, "connect", markerExtras, 2000)));

        ProviderApi30 p30 = (pkg, tag, authority, method, arg, extras) ->
                ("com.android.shell".equals(pkg) && tag == null && auth.equals(authority) && "connect".equals(method) && extras == markerExtras)
                        ? "ok-30" : null;
        check("binder_call_api30_six_arg_attribution_tag",
                "ok-30".equals(TaskBridgeServer.invokeProviderCall(
                        p30, ProviderApi30.class, auth, "connect", markerExtras, 2000)));

        ProviderApi31 p31 = (src, authority, method, arg, extras) ->
                (src != null && src.uid == 2000 && "com.android.shell".equals(src.packageName)
                        && auth.equals(authority) && "connect".equals(method) && extras == markerExtras)
                        ? "ok-31" : null;
        check("binder_call_api31_plus_attribution_source_builder",
                "ok-31".equals(TaskBridgeServer.invokeProviderCall(
                        p31, ProviderApi31.class, auth, "connect", markerExtras, 2000)));

        ProviderUnsupported pUnknown = code -> "unexpected";
        check("binder_call_unknown_signature_returns_null_safely",
                TaskBridgeServer.invokeProviderCall(
                        pUnknown, ProviderUnsupported.class, auth, "connect", markerExtras, 2000) == null);

        check("unwrap_null_task_slice",
                TaskBridgeServer.unwrapTaskList(null).isEmpty());
        check("unwrap_api23_raw_list_return",
                Arrays.asList("one", "two").equals(TaskBridgeServer.unwrapTaskList(Arrays.asList("one", "two"))));
        check("unwrap_api26_parceled_list_slice",
                Collections.singletonList("sliced").equals(
                        TaskBridgeServer.unwrapTaskList(new FakeParceledListSlice(Collections.singletonList("sliced")))));

        // Audio service reflection checks (API 23-35 signatures + fallback)
        int[] a28Call = {0};
        AudioApi28 a28 = new AudioApi28() {
            @Override public void adjustSuggestedStreamVolume(int d, int s, int f, String p, String c) {
                if (d == 1 && s == Integer.MIN_VALUE && f == 1 && "com.android.shell".equals(p)) a28Call[0]++;
            }
            @Override public int getStreamVolume(int s) { return s == 3 ? 42 : -1; }
            @Override public int getStreamMaxVolume(int s) { return s == 3 ? 100 : -1; }
        };
        check("audio_api28_five_arg_adjust_and_read",
                TaskBridgeServer.invokeAdjustSuggestedVolume(a28, AudioApi28.class, 1, Integer.MIN_VALUE, 1, "com.android.shell", "MarcoTV", 2000)
                && a28Call[0] == 1
                && TaskBridgeServer.invokeGetStreamVolume(a28, AudioApi28.class, 3) == 42
                && TaskBridgeServer.invokeGetStreamMaxVolume(a28, AudioApi28.class, 3) == 100);
        int[] a31Call = {0};
        AudioApi31 a31 = (d, s, f, p, c, u) -> { if (d == -1 && u == 2000) a31Call[0]++; };
        check("audio_api31_six_arg_uid_adjust",
                TaskBridgeServer.invokeAdjustSuggestedVolume(a31, AudioApi31.class, -1, Integer.MIN_VALUE, 1, "com.android.shell", "MarcoTV", 2000)
                && a31Call[0] == 1);
        int[] aFbCall = {0};
        AudioFallbackOnly aFb = (s, d, f, p) -> { if (s == 3 && d == 1 && "com.android.shell".equals(p)) aFbCall[0]++; };
        check("audio_fallback_adjust_stream_volume",
                TaskBridgeServer.invokeAdjustSuggestedVolume(aFb, AudioFallbackOnly.class, 1, Integer.MIN_VALUE, 1, "com.android.shell", "MarcoTV", 2000)
                && aFbCall[0] == 1);

        // 16-byte (32-bit armv7l) and 24-byte (64-bit arm64) struct input_event parsing
        VolumeRepeatEngine.InputKeyEvent ev32 = VolumeRepeatEngine.parseInputEvent(encodeInputEvent(false, 1, 115, 1), false);
        VolumeRepeatEngine.InputKeyEvent ev64 = VolumeRepeatEngine.parseInputEvent(encodeInputEvent(true, 1, 114, 0), true);
        check("input_event_32bit_and_64bit_decoding",
                ev32 != null && ev32.type == 1 && ev32.code == 115 && ev32.value == 1
                && ev64 != null && ev64.type == 1 && ev64.code == 114 && ev64.value == 0
                && VolumeRepeatEngine.parseInputEvent(new byte[8], false) == null);

        // VolumeRepeatEngine deterministic state machine checks
        VirtualScheduler sched = new VirtualScheduler();
        boolean[] flags = {true, true, true}; // enabled, connected, interactive
        int[] vol = {30};
        java.util.List<Integer> dirs = new java.util.ArrayList<>();
        VolumeRepeatEngine engine = new VolumeRepeatEngine(sched, new VolumeRepeatEngine.Callbacks() {
            @Override public boolean isEnabled() { return flags[0]; }
            @Override public boolean isConnected() { return flags[1]; }
            @Override public boolean isInteractive() { return flags[2]; }
            @Override public int readStreamVolume() { return vol[0]; }
            @Override public int readStreamMaxVolume() { return 100; }
            @Override public boolean adjustVolume(int direction) {
                dirs.add(direction);
                vol[0] = Math.max(0, Math.min(100, vol[0] + direction));
                return true;
            }
            @Override public long nowMs() { return sched.now; }
        });

        // 1. Short press (< 500ms) -> 0 synthetic steps (system handles single step only)
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31; // system handled initial press
        sched.advanceBy(450);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        sched.advanceBy(500);
        check("vol_engine_short_press_produces_zero_synthetic_steps",
                dirs.isEmpty() && !engine.isHolding() && "key_up".equals(engine.lastStopReason()));

        // 2. First long press Vol+ (~3s) -> starts at 500ms, steps every 55ms, stops IMMEDIATELY on key_up!
        dirs.clear();
        vol[0] = 30;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31; // system initial +1
        sched.advanceBy(499);
        int beforeDelay = dirs.size();
        sched.advanceBy(1); // 500ms -> 1st step
        int atDelay = dirs.size();
        sched.advanceBy(2500); // total 3000ms hold
        int atRelease = dirs.size();
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        sched.advanceBy(1000);
        check("vol_engine_first_long_press_steps_continuously_and_stops_on_release",
                beforeDelay == 0 && atDelay == 1 && atRelease >= 45 && dirs.size() == atRelease
                && !engine.isHolding() && "key_up".equals(engine.lastStopReason()));

        // 3. Long press Vol- works symmetrically
        dirs.clear();
        vol[0] = 60;
        engine.onKeyEvent("/dev/input/event5", 1, 114, 1);
        vol[0] = 59; // system initial -1
        sched.advanceBy(1200);
        engine.onKeyEvent("/dev/input/event5", 1, 114, 0);
        int downSteps = dirs.size();
        sched.advanceBy(500);
        check("vol_engine_volume_down_hold_and_immediate_stop",
                downSteps >= 12 && dirs.size() == downSteps && dirs.get(0) == -1 && vol[0] == 59 - downSteps);

        // 4. Native kernel repeat (val=2) cancels compensation immediately (no double-step!)
        dirs.clear();
        vol[0] = 40;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        sched.advanceBy(400);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 2);
        sched.advanceBy(1500);
        check("vol_engine_kernel_repeat_suppresses_compensation",
                dirs.isEmpty() && !engine.isHolding() && "kernel_repeat".equals(engine.lastStopReason()));

        // 5. External InputDispatcher native repeat (volume advances by itself) cancels compensation!
        dirs.clear();
        vol[0] = 40;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 41; // initial press
        sched.advanceBy(350); // baseline sampled at 41
        vol[0] = 42; // system repeated natively before 500ms!
        sched.advanceBy(800);
        check("vol_engine_native_dispatcher_repeat_aborts_compensation",
                dirs.isEmpty() && !engine.isHolding() && "native_repeat_detected".equals(engine.lastStopReason()));

        // 6. Bridge disconnect mid-hold stops immediately!
        dirs.clear();
        vol[0] = 40;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 41;
        sched.advanceBy(700);
        int beforeDisc = dirs.size();
        flags[1] = false;
        engine.cancelActive("bridge_disconnected");
        sched.advanceBy(1000);
        check("vol_engine_bridge_disconnect_stops_immediately",
                beforeDisc >= 3 && dirs.size() == beforeDisc && "bridge_disconnected".equals(engine.lastStopReason()));
        flags[1] = true;

        // 7. Remote Bluetooth disconnect (device EOF) stops immediately!
        dirs.clear();
        vol[0] = 40;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 41;
        sched.advanceBy(650);
        int beforeEof = dirs.size();
        engine.onDeviceDisconnected("/dev/input/event5");
        sched.advanceBy(1000);
        check("vol_engine_device_disconnect_stops_immediately",
                beforeEof >= 2 && dirs.size() == beforeEof && "device_disconnected".equals(engine.lastStopReason()));

        // 8. Lost release event hits safety ceiling and stops itself!
        dirs.clear();
        vol[0] = 10;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 11;
        sched.advanceBy(15000);
        check("vol_engine_lost_release_hits_safety_timeout",
                !engine.isHolding() && "safety_timeout".equals(engine.lastStopReason())
                && dirs.size() <= VolumeRepeatEngine.DEFAULT_MAX_STEPS);

        // 9. Another key press (e.g. HOME=172) stops active volume hold!
        dirs.clear();
        vol[0] = 30;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31;
        sched.advanceBy(600);
        int beforeOther = dirs.size();
        engine.onKeyEvent("/dev/input/event5", 1, 172, 1);
        sched.advanceBy(1000);
        check("vol_engine_other_key_stops_hold",
                beforeOther >= 2 && dirs.size() == beforeOther && "other_key".equals(engine.lastStopReason()));

        // 10. Screen off stops active hold!
        dirs.clear();
        vol[0] = 30;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31;
        sched.advanceBy(600);
        int beforeOff = dirs.size();
        flags[2] = false;
        sched.advanceBy(500);
        check("vol_engine_screen_off_stops_hold",
                beforeOff >= 2 && dirs.size() == beforeOff && "screen_off".equals(engine.lastStopReason()));
        flags[2] = true;

        System.out.println("OK " + passed + " task bridge cross-version compatibility checks");
    }
}
