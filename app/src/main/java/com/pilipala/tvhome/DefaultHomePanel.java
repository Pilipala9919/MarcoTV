// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.provider.Settings;

/** One entry for capability-based setup; system pickers and accessibility remain available without ADB. */
final class DefaultHomePanel {
    final MainActivity host;
    boolean pending;
    boolean pickerPending;
    boolean pickerCancelled;
    boolean compatibilityPending;
    private boolean returnToHelp;
    private boolean bootResetOffered;
    private boolean compatResetOffered;
    private int pendingEpoch;
    private LocalHomeAuthorizer authorizer;
    private final HomeDeviceGuide.ReturnGate guideReturn = new HomeDeviceGuide.ReturnGate();
    DefaultHomePanel(MainActivity h){
        host=h;
    }
    private String defaultHomeMessage(){
        HomeSetupPolicy.Route route=HomeSetup.route(host);
        HomeSetupPolicy.DesktopState state=SystemIntegration.desktopState(host);
        HomeSetupPolicy.GuardMode mode=SystemIntegration.guardMode(host);
        boolean ready=route==HomeSetupPolicy.Route.NATIVE_READY||route==HomeSetupPolicy.Route.COMPAT_READY;
        boolean nativeActive=!BuildEdition.RESTRICTED&&SystemIntegration.homeIsOurs(host);
        boolean bridgeConnected=TaskBridgeClient.connected();
        boolean bridgeOld=bridgeConnected&&"bridge_update_required".equals(TaskBridgeClient.volumeAssistReason());
        boolean bridgeWriteFailed=bridgeConnected&&TaskBridgeClient.volumeAudioWriteFailed();
        boolean bridgeVolumeVerified=TaskBridgeClient.volumeAssistActive()
                ||(TaskBridgeClient.volumeAudioReady()&&TaskBridgeClient.volumeInputDeviceCount()>0);
        boolean bridgeConnectedUnverified=bridgeConnected&&!bridgeOld&&!bridgeWriteFailed&&!bridgeVolumeVerified;
        boolean allowedUnbound=HomeSetup.prefs(host).getBoolean("home_compat",false)
                &&HomeSetup.serviceEnabled(host)&&!HomeKeyService.connected;
        String hint;
        if(ready){
            if(!nativeActive&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK&&bridgeVolumeVerified){
                hint="任务桥已连接且音量辅助已验证：可改用「快速 Home ＋ 任务桥」直接拦截实体 Home（在支持的固件上可减少切回原厂桌面，是否完全无闪屏以具体固件与实机画面录像为准），并保留首次长按音量连续调节。";
            }else if(!nativeActive&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK&&bridgeConnectedUnverified){
                hint="任务桥已连接，切换快速 Home 后验证音量辅助。窗口回拉模式不预先占用音量键读取；在支持的固件上改用「快速 Home」可减少切回原厂桌面（是否完全无闪屏以实机画面录像为准）。";
            }else if(!nativeActive&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK&&bridgeOld){
                hint="桌面服务已就绪。当前已连接旧版任务桥（最近任务可用，但需重启更新任务桥才支持音量辅助）。";
            }else if(!nativeActive&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK&&bridgeWriteFailed){
                hint="桌面服务已就绪。任务桥已连接，但上次音量调节写入被系统拒绝；保持窗口回拉可继续使用原厂音量手感，或切换「快速 Home」显式重试。";
            }else if(!nativeActive&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK){
                hint="桌面服务已就绪。窗口回拉由系统原生处理音量和方向键（部分固件回桌可能短暂闪过原厂桌面）；若希望直接拦截 Home 并验证首次长按音量连调，可先启动任务桥后改用「快速 Home」。";
            }else{
                hint="桌面服务已就绪，接下来检查 Home 返回和实际重启效果。";
            }
        }
        else if(route==HomeSetupPolicy.Route.PAUSED)hint="自动恢复已暂停；继续后将按本机当前能力重新选择可用入口。";
        else if(allowedUnbound)hint="系统已允许 Home 兼容服务，但服务尚未绑定连接；请重新确认辅助功能或检查使用效果。";
        else if(route==HomeSetupPolicy.Route.SYSTEM_PICKER)hint="本机提供系统默认桌面选择入口；主按钮将直接打开系统选择（无需 ADB）。";
        else if(route==HomeSetupPolicy.Route.ACCESSIBILITY)hint=(BuildEdition.RESTRICTED
                ?"受限兼容版不含系统默认桌面声明；本机开放辅助功能，主按钮直接进入 Home 兼容确认（无需 ADB）。"
                :"本机未检测到可用默认桌面选择页，但开放辅助功能；主按钮直接进入 Home 兼容确认（无需 ADB）。");
        else hint=(BuildEdition.RESTRICTED
                ?"受限兼容版不含系统默认桌面声明，且本机未检测到直接的辅助功能入口；点击主按钮查看探测结果与调试／手动指引。"
                :"本机未检测到默认桌面选择页或直接辅助功能入口；点击主按钮查看本机探测结果，以及普通网络调试和手动指引。");
        return deviceSummary()
                +"\n用户选择  ·  "+state.userChoiceLabel
                +"\n当前生效  ·  "+state.effectiveModeLabel
                +"\n降级原因  ·  "+state.fallbackReason
                +"\n任务桥    ·  "+SystemIntegration.bridgeStatusLabel(host)
                +"\n\n"+hint;
    }
    private String[] defaultHomeItems(){
        HomeSetupPolicy.Route route=HomeSetup.route(host);
        HomeSetupPolicy.DesktopState state=SystemIntegration.desktopState(host);
        HomeSetupPolicy.GuardMode mode=SystemIntegration.guardMode(host);
        boolean ready=route==HomeSetupPolicy.Route.NATIVE_READY||route==HomeSetupPolicy.Route.COMPAT_READY;
        boolean nativeActive=!BuildEdition.RESTRICTED&&SystemIntegration.homeIsOurs(host);
        boolean bridgeConnected=TaskBridgeClient.connected();
        boolean bridgeOld=bridgeConnected&&"bridge_update_required".equals(TaskBridgeClient.volumeAssistReason());
        boolean bridgeWriteFailed=bridgeConnected&&TaskBridgeClient.volumeAudioWriteFailed();
        boolean bridgeVolumeVerified=TaskBridgeClient.volumeAssistActive()
                ||(TaskBridgeClient.volumeAudioReady()&&TaskBridgeClient.volumeInputDeviceCount()>0);
        boolean bridgeConnectedUnverified=bridgeConnected&&!bridgeOld&&!bridgeWriteFailed&&!bridgeVolumeVerified;
        String fastTag=mode==HomeSetupPolicy.GuardMode.KEY_FILTER&&!nativeActive&&!HomeSetup.paused(host)
                ?(!HomeKeyService.connected||!HomeSetup.serviceEnabled(host)?"（等待服务连接）"
                        :(!HomeKeyService.runtimeKeyFilterRequested&&!HomeKeyService.runtimeVolumeYieldActive?"（按键过滤未生效）"
                                :(HomeKeyService.runtimeVolumeYieldFailed?"（无桥让渡受限）"
                                        :(TaskBridgeClient.volumeAssistActive()?"（任务桥辅助已验证） ✓"
                                                :(HomeKeyService.runtimeVolumeYieldActive?"（无桥让渡中） ✓"
                                                        :(bridgeOld?"（旧版任务桥 · 无桥让渡） ✓"
                                                                :(bridgeWriteFailed?"（任务桥调音受限 · 无桥让渡） ✓"
                                                                        :(bridgeConnected?"（任务桥已连接 · 音量辅助未就绪，无桥让渡） ✓":"（无桥让渡） ✓"))))))))
                :(bridgeVolumeVerified?"（任务桥音量辅助已验证 · 推荐）"
                        :(bridgeConnectedUnverified?"（任务桥已连接，切换快速 Home 后验证音量辅助）"
                                :(bridgeOld?"（旧版任务桥：需重启桥启用音量辅助）"
                                        :(bridgeWriteFailed?"（任务桥已连接 · 上次调音写入受限，切换可重试）"
                                                :"（未连桥：无桥需先点一下再长按）"))));
        String windowLabel=bridgeVolumeVerified
                ?"窗口回拉（兼容模式，部分固件可能短暂闪过原厂桌面）"
                :"窗口回拉（兼容模式，无桥推荐）";
        String primary=route==HomeSetupPolicy.Route.PAUSED?"继续一键设置":ready?"检查使用效果":"一键启用 Marco TV";
        String[] base=new String[]{
                primary,
                BuildEdition.RESTRICTED?"系统默认桌面（受限版不适用）":"系统默认桌面"+(nativeActive?" ✓":""),
                windowLabel+(!nativeActive&&!HomeSetup.paused(host)&&mode==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK&&HomeSetup.prefs(host).getBoolean("home_compat",false)&&!state.keyFilterStillActiveUnexpectedly?" ✓":""),
                "快速 Home"+fastTag,
                "原厂按键"+(!nativeActive&&!HomeSetup.paused(host)&&mode==HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY&&!state.keyFilterStillActiveUnexpectedly?" ✓":""),
                "更多设置方式"
        };
        if(!state.keyFilterStillActiveUnexpectedly)return base;
        String[] withRelease=new String[base.length+1];
        System.arraycopy(base,0,withRelease,0,base.length);
        withRelease[base.length]="仅关闭 Marco 辅助服务（释放按键过滤）";
        return withRelease;
    }
    void show(){
        TvDialog dialog=new TvDialog.Builder(host).setPageId("default_home").setTitle("桌面启用与遥控器兼容")
                .setMessage(defaultHomeMessage())
                .setItems(defaultHomeItems(),(d,w)->{
            if(w==0)start();
            else if(w==1)chooseNativeHome();
            else if(w==2)chooseWindowPullback();
            else if(w==3)chooseFastHome();
            else if(w==4)chooseNativeKeysOnly();
            else if(w==5)help();
            else if(w==6){SystemIntegration.releaseStuckKeyFilterService(host);show();}
        }).setNegativeButton("稍后",null).create();
        final Runnable listener=()->{
            if(dialog.isShowing()){
                dialog.updateMessage(defaultHomeMessage());
                dialog.updateItems(defaultHomeItems());
            }
        };
        TaskBridgeClient.addStateListener(listener);
        dialog.onLeave(()->TaskBridgeClient.removeStateListener(listener));
        host.showDialog(dialog);
    }
    private void chooseNativeHome(){
        if(BuildEdition.RESTRICTED){
            host.toast("受限兼容版不含系统默认桌面声明，请使用窗口回拉（兼容模式）或快速 Home");
            show();
            return;
        }
        SystemIntegration.setDesktopChoice(host,HomeSetupPolicy.DesktopChoice.NATIVE_HOME);
        if(SystemIntegration.homeIsOurs(host)){
            if(HomeSetup.paused(host)||!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
            HomeSetup.acceptNative(host);
            verification();
        }else if(HomeSetup.hasPicker(host)){
            request();
        }else{
            localAuthorization();
        }
    }
    private void chooseWindowPullback(){
        SystemIntegration.setGuardMode(host,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        if(HomeSetup.prefs(host).getBoolean("home_compat",false)&&HomeSetup.serviceEnabled(host)&&HomeKeyService.connected&&!HomeSetup.paused(host)){
            show();
        }else{
            compatibility();
        }
    }
    private void chooseFastHome(){
        boolean bridgeConnected=TaskBridgeClient.connected();
        boolean bridgeOld=bridgeConnected&&"bridge_update_required".equals(TaskBridgeClient.volumeAssistReason());
        boolean bridgeWriteFailed=bridgeConnected&&TaskBridgeClient.volumeAudioWriteFailed();
        boolean bridgeVolumeVerified=TaskBridgeClient.volumeAssistActive()
                ||(TaskBridgeClient.volumeAudioReady()&&TaskBridgeClient.volumeInputDeviceCount()>0);
        String guide;
        if(bridgeVolumeVerified){
            guide="任务桥已连接，音量长按辅助已验证就绪：直接拦截实体 Home（在支持的固件上可减少切回原厂桌面；是否完全无闪屏以具体固件与实机画面录像为准），短按音量交由系统处理，第一次长按即可连续调节，松手即停。\n若任务桥断开、写入受限或重启，将自动退回无桥模式或可切回窗口回拉。";
        }else if(bridgeOld){
            guide="已连接旧版任务桥（最近任务正常，但需重启更新任务桥才能启用音量辅助）。\n当前启用「快速 Home」将按无桥模式运行：调节音量请「先点一下音量，松手后再长按」，临时让渡 5 秒后自动恢复。\n直接拦截实体 Home（在支持的固件上可减少切回原厂桌面；是否完全无闪屏以具体固件与实机画面录像为准）。";
        }else if(bridgeWriteFailed){
            guide="任务桥已连接，但上次音量调节写入被系统拒绝（音量辅助未就绪）。\n点击「启用快速 Home」将显式重试一次音量辅助；若本机固件仍拒绝写入，将保持无桥临时让渡模式（先点一下音量，松手后再长按），或改用「窗口回拉（兼容模式）」。";
        }else if(bridgeConnected){
            guide="任务桥已连接，切换快速 Home 后验证音量辅助。\n当前窗口模式未启动音量键读取，启用「快速 Home」后将自动探测音量输入源与音频调节能力；验证通过后可实现首次长按连续调节，松手即停；若本机不支持，将自动退回无桥模式或可切回窗口回拉。\n\n直接拦截实体 Home（在支持的固件上可减少切回原厂桌面；是否完全无闪屏以具体固件与实机画面录像为准）。";
        }else{
            guide="当前未连接任务桥（无桥模式）：\n直接拦截实体 Home（在支持的固件上可减少切回原厂桌面；是否完全无闪屏以具体固件与实机画面录像为准），调节音量请「先点一下音量，松手后再长按」。Marco 会临时让渡 5 秒，计时结束后自动恢复快速 Home。\n\n若希望尝试首次长按即连调，可先启动任务桥并在切换快速 Home 后验证音量辅助；无任务桥时推荐使用「窗口回拉（兼容模式）」（部分固件在音量上限/下限时无法触发无桥让渡）。";
        }
        host.showDialog(new TvDialog.Builder(host).setPageId("home_fast_mode_confirm").setTitle("快速 Home")
                .setMessage(guide)
                .setItems(new String[]{
                        "启用快速 Home",
                        "改用窗口回拉（兼容模式，推荐）",
                        "返回"},(d,w)->{
                    if(w==0){
                        SystemIntegration.setGuardMode(host,HomeSetupPolicy.GuardMode.KEY_FILTER);
                        TaskBridgeClient.retryVolumeAssist(host);
                        if(HomeSetup.prefs(host).getBoolean("home_compat",false)&&HomeSetup.serviceEnabled(host)&&HomeKeyService.connected&&!HomeSetup.paused(host)){
                            show();
                        }else{
                            compatibility();
                        }
                    }else if(w==1){
                        chooseWindowPullback();
                    }else{
                        show();
                    }
                }).setNegativeButton("取消",null).create());
    }
    private void chooseNativeKeysOnly(){
        SystemIntegration.setGuardMode(host,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        host.toast("已切换为原厂按键（保留所有原厂按键手感，重启与唤醒后保持不变）");
        show();
    }
    void start(){
        HomeSetupPolicy.Route current=HomeSetup.route(host);
        HomeSetupPolicy.Route effective=current==HomeSetupPolicy.Route.PAUSED
                ?HomeSetup.capabilityRoute(host):current;
        switch(HomeSetupPolicy.startAction(effective)){
            case VERIFY_NATIVE:
                if(current==HomeSetupPolicy.Route.PAUSED
                        ||!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                HomeSetup.acceptNative(host);verification();break;
            case VERIFY_COMPAT:
                if(current==HomeSetupPolicy.Route.PAUSED
                        ||!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                verification();break;
            case REQUEST_PICKER:request();break;
            case COMPATIBILITY:compatibility();break;
            default:localAuthorization();break;
        }
    }
    void verification(){
        boolean autoBoot=HomeSetup.canIdentifyBootAutomatically(host)||HomeSetup.hasCurrentRoundBootArrival(host);
        String bootBtn=autoBoot?"我已重启，确认开机效果":"记录人工重启观察（系统无启动标识）";
        host.showDialog(new TvDialog.Builder(host).setPageId("home_verification").setTitle("检查使用效果")
                .setMessage(HomeSetup.summary(host)+"\n\n先检查 Home，再在方便时重启电视。开关屏幕不算重启。")
                .setItems(new String[]{"检查 Home 返回",bootBtn,"刷新当前状态"},(d,w)->{
                    if(w==0){HomeSetup.armHomeTest(host);host.exitMenus();host.toast("请打开另一个应用，再按一次 Home；90 秒内返回可记录结果。");}
                    else if(w==1){
                        if(autoBoot){
                            HomeSetup.confirmBoot(host);
                            if(!HomeSetup.prefs(host).contains("setup_boot_confirmed"))host.toast("还没有记录到实际重启返回；方便时重启电视后再来确认。");
                        }else{
                            if(HomeSetup.recordManualBootObservation(host))host.toast("已记录人工重启观察（系统缺少启动标识，未经自动关联）。");
                            else host.toast("当前状态暂不能记录人工重启观察；请先确认桌面设置已生效。");
                        }
                        verification();
                    }
                    else verification();
                }).setNegativeButton("返回",null).create());
    }
    void onPickerResult(int resultCode){
        if(pickerPending||HomeSetup.hasPendingPicker(host)){
            pending=true;
            pickerPending=true;
            pendingEpoch=host.menuEpoch;
            if(resultCode!=Activity.RESULT_OK)pickerCancelled=true;
        }
    }
    void request(){
        if(BuildEdition.RESTRICTED){compatibility();return;}
        cancelPending();
        pendingEpoch=host.menuEpoch;
        // Persist prior alias, prior HOME, and whether Marco was already default before enabling
        // HomeActivity so cancelling never mutates unchanged state while real changes commit cleanly.
        boolean wasOursBefore=SystemIntegration.homeIsOurs(host);
        int priorAlias=SystemIntegration.homeAliasState(host);
        String priorHome=SystemIntegration.currentExplicitOtherHome(host);
        HomeSetup.beginPendingPicker(host,priorAlias,priorHome,wasOursBefore);
        SystemIntegration.enableHome(host,true);
        if(Build.VERSION.SDK_INT>=29)try{
            Object roles=host.getSystemService("role");
            if(roles!=null){
                Class<?> type=Class.forName("android.app.role.RoleManager");
                if((Boolean)type.getMethod("isRoleAvailable",String.class).invoke(roles,"android.app.role.HOME")){
                    Intent request=(Intent)type.getMethod("createRequestRoleIntent",String.class).invoke(roles,"android.app.role.HOME");
                    pending=true;pickerPending=true;pickerCancelled=false;
                    host.startActivityForResult(request,812);
                    return;
                }
            }
        }catch(Exception ignored){
            pending=false;pickerPending=false;
        }
        try{
            Intent intent=new Intent(Settings.ACTION_HOME_SETTINGS);
            ResolveInfo info=host.getPackageManager().resolveActivity(intent,0);
            if(info!=null&&info.activityInfo!=null&&info.activityInfo.exported
                    &&!info.activityInfo.name.endsWith("EmptyStubActivity")){
                pending=true;pickerPending=true;pickerCancelled=false;
                host.startActivity(intent);
                return;
            }
        }catch(RuntimeException ignored){
            pending=false;pickerPending=false;
        }
        HomeSetup.resolvePendingPicker(host);
        pickerNextOptions(false);
    }
    private void pickerNextOptions(boolean cancelled){
        String current=SystemIntegration.currentHome(host);
        boolean canAccessibility=HomeSetup.hasAccessibilityEntry(host);
        String reason=(cancelled?"你取消了系统默认桌面选择。":"系统尚未将默认桌面切换为 Marco TV。")
                +"\n当前系统实际解析的 HOME："+(current.isEmpty()?"未指定或原厂桌面":current)
                +(canAccessibility?"\n\n本机提供辅助功能入口，可直接改用 Home 兼容（无需调试）。"
                        :"\n\n本机未检测到直接辅助功能入口，可查看本机探测结果、普通网络调试或手动指引。");
        String[] items=canAccessibility
                ?new String[]{"改用 Home 兼容（无需调试）","重新打开系统默认桌面选择","更多设置方式"}
                :new String[]{"查看本机调试与手动指引","重新打开系统默认桌面选择","更多设置方式"};
        host.showDialog(new TvDialog.Builder(host).setPageId("home_picker_next").setTitle("默认桌面尚未生效")
                .setMessage(reason)
                .setItems(items,(d,w)->{
                    if(w==0){if(canAccessibility)compatibilityWithWindowPullback();else localAuthorization();}
                    else if(w==1)request();
                    else help();
                }).setNegativeButton("返回",null).create());
    }
    private void afterCompatibilityReturn(){
        boolean enabled=HomeSetup.serviceEnabled(host);
        if(enabled&&HomeKeyService.connected){
            verification();
            return;
        }
        if(enabled){
            host.showDialog(new TvDialog.Builder(host).setPageId("home_compat_unbound").setTitle("Home 服务已允许但尚未连接")
                    .setMessage("系统已允许 Marco TV 辅助功能，但服务目前尚未绑定连接，还不能确认按键接管已就绪。\n\n可在辅助功能中重新开关一次 Marco TV，或先进入检查页查看状态。")
                    .setItems(new String[]{"重新打开辅助功能设置","检查使用效果","更多设置方式"},(d,w)->{
                        if(w==0)openAccessibilitySettings();
                        else if(w==1)verification();
                        else help();
                    }).setNegativeButton("返回",null).create());
            return;
        }
        host.toast("尚未启用 Home 兼容服务");
        show();
    }
    private void openAccessibilitySettings(){
        HomeSetup.beginPendingCompatibility(host);
        try{
            compatibilityPending=true;pending=true;pickerPending=false;pickerCancelled=false;
            returnToHelp=false;pendingEpoch=host.menuEpoch;
            host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }catch(RuntimeException e){
            compatibilityPending=false;pending=false;
            HomeSetup.finishCompatibilityConsent(host);
            localAuthorization();
        }
    }
    void resumed(){
        HomeSetup.resolvePendingPicker(host);
        HomeSetup.finishCompatibilityConsent(host);
        if(!bootResetOffered&&HomeSetup.nativeResetPending(host)&&!host.dialogs.open()){
            bootResetOffered=true;
            host.handler.post(()->{if(host.foreground&&!host.dialogs.open())bootReset();});
            return;
        }
        if(!compatResetOffered&&HomeSetup.compatResetPending(host)&&!host.dialogs.open()){
            compatResetOffered=true;
            host.handler.post(()->{if(host.foreground&&!host.dialogs.open())compatReset();});
            return;
        }
        if(guideReturn.take(host.menuEpoch,host.foreground)){
            host.handler.post(()->{
                if(host.foreground&&host.menuEpoch==pendingEpoch&&host.isPage("home_device_guide"))runAutomatic(false);
            });
            return;
        }
        if(pending){
            boolean wasPicker=pickerPending;
            boolean wasCancelled=pickerCancelled;
            boolean wasCompat=compatibilityPending;
            boolean wasHelp=returnToHelp;
            pending=false;
            pickerPending=false;
            pickerCancelled=false;
            compatibilityPending=false;
            returnToHelp=false;
            HomeSetup.reconcile(host);
            host.handler.post(()->{
                if(host.foreground&&host.menuEpoch==pendingEpoch){
                    if(wasHelp)help();
                    else if(wasPicker){
                        if(SystemIntegration.homeIsOurs(host)){
                            if(HomeSetup.paused(host))show();
                            else verification();
                        }else pickerNextOptions(wasCancelled);
                    }else if(wasCompat){
                        afterCompatibilityReturn();
                    }else show();
                }
            });
        }
    }
    /** Offered once per launch after a boot-time HOME reset; never re-applies HOME by itself. */
    void bootReset(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_boot_reset").setTitle("默认桌面被系统改回")
                .setMessage("这次开机后，电视把默认桌面改回了"+(SystemIntegration.currentHome(host).isEmpty()?"其他桌面":"原厂桌面")
                        +"。部分电视每次开机都会这样做，Marco TV 无法区分是系统重置还是你自己的选择，所以先来问你。\n\n"
                        +"重新设置后，如果电视每次开机都会重置，建议改用 Home 兼容方式。")
                .setItems(new String[]{"重新设为默认桌面","改用 Home 兼容方式","保持当前桌面，不再提醒"},(d,w)->{
                    if(w==2){HomeSetup.pause(host,"你选择保持当前默认桌面。");SystemIntegration.startRecovery(host);host.exitMenus();return;}
                    HomeSetup.clearNativeReset(host);
                    if(w==0)start();else compatibilityWithWindowPullback();
                }).setNegativeButton("稍后",null).create());
    }
    /** Offered once per launch after an abrupt stop cleared HomeKeyService; never re-applies silently. */
    void compatReset(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_compat_reset").setTitle("Home 兼容服务已被系统关闭")
                .setMessage("上次待机或应用被系统停止时，Home 兼容服务被关闭了。Marco TV 无法确认是否来自你自己的操作，所以先来问你。\n\n"
                        +"重新启用只恢复 Marco TV 自身的 Home 服务，不会改动其他辅助服务。")
                .setItems(new String[]{"重新启用 Home 兼容","暂停自动恢复，不再提醒"},(d,w)->{
                    if(w==1){
                        HomeSetup.pause(host,"你选择暂停 Home 兼容恢复。");
                        SystemIntegration.startRecovery(host);
                        host.exitMenus();
                        return;
                    }
                    if(SystemIntegration.authorized(host)){
                        boolean ok=SystemIntegration.restoreCompatOnUserConfirm(host);
                        host.exitMenus();
                        host.toast(ok?"已重新启用 Home 兼容服务":"系统未允许恢复，请从桌面启用重新检查");
                    }else{
                        HomeSetup.clearCompatReset(host);
                        compatibility();
                    }
                }).setNegativeButton("稍后",null).create());
    }
    private void compatibilityWithWindowPullback(){
        SystemIntegration.setGuardModePreservingChoice(host,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        compatibility();
    }
    void compatibility(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_compat_consent").setTitle("启用 Home 兼容")
                .setMessage((BuildEdition.RESTRICTED?"受限兼容版不含系统默认桌面声明，将通过辅助功能提供 Home 兼容。":"用于系统没有开放默认桌面选择的电视。此方式不等于系统默认桌面，开机和唤醒需要实测。")
                        +"\n无需启用 ADB；只管理 Marco TV 自身服务，保留其他辅助服务。")
                .setPositiveButton("继续",(d,w)->{
                    if(SystemIntegration.authorized(host)){
                        HomeSetup.requestCompatibility(host);
                        SystemIntegration.recover(host);
                        HomeSetup.finishCompatibilityConsent(host);
                        SystemIntegration.startRecovery(host);
                        afterCompatibilityReturn();
                    }else if(HomeSetup.serviceEnabled(host)&&HomeKeyService.connected){
                        HomeSetup.beginPendingCompatibility(host);
                        HomeSetup.finishCompatibilityConsent(host);
                        SystemIntegration.startRecovery(host);
                        verification();
                    }else openAccessibilitySettings();
                }).setNegativeButton("返回",null).create());
    }
    private String probeSummary(){
        String picker=BuildEdition.RESTRICTED?"受限兼容版不含系统默认桌面声明"
                :HomeSetup.hasPicker(host)?"系统提供默认桌面选择入口":"未检测到可用默认桌面选择页";
        String acc=HomeSetup.hasAccessibilityEntry(host)?"系统提供辅助功能入口":"未检测到直接辅助功能入口";
        return "本机探测  ·  "+picker+"；"+acc;
    }
    void localAuthorization(){
        host.showDialog(new TvDialog.Builder(host).stackedButtons().setPageId("home_local_consent").setTitle("一键启用 Marco TV")
                .setMessage(HomeDeviceGuide.family(Build.MANUFACTURER,Build.BRAND)+" · "+Build.MODEL+"\n"+probeSummary()+"\n\n"+(BuildEdition.RESTRICTED
                        ?"本版将通过本机普通网络调试（5555 端口）自动配置 Home 兼容，不申请系统默认桌面身份。"
                        :"优先尝试本机普通网络调试（5555 端口）配置默认桌面，电视不支持时再尝试 Home 兼容。")
                        +"\n在系统弹窗选择允许即可。连接失败不等于电视只支持配对码；也可先查看手动指引。")
                .setPositiveButton("开始自动设置",(d,w)->runAutomatic(false))
                .setNeutralButton("查看本机操作指引",(d,w)->deviceGuide(HomeDeviceGuide.Issue.NONE))
                .setNegativeButton("返回",null).create());
    }
    String deviceSummary(){
        return "识别电视  ·  "+HomeDeviceGuide.family(Build.MANUFACTURER,Build.BRAND)+" / "+Build.MODEL
                +"\n系统  ·  Android "+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"） / "+Build.DISPLAY;
    }
    void deviceGuide(HomeDeviceGuide.Issue issue){
        HomeDeviceGuide guide=HomeDeviceGuide.find(Build.MANUFACTURER,Build.BRAND);
        TvDialog dialog=new TvDialog.Builder(host).setPageId("home_device_guide").setTitle("本机设置指引")
                .setMessage((issue==HomeDeviceGuide.Issue.NONE?probeSummary()+"\n\n":HomeDeviceGuide.explanation(issue)+"\n\n")+guide.steps
                        +"\n\nUSB 调试不等于网络调试；若电视仅提供无线配对码，安装助手 1.0.1 暂不支持配对，请改用「使用 Home 兼容方式」（手动辅助功能）或在电脑使用官方 Android Platform Tools（adb pair / adb connect）按完整说明操作。")
                .setItems(new String[]{"打开调试设置，返回后自动继续","已准备好，继续自动设置","使用 Home 兼容方式","用手机查看向导","查看资料来源"},(d,w)->{
                    if(w==0)openGuideSettings();
                    else if(w==1)runAutomatic(false);
                    else if(w==2)compatibilityWithWindowPullback();
                    else if(w==3)assistant();
                    else host.showDialog(new TvDialog.Builder(host).setPageId("home_guide_source").setTitle("指引来源")
                            .setMessage(deviceSummary()+"\n\n"+guide.evidence+"\n核对日期：2026-11-03\n"+guide.source
                                    +"\n\n品牌资料用于帮助查找入口；自动配置仍按本机实际能力检查。")
                            .setNegativeButton("返回",null).create());
                }).setNegativeButton("返回",null).create();
        dialog.onLeave(guideReturn::cancel);
        host.showDialog(dialog);
    }
    private void openGuideSettings(){
        String[] actions={Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,Settings.ACTION_SETTINGS};
        for(String action:actions)try{
            Intent intent=new Intent(action);
            ResolveInfo info=host.getPackageManager().resolveActivity(intent,0);
            if(info==null||info.activityInfo==null||!info.activityInfo.exported
                    ||info.activityInfo.name.endsWith("EmptyStubActivity"))continue;
            pendingEpoch=host.menuEpoch;
            guideReturn.arm(pendingEpoch);
            host.startActivity(intent);
            return;
        }catch(RuntimeException ignored){guideReturn.cancel();}
        host.toast("电视没有开放直接入口，请按本页指引进入原厂设置，返回后选择继续。");
    }
    private void runAutomatic(boolean restore){
        TvDialog progress=new TvDialog.Builder(host).setPageId("home_local_authorize")
                .setTitle(restore?"正在恢复桌面设置":"正在自动设置")
                .setMessage("正在检查本机授权与系统能力。出现调试授权弹窗时，请用遥控器选择允许。\n最多等待 90 秒，可以返回取消；已完成的步骤可能保留。")
                .setNegativeButton("取消",null).create();
        destroy();authorizer=new LocalHomeAuthorizer(host);
        final LocalHomeAuthorizer active=authorizer;
        progress.onLeave(active::cancel);
        host.showDialog(progress);
        LocalHomeAuthorizer.Result callback=result->{
            if(authorizer!=active||!host.foreground||!host.isPage("home_local_authorize"))return;
            HomeSetupPolicy.Route route=HomeSetup.route(host);
            boolean restoreIncomplete=restore&&HomeSetup.prefs(host).getBoolean(HomeSetupSnapshot.PREFIX+"saved",false);
            HomeDeviceGuide.Issue issue=active.issue()!=HomeDeviceGuide.Issue.NONE?active.issue():
                    restoreIncomplete?HomeDeviceGuide.Issue.SYSTEM:
                    !restore&&route!=HomeSetupPolicy.Route.NATIVE_READY&&route!=HomeSetupPolicy.Route.COMPAT_READY
                            ?HomeDeviceGuide.Issue.SYSTEM:HomeDeviceGuide.Issue.NONE;
            host.dialogs.replace(progress,new TvDialog.Builder(host).setPageId("home_local_result").setTitle(restore?"恢复结果":"自动设置结果")
                    .setMessage(restore?result:issue==HomeDeviceGuide.Issue.NONE?HomeSetup.summary(host):HomeDeviceGuide.explanation(issue))
                    .setItems(new String[]{issue==HomeDeviceGuide.Issue.NONE?"检查使用效果":restore?"重试恢复":"按本机指引继续设置","其他设置方式","恢复设置前的桌面","查看执行详情"},(d,w)->{
                        if(w==0){if(issue==HomeDeviceGuide.Issue.NONE)verification();else if(restore)restore();else deviceGuide(issue);}
                        else if(w==1)help();else if(w==2)restore();
                        else host.showDialog(new TvDialog.Builder(host).setPageId("home_execution_detail").setTitle("执行详情")
                                .setMessage(result).setNegativeButton("返回",null).create());
                    }).setNegativeButton("返回",null).create());
        };
        if(restore)active.restore(callback);else active.start(callback);
    }
    void restore(){
        if(!HomeSetup.prefs(host).getBoolean(HomeSetupSnapshot.PREFIX+"saved",false)){
            host.showDialog(new TvDialog.Builder(host).setPageId("home_restore_manual").setTitle("选择恢复方式")
                    .setMessage("没有可靠的设置前快照，不能自动推测原配置。可暂停 Marco 接管，再在电视系统中选择桌面。壁纸、屏保和其他应用的辅助服务保持不变。")
                    .setItems(new String[]{"暂停 Marco 自动恢复","打开系统桌面选择"},(d,w)->{
                        if(w==0)pause();
                        else try{host.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));}
                        catch(RuntimeException e){host.toast("电视未提供桌面选择入口，请从原厂系统设置中选择。");}
                    }).setNegativeButton("返回",null).create());
            return;
        }
        host.showDialog(new TvDialog.Builder(host).setPageId("home_auto_restore").setTitle("恢复设置前的桌面？")
                .setMessage("尝试切回一键设置前记录的桌面，并撤回本次启用的 Home 兼容设置。保留壁纸、屏保、其他辅助服务、调试授权与系统设置权限。\n电视仍可能要求确认调试授权；没有可靠记录时会提示手动选择。")
                .setPositiveButton("恢复",(d,w)->runAutomatic(true)).setNegativeButton("取消",null).create());
    }
    void cancelPending(){
        boolean wasCompat=compatibilityPending;
        boolean wasPicker=pickerPending||HomeSetup.hasPendingPicker(host);
        pending=false;
        pickerPending=false;
        pickerCancelled=false;
        compatibilityPending=false;
        returnToHelp=false;
        guideReturn.cancel();
        if(wasPicker)HomeSetup.resolvePendingPicker(host);
        if(wasCompat)HomeSetup.finishCompatibilityConsent(host);
    }
    void destroy(){
        // Activity destruction may happen while the system picker or Accessibility Settings is
        // still open on top; preserve persisted pending transactions for the recreated instance.
        pending=false;
        pickerPending=false;
        pickerCancelled=false;
        compatibilityPending=false;
        returnToHelp=false;
        guideReturn.cancel();
        if(authorizer!=null){authorizer.cancel();authorizer=null;}
    }
    void help(){
        boolean hasDisabledOem=!BuildEdition.RESTRICTED&&SystemIntegration.hasDisabledOemExperiment(host);
        String[] items=BuildEdition.RESTRICTED
                ?new String[]{"打开电视系统设置","用手机查看设置向导","启用 Home 兼容","一键自动设置","暂停自动恢复","本机调试操作指引","恢复设置前的桌面"}
                :hasDisabledOem
                        ?new String[]{"打开电视系统设置","用手机查看设置向导","启用 Home 兼容","一键自动设置","暂停自动恢复","系统默认桌面选择（无需调试）","本机调试操作指引","恢复设置前的桌面","停用原厂桌面（高级实验）","恢复原厂桌面"}
                        :new String[]{"打开电视系统设置","用手机查看设置向导","启用 Home 兼容","一键自动设置","暂停自动恢复","系统默认桌面选择（无需调试）","本机调试操作指引","恢复设置前的桌面","停用原厂桌面（高级实验）"};
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home_help").setTitle("更多设置方式")
                .setMessage((BuildEdition.RESTRICTED?"受限兼容版不含系统默认桌面声明，请使用 Home 兼容；开机和唤醒效果仍需在电视上检查。":"不想使用调试时，可直接打开系统默认桌面选择，或在辅助功能中启用 Home 兼容。")
                        +"\n本机自动设置支持普通网络调试（5555 端口），不支持无线配对码；仅提供配对码时请改用手动辅助功能或电脑 Android Platform Tools（安装助手 1.0.1 仅用于普通网络 ADB 安装和打开应用）。")
                .setItems(items,(d,w)->{
            if(w==0)try{
                cancelPending();
                pending=true;returnToHelp=true;pendingEpoch=host.menuEpoch;
                host.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }catch(RuntimeException ex){
                pending=false;returnToHelp=false;
                host.toast("电视未提供该入口");
            }else if(w==1)assistant();
            else if(w==2)compatibility();
            else if(w==3)localAuthorization();
            else if(w==4)pause();
            else if(!BuildEdition.RESTRICTED&&w==5)request();
            else if((BuildEdition.RESTRICTED&&w==5)||(!BuildEdition.RESTRICTED&&w==6))deviceGuide(HomeDeviceGuide.Issue.NONE);
            else if((BuildEdition.RESTRICTED&&w==6)||(!BuildEdition.RESTRICTED&&w==7))restore();
            else if(!BuildEdition.RESTRICTED&&w==8)oemExperiment();
            else if(!BuildEdition.RESTRICTED&&w==9)runOemExperiment(true);
        }).setNegativeButton("返回",null).create());
    }
    void oemExperiment(){
        if(BuildEdition.RESTRICTED){
            host.toast("受限兼容版不支持停用原厂桌面实验");
            return;
        }
        SystemIntegration.OemExperimentSnapshot snap=SystemIntegration.readValidOemExperimentSnapshot(host,SystemIntegration.currentUserId());
        boolean hasSnapshot=snap!=null;
        String pkg=hasSnapshot?snap.oemPackage:SystemIntegration.oemExperimentCandidatePackage(host);
        OemLauncherExperimentPolicy.Decision decision=OemLauncherExperimentPolicy.evaluate(
                false,Build.MODEL,Build.DISPLAY,pkg);
        String msg="【高级实验 · 仅 Standard 版 · 不参与自动降级】\n"
                +"设备："+Build.MODEL+" / "+Build.DISPLAY
                +"\n识别原厂包名："+(pkg.isEmpty()?"未识别":pkg)
                +(hasSnapshot?"\n已保存恢复快照：用户 "+snap.userId+" / 原 HOME："+snap.priorHome+" / 原状态="+snap.priorState:"")
                +"\n白名单核验："+decision.reason
                +"\n\n风险提示：部分电视停用原厂桌面后可能出现 FallbackHome、无法进入桌面甚至持续黑屏，本功能无法保证不出现黑屏。操作前会记录原用户、原 HOME 与启用状态并在回读异常时尝试自动回滚，但仍需保持独立恢复途径（如 ADB）。";
        String[] items=hasSnapshot
                ?new String[]{"恢复原厂桌面","返回"}
                :decision.allowed()
                        ?new String[]{"我已了解风险，继续停用原厂桌面","改用窗口回拉（兼容模式）","返回"}
                        :new String[]{"改用窗口回拉（兼容模式）","返回"};
        host.showDialog(new TvDialog.Builder(host).setPageId("home_oem_experiment").setTitle("停用原厂桌面（高级实验）")
                .setMessage(msg)
                .setItems(items,(d,w)->{
                    if(hasSnapshot){
                        if(w==0)runOemExperiment(true);
                        else help();
                    }else if(decision.allowed()){
                        if(w==0)confirmOemExperimentDisable(pkg);
                        else if(w==1)chooseWindowPullback();
                        else help();
                    }else{
                        if(w==0)chooseWindowPullback();
                        else help();
                    }
                }).setNegativeButton("返回",null).create());
    }
    private void confirmOemExperimentDisable(String pkg){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_oem_experiment_confirm").setTitle("再次确认停用原厂桌面？")
                .setMessage("即将通过本机 ADB 对原厂桌面包 "+pkg+" 执行停用，并在停用后立即回读系统 HOME 解析和前台可启动性；若落回 FallbackHome 或解析异常将自动尝试恢复。\n\n此实验无法保证所有固件不发生黑屏，请确认当前具备独立 ADB 恢复能力后再继续。")
                .setPositiveButton("确认执行",(d,w)->runOemExperiment(false))
                .setNegativeButton("取消",null).create());
    }
    private void runOemExperiment(boolean restoreOem){
        TvDialog progress=new TvDialog.Builder(host).setPageId("home_oem_experiment_running")
                .setTitle(restoreOem?"正在恢复原厂桌面":"正在执行停用原厂桌面实验")
                .setMessage("正在连接本机 ADB 并核验系统状态，请稍候…")
                .setNegativeButton("取消",null).create();
        destroy();authorizer=new LocalHomeAuthorizer(host);
        final LocalHomeAuthorizer active=authorizer;
        progress.onLeave(active::cancel);
        host.showDialog(progress);
        LocalHomeAuthorizer.Result cb=result->{
            if(authorizer!=active||!host.foreground||!host.isPage("home_oem_experiment_running"))return;
            boolean canRetryRestore=SystemIntegration.hasValidOemExperimentSnapshot(host);
            TvDialog.Builder builder=new TvDialog.Builder(host).setPageId("home_oem_experiment_result")
                    .setTitle(restoreOem?"恢复原厂桌面结果":"停用原厂桌面实验结果")
                    .setMessage(result);
            if(canRetryRestore){
                builder.setPositiveButton("重试恢复原厂桌面",(d,w)->runOemExperiment(true))
                        .setNegativeButton("返回",(d,w)->show());
            }else{
                builder.setPositiveButton("返回",(d,w)->show());
            }
            host.dialogs.replace(progress,builder.create());
        };
        if(restoreOem)active.restoreOemExperiment(cb);
        else active.disableOemExperiment(cb);
    }
    void assistant(){
        android.widget.LinearLayout box=new android.widget.LinearLayout(host);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int gap=Math.round(20*host.getResources().getDisplayMetrics().density);
        box.setPadding(0,gap/2,0,gap/2);
        final io.nayuki.qrcodegen.QrCode qr=io.nayuki.qrcodegen.QrCode.encodeText("https://marcotv.wlj9919.top/install/#home",io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        android.view.View code=new android.view.View(host){
            protected void onDraw(android.graphics.Canvas canvas){
                android.graphics.Paint p=new android.graphics.Paint();
                float side=Math.min(getWidth(),getHeight()),unit=side/(qr.size+8),left=(getWidth()-side)/2,top=(getHeight()-side)/2;
                p.setColor(android.graphics.Color.WHITE);canvas.drawRect(left,top,left+side,top+side,p);
                p.setColor(android.graphics.Color.BLACK);
                for(int y=0;y<qr.size;y++)for(int x=0;x<qr.size;x++)if(qr.getModule(x,y))canvas.drawRect(left+(x+4)*unit,top+(y+4)*unit,left+(x+5)*unit,top+(y+5)*unit,p);
            }
        };
        code.setContentDescription("手机扫描打开桌面设置向导");
        box.addView(code,new android.widget.LinearLayout.LayoutParams(gap*8,gap*8));
        android.widget.TextView guide=new android.widget.TextView(host);
        guide.setTextColor(android.graphics.Color.WHITE);guide.setTextSize(16);guide.setPadding(gap,0,0,0);
        guide.setText("手机相机扫一扫\n选择「已经安装，设置默认桌面」\n\n也可在浏览器输入\nmarcotv.wlj9919.top/install/#home\n\n网页提供步骤，系统确认需在电视上完成。");
        box.addView(guide,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        host.showDialog(new TvDialog.Builder(host).centered().setPageId("home_assistant").setTitle("用手机查看设置向导")
                .setView(box)
                .setNegativeButton("返回",null).create());
    }
    void pause(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_pause_consent").setTitle("暂停自动恢复？")
                .setMessage("将暂停 Home 兼容与开机恢复。系统已经选择的默认桌面仍会保留；若想换回原桌面，请在系统设置中另行选择。")
                .setPositiveButton("暂停恢复",(d,w)->{HomeSetup.pause(host,"你已暂停自动恢复。");SystemIntegration.startRecovery(host);show();})
                .setNegativeButton("返回",null).create());
    }
}
