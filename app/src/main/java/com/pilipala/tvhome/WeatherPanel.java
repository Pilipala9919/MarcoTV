// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import java.util.List;

/** Dialog panel for configuring desktop weather source, city, local probe diagnostics, and styling. */
final class WeatherPanel {
    private final MainActivity host;
    private final WeatherRepository repo;
    private TvDialog page;

    WeatherPanel(MainActivity host) {
        this.host = host;
        this.repo = host.weather != null ? host.weather : new WeatherRepository(host);
    }

    private String summary() {
        boolean on = host.preferences.getBoolean("weather_enabled", true);
        int source = Math.max(0, Math.min(2, host.preferences.getInt("weather_source", 0)));
        return "状态：" + (on ? "已开启" : "已关闭")
                + " · 来源：" + WeatherSnapshot.SOURCE_MODES[source]
                + "\n城市：" + repo.cityLabel()
                + "\n" + repo.summaryLine();
    }

    private void refreshView() {
        if (host.isPage("weather") && page != null) {
            page.updateMessage(summary());
        }
    }

    void show() {
        repo.listen(new Runnable() {
            @Override public void run() {
                refreshView();
            }
        });
        boolean on = host.preferences.getBoolean("weather_enabled", true);
        page = new TvDialog.Builder(host)
                .setPageId("weather")
                .setTitle("桌面天气")
                .setMessage(summary())
                .setOrder("weather", "toggle", "source", "preset", "search", "refresh", "probe", "style", "clear")
                .setItems(new String[]{
                        on ? "关闭首页天气模块" : "开启首页天气模块",
                        "切换数据来源",
                        "选择常用城市",
                        "搜索其他城市",
                        "立即探测并刷新天气",
                        "查看本机天气探测报告",
                        "自定义天气图标与显示项",
                        "清除已选城市与缓存"
                }, (d, w) -> {
                    switch (w) {
                        case 0:
                            boolean next = !host.preferences.getBoolean("weather_enabled", true);
                            host.preferences.edit().putBoolean("weather_enabled", next).apply();
                            if (host.home != null) host.home.setWeather(next ? repo.current() : null);
                            if (next) repo.refresh(true);
                            show();
                            break;
                        case 1:
                            chooseSource();
                            break;
                        case 2:
                            choosePresetCity();
                            break;
                        case 3:
                            openCitySearch();
                            break;
                        case 4:
                            repo.refresh(true);
                            host.toast("正在刷新本机与城市天气...");
                            show();
                            break;
                        case 5:
                            showProbeReport();
                            break;
                        case 6:
                            d.dismiss();
                            new PersonalizationPanel(host).show("weather");
                            break;
                        case 7:
                            repo.clearCity();
                            host.toast("已清除城市配置与缓存");
                            show();
                            break;
                    }
                })
                .setNegativeButton("返回", (d, w) -> repo.listen(null))
                .create();
        page.setOnDismissListener(dialog -> {
            if (!host.isPage("weather")) repo.listen(null);
        });
        host.showDialog(page);
    }

    private void chooseSource() {
        int current = Math.max(0, Math.min(2, host.preferences.getInt("weather_source", 0)));
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("weather_source")
                .setTitle("天气数据来源")
                .setMessage("自动模式优先读取电视系统/厂商内置天气，若缺少温度范围或降雨概率则由已选城市的在线气象补齐。")
                .setSingleChoiceItems(WeatherSnapshot.SOURCE_MODES, current, (d, which) -> {
                    host.preferences.edit().putInt("weather_source", which).apply();
                    repo.refresh(true);
                    d.dismiss();
                    show();
                })
                .setNegativeButton("返回", null)
                .create());
    }

    private void choosePresetCity() {
        WeatherRepository.CityPreset[] presets = WeatherRepository.PRESETS;
        String[] labels = new String[presets.length];
        String savedKey = repo.prefs.getString("city_key", "");
        int selected = -1;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i].displayName();
            if (presets[i].key.equals(savedKey)) selected = i;
        }
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("weather_city_preset")
                .setTitle("选择常用城市")
                .setMessage("无需位置权限，选择城市后将用于显示或补齐温度范围与降雨概率。")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    if (which >= 0 && which < presets.length) {
                        repo.selectCity(presets[which]);
                        host.toast("已设置城市为 " + presets[which].name);
                    }
                    d.dismiss();
                    show();
                })
                .setNegativeButton("返回", null)
                .create());
    }

    private void openCitySearch() {
        final EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("输入城市中文名或拼音，如 厦门 / Qingdao");
        input.setHintTextColor(0x88CFD8E6);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        int padH = Math.round(16 * host.getResources().getDisplayMetrics().density);
        int padV = Math.round(12 * host.getResources().getDisplayMetrics().density);
        input.setPadding(padH, padV, padH, padV);
        input.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E2430);
        bg.setCornerRadius(12 * host.getResources().getDisplayMetrics().density);
        bg.setStroke(Math.round(host.getResources().getDisplayMetrics().density), 0x558CCBFF);
        input.setBackground(bg);

        host.showDialog(new TvDialog.Builder(host)
                .setPageId("weather_city_search")
                .setTitle("搜索城市")
                .setMessage("输入城市名称后点击搜索，将查询匹配的经纬度并缓存到本地。")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    final String query = input.getText().toString().trim();
                    if (query.isEmpty()) {
                        host.toast("请输入城市名称");
                        return;
                    }
                    host.toast("正在搜索 " + query + "...");
                    repo.searchCities(query, new WeatherRepository.SearchCallback() {
                        @Override public void onResult(List<WeatherRepository.CityPreset> results, String error) {
                            if (error != null) {
                                host.toast(error);
                                return;
                            }
                            if (results.isEmpty()) {
                                host.toast("未找到匹配城市：" + query);
                                return;
                            }
                            showSearchResults(results);
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .create());
    }

    private void showSearchResults(final List<WeatherRepository.CityPreset> results) {
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            labels[i] = results.get(i).displayName();
        }
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("weather_city_results")
                .setTitle("选择匹配城市")
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < results.size()) {
                        WeatherRepository.CityPreset picked = results.get(which);
                        repo.selectCity(picked);
                        host.toast("已设置城市为 " + picked.name);
                        show();
                    }
                })
                .setNegativeButton("返回", null)
                .create());
    }

    private void showProbeReport() {
        LocalWeatherProbe.Result res = repo.lastProbe();
        String msg = res != null
                ? res.summary()
                : "尚未完成本机探测，请先点击“立即探测并刷新天气”。";
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("weather_probe")
                .setTitle("本机天气探测报告")
                .setMessage(msg)
                .setPositiveButton("重新探测", (d, w) -> {
                    repo.refresh(true);
                    host.toast("已触发重新探测");
                })
                .setNegativeButton("返回", null)
                .create());
    }
}
