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

    public static void main(String[] args) throws Exception {
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
