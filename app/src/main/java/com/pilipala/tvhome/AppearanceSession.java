// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.net.Uri;
import java.io.File;
import java.util.ArrayList;

/** One transaction for Magic Desktop and its children. Only owns newly imported files. */
final class AppearanceSession {
    final PreviewPreferences draft;
    private final MainActivity host;
    private final ArrayList<File> imported=new ArrayList<>();
    boolean open=true;
    int pendingImports;
    long wallpaperRevision;
    Runnable changed;

    AppearanceSession(MainActivity host){
        this.host=host;
        draft=new PreviewPreferences(host.preferences);
    }

    void wallpaper(String uri,int builtin,boolean resetDim){
        if(!open)return;
        wallpaperRevision++;
        draft.strings.put("wallpaper",uri);
        draft.values.put("wallpaper_builtin",builtin);
        if(resetDim)draft.values.put("wallpaper_dim",10);
        if(changed!=null)changed.run();
        host.loadWallpaper();
    }

    boolean imported(File file,long revision){
        if(!open||host.appearanceSession!=this||wallpaperRevision!=revision){file.delete();return false;}
        imported.add(file);
        wallpaper(Uri.fromFile(file).toString(),draft.getInt("wallpaper_builtin",0),false);
        return true;
    }

    boolean save(){
        if(!open||pendingImports>0)return false;
        String selected=draft.getString("wallpaper","");
        for(File file:imported)if(Uri.fromFile(file).toString().equals(selected)){
            try{draft.strings.put("wallpaper",Uri.fromFile(WallpaperFile.publish(file,host.getFilesDir())).toString());}
            catch(java.io.IOException error){return false;}
            break;
        }
        // One editor is used below so observers never see a half-applied visual configuration.
        android.content.SharedPreferences.Editor editor=host.preferences.edit();
        draft.writeTo(editor);
        editor.apply();
        finish(true);
        if(draft.booleans.containsKey("ambient_enabled")){
            AmbientIntegration.configure(host);
            host.screensaver.resume();
        }
        return true;
    }

    void finish(boolean saved){
        if(!open)return;
        open=false;
        String selected=saved?draft.getString("wallpaper",""):"";
        for(File file:imported)if(!Uri.fromFile(file).toString().equals(selected))file.delete();
        imported.clear();
        changed=null;
        if(host.appearanceSession==this)host.appearanceSession=null;
        if(host.localPreview==draft)host.localPreview=null;
        if(!host.isDestroyed()&&!host.isFinishing()){
            if(draft.strings.containsKey("wallpaper"))host.loadWallpaper();
            host.home.refreshAppearance();
            host.home.invalidate();
        }
    }
}
