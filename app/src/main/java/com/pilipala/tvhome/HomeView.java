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
    boolean banner, pinned;
    int tint;
    String glyph;
    final MotionSpring emphasis = new MotionSpring(0, 22, 0.001f);
    final MotionSpring press = new MotionSpring(0, 32, 0.001f);
    String displayLabel, coverLabel;
    int kind;
    // 0 installed application, 1 TV input
}

/** A horizontal shelf of tiles such as "我的应用" or "输入源". */
final class Row {
    String title, symbol, orderKey;
    ArrayList<Tile> tiles = new ArrayList<>();
    int selected;
    final MotionSpring scroll = new MotionSpring(0, 18, 0.05f);
    Row(String title, String symbol) {
        this.title = title;
        this.symbol = symbol;
        orderKey = (title.equals("我的应用") || title.equals("TV 应用")) ? "order_tv" : title.equals("输入源") ? "order_inputs"
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
    private final ClockScene clockScene;
    boolean idleMode;
    private boolean wakingKey, longPressed;
    private final CardMover mover;
    boolean isMoving(){
        return mover.active();
    }
    void beginMove(){
        mover.begin();
    }
    ValueAnimator fade;
    private final RectF rect = new RectF();
    private final RectF iconBounds = new RectF();
    private final Path playbackClip = new Path();
    private final Path iconPath = new Path();
    private String lastPlaybackTitle, clippedPlaybackTitle = "";
    private String lastPlaybackSource, clippedPlaybackSource = "";
    private final Matrix artworkMatrix = new Matrix();
    private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final MotionSpring section = new MotionSpring(0, 20, 0.001f);
    private final Choreographer.FrameCallback motionFrame = this::advanceMotion;
    private boolean motionScheduled;
    private long lastFrameNanos;
    private float scale = 1, offsetY;
    private float radius = 70, focusScale = .24f;
    private float labelOpacity;
    private float surfaceStrength;
    private float motionSpeed=1;
    private final Paint surfacePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private final Matrix surfaceMatrix=new Matrix();
    private final RectF surfaceBounds=new RectF();
    private final LinearGradient surfaceGradient=new LinearGradient(0,0,0,1,
            new int[]{0x40FFFFFF,0x08FFFFFF,0x00000000,0x60081424},new float[]{0,.36f,.58f,1},Shader.TileMode.CLAMP);
    private final LinearGradient rimGradient=new LinearGradient(0,0,0,1,0xB0FFFFFF,0x20FFFFFF,Shader.TileMode.CLAMP);
    private final MotionSpring entrance=new MotionSpring(1,15,.001f);
    private final MotionSpring edge=new MotionSpring(0,20,.001f);
    private final MotionSpring magicFocus=new MotionSpring(0,20,.001f);
    private final Paint magicPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private final LinearGradient magicGradient=new LinearGradient(-11,-7,23,18,0xB078CADC,0x7039587F,Shader.TileMode.CLAMP);
    private boolean edgeOpen;
    private int edgeSelected;
    private final String[] edgeItems={"添加应用","编辑当前卡片","卡片外观","时钟与屏保","桌面设置"};
    void edge(boolean open){
        edgeOpen=open;
        edge.target(open?1:0);
        animateFocus();
        describeFocus();
    }
    private void edgeAction(){
        int action=edgeSelected;
        edge(false);
        if(action==0)host.library.show();
        else if(action==1)host.tileOptions();
        else if(action==2)host.appearanceSettings();
        else if(action==3)host.ambientSettings();
        else host.settings();
    }
    private String previewKey;
    private float overview;
    private UiMotion overviewAnimation;
    boolean previewDirty=true;
    private final MotionSpring headerLeft=new MotionSpring(88,26,.05f),headerRight=new MotionSpring(302,26,.05f),headerAlpha=new MotionSpring(0,
            26,.001f);
    private static final float HEADER_ANCHOR_X=88f;
    private static final float HEADER_START_X=112f;
    private static final float HEADER_ICON_GAP=14f;
    private static final float HEADER_ITEM_GAP=72f;
    private static final float HEADER_PILL_PAD_X=24f;
    private final float[] headerIconX=new float[4];
    private final float[] headerTextX=new float[4];
    private final float[] headerContentLeft=new float[4];
    private final float[] headerContentRight=new float[4];
    private float headerTotalRight=1040f;
    private void measureHeader(){
        float[] iconLeft={17f,21f,19f,14f};
        float[] iconRight={17f,21f,19f,14f};
        String[] labels={"MARCO TV","Marco 互联","Magic Desktop","全部应用"};
        float cursor=HEADER_START_X;
        for(int i=0;i<4;i++){
            headerContentLeft[i]=Math.round(cursor);
            headerIconX[i]=Math.round(headerContentLeft[i]+iconLeft[i]);
            headerTextX[i]=Math.round(headerIconX[i]+iconRight[i]+HEADER_ICON_GAP);
            paint.setTypeface(normal);
            paint.setTextSize(23);
            float textWidth=paint.measureText(labels[i]);
            if(i==0){
                paint.setTextSize(14);
                textWidth=Math.max(textWidth,paint.measureText("by Pilipala"));
            }
            headerContentRight[i]=Math.round(headerTextX[i]+textWidth);
            cursor=headerContentRight[i]+HEADER_ITEM_GAP;
        }
        headerTotalRight=headerContentRight[3]+HEADER_PILL_PAD_X;
    }
    private float headerFit(){
        float available=clockScene.left(host.appearance())-132f-HEADER_ANCHOR_X;
        float needed=Math.max(1f,headerTotalRight-HEADER_ANCHOR_X);
        return Math.min(1f,Math.max(.76f,available/needed));
    }
    private float headerPosition(float x){
        return HEADER_ANCHOR_X+(x-HEADER_ANCHOR_X)*headerFit();
    }
    private void previewTransform(float value){
        overview=value;
        super.invalidate();
    }
    @Override public void invalidate(){
        previewDirty=true;
        super.invalidate();
    }
    final OverviewRenderer overviewRenderer=new OverviewRenderer(this);
    float wifiAnchorX(){
        return (getWidth()-1920*Math.min(getWidth()/1920f,getHeight()/1080f))/2+(clockScene.left(host.appearance())-70)*Math.min(getWidth()/1920f,
                getHeight()/1080f);
    }
    float wifiAnchorY(){
        float s=Math.min(getWidth()/1920f,getHeight()/1080f);
        return (getHeight()-1080*s)/2+110*s;
    }
    private float previewAmbient;
    ValueAnimator previewFade;
    private int previewSavedRow=-2;
    void preview(String key){
        if((key==null)!=(previewKey==null)){
            if(overviewAnimation==null)overviewAnimation=new UiMotion(this,overview,26,this::previewTransform);
            overviewAnimation.to(key==null?0:1);
        }
        if(key!=null&&previewSavedRow==-2){
            previewSavedRow=row;
            row=Math.max(0,row);
            animateFocus();
        }
        else if(key==null&&previewSavedRow!=-2){
            row=previewSavedRow;
            previewSavedRow=-2;
            animateFocus();
        }
        boolean changedScene=previewKey==null||key==null||previewKey.startsWith("recent_")!=key.startsWith("recent_")||previewKey.startsWith("ambient_")!=key.startsWith("ambient_");
        previewKey=key;
        if(previewFade!=null)previewFade.cancel();
        previewAmbient=key!=null&&key.startsWith("ambient_")?1:0;
        if("ambient_transition".equals(key))replayPreview();
        if(changedScene)invalidate();
    }
    void replayPreview(){
        if(!"ambient_transition".equals(previewKey))return;
        if(previewFade!=null)previewFade.cancel();
        previewAmbient=0;
        previewFade=ValueAnimator.ofFloat(0,1);
        previewFade.setDuration(host.appearance().getInt("ambient_transition",3)*1000L);
        previewFade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        previewFade.addUpdateListener(a->{
            previewAmbient=(float)a.getAnimatedValue();
            invalidate();
        });
        previewFade.start();
    }

    HomeView(MainActivity host) {
        super(host);
        this.host = host;
        mover=new CardMover(host,this);
        clockScene=new ClockScene(host);
        symbols = Typeface.createFromAsset(host.getAssets(), "MaterialIcons-Regular.ttf");
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Marco TV。方向键选择应用，确定键打开，菜单键设置。");
        // Keep the window GPU renderer, without caching the entire animated screen
        // in an extra hardware layer on memory-constrained television GPUs.
        setLayerType(View.LAYER_TYPE_NONE, null);
        measureHeader();
        refreshAppearance();
    }

    void refreshAppearance() {
        radius = host.appearance().getInt("card_radius", 32);
        focusScale = host.appearance().getInt("focus_scale", 16) / 100f;
        labelOpacity = host.appearance().getInt("label_opacity", host.preferences.getInt("label_mode", 0) == 1 ? 55 : 0) / 100f;
        surfaceStrength=Math.max(0,Math.min(100,host.appearance().getInt("card_surface",55)))/100f;
        motionSpeed=260f/Math.max(120,Math.min(500,host.appearance().getInt("motion_duration",260)));
    }
    void describeFocus() {
        if(host.recents!=null&&host.recents.visible){
            host.recents.describe();
            return;
        }
        if(mover.active())setContentDescription("移动卡片，"+mover.movingRow.tiles.get(mover.movingRow.selected).label+"，左右移动，确定保存，返回取消");
        else if(edgeOpen)setContentDescription("分类操作栏，"+edgeItems[edgeSelected]);
        else if(row==-2&&host.playback!=null&&host.playback.item!=null) setContentDescription("正在播放，"+host.playback.item.title+"，"+host.playback.item.source);
        else if(row==-1)setContentDescription(topSelected==0?"桌面设置":topSelected==1?"Marco 互联，用手机整理电视":topSelected==2?"Magic Desktop，预览并调整桌面":topSelected==3?"全部应用":"网络与 Wi-Fi");
        else if (rows.isEmpty()) setContentDescription("Marco TV，暂无可见卡片，按菜单键恢复隐藏的卡片");
        else if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty())
                setContentDescription(rows.get(row).title + "，" + rows.get(row).tiles.get(rows.get(row).selected).label);
        else setContentDescription("我的应用，暂无应用，按左键展开菜单添加应用");
    }

    void resetToApps() {
        mover.reset();
        edgeOpen=false;
        edge.snap(0);
        stopMotion();
        row = 0;
        section.snap(0);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.selected = Math.max(0, Math.min(r.selected, r.tiles.size() - 1));
            for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.snap(i == 0 && j == r.selected ? 1 : 0);
        }
        ensureVisible();
        animateFocus();
        describeFocus();
        invalidate();
    }

    void setIdle(boolean value) {
        if (value == idleMode && ((fade != null && fade.isRunning()) || ambientProgress == (value ? 1 : 0))) {
            if (!value) host.armIdle();
            return;
        }
        idleMode = value;
        host.idleChanged(value);
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(ambientProgress, value ? 1 : 0);
        fade.setDuration(value ? host.appearance().getInt("ambient_transition",3) * 1000L : 400);
        fade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        fade.addUpdateListener(a -> {
            ambientProgress = (float) a.getAnimatedValue();
            uiAlpha = 1 - Math.min(1, ambientProgress * 4);
            invalidate();
        });
        fade.start();
        if (!value) host.armIdle();
    }

    void animateFocus() {
        float left=topSelected<4?headerPosition(headerContentLeft[topSelected]-HEADER_PILL_PAD_X):clockScene.left(host.appearance())-102;
        float right=topSelected<4?headerPosition(headerContentRight[topSelected]+HEADER_PILL_PAD_X):clockScene.left(host.appearance())-38;
        if(headerAlpha.value==0){
            headerLeft.snap(left);
            headerRight.snap(right);
        }else{
            headerLeft.target(left);
            headerRight.target(right);
        }
        headerAlpha.target(row==-1?1:0);
        section.target(Math.max(0, row));
        magicFocus.target(row==-1&&topSelected==2?1:0);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.target(i == row && j == r.selected ? 1 : 0);
        }
        if (!motionScheduled && host.foreground && isAttachedToWindow()) {
            motionScheduled = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(motionFrame);
        }
        invalidate();
    }

    void enter(){
        entrance.snap(0);
        entrance.target(1);
        animateFocus();
    }

    private void advanceMotion(long frameNanos) {
        if (!host.foreground || !isAttachedToWindow()) {
            motionScheduled = false;
            return;
        }
        float dt = lastFrameNanos == 0 ? 1f / 60 : Math.min(0.05f, (frameNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = frameNanos;
        dt*=motionSpeed;
        boolean moving = section.step(dt);
        moving|=edge.step(dt);
        moving|=entrance.step(dt);
        moving|=magicFocus.step(dt);
        moving|=headerLeft.step(dt);
        moving|=headerRight.step(dt);
        moving|=headerAlpha.step(dt);
        for (Row r : rows) {
            moving |= r.scroll.step(dt);
            for (Tile t : r.tiles) {
                moving |= t.emphasis.step(dt);
                moving |= t.press.step(dt);
            }
        }
        invalidate();
        if (moving) Choreographer.getInstance().postFrameCallback(motionFrame);
        else {
            motionScheduled = false;
            lastFrameNanos = 0;
        }
    }

    void stopMotion() {
        for(Row r:rows)for(Tile t:r.tiles)t.press.snap(0);
        Choreographer.getInstance().removeFrameCallback(motionFrame);
        motionScheduled = false;
        lastFrameNanos = 0;
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        clockScene.release();
        overviewRenderer.detach();
        super.onDetachedFromWindow();
    }

    private float clampedEmphasis(Tile t) {
        return Math.max(0, Math.min(1, t.emphasis.value));
    }

    void ensureVisible() {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        float next = Math.max(0, r.selected - 1) * 350f;
        r.scroll.target(next);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        overviewRenderer.draw(canvas,overview);
    }
    void drawScene(Canvas canvas){
        float ambient=previewKey==null?ambientProgress:previewAmbient;
        float contentAlpha=previewKey==null?uiAlpha:1-Math.min(1,ambient*4);
        if(previewKey==null&&host.phone!=null&&host.phone.ambientPreview()){
            ambient=1;
            contentAlpha=0;
        }
        scale = Math.min(getWidth() / 1920f, getHeight() / 1080f);
        offsetY = (getHeight() - 1080 * scale) / 2;
        canvas.drawColor(Color.BLACK);
        if (wallpaper != null) {
            float factor = Math.max(getWidth() / (float) wallpaper.getWidth(), getHeight() / (float) wallpaper.getHeight());
            float w = wallpaper.getWidth() * factor, h = wallpaper.getHeight() * factor;
            rect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            paint.setAlpha(255);
            canvas.drawBitmap(wallpaper, null, rect, paint);
        }
        canvas.save();
        canvas.translate((getWidth() - 1920 * scale) / 2, offsetY);
        canvas.scale(scale, scale);
        if(host.recents.visible||(previewKey!=null&&previewKey.startsWith("recent_"))){
            host.recents.draw(canvas,previewKey!=null);
            canvas.restore();
            return;
        }
        paint.setColor(Color.argb(ClockScene.dim(host.appearance(),ambient),0,0,0));
        canvas.drawRect(0,0,1920,1080,paint);
        // Only the real idle screen drifts; previews and phone previews stay put.
        clockScene.draw(canvas,host.appearance(),ambient,previewKey==null&&(host.phone==null||!host.phone.ambientPreview()));
        if (contentAlpha > 0.005f) {
            float wifiX = clockScene.left(host.appearance()) - 70;
            int muted = alphaColor(0xC0FFFFFF, contentAlpha);
            if(headerAlpha.value>.001f){
                paint.setColor(alphaColor(0x28FFFFFF,contentAlpha*headerAlpha.value));
                rect.set(headerLeft.value,74,headerRight.value,152);
                canvas.drawRoundRect(rect,24,24,paint);
            }
            int headerSave=canvas.save();
            float fit=headerFit();
            canvas.translate(HEADER_ANCHOR_X*(1-fit),0);
            canvas.scale(fit,1);
            paint.setTypeface(normal);
            paint.setTextSize(23);
            float labelBaseline=113f-(paint.ascent()+paint.descent())/2f;
            for(int i=0;i<4;i++){
                int color=alphaColor(i==topSelected&&row==-1?Color.WHITE:0xD4FFFFFF,contentAlpha);
                float ix=headerIconX[i],tx=headerTextX[i];
                if(i==0){
                    settingsIcon(canvas,ix,113,color);
                    text(canvas,"MARCO TV",tx,110,23,normal,color,Paint.Align.LEFT);
                    text(canvas,"by Pilipala",tx,134,14,normal,alphaColor(0x78FFFFFF,contentAlpha),Paint.Align.LEFT);
                }
                else if(i==1){
                    connectIcon(canvas,ix,112,color);
                    text(canvas,"Marco 互联",tx,labelBaseline,23,normal,color,Paint.Align.LEFT);
                }
                else if(i==2){
                    magicIcon(canvas,ix,112,color);
                    text(canvas,"Magic Desktop",tx,labelBaseline,23,normal,color,Paint.Align.LEFT);
                }
                else{
                    allAppsIcon(canvas,ix,113,color);
                    text(canvas,"全部应用",tx,labelBaseline,23,normal,color,Paint.Align.LEFT);
                }
            }
            if(host.phone!=null&&host.phone.active()){
                paint.setColor(0xFF9FE0C4);
                canvas.drawCircle(headerIconX[1]+20,95,4.5f,paint);
            }
            canvas.restoreToCount(headerSave);
            wifiIcon(canvas,Math.round(wifiX),113,row==-1&&topSelected==4?alphaColor(Color.WHITE,contentAlpha):muted);
            if(mover.active())text(canvas,"左右移动  ·  确定放下  ·  返回取消",960,960,26,bold,Color.WHITE,Paint.Align.CENTER);
            if(host.playback!=null&&host.playback.item!=null)drawPlayback(canvas,contentAlpha);
            if (rows.isEmpty()) text(canvas, host.allTiles.isEmpty() ? "正在读取应用…" : "没有可见卡片，按菜单键恢复", 112, 480, 34, normal, alphaColor(Color.WHITE,
                    contentAlpha), Paint.Align.LEFT);
            else {
                // Keep the moving shelves inside the content area, clear of the fixed clock.
                int shelfClip = canvas.save();
                canvas.clipRect(0, 285, 1920, 925);
                for (int i = 0; i < rows.size(); i++) {
                    float distance = i - section.value;
                    if (Math.abs(distance) < 1.35f) {
                        float opacity = contentAlpha * Math.max(0, 1 - Math.abs(distance) * 0.75f);
                        canvas.save();
                        canvas.translate(edge.value*390,0);
                        drawRow(canvas, rows.get(i), shelfCenterY() + distance * 515, opacity*(1-edge.value*.35f));
                        canvas.restore();
                    }
                }
                canvas.restoreToCount(shelfClip);
                int next = Math.max(0, row) + 1;
                if (next < rows.size()) {
                    Row r = rows.get(next);
                    drawShelfIcon(canvas, r.orderKey, 130, 1000, contentAlpha * .34f);
                    text(canvas, r.title, 166, 1011, 34, bold, alphaColor(Color.WHITE, contentAlpha * .34f), Paint.Align.LEFT);
                }
            }
            if(edge.value>.002f&&previewKey==null)drawEdge(canvas,contentAlpha);
        }
        canvas.restore();
    }

    private int alphaColor(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * a) << 24);
    }

    private void drawPlayback(Canvas canvas,float opacity) {
        NowPlaying.Item item=host.playback.item;
        rect.set(112,192,742,310);
        paint.setColor(alphaColor(row==-2?0xF2DCE8EF:0xDD182430,opacity));
        canvas.drawRoundRect(rect,24,24,paint);
        int foreground=alphaColor(row==-2?0xFF17232D:0xFFF1F5F8,opacity);
        if(item.artwork!=null && !item.artwork.isRecycled()) {
            int save=canvas.save();
            playbackClip.reset();
            iconBounds.set(126,206,216,296);
            playbackClip.addRoundRect(iconBounds,14,14,Path.Direction.CW);
            canvas.clipPath(playbackClip);
            artworkPaint.setAlpha(Math.round(255*opacity));
            canvas.drawBitmap(item.artwork,null,iconBounds,artworkPaint);
            canvas.restoreToCount(save);
        } else icon(canvas,item.playing?"\ue037":"\ue034",171,251,44,foreground);
        if(!Objects.equals(item.title,lastPlaybackTitle)){
            lastPlaybackTitle=item.title;
            clippedPlaybackTitle=clippedLabel(item.title==null?"":item.title,440,26);
        }
        if(!Objects.equals(item.source,lastPlaybackSource)){
            lastPlaybackSource=item.source;
            clippedPlaybackSource=clippedLabel(item.source==null?"":item.source,440,18);
        }
        text(canvas,item.playing?"正在播放":"已暂停",238,225,18,bold,foreground,Paint.Align.LEFT);
        text(canvas,clippedPlaybackTitle,238,258,26,bold,foreground,Paint.Align.LEFT);
        text(canvas,clippedPlaybackSource,238,287,18,normal,alphaColor(row==-2?0xB017232D:0xB0F1F5F8,opacity),Paint.Align.LEFT);
    }

    private void drawEdge(Canvas canvas,float opacity){
        float a=Math.max(0,Math.min(1,edge.value))*opacity;
        canvas.save();
        canvas.translate(-80*(1-edge.value),0);
        paint.setColor(alphaColor(0xEE17222E,a));
        rect.set(70,390,438,953);
        canvas.drawRoundRect(rect,28,28,paint);
        text(canvas,rows.get(Math.max(0,row)).title,100,438,25,bold,alphaColor(0xFFDCE8EE,a),Paint.Align.LEFT);
        for(int i=0;i<edgeItems.length;i++){
            float y=465+i*78;
            if(i==edgeSelected){
                paint.setColor(alphaColor(0xFFE4F0F5,a));
                rect.set(88,y,420,y+65);
                canvas.drawRoundRect(rect,16,16,paint);
            }
            text(canvas,edgeItems[i],110,y+42,25,normal,alphaColor(i==edgeSelected?0xFF152331:0xFFCDD7E0,a),Paint.Align.LEFT);
        }
        text(canvas,"右键返回卡片",100,916,19,normal,alphaColor(0xFFA1B3C3,a),Paint.Align.LEFT);
        canvas.restore();
    }

    private void text(Canvas canvas, String text, float x, float y, float size, Typeface font, int color, Paint.Align align) {
        paint.setColor(color);
        paint.setTypeface(font);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        canvas.drawText(text, x, y, paint);
    }

    private void settingsIcon(Canvas canvas,float x,float y,int color){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.2f);
        canvas.drawCircle(0,0,9.5f,paint);
        paint.setStrokeWidth(3.6f);
        for(int i=0;i<8;i++){
            double rad=i*Math.PI/4.0;
            float cos=(float)Math.cos(rad),sin=(float)Math.sin(rad);
            canvas.drawLine(cos*11.6f,sin*11.6f,cos*15.4f,sin*15.4f,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0,0,3.6f,paint);
        canvas.restore();
    }
    private void magicIcon(Canvas canvas,float x,float y,int color){
        float spread=Math.round(2*Math.max(0,Math.min(1,magicFocus.value)));
        float alpha=Color.alpha(color)/255f;
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        magicPaint.setShader(null);
        magicPaint.setStrokeCap(Paint.Cap.ROUND);
        magicPaint.setStrokeJoin(Paint.Join.ROUND);
        // Back card (clean open stroke so it never muddies behind the front card)
        magicPaint.setStyle(Paint.Style.STROKE);
        magicPaint.setStrokeWidth(2.6f);
        magicPaint.setColor(alphaColor(0xBBFFFFFF,alpha));
        float bx1=-18-spread,by1=-13-spread,bx2=10-spread,by2=7-spread;
        iconPath.reset();
        iconPath.moveTo(-10+spread,by2);
        iconPath.lineTo(bx1+4,by2);
        iconPath.quadTo(bx1,by2,bx1,by2-4);
        iconPath.lineTo(bx1,by1+4);
        iconPath.quadTo(bx1,by1,bx1+4,by1);
        iconPath.lineTo(bx2-4,by1);
        iconPath.quadTo(bx2,by1,bx2,by1+4);
        iconPath.lineTo(bx2,-7+spread);
        canvas.drawPath(iconPath,magicPaint);
        // Front glass card with crisp stroke and sharp 4-point sparkle
        rect.set(-9+spread,-6+spread,18+spread,14+spread);
        magicPaint.setStyle(Paint.Style.FILL);
        magicPaint.setColor(alphaColor(0x308CD6F0,alpha));
        canvas.drawRoundRect(rect,4.5f,4.5f,magicPaint);
        magicPaint.setStyle(Paint.Style.STROKE);
        magicPaint.setStrokeWidth(2.6f);
        magicPaint.setColor(color);
        canvas.drawRoundRect(rect,4.5f,4.5f,magicPaint);
        // Crisp 4-point star sparkle centered on front card
        float sx=4.5f+spread,sy=4f+spread,sr=5.2f,si=1.7f;
        iconPath.reset();
        iconPath.moveTo(sx,sy-sr);
        iconPath.lineTo(sx+si,sy-si);
        iconPath.lineTo(sx+sr,sy);
        iconPath.lineTo(sx+si,sy+si);
        iconPath.lineTo(sx,sy+sr);
        iconPath.lineTo(sx-si,sy+si);
        iconPath.lineTo(sx-sr,sy);
        iconPath.lineTo(sx-si,sy-si);
        iconPath.close();
        magicPaint.setStyle(Paint.Style.FILL);
        magicPaint.setColor(color);
        canvas.drawPath(iconPath,magicPaint);
        canvas.restore();
    }
    private void connectIcon(Canvas canvas,float x,float y,int color){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // Open-path TV display leaving clean space for the overlapping phone (no blurry clipRect)
        iconPath.reset();
        iconPath.moveTo(2,7);
        iconPath.lineTo(-17,7);
        iconPath.quadTo(-20,7,-20,4);
        iconPath.lineTo(-20,-10);
        iconPath.quadTo(-20,-13,-17,-13);
        iconPath.lineTo(8,-13);
        iconPath.quadTo(11,-13,11,-10);
        iconPath.lineTo(11,-8);
        canvas.drawPath(iconPath,paint);
        // Crisp TV stand
        canvas.drawLine(-7,7,-7,12,paint);
        canvas.drawLine(-13,12,-1,12,paint);
        // Crisp phone outline
        rect.set(5,-6,20,15);
        canvas.drawRoundRect(rect,3.8f,3.8f,paint);
        // Crisp speaker & home pill
        paint.setStyle(Paint.Style.FILL);
        rect.set(10,-3.2f,15,-1.4f);
        canvas.drawRoundRect(rect,1f,1f,paint);
        rect.set(9.5f,10.8f,15.5f,12.4f);
        canvas.drawRoundRect(rect,1f,1f,paint);
        canvas.restore();
    }
    private void allAppsIcon(Canvas canvas,float x,float y,int color){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        float[] pos={-13,-3,7};
        for(float py:pos)for(float px:pos){
            rect.set(px,py,px+6,py+6);
            canvas.drawRoundRect(rect,1.8f,1.8f,paint);
        }
        canvas.restore();
    }
    private void wifiIcon(Canvas canvas,float x,float y,int color){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0,9,2.8f,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for(int i=1;i<=3;i++){
            float rad=4+i*6.2f;
            rect.set(-rad,9-rad,rad,9+rad);
            canvas.drawArc(rect,228,84,false,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }
    private void drawShelfIcon(Canvas canvas,String orderKey,float x,float y,float opacity){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        // Sleek frosted glass squircle badge aligned flush at x=112
        rect.set(-18,-18,18,18);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alphaColor(0x28FFFFFF,opacity));
        canvas.drawRoundRect(rect,10,10,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(alphaColor(0x4EFFFFFF,opacity));
        rect.inset(.75f,.75f);
        canvas.drawRoundRect(rect,9.5f,9.5f,paint);
        paint.setStyle(Paint.Style.FILL);
        if("order_tv".equals(orderKey)){
            // Modern 2x2 rounded app deck: 3 crisp white tiles + 1 bright accent tile
            int white=alphaColor(Color.WHITE,opacity);
            int accent=alphaColor(0xFF7AD8FF,opacity);
            paint.setColor(white);
            rect.set(-9,-9,-1.5f,-1.5f);
            canvas.drawRoundRect(rect,2.4f,2.4f,paint);
            rect.set(-9,1.5f,-1.5f,9);
            canvas.drawRoundRect(rect,2.4f,2.4f,paint);
            rect.set(1.5f,1.5f,9,9);
            canvas.drawRoundRect(rect,2.4f,2.4f,paint);
            paint.setColor(accent);
            rect.set(1.5f,-9,9,-1.5f);
            canvas.drawRoundRect(rect,2.4f,2.4f,paint);
        }else if("order_favorites".equals(orderKey)){
            // Crisp 5-point star
            paint.setColor(alphaColor(0xFFFFE082,opacity));
            iconPath.reset();
            for(int i=0;i<10;i++){
                double ang=-Math.PI/2+i*Math.PI/5.0;
                float r=(i%2==0)?10.5f:4.6f;
                float px=(float)(Math.cos(ang)*r),py=(float)(Math.sin(ang)*r)+.6f;
                if(i==0)iconPath.moveTo(px,py);
                else iconPath.lineTo(px,py);
            }
            iconPath.close();
            canvas.drawPath(iconPath,paint);
        }else if("order_inputs".equals(orderKey)){
            // Clean signal-in screen icon
            paint.setColor(alphaColor(Color.WHITE,opacity));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            iconPath.reset();
            iconPath.moveTo(-4,-8);
            iconPath.lineTo(7,-8);
            iconPath.quadTo(10,-8,10,-5);
            iconPath.lineTo(10,5);
            iconPath.quadTo(10,8,7,8);
            iconPath.lineTo(-4,8);
            canvas.drawPath(iconPath,paint);
            canvas.drawLine(-10,0,3,0,paint);
            canvas.drawLine(-1,-4,3,0,paint);
            canvas.drawLine(-1,4,3,0,paint);
            paint.setStyle(Paint.Style.FILL);
        }else{
            // Clean 3x3 mini grid for other apps
            paint.setColor(alphaColor(Color.WHITE,opacity));
            float[] p={-8.5f,-2,4.5f};
            for(float py:p)for(float px:p){
                rect.set(px,py,px+4,py+4);
                canvas.drawRoundRect(rect,1.2f,1.2f,paint);
            }
        }
        canvas.restore();
    }
    private void icon(Canvas canvas, String glyph, float x, float y, float size, int color) {
        paint.setTypeface(symbols);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color);
        canvas.drawText(glyph, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    float cardHeight(){
        return 1.8f*Math.max(70,Math.min(130,host.appearance().getInt("card_height",100)));
    }
    float shelfCenterY(){
        float extra=cardHeight()/2-90,position=Math.max(0,Math.min(100,host.appearance().getInt("card_row_position",75)))/100f;
        return 495+extra+(220-2*extra)*position;
    }
    boolean inShelfTouchBand(float y){
        return y>shelfCenterY()-cardHeight()/2-50&&y<shelfCenterY()+cardHeight()/2+60;
    }

    private void drawRow(Canvas canvas, Row r, float y, float opacity) {
        float arrival=Math.max(0,Math.min(1,entrance.value));
        y+=30*(1-arrival);
        opacity*=.65f+.35f*arrival;
        drawShelfIcon(canvas, r.orderKey, 130, y - cardHeight()/2-86, opacity);
        text(canvas, r.title, 166, y - cardHeight()/2-75, 34, bold, alphaColor(Color.WHITE, opacity), Paint.Align.LEFT);
        if(r.tiles.isEmpty())text(canvas,"暂无应用，按左键展开菜单添加",112,y,28,normal,alphaColor(0xC0FFFFFF,opacity),Paint.Align.LEFT);
        int raised = -1;
        for (int i = 0; i < r.tiles.size(); i++) if (raised < 0 || r.tiles.get(i).emphasis.value > r.tiles.get(raised).emphasis.value) raised = i;
        for (int i = 0; i < r.tiles.size(); i++) if (i != raised) drawTile(canvas, r.tiles.get(i), 272 + i * 350 - r.scroll.value, y, opacity);
        if (raised >= 0) drawTile(canvas, r.tiles.get(raised), 272 + raised * 350 - r.scroll.value, y, opacity);
    }

    private void drawTile(Canvas canvas, Tile tile, float x, float shelfY, float opacity) {
        if (x < -250 || x > 2180) return;
        float emphasis = clampedEmphasis(tile);
        float zoom = (1 + focusScale * emphasis)*(1-.035f*Math.max(0,Math.min(1,tile.press.value)));
        float y = shelfY - 14 * emphasis;
        float baseHeight=cardHeight(),contentZoom=zoom*Math.min(1,baseHeight/180);
        float w = 320 * zoom, h = baseHeight * zoom;
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        if(surfaceStrength>0){
            // Several translucent rounded silhouettes avoid GPU blur and hard clip-path edges.
            for(int i=3;i>=1;i--){
                surfaceBounds.set(rect);
                surfaceBounds.offset(0,(5+i*2+5*emphasis)*zoom);
                surfaceBounds.inset(-i*1.5f*zoom,-i*1.5f*zoom);
                surfacePaint.setColor(alphaColor(0x1A030812,opacity*surfaceStrength*(.6f+.4f*emphasis)));
                canvas.drawRoundRect(surfaceBounds,radius*zoom+i*zoom,radius*zoom+i*zoom,surfacePaint);
            }
        }
        paint.setColor(alphaColor(tile.tint != 0 ? tile.tint : tile.banner ? Color.BLACK : 0xFF243541, opacity));
        canvas.drawRoundRect(rect, radius * zoom, radius * zoom, paint);
        if (tile.artwork != null) {
            if (tile.banner) {
                if (tile.shader == null) tile.shader = new BitmapShader(tile.artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float fit=Math.max(rect.width()/tile.artwork.getWidth(),rect.height()/tile.artwork.getHeight());
                artworkMatrix.setScale(fit,fit);
                artworkMatrix.postTranslate(rect.centerX()-tile.artwork.getWidth()*fit/2,rect.centerY()-tile.artwork.getHeight()*fit/2);
                tile.shader.setLocalMatrix(artworkMatrix);
                artworkPaint.setShader(tile.shader);
                artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawRoundRect(rect, radius * zoom, radius * zoom, artworkPaint);
                artworkPaint.setShader(null);
            } else {
                float size = 116 * contentZoom;
                iconBounds.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
                artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawBitmap(tile.artwork, null, iconBounds, artworkPaint);
            }
        } else if (tile.tint != 0) {
            icon(canvas, tile.glyph, x, y - 20 * contentZoom, 60 * contentZoom, alphaColor(0xB0FFFFFF, opacity));
            if (tile.coverLabel == null) tile.coverLabel = clippedLabel(tile.label, 235, 28);
            text(canvas, tile.coverLabel, x, y + 48 * contentZoom, 28 * contentZoom, bold, alphaColor(Color.WHITE, opacity), Paint.Align.CENTER);
        } else icon(canvas, tile.kind == 1 ? "\ue333" : "\ue859", x, y, 112 * contentZoom, alphaColor(Color.WHITE, opacity));
        if(surfaceStrength>0){
            surfaceMatrix.setScale(rect.width(),rect.height());
            surfaceMatrix.postTranslate(rect.left,rect.top);
            surfaceGradient.setLocalMatrix(surfaceMatrix);
            surfacePaint.setShader(surfaceGradient);
            surfacePaint.setAlpha(Math.round(255*opacity*surfaceStrength));
            canvas.drawRoundRect(rect,radius*zoom,radius*zoom,surfacePaint);
            surfacePaint.setShader(null);
            // Draw the highlight inside the antialiased shape so it never clips outside the card.
            surfaceBounds.set(rect);
            surfaceBounds.inset(1.3f*zoom,1.3f*zoom);
            rimGradient.setLocalMatrix(surfaceMatrix);
            surfacePaint.setShader(rimGradient);
            surfacePaint.setStyle(Paint.Style.STROKE);
            surfacePaint.setStrokeWidth((1+1.2f*emphasis)*zoom);
            surfacePaint.setAlpha(Math.round(255*opacity*surfaceStrength*(.3f+.7f*emphasis)));
            canvas.drawRoundRect(surfaceBounds,Math.max(0,radius*zoom-1.3f*zoom),Math.max(0,radius*zoom-1.3f*zoom),surfacePaint);
            surfacePaint.setShader(null);
            surfacePaint.setStyle(Paint.Style.FILL);
        }
        if(tile.pinned) {
            paint.setColor(alphaColor(0xBB13202B,opacity));
            canvas.drawCircle(x+w/2-28*zoom,y-h/2+28*zoom,17*zoom,paint);
            icon(canvas,"\ue838",x+w/2-28*zoom,y-h/2+28*zoom,22*zoom,alphaColor(0xFFFFD68A,opacity));
        }
        if (emphasis > .005f || labelOpacity > 0) {
            if (tile.displayLabel == null) {
                tile.displayLabel = clippedLabel(tile.label, 300, 36);
            }
            text(canvas, tile.displayLabel, Math.max(112, x - 160), shelfY + baseHeight/2+98-6*emphasis, 36, bold,
                    alphaColor(Color.WHITE, opacity * (labelOpacity + (1-labelOpacity) * emphasis)), Paint.Align.LEFT);
        }
    }

    private String clippedLabel(String value, float width, float size) {
        paint.setTypeface(bold);
        paint.setTextSize(size);
        if (paint.measureText(value) <= width) return value;
        int end = value.length();
        while (end > 0 && paint.measureText(value.substring(0, end) + "…") > width)
                end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end) + "…";
    }

    private void pressSelected(boolean down){
        for(int i=0;i<rows.size();i++){
            Row r=rows.get(i);
            for(int j=0;j<r.tiles.size();j++)r.tiles.get(j).press.target(down&&row==i&&r.selected==j?1:0);
        }
        animateFocus();
    }
    static boolean isConfirmKey(int code){
        return code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER
                ||code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_BUTTON_A;
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if(host.recents.visible)return host.recents.key(code,event);
        boolean navigation = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || isConfirmKey(code)
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
        if (!navigation) return super.onKeyDown(code, event);
        if (idleMode) {
            wakingKey = true;
            setIdle(false);
            return true;
        }
        host.armIdle();
        if(mover.active())return mover.keyDown(code);
        if(edgeOpen){
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_BACK)edge(false);
            else if(code==KeyEvent.KEYCODE_DPAD_UP)edgeSelected=Math.max(0,edgeSelected-1);
            else if(code==KeyEvent.KEYCODE_DPAD_DOWN)edgeSelected=Math.min(edgeItems.length-1,edgeSelected+1);
            else if(code==KeyEvent.KEYCODE_MENU){
                edge(false);
                host.settings();
            }
            if(edgeOpen)setContentDescription("分类操作栏，"+edgeItems[edgeSelected]);
            else describeFocus();
            invalidate();
            return true;
        }
        if (isConfirmKey(code)) {
            if (event.getRepeatCount() == 0) {
                longPressed = false;
                event.startTracking();
                pressSelected(true);
            }
            else if ((event.isLongPress() || event.getRepeatCount() >= 3) && !longPressed && row >= 0) {
                longPressed = true;
                pressSelected(false);
                host.tileOptions();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) host.settings();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (row != 0) {
                row = 0;
                ensureVisible();
                animateFocus();
            } else setIdle(true);
            return true;
        }
        if (rows.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                row = -1;
                invalidate();
            }
            return true;
        }
        if (row == -2) {
            if(code==KeyEvent.KEYCODE_DPAD_UP)row=-1;
            else if(code==KeyEvent.KEYCODE_DPAD_DOWN)row=0;
            describeFocus();
            ensureVisible();
            animateFocus();
            invalidate();
            return true;
        }
        if (row == -1) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = host.playback.item==null?0:-2;
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) topSelected = Math.max(0,topSelected-1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) topSelected = Math.min(4,topSelected+1);
            ensureVisible();
            animateFocus();
        } else {
            Row r = rows.get(row);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if(r.selected==0&&event.getRepeatCount()==0){
                    edgeSelected=0;
                    edge(true);
                    setContentDescription("分类操作栏，"+edgeItems[0]);
                    return true;
                }
                r.selected=Math.max(0,r.selected-1);
            }
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) r.selected = Math.max(0,Math.min(r.tiles.size() - 1, r.selected + 1));
            else if (code == KeyEvent.KEYCODE_DPAD_UP) row=row==0&&host.playback.item!=null?-2:row-1;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = Math.min(rows.size() - 1, row + 1);
            ensureVisible();
            animateFocus();
        }
        describeFocus();
        if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty()) {
            String label = rows.get(row).tiles.get(rows.get(row).selected).label;
            setContentDescription(rows.get(row).title + "，" + label);
            announceForAccessibility(label);
        }
        invalidate();
        return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if(host.recents.visible)return host.recents.key(code,event);
        if(mover.swallowKeyUp())return true;
        if (wakingKey) {
            wakingKey = false;
            return true;
        }
        if(edgeOpen&&isConfirmKey(code)){
            edgeAction();
            return true;
        }
        if (isConfirmKey(code)) {
            pressSelected(false);
            if (longPressed) {
                longPressed = false;
                return true;
            }
            if (row == -2) host.playback.controls();
            else if (row == -1) {
                if (topSelected == 0) host.settings();
                else if(topSelected==1)host.phone.show();
                else if(topSelected==2)host.navigator.personalization("cards");
                else if(topSelected==3)host.drawer.show();
                else host.network.show();
            }
            else if (!rows.isEmpty()) {
                Row r = rows.get(row);
                if (!r.tiles.isEmpty()) host.launch(r.tiles.get(r.selected));
            }
            return true;
        }
        return super.onKeyUp(code, event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if(host.recents.visible){
            host.recents.touch(event.getAction(),(event.getX()-(getWidth()-1920*scale)/2)/scale,(event.getY()-offsetY)/scale);
            return true;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (idleMode) {
            setIdle(false);
            return true;
        }
        if(mover.active()){
            mover.finish(true);
            return true;
        }
        host.armIdle();
        float x = (event.getX() - (getWidth() - 1920 * scale) / 2) / scale;
        float y = (event.getY() - offsetY) / scale;
        if(edgeOpen){
            if(x>=88&&x<=420&&y>=465&&y<465+edgeItems.length*78){
                edgeSelected=Math.min(edgeItems.length-1,(int)((y-465)/78));
                edgeAction();
            }
            else edge(false);
            return true;
        }
        if(y<175&&x<clockScene.left(host.appearance())-112){
            float local=HEADER_ANCHOR_X+(x-HEADER_ANCHOR_X)/headerFit();
            int entry=-1;
            for(int i=0;i<4;i++){
                float leftBound=i==0?0:(headerContentRight[i-1]+headerContentLeft[i])*.5f;
                float rightBound=i==3?headerTotalRight+24f:(headerContentRight[i]+headerContentLeft[i+1])*.5f;
                if(local>=leftBound&&local<rightBound){
                    entry=i;
                    break;
                }
            }
            if(entry>=0){
                topSelected=entry;
                row=-1;
                animateFocus();
                if(entry==0)host.settings();
                else if(entry==1)host.phone.show();
                else if(entry==2)host.navigator.personalization("cards");
                else host.drawer.show();
                return true;
            }
        }
        if (y < 205 && Math.abs(x-(clockScene.left(host.appearance())-70)) < 65) {
            host.network.show();
            return true;
        }
        if(y>=192&&y<=310&&x>=112&&x<=742&&host.playback.item!=null){
            row=-2;
            describeFocus();
            host.playback.controls();
            return true;
        }
        if (!rows.isEmpty() && inShelfTouchBand(y)) {
            Row r = rows.get(Math.max(0, row));
            int index = Math.round((x - 272 + r.scroll.value) / 350);
            if (index >= 0 && index < r.tiles.size()) {
                if (row >= 0 && index == r.selected) host.launch(r.tiles.get(index));
                else {
                    row = Math.max(0, row);
                    r.selected = index;
                    ensureVisible();
                    animateFocus();
                }
            }
        }
        return true;
    }
}
