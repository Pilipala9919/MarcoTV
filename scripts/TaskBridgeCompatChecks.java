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

        System.out.println("OK " + passed + " task bridge cross-version compatibility checks");
    }
}
