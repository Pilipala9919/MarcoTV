// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;

/** Reorders one home shelf with the remote: left/right moves the card, OK saves, Back restores. */
final class CardMover {
    private final MainActivity host;
    private final HomeView view;
    Row movingRow;
    private ArrayList<Tile> moveOriginal;
    private int moveOriginalIndex;
    private boolean swallowMoveKey;

    CardMover(MainActivity host, HomeView view) {
        this.host = host;
        this.view = view;
    }

    boolean active(){
        return movingRow!=null;
    }

    void begin(){
        if(view.row<0||view.rows.isEmpty()||view.rows.get(view.row).tiles.isEmpty())return;
        movingRow=view.rows.get(view.row);
        moveOriginal=new ArrayList<>(movingRow.tiles);
        moveOriginalIndex=movingRow.selected;
        view.describeFocus();
        host.toast("左右移动 · 确定放下 · 返回取消");
        view.invalidate();
    }

    void finish(boolean save){
        if(movingRow==null)return;
        if(save){
            StringBuilder order=new StringBuilder();
            for(Tile t:movingRow.tiles){
                if(order.length()>0)order.append('|');
                order.append(t.id);
            }
            android.content.SharedPreferences.Editor edit=host.preferences.edit().putString(movingRow.orderKey,order.toString());
            if(movingRow.orderKey.equals("order_tv"))edit.putString("tv_sort","custom");
            edit.apply();
        }else{
            movingRow.tiles=moveOriginal;
            movingRow.selected=moveOriginalIndex;
        }
        movingRow=null;
        moveOriginal=null;
        view.ensureVisible();
        view.animateFocus();
        view.describeFocus();
        view.invalidate();
    }

    /** Cancels an unfinished move and forgets a pending key release. */
    void reset(){
        finish(false);
        swallowMoveKey=false;
    }

    /** Handles a key press while moving; the matching key release is swallowed. */
    boolean keyDown(int code){
        swallowMoveKey=true;
        if(code==KeyEvent.KEYCODE_BACK)finish(false);
        else if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)finish(true);
        else if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){
            int from=movingRow.selected,to=from+(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);
            if(to>=0&&to<movingRow.tiles.size()){
                if(movingRow.tiles.get(from).pinned!=movingRow.tiles.get(to).pinned)host.toast("置顶卡片优先，请先调整置顶状态");
                else {
                    Collections.swap(movingRow.tiles,from,to);
                    movingRow.selected=to;
                    view.ensureVisible();
                    view.animateFocus();
                    view.describeFocus();
                }
            }
        }
        view.invalidate();
        return true;
    }

    boolean swallowKeyUp(){
        if(!swallowMoveKey)return false;
        swallowMoveKey=false;
        return true;
    }
}
