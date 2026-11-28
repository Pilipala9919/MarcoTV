// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort read-only probe for on-device OEM TV weather providers, Settings keys,
 * and sticky broadcasts. Never runs on the UI thread; zero network permissions required.
 */
final class LocalWeatherProbe {
    private static final String[] KNOWN_PACKAGES = {
            "com.xiaomi.tweather",
            "com.miui.weather2",
            "com.xiaomi.tv.weather",
            "com.tcl.weather",
            "com.hisense.tv.weather",
            "com.skyworth.weather",
            "com.konka.weather",
            "org.omnirom.omnijaws",
            "com.huawei.android.totemweather"
    };

    private static final String[] CURATED_URIS = {
            "content://weather/actualWeatherData/1",
            "content://weather/weather",
            "content://weather/daily_weather/1",
            "content://com.xiaomi.tweather.provider/weather",
            "content://com.xiaomi.tv.weather.provider/weather",
            "content://org.omnirom.omnijaws.provider/weather"
    };

    private static final String[] SETTINGS_KEYS = {
            "weather_info", "tv_weather", "system_weather", "miui_weather", "weather_summary"
    };

    private static final String[] BROADCAST_ACTIONS = {
            "com.xiaomi.tweather.WEATHER_UPDATE",
            "miui.intent.action.WEATHER_CHANGED",
            "android.intent.action.WEATHER_UPDATE"
    };

    static final class Result {
        final WeatherSnapshot snapshot;
        final List<String> installedPackages;
        final int candidateProviders;
        final int readableProviders;
        final int lockedProviders;
        final String note;

        Result(WeatherSnapshot snapshot, List<String> installedPackages,
               int candidateProviders, int readableProviders, int lockedProviders, String note) {
            this.snapshot = snapshot;
            this.installedPackages = Collections.unmodifiableList(installedPackages);
            this.candidateProviders = candidateProviders;
            this.readableProviders = readableProviders;
            this.lockedProviders = lockedProviders;
            this.note = note == null ? "" : note;
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            if (installedPackages.isEmpty()) {
                sb.append("未检测到常见厂商内置天气应用");
            } else {
                sb.append("检测到天气组件：").append(join(installedPackages));
            }
            sb.append("\n数据接口探测：共 ").append(candidateProviders)
                    .append(" 个候选 · 可读取 ").append(readableProviders)
                    .append(" 个 · 受系统权限保护 ").append(lockedProviders).append(" 个");
            if (snapshot != null) {
                sb.append("\n当前读取结果：")
                        .append(snapshot.city.isEmpty() ? "本机位置" : snapshot.city)
                        .append(" · ").append(WeatherSnapshot.conditionLabel(snapshot.condition));
                if (snapshot.tempC != null) sb.append(" ").append(snapshot.tempC).append("°");
                if (snapshot.lowC != null && snapshot.highC != null) {
                    sb.append("（").append(snapshot.lowC).append("°~").append(snapshot.highC).append("°）");
                }
                if (snapshot.precipProb != null) {
                    sb.append(" · 降水 ").append(snapshot.precipProb).append("%");
                }
            } else if (!note.isEmpty()) {
                sb.append("\n说明：").append(note);
            }
            return sb.toString();
        }

        private static String join(List<String> items) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(items.get(i));
            }
            return sb.toString();
        }
    }

    private static final class ProbeAccumulator {
        String providerName = "";
        String city = "";
        int condition = WeatherSnapshot.COND_UNKNOWN;
        Integer tempC;
        Integer lowC;
        Integer highC;
        Integer precipProb;
        boolean hasData() {
            return tempC != null || (lowC != null && highC != null) || condition != WeatherSnapshot.COND_UNKNOWN;
        }
    }

    static Result probe(Context context) {
        if (context == null) {
            return new Result(null, new ArrayList<String>(), 0, 0, 0, "上下文不可用");
        }
        List<String> installed = detectPackages(context);
        Set<String> uris = new LinkedHashSet<String>();
        for (int i = 0; i < CURATED_URIS.length; i++) uris.add(CURATED_URIS[i]);

        int candidateProviders = 0;
        int lockedProviders = 0;
        try {
            PackageManager pm = context.getPackageManager();
            List<ProviderInfo> providers = pm.queryContentProviders(null, 0, 0);
            if (providers != null) {
                for (int i = 0; i < providers.size(); i++) {
                    ProviderInfo pi = providers.get(i);
                    if (pi == null || pi.authority == null) continue;
                    String authLower = pi.authority.toLowerCase(Locale.ROOT);
                    String pkgLower = pi.packageName == null ? "" : pi.packageName.toLowerCase(Locale.ROOT);
                    if (!authLower.contains("weather") && !pkgLower.contains("weather")) continue;
                    candidateProviders++;
                    if (!pi.exported) {
                        lockedProviders++;
                        continue;
                    }
                    String[] parts = pi.authority.split(";");
                    for (int j = 0; j < parts.length; j++) {
                        String a = parts[j].trim();
                        if (a.isEmpty()) continue;
                        uris.add("content://" + a + "/weather");
                        uris.add("content://" + a + "/actualWeatherData/1");
                        uris.add("content://" + a + "/daily_weather/1");
                        uris.add("content://" + a);
                    }
                }
            }
        } catch (Throwable ignored) {}

        ProbeAccumulator acc = new ProbeAccumulator();
        int readableProviders = 0;
        ContentResolver resolver = context.getContentResolver();
        if (resolver != null) {
            for (String uriStr : uris) {
                Cursor cursor = null;
                try {
                    cursor = resolver.query(Uri.parse(uriStr), null, null, null, null);
                    if (cursor != null) {
                        readableProviders++;
                        if (cursor.moveToFirst()) {
                            extractFromCursor(cursor, uriStr, acc);
                        }
                    }
                } catch (SecurityException se) {
                    lockedProviders++;
                } catch (Throwable ignored) {
                } finally {
                    if (cursor != null) {
                        try { cursor.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        if (!acc.hasData()) {
            probeSettings(context, acc);
        }
        if (!acc.hasData()) {
            probeBroadcasts(context, acc);
        }

        WeatherSnapshot snap = null;
        if (acc.hasData()) {
            long now = System.currentTimeMillis();
            String label = acc.providerName.isEmpty() ? "本机系统天气" : acc.providerName;
            snap = new WeatherSnapshot("local", label, acc.city, acc.condition, true,
                    acc.tempC, acc.lowC, acc.highC, acc.precipProb, now, now);
        }
        String note = snap != null ? "" : (lockedProviders > 0
                ? "系统天气组件受电视厂商签名权限隔离，未向第三方桌面开放读取"
                : "本机未开放可读的系统天气接口");
        return new Result(snap, installed, Math.max(candidateProviders, readableProviders),
                readableProviders, lockedProviders, note);
    }

    private static List<String> detectPackages(Context context) {
        ArrayList<String> found = new ArrayList<String>();
        PackageManager pm = context.getPackageManager();
        if (pm == null) return found;
        for (int i = 0; i < KNOWN_PACKAGES.length; i++) {
            String pkg = KNOWN_PACKAGES[i];
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi != null) found.add(pkg);
            } catch (Throwable ignored) {}
        }
        return found;
    }

    private static void extractFromCursor(Cursor cursor, String uriStr, ProbeAccumulator acc) {
        if (acc.providerName.isEmpty()) {
            acc.providerName = uriStr.contains("omni") ? "OmniJaws" : "系统天气";
        }
        String desc = firstString(cursor, new String[]{
                "description", "weather", "condition", "weather_desc", "weathernamesfrom", "text_short", "phenomena"
        });
        int rawCode = firstInt(cursor, new String[]{
                "weather_type", "weather_code", "condition_code", "icon_code", "code"
        }, -1);
        int mapped = WeatherSnapshot.mapOemCondition(desc, rawCode);
        if (mapped != WeatherSnapshot.COND_UNKNOWN && acc.condition == WeatherSnapshot.COND_UNKNOWN) {
            acc.condition = mapped;
        }
        if (acc.city.isEmpty()) {
            String city = firstString(cursor, new String[]{
                    "city_name", "city", "location", "area_name", "district", "name"
            });
            if (city != null) acc.city = city;
        }
        if (acc.tempC == null) {
            String tempStr = firstString(cursor, new String[]{
                    "temperature", "temp", "current_temp", "actual_temp", "now_temp", "tem"
            });
            acc.tempC = WeatherSnapshot.parseTemp(tempStr);
        }
        if (acc.lowC == null || acc.highC == null) {
            String loStr = firstString(cursor, new String[]{
                    "tmplows", "temp_low", "min_temp", "low_temp", "low", "tem_low", "min"
            });
            String hiStr = firstString(cursor, new String[]{
                    "tmphighs", "temp_high", "max_temp", "high_temp", "high", "tem_high", "max"
            });
            Integer lo = WeatherSnapshot.parseTemp(loStr);
            Integer hi = WeatherSnapshot.parseTemp(hiStr);
            if (lo != null && hi != null) {
                acc.lowC = Math.min(lo.intValue(), hi.intValue());
                acc.highC = Math.max(lo.intValue(), hi.intValue());
            } else {
                String rangeStr = firstString(cursor, new String[]{
                        "temperature_range", "temp_range", "range"
                });
                int[] pair = WeatherSnapshot.parseRange(rangeStr);
                if (pair != null) {
                    acc.lowC = pair[0];
                    acc.highC = pair[1];
                }
            }
        }
        if (acc.precipProb == null) {
            String popStr = firstString(cursor, new String[]{
                    "pop", "precip_prob", "rain_probability", "precipitation_probability", "rain_prob"
            });
            acc.precipProb = WeatherSnapshot.parsePercent(popStr);
        }
    }

    private static void probeSettings(Context context, ProbeAccumulator acc) {
        ContentResolver cr = context.getContentResolver();
        if (cr == null) return;
        for (int i = 0; i < SETTINGS_KEYS.length; i++) {
            String val = null;
            try {
                val = Settings.System.getString(cr, SETTINGS_KEYS[i]);
            } catch (Throwable ignored) {}
            if ((val == null || val.isEmpty()) && Build.VERSION.SDK_INT >= 17) {
                try {
                    Class<?> global = Class.forName("android.provider.Settings$Global");
                    Method m = global.getMethod("getString", ContentResolver.class, String.class);
                    Object obj = m.invoke(null, cr, SETTINGS_KEYS[i]);
                    if (obj instanceof String) val = (String) obj;
                } catch (Throwable ignored) {}
            }
            if (val != null && !val.trim().isEmpty()) {
                parseLooseSummary(val, "系统设置天气", acc);
                if (acc.hasData()) return;
            }
        }
    }

    private static void probeBroadcasts(Context context, ProbeAccumulator acc) {
        for (int i = 0; i < BROADCAST_ACTIONS.length; i++) {
            try {
                Intent sticky = context.registerReceiver(null, new IntentFilter(BROADCAST_ACTIONS[i]));
                if (sticky == null || sticky.getExtras() == null) continue;
                Bundle extras = sticky.getExtras();
                String city = firstExtraString(extras, new String[]{"city", "city_name", "cityName", "location"});
                if (city != null && acc.city.isEmpty()) acc.city = city;
                String desc = firstExtraString(extras, new String[]{"weather", "description", "condition", "weatherName"});
                int code = firstExtraInt(extras, new String[]{"weather_type", "weatherType", "code"}, -1);
                int mapped = WeatherSnapshot.mapOemCondition(desc, code);
                if (mapped != WeatherSnapshot.COND_UNKNOWN) acc.condition = mapped;
                if (acc.tempC == null) {
                    acc.tempC = WeatherSnapshot.parseTemp(firstExtraString(extras, new String[]{"temp", "temperature", "curTemp"}));
                }
                if (acc.lowC == null || acc.highC == null) {
                    int[] pair = WeatherSnapshot.parseRange(firstExtraString(extras, new String[]{"tempRange", "temperature_range"}));
                    if (pair != null) {
                        acc.lowC = pair[0];
                        acc.highC = pair[1];
                    }
                }
                if (acc.hasData()) {
                    acc.providerName = "系统天气广播";
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void parseLooseSummary(String raw, String sourceName, ProbeAccumulator acc) {
        acc.providerName = sourceName;
        int cond = WeatherSnapshot.mapOemCondition(raw, -1);
        if (cond != WeatherSnapshot.COND_UNKNOWN) acc.condition = cond;
        int[] range = WeatherSnapshot.parseRange(raw);
        if (range != null) {
            acc.lowC = range[0];
            acc.highC = range[1];
        }
        Integer t = WeatherSnapshot.parseTemp(raw);
        if (t != null) acc.tempC = t;
    }

    private static String firstString(Cursor cursor, String[] names) {
        for (int i = 0; i < names.length; i++) {
            int idx = cursor.getColumnIndex(names[i]);
            if (idx >= 0) {
                try {
                    String v = cursor.getString(idx);
                    if (v != null && !v.trim().isEmpty()) return v.trim();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static int firstInt(Cursor cursor, String[] names, int fallback) {
        for (int i = 0; i < names.length; i++) {
            int idx = cursor.getColumnIndex(names[i]);
            if (idx >= 0) {
                try {
                    return cursor.getInt(idx);
                } catch (Throwable ignored) {
                    try {
                        String s = cursor.getString(idx);
                        if (s != null) return Integer.parseInt(s.trim());
                    } catch (Throwable ignored2) {}
                }
            }
        }
        return fallback;
    }

    private static String firstExtraString(Bundle extras, String[] names) {
        for (int i = 0; i < names.length; i++) {
            Object v = extras.get(names[i]);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static int firstExtraInt(Bundle extras, String[] names, int fallback) {
        for (int i = 0; i < names.length; i++) {
            Object v = extras.get(names[i]);
            if (v instanceof Number) return ((Number) v).intValue();
        }
        return fallback;
    }
}
