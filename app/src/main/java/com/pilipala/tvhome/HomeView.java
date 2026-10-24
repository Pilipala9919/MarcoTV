// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** A launcher card: an installed application or a TV input. */
final class Tile {
    String label, id;
    android.content.Intent intent;
    Bitmap artwork;
    BitmapShader shader;
    boolean banner;
    int tint;
    String glyph;
    final MotionSpring emphasis = new MotionSpring(0, 22, 0.001f);
    String displayLabel, coverLabel;
    int kind; // 0 installed application, 1 TV input
}

/** A horizontal shelf of tiles such as "TV 应用" or "输入源". */
final class Row {
    String title, symbol, orderKey;
    ArrayList<Tile> tiles = new ArrayList<>();
    int selected;
    final MotionSpring scroll = new MotionSpring(0, 18, 0.05f);
    Row(String title, String symbol) {
        this.title = title; this.symbol = symbol;
        orderKey = title.equals("TV 应用") ? "order_tv" : title.equals("输入源") ? "order_inputs"
                : title.equals("收藏") ? "order_favorites" : "order_other";
    }
}

/** The launcher home screen, drawn by hand on a single full-screen view. */
final class HomeView extends View {
    private final MainActivity host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface symbols;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
    ArrayList<Row> rows = new ArrayList<>();
    Bitmap wallpaper;
    int row = 0;
    private int topSelected = 0;
    private float uiAlpha = 1, ambientProgress;
    private final ClockScene clockScene = new ClockScene();
    private boolean idleMode, wakingKey, longPressed;
    ValueAnimator fade;
    private final RectF rect = new RectF();
    private final RectF iconBounds = new RectF();
    private final Matrix artworkMatrix = new Matrix();
    private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final MotionSpring section = new MotionSpring(0, 20, 0.001f);
    private final Choreographer.FrameCallback motionFrame = this::advanceMotion;
    private boolean motionScheduled;
    private long lastFrameNanos;
    private float scale = 1, offsetY;
    private float radius = 70, focusScale = .24f;
    private float labelOpacity;
    private final MotionSpring edge=new MotionSpring(0,20,.001f);
    private boolean edgeOpen;
    private int edgeSelected;
    private final String[] edgeItems={"编辑当前卡片","卡片外观","时钟与屏保","桌面设置"};
    void edge(boolean open){edgeOpen=open;edge.target(open?1:0);animateFocus();describeFocus();}
    private void edgeAction(){
        int action=edgeSelected;edge(false);
        if(action==0)host.tileOptions();else if(action==1)host.appearanceSettings();else if(action==2)host.ambientSettings();else host.settings();
    }
    private String previewKey;
    private float previewAmbient;
    ValueAnimator previewFade;
    private int previewSavedRow=-2;
    void preview(String key){
        if(key!=null&&previewSavedRow==-2){previewSavedRow=row;row=Math.max(0,row);animateFocus();}
        else if(key==null&&previewSavedRow!=-2){row=previewSavedRow;previewSavedRow=-2;animateFocus();}
        previewKey=key;
        if(previewFade!=null)previewFade.cancel();
        previewAmbient=key!=null&&key.startsWith("ambient_")?1:0;
        if("ambient_transition".equals(key))replayPreview();
        invalidate();
    }
    void replayPreview(){
        if(!"ambient_transition".equals(previewKey))return;
        if(previewFade!=null)previewFade.cancel();
        previewAmbient=0;previewFade=ValueAnimator.ofFloat(0,1);
        previewFade.setDuration(host.preferences.getInt("ambient_transition",3)*1000L);
        previewFade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        previewFade.addUpdateListener(a->{previewAmbient=(float)a.getAnimatedValue();invalidate();});previewFade.start();
    }

    HomeView(MainActivity host) {
        super(host); this.host = host;
        symbols = Typeface.createFromAsset(host.getAssets(), "MaterialIcons-Regular.ttf");
        setFocusable(true); setFocusableInTouchMode(true);
        setContentDescription("Marco TV。方向键选择应用，确定键打开，菜单键设置。");
        // Keep the window GPU renderer, without caching the entire animated screen
        // in an extra hardware layer on memory-constrained television GPUs.
        setLayerType(View.LAYER_TYPE_NONE, null);
        refreshAppearance();
    }

    void refreshAppearance() {
        radius = host.preferences.getInt("card_radius", 32);
        focusScale = host.preferences.getInt("focus_scale", 16) / 100f;
        labelOpacity = host.preferences.getInt("label_opacity", host.preferences.getInt("label_mode", 0) == 1 ? 55 : 0) / 100f;
    }
    void describeFocus() {
        if (rows.isEmpty()) setContentDescription("Marco TV，暂无可见卡片，按菜单键恢复隐藏的卡片");
        else if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty())
            setContentDescription(rows.get(row).title + "，" + rows.get(row).tiles.get(rows.get(row).selected).label);
    }

    void resetToApps() {
        edgeOpen=false;edge.snap(0);
        stopMotion(); row = 0; section.snap(0);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i); r.selected = Math.max(0, Math.min(r.selected, r.tiles.size() - 1));
            for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.snap(i == 0 && j == r.selected ? 1 : 0);
        }
        ensureVisible(); animateFocus(); invalidate();
    }

    void setIdle(boolean value) {
        if (value == idleMode && ((fade != null && fade.isRunning()) || ambientProgress == (value ? 1 : 0))) { if (!value) host.armIdle(); return; }
        idleMode = value;
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(ambientProgress, value ? 1 : 0);
        fade.setDuration(value ? host.preferences.getInt("ambient_transition",3) * 1000L : 400);
        fade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        fade.addUpdateListener(a -> { ambientProgress = (float) a.getAnimatedValue(); uiAlpha = 1 - Math.min(1, ambientProgress * 4); invalidate(); });
        fade.start();
        if (!value) host.armIdle();
    }

    void animateFocus() {
        section.target(Math.max(0, row));
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.target(i == row && j == r.selected ? 1 : 0);
        }
        if (!motionScheduled && host.foreground && isAttachedToWindow()) {
            motionScheduled = true; lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(motionFrame);
        }
        invalidate();
    }

    private void advanceMotion(long frameNanos) {
        if (!host.foreground || !isAttachedToWindow()) { motionScheduled = false; return; }
        float dt = lastFrameNanos == 0 ? 1f / 60 : Math.min(0.05f, (frameNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = frameNanos;
        boolean moving = section.step(dt);moving|=edge.step(dt);
        for (Row r : rows) {
            moving |= r.scroll.step(dt);
            for (Tile t : r.tiles) moving |= t.emphasis.step(dt);
        }
        invalidate();
        if (moving) Choreographer.getInstance().postFrameCallback(motionFrame);
        else { motionScheduled = false; lastFrameNanos = 0; }
    }

    void stopMotion() {
        Choreographer.getInstance().removeFrameCallback(motionFrame);
        motionScheduled = false; lastFrameNanos = 0;
    }

    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }

    private float clampedEmphasis(Tile t) {
        return Math.max(0, Math.min(1, t.emphasis.value));
    }

    void ensureVisible() {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        float next = r.scroll.target;
        float selectedX = 272 + r.selected * 350 - next;
        if (selectedX > 1630) next += selectedX - 1630;
        if (selectedX < 272) next = Math.max(0, next + selectedX - 272);
        r.scroll.target(next);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float ambient=previewKey==null?ambientProgress:previewAmbient;
        float contentAlpha=previewKey==null?uiAlpha:1-Math.min(1,ambient*4);
        scale = Math.min(getWidth() / 1920f, getHeight() / 1080f);
        offsetY = (getHeight() - 1080 * scale) / 2;
        canvas.drawColor(Color.BLACK);
        if (wallpaper != null) {
            float factor = Math.max(getWidth() / (float) wallpaper.getWidth(), getHeight() / (float) wallpaper.getHeight());
            float w = wallpaper.getWidth() * factor, h = wallpaper.getHeight() * factor;
            rect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            paint.setAlpha(255); canvas.drawBitmap(wallpaper, null, rect, paint);
        }
        canvas.save(); canvas.translate((getWidth() - 1920 * scale) / 2, offsetY); canvas.scale(scale, scale);
        paint.setColor(Color.argb(ClockScene.dim(host.preferences,ambient),0,0,0)); canvas.drawRect(0,0,1920,1080,paint);
        clockScene.draw(canvas,host.preferences,ambient);
        if (contentAlpha > 0.005f) {
            int muted = alphaColor(0xC0FFFFFF, contentAlpha);
            float wifiX = clockScene.left(host.preferences) - 70;
            icon(canvas, "\uee38", 132, 110, 40, muted);
            text(canvas, "MARCO TV", 178, 119, 23, normal, alphaColor(0xB0FFFFFF,contentAlpha), Paint.Align.LEFT);
            text(canvas, "by Pilipala", 178, 145, 14, normal, alphaColor(0x78FFFFFF,contentAlpha), Paint.Align.LEFT);
            icon(canvas, "\ue63c", wifiX, 110, 38, muted);
            if (row == -1) {
                float selectedX = topSelected == 0 ? 132 : wifiX;
                paint.setColor(alphaColor(0x28FFFFFF, contentAlpha));canvas.drawCircle(selectedX,110,34,paint);
                icon(canvas,topSelected==0?"\uee38":"\ue63c",selectedX,110,40,alphaColor(Color.WHITE,contentAlpha));
            }
            if (rows.isEmpty()) text(canvas, host.allTiles.isEmpty() ? "正在读取应用…" : "没有可见卡片，按菜单键恢复", 112, 480, 34, normal, alphaColor(Color.WHITE, contentAlpha), Paint.Align.LEFT);
            else {
                // Keep the moving shelves inside the content area, clear of the fixed clock.
                int shelfClip = canvas.save(); canvas.clipRect(0, previewKey==null?285:210, 1920, previewKey==null?925:660);
                for (int i = 0; i < rows.size(); i++) {
                    float distance = i - section.value;
                    if (Math.abs(distance) < 1.35f) {
                        float opacity = contentAlpha * Math.max(0, 1 - Math.abs(distance) * 0.75f);
                        canvas.save();canvas.translate(edge.value*390,0);
                        drawRow(canvas, rows.get(i), (previewKey==null?660:450) + distance * 515, opacity*(1-edge.value*.35f));canvas.restore();
                    }
                }
                canvas.restoreToCount(shelfClip);
                int next = Math.max(0, row) + 1;
                if (next < rows.size() && previewKey==null) {
                    Row r = rows.get(next);
                    icon(canvas, r.symbol, 136, 1003, 48, alphaColor(Color.WHITE, contentAlpha * .3f));
                    text(canvas, r.title, 176, 1010, 34, bold, alphaColor(Color.WHITE, contentAlpha * .3f), Paint.Align.LEFT);
                }
            }
            if(edge.value>.002f&&previewKey==null)drawEdge(canvas,contentAlpha);
        }
        canvas.restore();
    }

    private int alphaColor(int color, float a) { return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * a) << 24); }

    private void drawEdge(Canvas canvas,float opacity){
        float a=Math.max(0,Math.min(1,edge.value))*opacity;
        canvas.save();canvas.translate(-80*(1-edge.value),0);
        paint.setColor(alphaColor(0xEE17222E,a));rect.set(70,390,438,875);canvas.drawRoundRect(rect,28,28,paint);
        text(canvas,rows.get(Math.max(0,row)).title,100,438,25,bold,alphaColor(0xFFDCE8EE,a),Paint.Align.LEFT);
        for(int i=0;i<edgeItems.length;i++){
            float y=465+i*78;
            if(i==edgeSelected){paint.setColor(alphaColor(0xFFE4F0F5,a));rect.set(88,y,420,y+65);canvas.drawRoundRect(rect,16,16,paint);}
            text(canvas,edgeItems[i],110,y+42,25,normal,alphaColor(i==edgeSelected?0xFF152331:0xFFCDD7E0,a),Paint.Align.LEFT);
        }
        text(canvas,"右键返回卡片",100,838,19,normal,alphaColor(0xFFA1B3C3,a),Paint.Align.LEFT);canvas.restore();
    }

    private void text(Canvas canvas, String text, float x, float y, float size, Typeface font, int color, Paint.Align align) {
        paint.setColor(color); paint.setTypeface(font); paint.setTextSize(size); paint.setTextAlign(align);
        canvas.drawText(text, x, y, paint);
    }

    private void icon(Canvas canvas, String glyph, float x, float y, float size, int color) {
        paint.setTypeface(symbols); paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(color);
        canvas.drawText(glyph, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private void drawRow(Canvas canvas, Row r, float y, float opacity) {
        icon(canvas, r.symbol, 136, y - 172, 48, alphaColor(Color.WHITE, opacity));
        text(canvas, r.title, 176, y - 165, 34, bold, alphaColor(Color.WHITE, opacity), Paint.Align.LEFT);
        int raised = -1;
        for (int i = 0; i < r.tiles.size(); i++) if (raised < 0 || r.tiles.get(i).emphasis.value > r.tiles.get(raised).emphasis.value) raised = i;
        for (int i = 0; i < r.tiles.size(); i++) if (i != raised) drawTile(canvas, r.tiles.get(i), 272 + i * 350 - r.scroll.value, y, opacity);
        if (raised >= 0) drawTile(canvas, r.tiles.get(raised), 272 + raised * 350 - r.scroll.value, y, opacity);
    }

    private void drawTile(Canvas canvas, Tile tile, float x, float shelfY, float opacity) {
        if (x < -250 || x > 2180) return;
        float emphasis = clampedEmphasis(tile);
        float zoom = 1 + focusScale * emphasis;
        float y = shelfY - 6 * emphasis;
        float w = 320 * zoom, h = 180 * zoom;
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        paint.setColor(alphaColor(tile.tint != 0 ? tile.tint : tile.banner ? Color.BLACK : 0xFF243541, opacity));
        canvas.drawRoundRect(rect, radius * zoom, radius * zoom, paint);
        if (tile.artwork != null) {
            if (tile.banner) {
                if (tile.shader == null) tile.shader = new BitmapShader(tile.artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                artworkMatrix.setScale(rect.width() / tile.artwork.getWidth(), rect.height() / tile.artwork.getHeight());
                artworkMatrix.postTranslate(rect.left, rect.top); tile.shader.setLocalMatrix(artworkMatrix);
                artworkPaint.setShader(tile.shader); artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawRoundRect(rect, radius * zoom, radius * zoom, artworkPaint);
                artworkPaint.setShader(null);
            } else {
                float size = 116 * zoom;
                iconBounds.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
                artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawBitmap(tile.artwork, null, iconBounds, artworkPaint);
            }
        } else if (tile.tint != 0) {
            icon(canvas, tile.glyph, x, y - 20 * zoom, 60 * zoom, alphaColor(0xB0FFFFFF, opacity));
            if (tile.coverLabel == null) tile.coverLabel = clippedLabel(tile.label, 235, 28);
            text(canvas, tile.coverLabel, x, y + 48 * zoom, 28 * zoom, bold, alphaColor(Color.WHITE, opacity), Paint.Align.CENTER);
        } else icon(canvas, tile.kind == 1 ? "\ue333" : "\ue879", x, y, 112 * zoom, alphaColor(Color.WHITE, opacity));
        if (emphasis > .005f || labelOpacity > 0) {
            if (tile.displayLabel == null) {
                tile.displayLabel = clippedLabel(tile.label, 300, 36);
            }
            text(canvas, tile.displayLabel, Math.max(112, x - 160), shelfY + 182, 36, bold,
                    alphaColor(Color.WHITE, opacity * (labelOpacity + (1-labelOpacity) * emphasis)), Paint.Align.LEFT);
        }
    }

    private String clippedLabel(String value, float width, float size) {
        paint.setTypeface(bold); paint.setTextSize(size);
        if (paint.measureText(value) <= width) return value;
        int end = value.length();
        while (end > 0 && paint.measureText(value.substring(0, end) + "…") > width)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end) + "…";
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        boolean navigation = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
        if (!navigation) return super.onKeyDown(code, event);
        if (idleMode) { wakingKey = true; setIdle(false); return true; }
        host.armIdle();
        if(edgeOpen){
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_BACK)edge(false);
            else if(code==KeyEvent.KEYCODE_DPAD_UP)edgeSelected=Math.max(0,edgeSelected-1);
            else if(code==KeyEvent.KEYCODE_DPAD_DOWN)edgeSelected=Math.min(edgeItems.length-1,edgeSelected+1);
            else if(code==KeyEvent.KEYCODE_MENU){edge(false);host.settings();}
            if(edgeOpen)setContentDescription("分类操作栏，"+edgeItems[edgeSelected]);else describeFocus();
            invalidate();return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) { longPressed = false; event.startTracking(); }
            else if (event.isLongPress() && !longPressed && row >= 0) { longPressed = true; host.tileOptions(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) { if (event.getRepeatCount() == 0) host.settings(); return true; }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (row != 0) { row = 0; ensureVisible(); animateFocus(); } else setIdle(true);
            return true;
        }
        if (rows.isEmpty()) { if (code == KeyEvent.KEYCODE_DPAD_UP) { row = -1; invalidate(); } return true; }
        if (row == -1) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = 0;
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) topSelected = 0;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) topSelected = 1;
            ensureVisible(); animateFocus();
        } else {
            Row r = rows.get(row);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if(r.selected==0&&event.getRepeatCount()==0){edgeSelected=0;edge(true);setContentDescription("分类操作栏，"+edgeItems[0]);return true;}
                r.selected=Math.max(0,r.selected-1);
            }
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) r.selected = Math.min(r.tiles.size() - 1, r.selected + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) row--;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = Math.min(rows.size() - 1, row + 1);
            ensureVisible(); animateFocus();
        }
        if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty()) {
            String label = rows.get(row).tiles.get(rows.get(row).selected).label;
            setContentDescription(rows.get(row).title + "，" + label); announceForAccessibility(label);
        }
        invalidate(); return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (wakingKey) { wakingKey = false; return true; }
        if(edgeOpen&&(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)){edgeAction();return true;}
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (longPressed) { longPressed = false; return true; }
            if (row == -1) { if (topSelected == 0) host.settings(); else host.network.show(); }
            else if (!rows.isEmpty()) { Row r = rows.get(row); if (!r.tiles.isEmpty()) host.launch(r.tiles.get(r.selected)); }
            return true;
        }
        return super.onKeyUp(code, event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (idleMode) { setIdle(false); return true; }
        host.armIdle();
        float x = (event.getX() - (getWidth() - 1920 * scale) / 2) / scale;
        float y = (event.getY() - offsetY) / scale;
        if(edgeOpen){
            if(x>=88&&x<=420&&y>=465&&y<777){edgeSelected=Math.min(3,(int)((y-465)/78));edgeAction();}
            else edge(false);return true;
        }
        if (y < 205 && x < 380) { host.settings(); return true; }
        if (y < 205 && Math.abs(x-(clockScene.left(host.preferences)-70)) < 65) { host.network.show(); return true; }
        if (!rows.isEmpty() && y > 520 && y < 810) {
            Row r = rows.get(Math.max(0, row));
            int index = Math.round((x - 272 + r.scroll.value) / 350);
            if (index >= 0 && index < r.tiles.size()) {
                if (row >= 0 && index == r.selected) host.launch(r.tiles.get(index));
                else { row = Math.max(0, row); r.selected = index; ensureVisible(); animateFocus(); }
            }
        }
        return true;
    }
}
