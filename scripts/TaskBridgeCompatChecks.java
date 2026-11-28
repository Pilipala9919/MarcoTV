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

    public interface AudioBothStreams {
        void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);
        void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller);
        int getStreamVolume(int streamType);
        int getStreamMaxVolume(int streamType);
        int getStreamMinVolume(int streamType);
    }

    public interface AudioReadOnly {
        int getStreamVolume(int streamType);
        int getStreamMaxVolume(int streamType);
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
        if (args.length == 1 && "--child-hang".equals(args[0])) {
            Thread.sleep(30000L);
            return;
        }
        if (args.length == 1 && "--child-flood".equals(args[0])) {
            byte[] chunk = new byte[4096];
            Arrays.fill(chunk, (byte) 'A');
            while (true) {
                System.out.write(chunk);
                System.out.flush();
            }
        }
        if (args.length == 1 && "--child-ok".equals(args[0])) {
            System.out.print("add device 1: /dev/input/event5\n");
            System.out.flush();
            return;
        }
        if (args.length == 1 && "--child-fail".equals(args[0])) {
            System.out.print("getevent: cannot enumerate devices\n");
            System.out.flush();
            System.exit(1);
        }
        if (args.length == 1 && "--child-empty-ok".equals(args[0])) {
            System.out.flush();
            return;
        }
        if (args.length == 1 && "--child-partial-error".equals(args[0])) {
            System.out.print("could not open /dev/input/event5, Permission denied\n"
                    + "add device 2: /dev/input/event12\n"
                    + "  events:\n"
                    + "    KEY (0001): 0067 006c\n");
            System.out.flush();
            return;
        }
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

        // Audio service reflection checks (API 23-35 signatures + stream-consistency + capability probe)
        int[] a28Call = {0};
        AudioApi28 a28 = new AudioApi28() {
            @Override public void adjustSuggestedStreamVolume(int d, int s, int f, String p, String c) {
                if (d == 1 && s == 3 && f == 1 && "com.android.shell".equals(p)) a28Call[0]++;
            }
            @Override public int getStreamVolume(int s) { return s == 3 ? 42 : -1; }
            @Override public int getStreamMaxVolume(int s) { return s == 3 ? 100 : -1; }
        };
        check("audio_api28_five_arg_adjust_and_read",
                TaskBridgeServer.invokeAdjustStreamOrSuggestedVolume(a28, AudioApi28.class, 1, 3, 1, "com.android.shell", "MarcoTV", 2000)
                && a28Call[0] == 1
                && TaskBridgeServer.invokeGetStreamVolume(a28, AudioApi28.class, 3) == 42
                && TaskBridgeServer.invokeGetStreamMaxVolume(a28, AudioApi28.class, 3) == 100
                && TaskBridgeServer.isAudioControlCapable(a28, AudioApi28.class, 3));
        int[] a31Call = {0};
        AudioApi31 a31 = (d, s, f, p, c, u) -> { if (d == -1 && s == 3 && u == 2000) a31Call[0]++; };
        check("audio_api31_six_arg_uid_adjust",
                TaskBridgeServer.invokeAdjustStreamOrSuggestedVolume(a31, AudioApi31.class, -1, 3, 1, "com.android.shell", "MarcoTV", 2000)
                && a31Call[0] == 1);
        int[] aFbCall = {0};
        AudioFallbackOnly aFb = (s, d, f, p) -> { if (s == 3 && d == 1 && "com.android.shell".equals(p)) aFbCall[0]++; };
        check("audio_fallback_adjust_stream_volume",
                TaskBridgeServer.invokeAdjustStreamOrSuggestedVolume(aFb, AudioFallbackOnly.class, 1, 3, 1, "com.android.shell", "MarcoTV", 2000)
                && aFbCall[0] == 1);

        // When both adjustStreamVolume and adjustSuggestedStreamVolume exist, prefer adjustStreamVolume(STREAM_MUSIC)
        // so readback and adjust always target the exact same audio stream.
        int[] bothCalls = {0, 0}; // [stream, suggested]
        AudioBothStreams aBoth = new AudioBothStreams() {
            @Override public void adjustStreamVolume(int s, int d, int f, String p) { if (s == 3 && d == 1) bothCalls[0]++; }
            @Override public void adjustSuggestedStreamVolume(int d, int s, int f, String p, String c) { bothCalls[1]++; }
            @Override public int getStreamVolume(int s) { return 25; }
            @Override public int getStreamMaxVolume(int s) { return 100; }
            @Override public int getStreamMinVolume(int s) { return 0; }
        };
        check("audio_prefers_adjust_stream_volume_for_read_adjust_consistency",
                TaskBridgeServer.invokeAdjustStreamOrSuggestedVolume(aBoth, AudioBothStreams.class, 1, 3, 1, "com.android.shell", "MarcoTV", 2000)
                && bothCalls[0] == 1 && bothCalls[1] == 0
                && TaskBridgeServer.invokeGetStreamMinVolume(aBoth, AudioBothStreams.class, 3) == 0
                && TaskBridgeServer.isAudioControlCapable(aBoth, AudioBothStreams.class, 3));

        // Audio capability probe MUST reject objects lacking adjust method or failing volume readback!
        AudioReadOnly readOnlyAudio = new AudioReadOnly() {
            @Override public int getStreamVolume(int s) { return 20; }
            @Override public int getStreamMaxVolume(int s) { return 100; }
        };
        AudioApi28 brokenReadAudio = new AudioApi28() {
            @Override public void adjustSuggestedStreamVolume(int d, int s, int f, String p, String c) { }
            @Override public int getStreamVolume(int s) { return -1; }
            @Override public int getStreamMaxVolume(int s) { return 100; }
        };
        check("audio_capability_probe_rejects_missing_adjust_or_broken_readback",
                !TaskBridgeServer.isAudioControlCapable(null, null, 3)
                && !TaskBridgeServer.isAudioControlCapable(new Object(), Object.class, 3)
                && !TaskBridgeServer.isAudioControlCapable(readOnlyAudio, AudioReadOnly.class, 3)
                && !TaskBridgeServer.isAudioControlCapable(brokenReadAudio, AudioApi28.class, 3));

        // getevent -p capability parsing: plain keyboard without KEY_VOLUMEDOWN (0072) + KEY_VOLUMEUP (0073) MUST be rejected!
        String plainKeyboardGetevent =
                "add device 1: /dev/input/event1\n"
                + "  name:     \"USB Keyboard\"\n"
                + "  events:\n"
                + "    KEY (0001): 001c  0067  0069  006a  006c  00ac\n"
                + "add device 2: /dev/input/event2\n"
                + "  name:     \"VolumeUpOnly\"\n"
                + "  events:\n"
                + "    KEY (0001): 0073\n";
        check("getevent_rejects_plain_keyboard_and_single_volume_key_devices",
                TaskBridgeServer.parseVolumeCapableEventPaths(plainKeyboardGetevent).isEmpty());

        // Real MiTV-ASTP0 / 22.8.30.1385 getevent -p format: selects ONLY event5 (BT remote) & event0 (keypad), never event1..4!
        String mitvAstp0Getevent =
                "add device 1: /dev/input/event5\n"
                + "  name:     \"Xiaomi BT Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): 001c  0067  0069  006a  006c  0072  0073  00ac\n"
                + "add device 2: /dev/input/event4\n"
                + "  name:     \"adc_keypad\"\n"
                + "  events:\n"
                + "    KEY (0001): 0074\n"
                + "add device 3: /dev/input/event3\n"
                + "  name:     \"vad_keypad\"\n"
                + "  events:\n"
                + "    KEY (0001): 00d9\n"
                + "add device 4: /dev/input/event0\n"
                + "  name:     \"aml_keypad\"\n"
                + "  events:\n"
                + "    KEY (0001): 001c  0072  0073  0074\n";
        java.util.Set<String> mitvVolPaths = TaskBridgeServer.parseVolumeCapableEventPaths(mitvAstp0Getevent);
        check("getevent_selects_only_real_volume_capable_devices",
                mitvVolPaths.size() == 2
                && mitvVolPaths.contains("/dev/input/event5")
                && mitvVolPaths.contains("/dev/input/event0")
                && !mitvVolPaths.contains("/dev/input/event4")
                && !mitvVolPaths.contains("/dev/input/event3"));

        // Server status JSON strictly gates volumeAssistActive on requested + audioCapable + verified device count > 0!
        org.json.JSONObject stNoDev = TaskBridgeServer.buildVolumeStatusJson(true, true, aBoth, AudioBothStreams.class, false, 0, 0, "idle");
        org.json.JSONObject stNoAudio = TaskBridgeServer.buildVolumeStatusJson(true, true, readOnlyAudio, AudioReadOnly.class, false, 2, 0, "idle");
        org.json.JSONObject stAudioFailed = TaskBridgeServer.buildVolumeStatusJson(true, true, aBoth, AudioBothStreams.class, true, 2, 0, "audio_read_unavailable");
        org.json.JSONObject stReady = TaskBridgeServer.buildVolumeStatusJson(true, true, aBoth, AudioBothStreams.class, false, 2, 5, "key_up");
        org.json.JSONObject stOff = TaskBridgeServer.buildVolumeStatusJson(false, true, aBoth, AudioBothStreams.class, false, 2, 0, "disabled");
        check("server_status_gates_volume_assist_on_audio_and_volume_devices",
                !stNoDev.optBoolean("volumeAssistActive") && "no_volume_input_device".equals(stNoDev.optString("volumeAssistReason"))
                && !stNoAudio.optBoolean("volumeAssistActive") && !stNoAudio.optBoolean("volumeAudioReady") && "audio_unavailable".equals(stNoAudio.optString("volumeAssistReason"))
                && !stAudioFailed.optBoolean("volumeAssistActive") && !stAudioFailed.optBoolean("volumeAudioReady") && "audio_runtime_failed".equals(stAudioFailed.optString("volumeAssistReason"))
                && stReady.optBoolean("volumeAssistActive") && stReady.optBoolean("volumeAudioReady") && "ready".equals(stReady.optString("volumeAssistReason"))
                && !stOff.optBoolean("volumeAssistActive") && "disabled".equals(stOff.optString("volumeAssistReason")));

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
        int[] minVol = {0};
        int[] maxVol = {100};
        java.util.List<Integer> dirs = new java.util.ArrayList<>();
        java.util.List<String> capFailures = new java.util.ArrayList<>();
        VolumeRepeatEngine engine = new VolumeRepeatEngine(sched, new VolumeRepeatEngine.Callbacks() {
            @Override public boolean isEnabled() { return flags[0]; }
            @Override public boolean isConnected() { return flags[1]; }
            @Override public boolean isInteractive() { return flags[2]; }
            @Override public int readStreamVolume() { return vol[0]; }
            @Override public int readStreamMinVolume() { return minVol[0]; }
            @Override public int readStreamMaxVolume() { return maxVol[0]; }
            @Override public boolean adjustVolume(int direction) {
                dirs.add(direction);
                vol[0] = Math.max(minVol[0], Math.min(maxVol[0], vol[0] + direction));
                return true;
            }
            @Override public void onCapabilityFailure(String reason) { capFailures.add(reason); }
            @Override public long nowMs() { return sched.now; }
        });

        // 1. Short press (< 550ms) -> 0 synthetic steps (system handles single step only)
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31; // system handled initial press
        sched.advanceBy(500);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        sched.advanceBy(500);
        check("vol_engine_short_press_produces_zero_synthetic_steps",
                dirs.isEmpty() && !engine.isHolding() && "key_up".equals(engine.lastStopReason()));

        // 2. First long press Vol+ (~3s) -> starts at 550ms, steps every 55ms, stops IMMEDIATELY on key_up!
        dirs.clear();
        vol[0] = 30;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31; // system initial +1
        sched.advanceBy(549);
        int beforeDelay = dirs.size();
        sched.advanceBy(1); // 550ms -> 1st step
        int atDelay = dirs.size();
        sched.advanceBy(2450); // total 3000ms hold
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

        // 5A. Codex P1 Control: System starts native repeat at 430ms every 55ms during 1.1s hold -> 0 Marco steps!
        dirs.clear();
        vol[0] = 20;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 21; // system initial +1 at t=0
        long holdStart430 = sched.now;
        for (long t = 430; t < 1100; t += 55) {
            sched.advanceBy((holdStart430 + t) - sched.now);
            vol[0] = Math.min(100, vol[0] + 1);
        }
        sched.advanceBy((holdStart430 + 1100) - sched.now);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        sched.advanceBy(300);
        check("vol_engine_system_repeat_at_430ms_produces_zero_marco_steps",
                dirs.isEmpty() && vol[0] == 34 && !engine.isHolding()
                && "native_repeat_detected".equals(engine.lastStopReason()));

        // 5B. Codex P1 Counterexample: System starts native repeat at 530ms every 55ms during 1.1s hold from vol=20 ->
        // MUST end at EXACT vol=31 (NOT 42!) with ZERO Marco steps!
        dirs.clear();
        vol[0] = 20;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 21; // system initial +1 at t=0
        long holdStart530 = sched.now;
        for (long t = 530; t < 1080; t += 55) { // 10 native repeat steps: 530, 585, ..., 1025 => 21 + 10 = 31
            sched.advanceBy((holdStart530 + t) - sched.now);
            vol[0] = Math.min(100, vol[0] + 1);
        }
        sched.advanceBy((holdStart530 + 1100) - sched.now);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        sched.advanceBy(300);
        check("vol_engine_system_repeat_at_530ms_no_double_step_exact_31",
                dirs.isEmpty() && vol[0] == 31 && !engine.isHolding()
                && "native_repeat_detected".equals(engine.lastStopReason()));

        // 5C. Even if system starts late native repeat at 580ms (AFTER Marco's 1st step at 550ms),
        // Marco detects moved(2) > stepsIssued(1) on next tick and stops immediately (never doubles)!
        dirs.clear();
        vol[0] = 20;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 21;
        long holdStart580 = sched.now;
        sched.advanceBy(580); // Marco took 1 step at 550ms -> vol=22
        vol[0] = 23; // System starts native repeat at 580ms!
        for (long t = 635; t < 1100; t += 55) {
            sched.advanceBy((holdStart580 + t) - sched.now);
            vol[0] = Math.min(100, vol[0] + 1);
        }
        sched.advanceBy((holdStart580 + 1100) - sched.now);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("vol_engine_late_system_repeat_at_580ms_aborts_immediately",
                dirs.size() == 1 && !engine.isHolding()
                && "external_volume_advance".equals(engine.lastStopReason()));

        // 5D. Readback failure (readStreamVolume == -1) -> ZERO adjustments and notifies capability failure!
        dirs.clear();
        capFailures.clear();
        vol[0] = -1;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        sched.advanceBy(1100);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("vol_engine_readback_failure_issues_zero_adjustments",
                dirs.isEmpty() && !engine.isHolding()
                && "audio_read_unavailable".equals(engine.lastStopReason())
                && !capFailures.isEmpty());

        // 5E. Already at max (100, Vol+) or min (0, Vol-) -> ZERO adjustments!
        dirs.clear();
        vol[0] = 100;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        sched.advanceBy(1100);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("vol_engine_at_max_boundary_issues_zero_adjustments",
                dirs.isEmpty() && !engine.isHolding() && "boundary_reached".equals(engine.lastStopReason()));

        dirs.clear();
        vol[0] = 0;
        engine.onKeyEvent("/dev/input/event5", 1, 114, 1);
        sched.advanceBy(1100);
        engine.onKeyEvent("/dev/input/event5", 1, 114, 0);
        check("vol_engine_at_min_boundary_issues_zero_adjustments",
                dirs.isEmpty() && !engine.isHolding() && "boundary_reached".equals(engine.lastStopReason()));

        // Reaching boundary mid-hold (98 -> system 99 -> Marco 100) stops immediately with 0 redundant steps!
        dirs.clear();
        vol[0] = 98;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 99;
        sched.advanceBy(1100);
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("vol_engine_stops_cleanly_upon_reaching_max_boundary",
                dirs.size() == 1 && vol[0] == 100 && !engine.isHolding()
                && "boundary_reached".equals(engine.lastStopReason()));

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

        // 8. Lost release event hits safety ceiling even on wide-scale audio stream!
        dirs.clear();
        maxVol[0] = 200;
        vol[0] = 10;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 11;
        sched.advanceBy(15000);
        check("vol_engine_lost_release_hits_safety_timeout",
                !engine.isHolding() && "safety_timeout".equals(engine.lastStopReason())
                && dirs.size() <= VolumeRepeatEngine.DEFAULT_MAX_STEPS);
        maxVol[0] = 100;

        // 9. Another key press (e.g. HOME=172) stops active volume hold!
        dirs.clear();
        vol[0] = 30;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 31;
        sched.advanceBy(620);
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
        sched.advanceBy(620);
        int beforeOff = dirs.size();
        flags[2] = false;
        sched.advanceBy(500);
        check("vol_engine_screen_off_stops_hold",
                beforeOff >= 2 && dirs.size() == beforeOff && "screen_off".equals(engine.lastStopReason()));
        flags[2] = true;

        // =========================================================================
        // Codex 82c1c8d Rework Regressions:
        // 1A. Held keys in getevent -p (0072*, 0073*, KEY_VOLUMEDOWN*, KEY_VOLUMEUP*) survive periodic scan!
        // =========================================================================
        String heldVolUpGetevent =
                "add device 1: /dev/input/event5\n"
                + "  name:     \"Xiaomi BT Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): 001c  0072  0073*  00ac\n";
        String heldVolDownGetevent =
                "add device 1: /dev/input/event5\n"
                + "  name:     \"Xiaomi BT Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): 001c  0072*  0073  00ac\n";
        String heldNamedGetevent =
                "add device 1: /dev/input/event5\n"
                + "  events:\n"
                + "    KEY (0001): KEY_VOLUMEDOWN*  KEY_VOLUMEUP*\n";
        check("codex_1a_getevent_matches_held_key_asterisks",
                TaskBridgeServer.parseVolumeCapableEventPaths(heldVolUpGetevent).contains("/dev/input/event5")
                && TaskBridgeServer.parseVolumeCapableEventPaths(heldVolDownGetevent).contains("/dev/input/event5")
                && TaskBridgeServer.parseVolumeCapableEventPaths(heldNamedGetevent).contains("/dev/input/event5"));

        // Hold Vol+ and Vol- across simulated periodic scan (at t=600ms) -> 0 readers closed, continuous 11 steps in 1.1s!
        // Then real unplug scan -> closes reader and stops immediately!
        java.util.Set<String> openReaders = new java.util.LinkedHashSet<>(Collections.singletonList("/dev/input/event5"));
        dirs.clear();
        vol[0] = 20;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 21;
        sched.advanceBy(600); // 1 step at 550ms
        java.util.Set<String> removedDuringUp = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, TaskBridgeServer.parseVolumeCapableEventPaths(heldVolUpGetevent));
        for (String p : removedDuringUp) engine.onDeviceDisconnected(p);
        sched.advanceBy(500); // total 1100ms -> 11 steps at 550..1100ms
        int stepsUpAcrossScan = dirs.size();
        engine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("codex_1a_vol_up_survives_periodic_scan_with_asterisk",
                removedDuringUp.isEmpty() && stepsUpAcrossScan == 11 && vol[0] == 32);

        dirs.clear();
        vol[0] = 60;
        engine.onKeyEvent("/dev/input/event5", 1, 114, 1);
        vol[0] = 59;
        sched.advanceBy(600);
        java.util.Set<String> removedDuringDown = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, TaskBridgeServer.parseVolumeCapableEventPaths(heldVolDownGetevent));
        for (String p : removedDuringDown) engine.onDeviceDisconnected(p);
        sched.advanceBy(500);
        int stepsDownAcrossScan = dirs.size();
        // Now real unplug occurs during hold -> device disappears from scan -> stops immediately!
        java.util.Set<String> removedOnUnplug = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, TaskBridgeServer.parseVolumeCapableEventPaths(""));
        for (String p : removedOnUnplug) engine.onDeviceDisconnected(p);
        sched.advanceBy(500);
        check("codex_1a_vol_down_survives_scan_and_real_unplug_still_stops",
                removedDuringDown.isEmpty() && stepsDownAcrossScan == 11
                && removedOnUnplug.contains("/dev/input/event5")
                && dirs.size() == stepsDownAcrossScan
                && !engine.isHolding()
                && "device_disconnected".equals(engine.lastStopReason()));

        // 1B. API 23-25 safe readProcessOutputWithTimeout: fast ok, silent hang timeout, and endless flood bound!
        String javaBin = new java.io.File(System.getProperty("java.home"),
                "bin" + java.io.File.separator
                + (System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win") ? "java.exe" : "java"))
                .getAbsolutePath();
        String selfCp = System.getProperty("java.class.path");
        java.lang.Process okProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-ok")
                .redirectErrorStream(true).start();
        String okOut = TaskBridgeServer.readProcessOutputWithTimeout(okProc, 5000L, 65536);
        check("codex_1b_probe_process_fast_exit_returns_output",
                okOut != null && okOut.contains("/dev/input/event5"));

        long hangStart = System.nanoTime();
        java.lang.Process hangProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-hang")
                .redirectErrorStream(true).start();
        String hangOut = TaskBridgeServer.readProcessOutputWithTimeout(hangProc, 300L, 65536);
        long hangMs = (System.nanoTime() - hangStart) / 1000000L;
        for (int i = 0; i < 20 && hangProc.isAlive(); i++) Thread.sleep(25);
        check("codex_1b_probe_process_silent_hang_times_out_and_destroys_child",
                hangOut == null && hangMs < 4000L && !hangProc.isAlive());

        long floodStart = System.nanoTime();
        java.lang.Process floodProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-flood")
                .redirectErrorStream(true).start();
        String floodOut = TaskBridgeServer.readProcessOutputWithTimeout(floodProc, 3000L, 8192);
        long floodMs = (System.nanoTime() - floodStart) / 1000000L;
        for (int i = 0; i < 20 && floodProc.isAlive(); i++) Thread.sleep(25);
        check("codex_1b_probe_process_endless_output_aborts_cleanly_at_limit",
                floodOut == null && floodMs < 4000L && !floodProc.isAlive());

        // f0983a3 P2 [ProcReview]: Non-zero exit code with error text MUST return null and preserve open readers,
        // whereas clean exit 0 with empty output returns "" and removes truly unplugged devices!
        java.lang.Process failProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-fail")
                .redirectErrorStream(true).start();
        String failOut = TaskBridgeServer.readProcessOutputWithTimeout(failProc, 5000L, 65536);
        java.util.Set<String> removedOnFail = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, failOut == null ? null : TaskBridgeServer.parseVolumeCapableEventPaths(failOut));
        check("f0983a3_p2_probe_nonzero_exit_returns_null_and_preserves_readers",
                failOut == null && removedOnFail.isEmpty());

        java.lang.Process emptyOkProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-empty-ok")
                .redirectErrorStream(true).start();
        String emptyOkOut = TaskBridgeServer.readProcessOutputWithTimeout(emptyOkProc, 5000L, 65536);
        java.util.Set<String> removedOnCleanEmpty = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, emptyOkOut == null ? null : TaskBridgeServer.parseVolumeCapableEventPaths(emptyOkOut));
        dirs.clear();
        vol[0] = 40;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 41;
        sched.advanceBy(600);
        int stepsBeforeCleanEmpty = dirs.size();
        for (String p : removedOnCleanEmpty) engine.onDeviceDisconnected(p);
        sched.advanceBy(500);
        check("f0983a3_p2_probe_clean_zero_devices_removes_reader_and_stops_hold",
                emptyOkOut != null
                && emptyOkOut.isEmpty()
                && removedOnCleanEmpty.contains("/dev/input/event5")
                && stepsBeforeCleanEmpty == 1
                && dirs.size() == stepsBeforeCleanEmpty
                && !engine.isHolding()
                && "device_disconnected".equals(engine.lastStopReason()));

        // e33d5ae P2 #3: Exit-0 getevent scan where ONE already-open node hits Permission denied while
        // other nodes list normally MUST preserve that healthy open node (and keep long-press running),
        // while still removing genuinely unplugged nodes (absent from scan or ENOENT/ENODEV)!
        java.lang.Process partialProc = new ProcessBuilder(javaBin, "-cp", selfCp, TaskBridgeCompatChecks.class.getName(), "--child-partial-error")
                .redirectErrorStream(true).start();
        String partialOut = TaskBridgeServer.readProcessOutputWithTimeout(partialProc, 5000L, 65536);
        TaskBridgeServer.VolumeScanResult partialScan = TaskBridgeServer.parseVolumeScanOutput(partialOut);
        java.util.Set<String> twoOpenReaders = new java.util.LinkedHashSet<>(Arrays.asList("/dev/input/event5", "/dev/input/event7"));
        java.util.Set<String> removedPartialSingle = TaskBridgeServer.devicesToRemoveAfterScan(openReaders, partialScan);
        java.util.Set<String> removedPartialTwo = TaskBridgeServer.devicesToRemoveAfterScan(twoOpenReaders, partialScan);
        dirs.clear();
        vol[0] = 20;
        engine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        vol[0] = 21;
        sched.advanceBy(600);
        for (String p : removedPartialSingle) engine.onDeviceDisconnected(p);
        sched.advanceBy(500);
        int stepsAcrossPartialErr = dirs.size();
        // Now remote genuinely unplugs (clean exit-0 scan omits event5, or reports No such device) -> stops immediately!
        java.util.Set<String> removedAfterRealUnplug = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, "add device 2: /dev/input/event12\n  events:\n    KEY (0001): 0067 006c\n");
        java.util.Set<String> removedOnEnoent = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, "could not open /dev/input/event5, No such file or directory\n");
        java.util.Set<String> removedOnDirDenied = TaskBridgeServer.devicesToRemoveAfterScan(
                openReaders, "could not open /dev/input, Permission denied\n");
        for (String p : removedAfterRealUnplug) engine.onDeviceDisconnected(p);
        sched.advanceBy(500);
        check("e33d5ae_p2_partial_node_error_preserves_open_reader_and_real_unplug_still_stops",
                partialOut != null
                && partialScan.unreadablePaths.contains("/dev/input/event5")
                && !partialScan.directoryFailed
                && removedPartialSingle.isEmpty()
                && !removedPartialTwo.contains("/dev/input/event5")
                && removedPartialTwo.contains("/dev/input/event7")
                && stepsAcrossPartialErr == 11
                && removedAfterRealUnplug.contains("/dev/input/event5")
                && removedOnEnoent.contains("/dev/input/event5")
                && removedOnDirDenied.isEmpty()
                && dirs.size() == stepsAcrossPartialErr
                && !engine.isHolding()
                && "device_disconnected".equals(engine.lastStopReason()));

        // Real Android TV getevent -p output includes `/dev/input/mice` & `/dev/input/mouse0` ("Not a typewriter"),
        // sometimes interleaved mid-line with KEY tokens; MUST NOT set directoryFailed and MUST discover event0/event5!
        String realTvGetevent =
                "could not get driver version for /dev/input/mice, Not a typewriter\n"
                + "add device 1: /dev/input/event5\n"
                + "  name:     \"Xiaomi BT Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): 0071  0072  0073could not get driver version for /dev/input/mouse0, Not a typewriter\n"
                + "                0074\n"
                + "add device 5: /dev/input/event0\n"
                + "  name:     \"aml_keypad\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072  0073\n";
        TaskBridgeServer.VolumeScanResult realTvScan = TaskBridgeServer.parseVolumeScanOutput(realTvGetevent);
        check("e33d5ae_real_tv_mice_and_interleaved_mouse0_never_trip_directory_failure",
                !realTvScan.directoryFailed
                && realTvScan.unreadablePaths.isEmpty()
                && realTvScan.capablePaths.contains("/dev/input/event5")
                && realTvScan.capablePaths.contains("/dev/input/event0")
                && TaskBridgeServer.devicesToRemoveAfterScan(
                        new java.util.LinkedHashSet<>(Arrays.asList("/dev/input/event5", "/dev/input/event9")),
                        realTvScan).equals(Collections.singleton("/dev/input/event9")));

        // 2A + f0983a3 P1: Sticky write failure stays degraded across periodic scans AND passive false->true
        // configure cycles (such as 5s no-bridge yield expiry); only explicit retry or confirmed write success clears it!
        TaskBridgeServer.AudioHealthState health = new TaskBridgeServer.AudioHealthState();
        health.recordFailure("audio_adjust_failed");
        health.onConfigure(false, false); // 5s yield pauses volumeAssist
        health.onConfigure(true, false);  // 5s yield expires -> passive configure(volumeAssist=true) without retry
        health.onPeriodicReadProbe(true);
        health.onPeriodicReadProbe(true);
        org.json.JSONObject stWriteDenied = TaskBridgeServer.buildVolumeStatusJson(
                true, true, aBoth, AudioBothStreams.class,
                health.readFailed, health.writeFailed, health.failureReason, 2, 0, "audio_adjust_failed");
        check("codex_2a_write_failure_not_cleared_by_periodic_read_probe",
                health.writeFailed
                && !TaskBridgeServer.shouldClearAudioFailureOnConfigure(true, false)
                && !TaskBridgeServer.shouldClearAudioFailureOnConfigure(false, true)
                && TaskBridgeServer.shouldClearAudioFailureOnConfigure(true, true)
                && !stWriteDenied.optBoolean("volumeAssistActive")
                && !stWriteDenied.optBoolean("volumeAudioReady")
                && stWriteDenied.optBoolean("volumeAudioWriteFailed")
                && stWriteDenied.optInt("volumeProtocolVersion") == TaskBridgeServer.VOLUME_PROTOCOL_VERSION
                && "audio_adjust_failed".equals(stWriteDenied.optString("volumeAssistReason")));
        health.onConfigure(true, true);
        org.json.JSONObject stAfterRetry = TaskBridgeServer.buildVolumeStatusJson(
                true, true, aBoth, AudioBothStreams.class,
                health.readFailed, health.writeFailed, health.failureReason, 2, 0, "idle");
        check("codex_2a_explicit_retry_restores_ready_and_read_failure_auto_recovers",
                !health.writeFailed && stAfterRetry.optBoolean("volumeAssistActive") && "ready".equals(stAfterRetry.optString("volumeAssistReason")));
        health.recordFailure("audio_adjust_failed");
        health.recordWriteSuccess();
        check("f0983a3_p1_confirmed_write_success_also_clears_write_failure",
                !health.writeFailed && health.failureReason.isEmpty());
        health.recordFailure("audio_read_unavailable");
        health.onPeriodicReadProbe(true);
        check("codex_2a_transient_read_failure_recovers_on_read_probe", !health.readFailed && !health.writeFailed);

        // Bonus Boundary A: Multi-unit step firmware (2 units per adjustVolume) completes full hold without false abort!
        VirtualScheduler multiSched = new VirtualScheduler();
        int[] multiVol = {20};
        java.util.List<Integer> multiDirs = new java.util.ArrayList<>();
        VolumeRepeatEngine multiEngine = new VolumeRepeatEngine(multiSched, new VolumeRepeatEngine.Callbacks() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isConnected() { return true; }
            @Override public boolean isInteractive() { return true; }
            @Override public int readStreamVolume() { return multiVol[0]; }
            @Override public int readStreamMinVolume() { return 0; }
            @Override public int readStreamMaxVolume() { return 100; }
            @Override public boolean adjustVolume(int direction) {
                multiDirs.add(direction);
                multiVol[0] = Math.max(0, Math.min(100, multiVol[0] + direction * 2));
                return true;
            }
            @Override public long nowMs() { return multiSched.now; }
        });
        multiEngine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        multiVol[0] = 22; // system initial +2
        multiSched.advanceBy(1100);
        multiEngine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("codex_boundary_multi_unit_step_firmware_does_not_false_abort",
                multiDirs.size() == 11 && multiVol[0] == 44 && "key_up".equals(multiEngine.lastStopReason()));

        // Bonus Boundary B: adjustVolume returns true with no volume change (not at boundary) ->
        // stops after 2 consecutive no-effect calls and reports audio_adjust_no_effect!
        VirtualScheduler noopSched = new VirtualScheduler();
        java.util.List<Integer> noopDirs = new java.util.ArrayList<>();
        java.util.List<String> noopFailures = new java.util.ArrayList<>();
        VolumeRepeatEngine noopEngine = new VolumeRepeatEngine(noopSched, new VolumeRepeatEngine.Callbacks() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isConnected() { return true; }
            @Override public boolean isInteractive() { return true; }
            @Override public int readStreamVolume() { return 30; }
            @Override public int readStreamMinVolume() { return 0; }
            @Override public int readStreamMaxVolume() { return 100; }
            @Override public boolean adjustVolume(int direction) {
                noopDirs.add(direction);
                return true; // returns true, yet stream volume stays 30!
            }
            @Override public void onCapabilityFailure(String reason) { noopFailures.add(reason); }
            @Override public long nowMs() { return noopSched.now; }
        });
        noopEngine.onKeyEvent("/dev/input/event5", 1, 115, 1);
        noopSched.advanceBy(1100);
        noopEngine.onKeyEvent("/dev/input/event5", 1, 115, 0);
        check("codex_boundary_no_effect_adjust_stops_after_2_calls_and_reports_failure",
                noopDirs.size() == 2
                && !noopEngine.isHolding()
                && "audio_adjust_no_effect".equals(noopEngine.lastStopReason())
                && noopFailures.contains("audio_adjust_no_effect"));

        // First-launch official notice & contact author copy integrity checks
        String firstNotice = ProjectInfo.firstLaunchNotice();
        String contactCopy = ProjectInfo.contactAuthor();
        String aboutCopy = ProjectInfo.about();
        check("project_info_first_launch_notice_gate",
                ProjectInfo.shouldShowFirstLaunchNotice(false, false, false, false)
                && !ProjectInfo.shouldShowFirstLaunchNotice(true, false, false, false)
                && !ProjectInfo.shouldShowFirstLaunchNotice(false, true, false, false)
                && !ProjectInfo.shouldShowFirstLaunchNotice(false, false, true, false)
                && !ProjectInfo.shouldShowFirstLaunchNotice(false, false, false, true));
        check("project_info_official_channels_and_warning_complete",
                firstNotice.contains("PILIPALA来信")
                && firstNotice.contains("小红书") && firstNotice.contains("B站") && firstNotice.contains("抖音")
                && firstNotice.contains("公众号") && firstNotice.contains("视频号")
                && firstNotice.contains("marcotv.wlj9919.top")
                && firstNotice.contains("以防不明渠道软件添加病毒等不良内容")
                && contactCopy.contains("PILIPALA来信")
                && contactCopy.contains("https://marcotv.wlj9919.top/tv/#group")
                && contactCopy.contains("marcotv.wlj9919.top")
                && aboutCopy.contains("PILIPALA来信")
                && aboutCopy.contains("https://marcotv.wlj9919.top/tv/#group"));

        System.out.println("OK " + passed + " task bridge cross-version compatibility checks");
    }
}
