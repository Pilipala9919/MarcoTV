// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.ArrayList;

/** The menu page stack: the visible TvDialog plus the parent pages Back returns to. */
final class DialogStack {
    private final MainActivity host;
    private TvDialog activeDialog;
    private final ArrayList<TvDialog> dialogHistory = new ArrayList<>();
    private boolean dialogOpen;

    DialogStack(MainActivity host) {
        this.host = host;
    }

    boolean open() {
        return dialogOpen;
    }

    TvDialog active() {
        return activeDialog;
    }

    void show(TvDialog dialog) {
        if(host.isDestroyed()||host.isFinishing())return;
        if(activeDialog!=null){
            activeDialog.setOnDismissListener(null);
            activeDialog.dismiss();
        }
        // Explicit callbacks which refresh an existing page replace it rather than
        // adding a duplicate ancestor. Keep parent views so focus/scroll survive Back.
        for(int i=0;i<dialogHistory.size();i++)if(dialogHistory.get(i).pageKey().equals(dialog.pageKey())){
            while(dialogHistory.size()>i)dialogHistory.remove(dialogHistory.size()-1).leave();
            break;
        }
        if(dialogHistory.isEmpty()&&!dialog.pageKey().equals("main")&&(dialog.reorderable()||dialog.pageKey()
                .equals("网络与 Wi-Fi")||dialog.pageKey().equals("壁纸图库")))dialogHistory.add(host.navigator.settingsDialog());
        dialog.returning(false);
        dialogHistory.add(dialog);
        presentDialog(dialog);
    }

    boolean isPage(String id){
        return activeDialog!=null&&activeDialog.isShowing()&&activeDialog.pageKey().equals(id);
    }

    /** Shows the current page again after the activity returns to the foreground. */
    void resume(){
        if(activeDialog!=null&&!activeDialog.isShowing())presentDialog(activeDialog);
    }

    private void presentDialog(TvDialog dialog){
        activeDialog=dialog;
        dialogOpen=true;
        host.cancelIdle();
        host.home.preview(dialog.previewKey());
        dialog.setBackAction(this::menuBack);
        dialog.setOnDismissListener(d->host.handler.postDelayed(()->{
            // Item callbacks may open a child, refresh a page, or launch a picker.
            if(activeDialog==dialog&&!dialog.isShowing()&&host.foreground&&!host.isFinishing()){
                if(dialog.isForwardDismiss())presentDialog(dialog);
                else menuBack();
            }
        },100));
        dialog.show();
    }

    private void menuBack(){
        if(dialogHistory.isEmpty())return;
        if(dialogHistory.size()==1){
            host.closeMenus();
            host.home.requestFocus();
            host.armIdle();
            return;
        }
        if(activeDialog!=null){
            activeDialog.setOnDismissListener(null);
            activeDialog.dismiss();
        }
        dialogHistory.remove(dialogHistory.size()-1).leave();
        TvDialog parent=dialogHistory.get(dialogHistory.size()-1);
        parent.returning(true);
        presentDialog(parent);
    }

    /** Dismisses every page; the caller handles the surrounding menu state. */
    void close(){
        TvDialog previous=activeDialog;
        activeDialog=null;
        for(TvDialog page:dialogHistory)page.leave();
        dialogHistory.clear();
        dialogOpen=false;
        if(previous!=null){
            previous.setOnDismissListener(null);
            previous.dismiss();
        }
    }
}
