// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dual-track weather repository combining zero-permission local OEM probes
 * with keyless HTTPS Open-Meteo forecasts and rate-limited caching.
 */
final class WeatherRepository {
    static final class CityPreset {
        final String key;
        final String name;
        final String region;
        final double lat;
        final double lon;

        CityPreset(String key, String name, String region, double lat, double lon) {
            this.key = key;
            this.name = name;
            this.region = region;
            this.lat = lat;
            this.lon = lon;
        }

        String displayName() {
            return region.isEmpty() ? name : name + " · " + region;
        }
    }

    interface SearchCallback {
        void onResult(List<CityPreset> results, String error);
    }

    static final CityPreset[] PRESETS = {
            new CityPreset("beijing", "北京", "北京市", 39.9042, 116.4074),
            new CityPreset("shanghai", "上海", "上海市", 31.2304, 121.4737),
            new CityPreset("guangzhou", "广州", "广东省", 23.1291, 113.2644),
            new CityPreset("shenzhen", "深圳", "广东省", 22.5431, 114.0579),
            new CityPreset("hangzhou", "杭州", "浙江省", 30.2741, 120.1551),
            new CityPreset("chengdu", "成都", "四川省", 30.5728, 104.0668),
            new CityPreset("nanjing", "南京", "江苏省", 32.0603, 118.7969),
            new CityPreset("wuhan", "武汉", "湖北省", 30.5928, 114.3055),
            new CityPreset("xian", "西安", "陕西省", 34.3416, 108.9398),
            new CityPreset("chongqing", "重庆", "重庆市", 29.5630, 106.5516),
            new CityPreset("tianjin", "天津", "天津市", 39.0842, 117.2009),
            new CityPreset("suzhou", "苏州", "江苏省", 31.2989, 120.5853)
    };

    private static final String WEATHER_PREFS = "weather";
    private static final String CACHE_FILE = "weather-cache.json";

    final MainActivity host;
    final Context context;
    final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WeatherRefreshPolicy policy = new WeatherRefreshPolicy();

    private volatile WeatherSnapshot current;
    private volatile LocalWeatherProbe.Result lastProbe;
    private volatile String statusNote = "等待初始化天气探测";
    private volatile boolean destroyed;
    private Runnable listener;

    WeatherRepository(MainActivity host) {
        this.host = host;
        this.context = host.getApplicationContext();
        this.prefs = context.getSharedPreferences(WEATHER_PREFS, 0);
        String savedKey = prefs.getString("city_key", "");
        if (!savedKey.isEmpty()) {
            policy.select(savedKey);
        }
        CachedRecord cached = readCacheRecord(context);
        if (cached != null && WeatherRefreshPolicy.freshness(System.currentTimeMillis(), cached.snapshot.fetchedAtMs) != WeatherRefreshPolicy.Freshness.EXPIRED) {
            current = cached.snapshot;
            statusNote = "已加载缓存天气（" + cached.snapshot.providerName + "）";
        }
    }

    synchronized void listen(Runnable cb) {
        listener = cb;
    }

    WeatherSnapshot current() {
        if (!enabled()) return null;
        return current;
    }

    boolean enabled() {
        return host.preferences.getBoolean("weather_enabled", true);
    }

    int sourceMode() {
        return Math.max(0, Math.min(2, host.preferences.getInt("weather_source", 0)));
    }

    LocalWeatherProbe.Result lastProbe() {
        return lastProbe;
    }

    String statusNote() {
        return statusNote;
    }

    String cityLabel() {
        String name = prefs.getString("city_name", "");
        if (!name.isEmpty()) {
            String region = prefs.getString("city_region", "");
            return region.isEmpty() ? name : name + "（" + region + "）";
        }
        WeatherSnapshot snap = current;
        if (snap != null && !snap.city.isEmpty()) {
            return snap.city + "（本机识别）";
        }
        return "未指定城市";
    }

    String summaryLine() {
        WeatherSnapshot snap = current;
        if (snap == null) return statusNote;
        StringBuilder sb = new StringBuilder();
        sb.append(snap.city.isEmpty() ? "当前区域" : snap.city).append(" · ")
                .append(WeatherSnapshot.conditionLabel(snap.condition));
        if (snap.tempC != null) sb.append(" ").append(snap.tempC).append("°");
        if (snap.lowC != null && snap.highC != null) {
            sb.append("（").append(snap.lowC).append("° ~ ").append(snap.highC).append("°）");
        }
        if (snap.precipProb != null) {
            sb.append(" · 降水概率 ").append(snap.precipProb).append("%");
        }
        sb.append("\n数据来自：").append(snap.providerName);
        return sb.toString();
    }

    void selectCity(CityPreset preset) {
        if (preset == null) return;
        prefs.edit()
                .putString("city_key", preset.key)
                .putString("city_name", preset.name)
                .putString("city_region", preset.region)
                .putString("lat", String.format(Locale.US, "%.4f", preset.lat))
                .putString("lon", String.format(Locale.US, "%.4f", preset.lon))
                .apply();
        policy.select(preset.key);
        statusNote = "已切换城市：" + preset.name + "，正在刷新...";
        notifyChanged();
        refresh(true);
    }

    void clearCity() {
        prefs.edit()
                .remove("city_key")
                .remove("city_name")
                .remove("city_region")
                .remove("lat")
                .remove("lon")
                .apply();
        policy.select("");
        File f = new File(context.getNoBackupFilesDir(), CACHE_FILE);
        if (f.exists()) f.delete();
        current = null;
        statusNote = "已清除城市与在线缓存，保留本机天气探测";
        notifyChanged();
        refresh(true);
    }

    void refresh(final boolean manual) {
        if (destroyed) return;
        final boolean isEnabled = enabled();
        if (!isEnabled) {
            statusNote = "首页天气显示已关闭";
            notifyChanged();
            return;
        }
        final int mode = sourceMode();
        final boolean visible = host.foreground && host.isDesktopVisible();
        worker.execute(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                try {
                    runRefresh(manual, mode, visible);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void runRefresh(boolean manual, int mode, boolean visible) {
        LocalWeatherProbe.Result localResult = null;
        if (mode != 2 || manual) {
            localResult = LocalWeatherProbe.probe(context);
            lastProbe = localResult;
        }

        WeatherSnapshot localSnap = localResult == null ? null : localResult.snapshot;
        if (mode == 1) {
            if (localSnap != null) {
                current = localSnap;
                writeCache(context, "local:" + localSnap.city, localSnap);
                statusNote = "已读取本机天气（" + localSnap.providerName + "）";
            } else {
                CachedRecord rec = readCacheRecord(context);
                if (rec != null && "local".equals(rec.snapshot.source)
                        && WeatherRefreshPolicy.freshness(System.currentTimeMillis(), rec.snapshot.fetchedAtMs) != WeatherRefreshPolicy.Freshness.EXPIRED) {
                    current = rec.snapshot;
                    statusNote = "本机暂未返回新数据，沿用最近本机缓存";
                } else {
                    current = null;
                    statusNote = localResult != null && !localResult.note.isEmpty()
                            ? localResult.note : "本机未开放免权系统天气接口";
                }
            }
            notifyChanged();
            return;
        }

        String cityKey = prefs.getString("city_key", "");
        String cityName = prefs.getString("city_name", "");
        String latStr = prefs.getString("lat", "");
        String lonStr = prefs.getString("lon", "");
        boolean hasCity = !cityKey.isEmpty() && !latStr.isEmpty() && !lonStr.isEmpty();

        // If automatic mode has no configured city yet, publish local snapshot right away if present.
        if (!hasCity) {
            if (localSnap != null) {
                current = localSnap;
                writeCache(context, "local:" + localSnap.city, localSnap);
                statusNote = "使用本机系统天气" + (localSnap.precipProb == null ? "（选择城市后可补齐温差与降水概率）" : "");
            } else {
                statusNote = mode == 0
                        ? "未读到开放的本机天气接口，请选择城市使用在线气象服务"
                        : "尚未选择在线天气城市";
            }
            notifyChanged();
            return;
        }

        CachedRecord rec = readCacheRecord(context);
        boolean matchingFresh = rec != null
                && cityKey.equals(rec.cityKey)
                && WeatherRefreshPolicy.freshness(System.currentTimeMillis(), rec.snapshot.fetchedAtMs) == WeatherRefreshPolicy.Freshness.FRESH;

        boolean online = isOnline();
        long token = policy.begin(true, visible || manual, online, SystemClock.elapsedRealtime(), matchingFresh, manual);
        if (token == 0L) {
            WeatherSnapshot fallback = rec != null && cityKey.equals(rec.cityKey)
                    && WeatherRefreshPolicy.freshness(System.currentTimeMillis(), rec.snapshot.fetchedAtMs) != WeatherRefreshPolicy.Freshness.EXPIRED
                    ? rec.snapshot : null;
            if (mode == 0 && localSnap != null) {
                current = mergeLocalAndOnline(localSnap, fallback, cityName);
                statusNote = online ? "已使用本机天气与最新缓存" : "离线状态：已合并本机天气与缓存";
            } else if (fallback != null) {
                current = fallback;
                statusNote = online ? "当前缓存仍在有效期内" : "当前无网络连接，沿用最近城市缓存";
            } else if (!online) {
                statusNote = "当前无网络连接，暂无法获取在线天气";
            }
            notifyChanged();
            return;
        }

        if (mode == 0 && localSnap != null) {
            current = mergeLocalAndOnline(localSnap, rec != null && cityKey.equals(rec.cityKey) ? rec.snapshot : null, cityName);
            notifyChanged();
        }

        WeatherSnapshot remote = null;
        long retryAfter = 0L;
        try {
            remote = fetchOpenMeteo(latStr, lonStr, cityName);
        } catch (RetryLaterException rle) {
            retryAfter = rle.retryAfterMs;
        } catch (Throwable ignored) {}

        boolean ok = policy.complete(token, SystemClock.elapsedRealtime(), remote != null, retryAfter);
        if (!ok) return;

        if (remote != null) {
            WeatherSnapshot combined = (mode == 0 && localSnap != null)
                    ? mergeLocalAndOnline(localSnap, remote, cityName)
                    : remote;
            current = combined;
            writeCache(context, cityKey, remote);
            statusNote = (mode == 0 && localSnap != null)
                    ? "已同步本机天气并补齐在线温差/降水概率"
                    : "已更新 " + combined.city + " 实况天气";
        } else if (mode == 0 && localSnap != null) {
            current = localSnap;
            statusNote = "在线请求未成功，已保留本机天气";
        } else if (rec != null && cityKey.equals(rec.cityKey)
                && WeatherRefreshPolicy.freshness(System.currentTimeMillis(), rec.snapshot.fetchedAtMs) != WeatherRefreshPolicy.Freshness.EXPIRED) {
            current = rec.snapshot;
            statusNote = "在线刷新失败，暂用最近缓存数据";
        } else {
            statusNote = "在线天气请求失败，请检查网络后重试";
        }
        notifyChanged();
    }

    void searchCities(final String query, final SearchCallback callback) {
        if (destroyed || callback == null) return;
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            host.handler.post(new Runnable() {
                @Override public void run() { callback.onResult(new ArrayList<CityPreset>(), "请输入城市名"); }
            });
            return;
        }
        worker.execute(new Runnable() {
            @Override public void run() {
                final List<CityPreset> found = new ArrayList<CityPreset>();
                String err = null;
                if (!isOnline()) {
                    err = "当前无网络连接";
                } else {
                    try {
                        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                                + URLEncoder.encode(q, "UTF-8") + "&count=8&language=zh&format=json";
                        String body = httpGet(url);
                        JSONObject root = new JSONObject(body);
                        JSONArray arr = root.optJSONArray("results");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject item = arr.optJSONObject(i);
                                if (item == null) continue;
                                String name = item.optString("name", "");
                                if (name.isEmpty()) continue;
                                String admin = item.optString("admin1", "");
                                String country = item.optString("country", "");
                                String region = !admin.isEmpty() ? admin : country;
                                double lat = item.optDouble("latitude", Double.NaN);
                                double lon = item.optDouble("longitude", Double.NaN);
                                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                                long id = item.optLong("id", 0L);
                                String key = id > 0 ? "geo_" + id : String.format(Locale.US, "geo_%.3f_%.3f", lat, lon);
                                found.add(new CityPreset(key, name, region, lat, lon));
                            }
                        }
                    } catch (Throwable t) {
                        err = "城市检索失败，请稍后重试";
                    }
                }
                final String finalErr = err;
                if (!destroyed) {
                    host.handler.post(new Runnable() {
                        @Override public void run() {
                            callback.onResult(found, finalErr);
                        }
                    });
                }
            }
        });
    }

    private static WeatherSnapshot mergeLocalAndOnline(WeatherSnapshot local, WeatherSnapshot online, String fallbackCity) {
        if (local == null) return online;
        if (online == null) return local;
        String city = !local.city.isEmpty() ? local.city : (fallbackCity.isEmpty() ? online.city : fallbackCity);
        int cond = local.condition != WeatherSnapshot.COND_UNKNOWN ? local.condition : online.condition;
        Integer temp = local.tempC != null ? local.tempC : online.tempC;
        Integer low = local.lowC != null ? local.lowC : online.lowC;
        Integer high = local.highC != null ? local.highC : online.highC;
        Integer precip = local.precipProb != null ? local.precipProb : online.precipProb;
        return new WeatherSnapshot(
                "local+online",
                local.providerName + " + Open-Meteo",
                city,
                cond,
                online.isDay,
                temp,
                low,
                high,
                precip,
                Math.max(local.observedAtMs, online.observedAtMs),
                Math.max(local.fetchedAtMs, online.fetchedAtMs)
        );
    }

    private static WeatherSnapshot fetchOpenMeteo(String latStr, String lonStr, String cityName) throws Exception {
        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude="
                + URLEncoder.encode(latStr, "UTF-8")
                + "&longitude=" + URLEncoder.encode(lonStr, "UTF-8")
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=1&timezone=auto";
        String body = httpGet(endpoint);
        JSONObject root = new JSONObject(body);
        JSONObject currentObj = root.optJSONObject("current");
        JSONObject dailyObj = root.optJSONObject("daily");

        Integer tempC = null;
        int wmo = -1;
        boolean isDay = true;
        if (currentObj != null) {
            if (currentObj.has("temperature_2m") && !currentObj.isNull("temperature_2m")) {
                tempC = Math.round((float) currentObj.getDouble("temperature_2m"));
            }
            if (currentObj.has("weather_code") && !currentObj.isNull("weather_code")) {
                wmo = currentObj.getInt("weather_code");
            }
            if (currentObj.has("is_day") && !currentObj.isNull("is_day")) {
                isDay = currentObj.getInt("is_day") != 0;
            }
        }

        Integer lowC = null, highC = null, precip = null;
        if (dailyObj != null) {
            JSONArray mins = dailyObj.optJSONArray("temperature_2m_min");
            JSONArray maxs = dailyObj.optJSONArray("temperature_2m_max");
            JSONArray probs = dailyObj.optJSONArray("precipitation_probability_max");
            JSONArray codes = dailyObj.optJSONArray("weather_code");
            if (mins != null && mins.length() > 0 && !mins.isNull(0)) {
                lowC = Math.round((float) mins.getDouble(0));
            }
            if (maxs != null && maxs.length() > 0 && !maxs.isNull(0)) {
                highC = Math.round((float) maxs.getDouble(0));
            }
            if (probs != null && probs.length() > 0 && !probs.isNull(0)) {
                precip = Math.max(0, Math.min(100, Math.round((float) probs.getDouble(0))));
            }
            if (wmo < 0 && codes != null && codes.length() > 0 && !codes.isNull(0)) {
                wmo = codes.getInt(0);
            }
        }

        long now = System.currentTimeMillis();
        return new WeatherSnapshot(
                "online",
                "Open-Meteo",
                cityName == null ? "" : cityName,
                WeatherSnapshot.mapWmoCode(wmo),
                isDay,
                tempC,
                lowC,
                highC,
                precip,
                now,
                now
        );
    }

    private static final class RetryLaterException extends IOException {
        final long retryAfterMs;
        RetryLaterException(long retryAfterMs) {
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static String httpGet(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "MarcoTV/" + ProjectInfo.VERSION);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code == 429 || code == 503) {
                long retryMs = 5 * 60 * 1000L;
                String ra = conn.getHeaderField("Retry-After");
                if (ra != null) {
                    try {
                        retryMs = Math.max(60_000L, Long.parseLong(ra.trim()) * 1000L);
                    } catch (NumberFormatException ignored) {}
                }
                throw new RetryLaterException(retryMs);
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[2048];
                int n;
                int total = 0;
                while ((n = br.read(buf)) >= 0) {
                    total += n;
                    if (total > 262144) throw new IOException("Response too large");
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void notifyChanged() {
        if (destroyed) return;
        host.handler.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                if (host.home != null) host.home.setWeather(current());
                Runnable cb;
                synchronized (WeatherRepository.this) {
                    cb = listener;
                }
                if (cb != null) cb.run();
            }
        });
    }

    void destroy() {
        destroyed = true;
        policy.cancel();
        synchronized (this) {
            listener = null;
        }
        worker.shutdownNow();
    }

    static WeatherSnapshot loadCached(Context context) {
        CachedRecord rec = readCacheRecord(context);
        if (rec == null) return null;
        if (WeatherRefreshPolicy.freshness(System.currentTimeMillis(), rec.snapshot.fetchedAtMs) == WeatherRefreshPolicy.Freshness.EXPIRED) {
            return null;
        }
        return rec.snapshot;
    }

    private static final class CachedRecord {
        final String cityKey;
        final WeatherSnapshot snapshot;
        CachedRecord(String cityKey, WeatherSnapshot snapshot) {
            this.cityKey = cityKey;
            this.snapshot = snapshot;
        }
    }

    private static CachedRecord readCacheRecord(Context context) {
        if (context == null) return null;
        File f = new File(context.getNoBackupFilesDir(), CACHE_FILE);
        if (!f.exists() || f.length() <= 0 || f.length() > 65536) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] data;
            try {
                data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int r = in.read(data, read, data.length - read);
                    if (r < 0) break;
                    read += r;
                }
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (o.optInt("schema", 0) != 1) return null;
            String cityKey = o.optString("cityKey", "");
            Integer temp = o.has("tempC") && !o.isNull("tempC") ? o.getInt("tempC") : null;
            Integer low = o.has("lowC") && !o.isNull("lowC") ? o.getInt("lowC") : null;
            Integer high = o.has("highC") && !o.isNull("highC") ? o.getInt("highC") : null;
            Integer precip = o.has("precipProb") && !o.isNull("precipProb") ? o.getInt("precipProb") : null;
            WeatherSnapshot snap = new WeatherSnapshot(
                    o.optString("source", "cache"),
                    o.optString("provider", "天气缓存"),
                    o.optString("city", ""),
                    o.optInt("condition", WeatherSnapshot.COND_UNKNOWN),
                    o.optBoolean("isDay", true),
                    temp, low, high, precip,
                    o.optLong("observedAtMs", 0L),
                    o.optLong("fetchedAtMs", 0L)
            );
            return new CachedRecord(cityKey, snap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeCache(Context context, String cityKey, WeatherSnapshot snap) {
        if (context == null || snap == null) return;
        File dir = context.getNoBackupFilesDir();
        if (dir == null) return;
        File tmp = new File(dir, CACHE_FILE + ".tmp");
        File target = new File(dir, CACHE_FILE);
        try {
            JSONObject o = new JSONObject();
            o.put("schema", 1);
            o.put("cityKey", cityKey == null ? "" : cityKey);
            o.put("source", snap.source);
            o.put("provider", snap.providerName);
            o.put("city", snap.city);
            o.put("condition", snap.condition);
            o.put("isDay", snap.isDay);
            if (snap.tempC != null) o.put("tempC", snap.tempC.intValue());
            if (snap.lowC != null) o.put("lowC", snap.lowC.intValue());
            if (snap.highC != null) o.put("highC", snap.highC.intValue());
            if (snap.precipProb != null) o.put("precipProb", snap.precipProb.intValue());
            o.put("observedAtMs", snap.observedAtMs);
            o.put("fetchedAtMs", snap.fetchedAtMs);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (Throwable ignored) {
            tmp.delete();
        }
    }
}
