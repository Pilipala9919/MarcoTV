// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.*;
import java.util.function.BiConsumer;
import org.json.JSONObject;

/** No network, no install, and no user settings changed. Uses the supplied candidate APK. */
final class UpdateDeviceChecks {
    static JSONObject metadata(PackageInfo info,File file)throws Exception{return new JSONObject().put("schema",1)
        .put("versionCode",info.versionCode).put("versionName",info.versionName).put("minSdk",23).put("size",file.length())
        .put("sha256",UpdateManager.sha(file)).put("url","https://github.com/Pilipala9919/MarcoTV/releases/download/test/test.apk")
        .put("prerelease",true).put("notes","test fixture");}
    static boolean rejected(Context c,File file,JSONObject json){try{UpdateManager.verifyApk(c,file,new UpdateManager.Release(json,"preview"));return false;}catch(Exception e){return true;}}
    static void run(Context c,String candidate,String wrongSigner,BiConsumer<String,Boolean> check)throws Exception{
        File apk=new File(candidate);PackageInfo info=c.getPackageManager().getPackageArchiveInfo(candidate,PackageManager.GET_SIGNATURES);
        if(info==null)throw new AssertionError("candidate APK unavailable");JSONObject data=metadata(info,apk);
        UpdateManager.verifyApk(c,apk,new UpdateManager.Release(data,"preview"));check.accept("newer_same_signed_apk_accepted",true);
        File untrusted=new File(wrongSigner);PackageInfo bad=c.getPackageManager().getPackageArchiveInfo(wrongSigner,PackageManager.GET_SIGNATURES);
        if(bad==null||!bad.packageName.equals(info.packageName)||bad.versionCode!=info.versionCode)throw new AssertionError("wrong-signer fixture identity");
        check.accept("different_signer_rejected",rejected(c,untrusted,metadata(bad,untrusted)));
        JSONObject changed=new JSONObject(data.toString()).put("size",apk.length()+1);check.accept("wrong_download_size_rejected",rejected(c,apk,changed));
        changed=new JSONObject(data.toString()).put("sha256",new String(new char[64]).replace('\0','0'));check.accept("wrong_digest_rejected",rejected(c,apk,changed));
        changed=new JSONObject(data.toString()).put("versionCode",info.versionCode+1);check.accept("metadata_code_mismatch_rejected",rejected(c,apk,changed));
        changed=new JSONObject(data.toString()).put("versionName","9.9.9");check.accept("metadata_name_mismatch_rejected",rejected(c,apk,changed));
        File installed=new File(c.getApplicationInfo().sourceDir);PackageInfo current=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);
        check.accept("installed_version_rejected_as_update",rejected(c,installed,metadata(current,installed)));
        ProviderInfo provider=c.getPackageManager().resolveContentProvider(UpdateApkProvider.AUTHORITY,0);
        check.accept("update_provider_not_exported",provider!=null&&!provider.exported&&provider.grantUriPermissions);
        boolean blocked=false;try{c.getContentResolver().openFileDescriptor(Uri.parse("content://"+UpdateApkProvider.AUTHORITY+"/../shared_prefs/home.xml"),"r");}catch(Exception e){blocked=true;}
        check.accept("provider_rejects_other_paths",blocked);
        blocked=false;try{c.getContentResolver().openFileDescriptor(UpdateApkProvider.URI,"w");}catch(Exception e){blocked=true;}
        check.accept("provider_rejects_write_access",blocked);
        check.accept("enabled_override_respected",AppEntries.enabled(false,PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
        check.accept("disabled_override_respected",!AppEntries.enabled(true,PackageManager.COMPONENT_ENABLED_STATE_DISABLED));
        check.accept("default_disabled_stays_disabled",!AppEntries.enabled(false,PackageManager.COMPONENT_ENABLED_STATE_DEFAULT));
        AppEntries.Scan scan=AppEntries.scan(c,"com.pilipala.tvhome.regression");
        check.accept("tv_launcher_alias_discovered",scan.primary.containsKey(new ComponentName("com.pilipala.tvhome.regression","com.pilipala.tvhome.regression.FixtureAlias").flattenToString()));
        check.accept("private_entry_not_exposed",!scan.all.containsKey(new ComponentName("com.pilipala.tvhome.regression","com.pilipala.tvhome.regression.PrivateFixture").flattenToString()));
        check.accept("permission_guarded_entry_not_exposed",!scan.all.containsKey(new ComponentName("com.pilipala.tvhome.regression","com.pilipala.tvhome.regression.ProtectedFixture").flattenToString()));
        check.accept("disabled_entry_not_exposed",!scan.all.containsKey(new ComponentName("com.pilipala.tvhome.regression","com.pilipala.tvhome.regression.DisabledFixture").flattenToString()));
    }
}
