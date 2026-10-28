// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.SharedPreferences;
import java.util.*;
/** Drawing-only overlay. A phone preview never writes preferences or backup data. */
final class PreviewPreferences implements SharedPreferences {
    private final SharedPreferences base;final Map<String,Integer> values=new HashMap<>();
    PreviewPreferences(SharedPreferences p){base=p;}
    public Map<String,?> getAll(){Map<String,Object> m=new HashMap<>(base.getAll());m.putAll(values);return m;}
    public int getInt(String k,int d){return values.containsKey(k)?values.get(k):base.getInt(k,d);}
    public String getString(String k,String d){return base.getString(k,d);}
    public Set<String> getStringSet(String k,Set<String> d){return base.getStringSet(k,d);}
    public long getLong(String k,long d){return base.getLong(k,d);}
    public float getFloat(String k,float d){return base.getFloat(k,d);}
    public boolean getBoolean(String k,boolean d){return base.getBoolean(k,d);}
    public boolean contains(String k){return values.containsKey(k)||base.contains(k);}
    public Editor edit(){throw new UnsupportedOperationException("preview is read only");}
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){base.registerOnSharedPreferenceChangeListener(l);}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){base.unregisterOnSharedPreferenceChangeListener(l);}
}
