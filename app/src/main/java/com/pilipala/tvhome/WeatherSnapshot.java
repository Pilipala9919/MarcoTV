// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.Locale;

/** Immutable API-14 weather snapshot, parsers, and customizable line formatting. */
final class WeatherSnapshot {
    static final int COND_CLEAR = 0;
    static final int COND_PARTLY_CLOUDY = 1;
    static final int COND_CLOUDY = 2;
    static final int COND_FOG = 3;
    static final int COND_RAIN = 4;
    static final int COND_THUNDER = 5;
    static final int COND_SNOW = 6;
    static final int COND_SLEET = 7;
    static final int COND_UNKNOWN = 8;

    static final int PRECIP_OFF = 0;
    static final int PRECIP_WHEN_POSITIVE = 1;
    static final int PRECIP_ALWAYS = 2;

    static final String[] SOURCE_MODES = {"自动（本机优先+在线补齐）", "仅本机系统天气", "在线城市天气"};
    static final String[] PRECIP_MODES = {"关闭", "有降水概率时显示", "始终显示"};

    final String source;
    final String providerName;
    final String city;
    final int condition;
    final boolean isDay;
    final Integer tempC;
    final Integer lowC;
    final Integer highC;
    final Integer precipProb;
    final long observedAtMs;
    final long fetchedAtMs;

    WeatherSnapshot(String source, String providerName, String city, int condition, boolean isDay,
                    Integer tempC, Integer lowC, Integer highC, Integer precipProb,
                    long observedAtMs, long fetchedAtMs) {
        this.source = source == null ? "local" : source;
        this.providerName = providerName == null ? "" : providerName;
        this.city = city == null ? "" : city.trim();
        this.condition = condition;
        this.isDay = isDay;
        this.tempC = tempC;
        this.lowC = lowC;
        this.highC = highC;
        this.precipProb = precipProb;
        this.observedAtMs = observedAtMs;
        this.fetchedAtMs = fetchedAtMs;
    }

    static WeatherSnapshot samplePreview() {
        long now = System.currentTimeMillis();
        return new WeatherSnapshot("preview", "示例预览", "杭州", COND_PARTLY_CLOUDY, true,
                21, 15, 24, 30, now, now);
    }

    static int mapWmoCode(int code) {
        if (code == 0) return COND_CLEAR;
        if (code == 1 || code == 2) return COND_PARTLY_CLOUDY;
        if (code == 3) return COND_CLOUDY;
        if (code == 45 || code == 48) return COND_FOG;
        if (code == 66 || code == 67) return COND_SLEET;
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) return COND_RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return COND_SNOW;
        if (code >= 95 && code <= 99) return COND_THUNDER;
        return COND_UNKNOWN;
    }

    static int mapOemCondition(String text, int code) {
        if (text != null) {
            String s = text.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) {
                if (s.contains("雷") || s.contains("thunder") || s.contains("storm")) return COND_THUNDER;
                if (s.contains("雨夹雪") || s.contains("冻雨") || s.contains("冰雹") || s.contains("sleet")) return COND_SLEET;
                if (s.contains("雪") || s.contains("snow")) return COND_SNOW;
                if (s.contains("雨") || s.contains("rain") || s.contains("drizzle") || s.contains("shower")) return COND_RAIN;
                if (s.contains("雾") || s.contains("霾") || s.contains("沙") || s.contains("尘")
                        || s.contains("fog") || s.contains("haze") || s.contains("mist") || s.contains("dust")) return COND_FOG;
                if (s.contains("阴") || s.contains("overcast")) return COND_CLOUDY;
                if (s.contains("多云") || s.contains("少云") || s.contains("partly")) return COND_PARTLY_CLOUDY;
                if (s.contains("云") || s.contains("cloud")) return COND_CLOUDY;
                if (s.contains("晴") || s.contains("sunny") || s.contains("clear")) return COND_CLEAR;
            }
        }
        if (code == 0) return COND_CLEAR;
        if (code == 1) return COND_PARTLY_CLOUDY;
        if (code == 2) return COND_CLOUDY;
        if (code == 3 || (code >= 7 && code <= 12) || code == 19 || code == 21 || code == 22 || code == 23) return COND_RAIN;
        if (code == 4 || code == 5) return COND_THUNDER;
        if (code == 6) return COND_SLEET;
        if ((code >= 13 && code <= 17) || code == 26 || code == 27 || code == 28) return COND_SNOW;
        if (code == 18 || code == 20 || code == 29 || code == 30 || code == 31 || code == 32 || code == 53) return COND_FOG;
        return COND_UNKNOWN;
    }

    static Integer parseTemp(String raw) {
        if (raw == null) return null;
        StringBuilder digits = new StringBuilder();
        boolean seenDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '-' && !seenDigit && digits.length() == 0) {
                digits.append(ch);
            } else if (ch >= '0' && ch <= '9') {
                digits.append(ch);
                seenDigit = true;
            } else if (ch == '.' && seenDigit) {
                digits.append(ch);
            } else if (seenDigit) {
                break;
            }
        }
        if (!seenDigit) return null;
        try {
            int val = Math.round(Float.parseFloat(digits.toString()));
            return (val >= -90 && val <= 65) ? Integer.valueOf(val) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int[] parseRange(String raw) {
        if (raw == null) return null;
        Integer first = null, second = null;
        StringBuilder token = new StringBuilder();
        boolean seenDigit = false;
        for (int i = 0; i <= raw.length(); i++) {
            char ch = i < raw.length() ? raw.charAt(i) : ' ';
            if (ch == '-' && !seenDigit && token.length() == 0) {
                token.append(ch);
            } else if (ch >= '0' && ch <= '9') {
                token.append(ch);
                seenDigit = true;
            } else if (ch == '.' && seenDigit) {
                token.append(ch);
            } else if (seenDigit) {
                Integer val = parseTemp(token.toString());
                if (val != null) {
                    if (first == null) first = val;
                    else {
                        second = val;
                        break;
                    }
                }
                token.setLength(0);
                seenDigit = false;
                if (ch == '-') token.append(ch);
            } else {
                token.setLength(0);
            }
        }
        if (first == null || second == null) return null;
        int lo = Math.min(first.intValue(), second.intValue());
        int hi = Math.max(first.intValue(), second.intValue());
        return new int[]{lo, hi};
    }

    static Integer parsePercent(String raw) {
        Integer v = parseTemp(raw);
        if (v == null) return null;
        int n = v.intValue();
        return (n >= 0 && n <= 100) ? Integer.valueOf(n) : null;
    }

    static String conditionLabel(int condition) {
        switch (condition) {
            case COND_CLEAR: return "晴";
            case COND_PARTLY_CLOUDY: return "多云";
            case COND_CLOUDY: return "阴";
            case COND_FOG: return "雾霾";
            case COND_RAIN: return "雨";
            case COND_THUNDER: return "雷雨";
            case COND_SNOW: return "雪";
            case COND_SLEET: return "雨夹雪";
            default: return "天气";
        }
    }

    boolean hasPrecipToShow(int precipMode) {
        if (precipProb == null || precipMode == PRECIP_OFF) return false;
        if (precipMode == PRECIP_ALWAYS) return true;
        return precipProb.intValue() > 0;
    }

    /** Returns [mainText, precipTextOrEmpty]. */
    String[] buildDisplayParts(boolean showCity, boolean showCurrent, boolean showRange, int precipMode) {
        StringBuilder main = new StringBuilder();
        if ("preview".equals(source)) {
            main.append("示例 · ");
        }
        if (showCity && !city.isEmpty()) {
            main.append(city);
        }
        if (showCurrent && tempC != null) {
            if (main.length() > 0 && main.charAt(main.length() - 1) != ' ') main.append(" · ");
            main.append(tempC.intValue()).append("°");
        }
        if (showRange && lowC != null && highC != null) {
            if (main.length() > 0 && main.charAt(main.length() - 1) != ' ') {
                main.append(showCurrent && tempC != null ? "  " : " · ");
            }
            main.append(lowC.intValue()).append("°~").append(highC.intValue()).append("°");
        }
        if (main.length() == 0 && !showCity && !showCurrent && !showRange && !hasPrecipToShow(precipMode)) {
            main.append(conditionLabel(condition));
        }
        String precip = hasPrecipToShow(precipMode) ? (precipProb.intValue() + "%") : "";
        return new String[]{main.toString(), precip};
    }

    boolean isMissingRangeOrPrecip() {
        return lowC == null || highC == null || precipProb == null;
    }
}
