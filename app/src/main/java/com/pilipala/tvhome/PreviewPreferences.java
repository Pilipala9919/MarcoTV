// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.SharedPreferences;
import java.util.*;
/** Read-only overlay; only an explicit editor transaction persists a local visual draft. */
final class PreviewPreferences implements SharedPreferences {
    private final SharedPreferences base;
    final Map<String,Integer> values=new HashMap<>();
    final Map<String,String> strings=new HashMap<>();
    final Map<String,Boolean> booleans=new HashMap<>();
    PreviewPreferences(SharedPreferences p){
        base=p;
    }
    public Map<String,?> getAll(){
        Map<String,Object> m=new HashMap<>(base.getAll());
        m.putAll(values);
        m.putAll(strings);
        m.putAll(booleans);
        return m;
    }
    public int getInt(String k,int d){
        return values.containsKey(k)?values.get(k):base.getInt(k,d);
    }
    public String getString(String k,String d){
        return strings.containsKey(k)?strings.get(k):base.getString(k,d);
    }
    public Set<String> getStringSet(String k,Set<String> d){
        return base.getStringSet(k,d);
    }
    public long getLong(String k,long d){
        return base.getLong(k,d);
    }
    public float getFloat(String k,float d){
        return base.getFloat(k,d);
    }
    public boolean getBoolean(String k,boolean d){
        return booleans.containsKey(k)?booleans.get(k):base.getBoolean(k,d);
    }
    public boolean contains(String k){
        return values.containsKey(k)||strings.containsKey(k)||booleans.containsKey(k)||base.contains(k);
    }
    /** Commit only edited keys, leaving unrelated concurrent settings untouched. */
    void writeTo(Editor editor){
        for(Map.Entry<String,Integer> value:values.entrySet())editor.putInt(value.getKey(),value.getValue());
        for(Map.Entry<String,String> value:strings.entrySet())editor.putString(value.getKey(),value.getValue());
        for(Map.Entry<String,Boolean> value:booleans.entrySet())editor.putBoolean(value.getKey(),value.getValue());
    }
    public Editor edit(){
        throw new UnsupportedOperationException("preview is read only");
    }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){
        base.registerOnSharedPreferenceChangeListener(l);
    }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){
        base.unregisterOnSharedPreferenceChangeListener(l);
    }
}
