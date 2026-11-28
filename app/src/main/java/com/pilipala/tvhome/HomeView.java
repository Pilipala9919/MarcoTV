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
    float displayLabelWidth, coverLabelWidth;
    int customWidth;
    int customHeight;
    int customDepth = -1;
    int customRadius = -1;
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
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
    ArrayList<Row> rows = new ArrayList<>();
    Bitmap wallpaper;
    int row = 0;
    final HomeHeaderMenu headerMenu = new HomeHeaderMenu();
    private float uiAlpha = 1, ambientProgress;
    private final ClockScene clockScene;
    private float wallTopLeftLum = 0.16f, wallTopRightLum = 0.16f, wallShelfLum = 0.16f;
    private boolean desktopBrightShelf;
    private WeatherSnapshot currentWeather;
    void setWallpaperLuminance(float topLeft, float topRight, float shelf) {
        wallTopLeftLum = topLeft;
        wallTopRightLum = topRight;
        wallShelfLum = shelf;
        clockScene.setTopRightLuminance(topRight);
        invalidate();
    }
    void setWeather(WeatherSnapshot weather) {
        currentWeather = weather;
        invalidate();
    }
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
    private final Path headerClipPath = new Path();
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
    private final Paint shadowPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private static final float SHADOW_PAD = 80f;
    private static final float SHADOW_SCALE = 0.5f;
    private static final float CARD_GAP = 30f;
    private static final float SHELF_START_X = 112f;
    private static final int MAX_SHADOW_CACHE = 12;
    private final LinkedHashMap<Long, Bitmap[]> shadowCache = new LinkedHashMap<Long, Bitmap[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap[]> eldest) {
            if (size() > MAX_SHADOW_CACHE) {
                Bitmap[] pair = eldest.getValue();
                if (pair != null) {
                    for (Bitmap b : pair) if (b != null && !b.isRecycled()) b.recycle();
                }
                return true;
            }
            return false;
        }
    };
    private final Matrix surfaceMatrix=new Matrix();
    private final RectF surfaceBounds=new RectF();
    private final LinearGradient surfaceGradient=new LinearGradient(0,0,0,1,
            new int[]{0x22FFFFFF,0x0CFFFFFF,0x00000000,0x00000000,0x28040A14},new float[]{0,.14f,.28f,.62f,1},Shader.TileMode.CLAMP);
    private final LinearGradient sheenGradient=new LinearGradient(0,0,.78f,.56f,
            new int[]{0x24FFFFFF,0x0AFFFFFF,0x00000000},new float[]{0,.34f,1},Shader.TileMode.CLAMP);
    private final LinearGradient rimGradient=new LinearGradient(0,0,0,1,
            new int[]{0xF4FFFFFF,0x76FFFFFF,0x34FFFFFF},new float[]{0,.45f,1},Shader.TileMode.CLAMP);
    private final LinearGradient depthSlabGradient=new LinearGradient(0,0,1,0,
            new int[]{0x38FFFFFF,0x0CFFFFFF,0x22FFFFFF,0x08FFFFFF,0x34FFFFFF},new float[]{0f,.18f,.50f,.82f,1f},Shader.TileMode.CLAMP);
    private final MotionSpring entrance=new MotionSpring(1,15,.001f);
    private final MotionSpring edge=new MotionSpring(0,20,.001f);
    private final MotionSpring headerMenuExpand=new MotionSpring(0,22,.001f);
    private final MotionSpring[] headerFocus={
        new MotionSpring(0,20,.001f),
        new MotionSpring(0,20,.001f),
        new MotionSpring(0,20,.001f),
        new MotionSpring(0,20,.001f),
        new MotionSpring(0,20,.001f)
    };
    private final Paint magicPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private boolean edgeOpen;
    private int edgeSelected;
    private final String[] edgeItems={"添加应用","编辑当前卡片","卡片外观","时钟与屏保","桌面设置"};
    void edge(boolean open){
        if(open&&headerMenu.isExpanded()){
            headerMenu.collapseToBrand();
            headerMenuExpand.target(0);
        }
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
    private UiMotion previewAmbientMotion, previewRecentMotion, posterRevealMotion;
    private boolean liveVideoReady;
    private float posterOverlayAlpha = 1f;
    boolean previewDirty=true;
    private final MotionSpring headerLeft=new MotionSpring(88,26,.05f),headerRight=new MotionSpring(302,26,.05f),headerAlpha=new MotionSpring(0,
            26,.001f);
    private static final float HEADER_ANCHOR_X=88f;
    private static final float HEADER_START_X=112f;
    private static final float HEADER_ICON_GAP=14f;
    private static final float HEADER_SUB_ICON_GAP=12f;
    private static final float HEADER_ITEM_GAP=48f;
    private static final float HEADER_PILL_PAD_X=22f;
    private final float[] headerIconX=new float[HomeHeaderMenu.LEFT_SLOT_COUNT];
    private final float[] headerTextX=new float[HomeHeaderMenu.LEFT_SLOT_COUNT];
    private final float[] headerContentLeft=new float[HomeHeaderMenu.LEFT_SLOT_COUNT];
    private final float[] headerContentRight=new float[HomeHeaderMenu.LEFT_SLOT_COUNT];
    private float headerChevronX=272f;
    private float headerCollapsedRight=304f;
    private float headerDividerX=318f;
    private float headerTotalRight=1160f;
    private void measureHeader(){
        // Slot 0: Collapsible MarcoTV brand trigger
        headerContentLeft[0]=Math.round(HEADER_START_X);
        headerIconX[0]=Math.round(headerContentLeft[0]+17f);
        headerTextX[0]=Math.round(headerIconX[0]+17f+HEADER_ICON_GAP);
        paint.setTypeface(bold);
        paint.setTextSize(23);
        float brandWidth=paint.measureText(HomeHeaderMenu.BRAND_LABEL);
        headerChevronX=Math.round(headerTextX[0]+brandWidth+15f);
        headerContentRight[0]=Math.round(headerChevronX+8f);
        headerCollapsedRight=headerContentRight[0]+HEADER_PILL_PAD_X;
        headerDividerX=Math.round(headerCollapsedRight+14f);

        // Slots 1..4: 桌面设置, Marco互联, MagicDesktop, 应用管理
        float[] iconHalf={15f,20f,18f,14f};
        float cursor=headerDividerX+28f;
        paint.setTypeface(normal);
        paint.setTextSize(23);
        for(int i=1;i<HomeHeaderMenu.LEFT_SLOT_COUNT;i++){
            headerContentLeft[i]=Math.round(cursor);
            headerIconX[i]=Math.round(headerContentLeft[i]+iconHalf[i-1]);
            headerTextX[i]=Math.round(headerIconX[i]+iconHalf[i-1]+HEADER_SUB_ICON_GAP);
            float textWidth=paint.measureText(HomeHeaderMenu.EXPANDED_LABELS[i-1]);
            headerContentRight[i]=Math.round(headerTextX[i]+textWidth);
            cursor=headerContentRight[i]+HEADER_ITEM_GAP;
        }
        headerTotalRight=headerContentRight[HomeHeaderMenu.LEFT_SLOT_COUNT-1]+HEADER_PILL_PAD_X;
    }
    private float headerFit(){
        float available=clockScene.left(host.appearance())-132f-HEADER_ANCHOR_X;
        float needed=Math.max(1f,headerTotalRight-HEADER_ANCHOR_X);
        return Math.min(1f,Math.max(.72f,available/needed));
    }
    private float headerPosition(float x){
        return HEADER_ANCHOR_X+(x-HEADER_ANCHOR_X)*headerFit();
    }
    private void previewTransform(float value){
        overview=value;
        if(overviewRenderer.hasStage()){
            overviewRenderer.applyStage(value);
        }else{
            super.invalidate();
        }
    }
    void setLiveVideoReady(boolean ready){
        if(liveVideoReady==ready)return;
        liveVideoReady=ready;
        android.util.Log.i("MarcoTV", "HomeView poster overlay ready=" + ready + " posterAlpha=" + posterOverlayAlpha);
        if(posterRevealMotion==null){
            posterRevealMotion=new UiMotion(this,posterOverlayAlpha,18,v->{
                posterOverlayAlpha=Math.max(0f,Math.min(1f,v));
                super.invalidate();
            });
        }
        if(ready){
            posterRevealMotion.to(0f);
        }else{
            posterRevealMotion.snap(1f);
            super.invalidate();
        }
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
    private float previewAmbient, previewRecent;
    ValueAnimator previewFade;
    private int previewSavedRow=-2;
    void preview(String key){
        boolean wasNull=previewKey==null;
        boolean nowNull=key==null;
        if(wasNull!=nowNull){
            if(overviewAnimation==null)overviewAnimation=new UiMotion(this,overview,26,this::previewTransform);
            if(!nowNull&&overview<=0.001f){
                // Start the 3D entrance on the next vsync so dialog inflation/layout never eats frame 1.
                postOnAnimation(()->{
                    if(previewKey!=null)overviewAnimation.to(1);
                });
            }else{
                overviewAnimation.to(nowNull?0:1);
            }
        }
        if(key!=null&&previewSavedRow==-2){
            previewSavedRow=row;
            if(headerMenu.isExpanded()){
                headerMenu.collapseToBrand();
                headerMenuExpand.snap(0);
            }
            if(row<0){
                row=0;
                animateFocus();
            }
        }
        else if(key==null&&previewSavedRow!=-2){
            int restore=previewSavedRow;
            previewSavedRow=-2;
            if(row!=restore){
                row=restore;
                animateFocus();
            }
        }
        boolean wantRecent=key!=null&&key.startsWith("recent_");
        boolean wantAmbient=key!=null&&key.startsWith("ambient_");
        boolean changedScene=wasNull||nowNull
                ||previewKey.startsWith("recent_")!=wantRecent
                ||previewKey.startsWith("ambient_")!=wantAmbient;
        previewKey=key;
        if(previewFade!=null)previewFade.cancel();
        if(previewAmbientMotion==null){
            previewAmbientMotion=new UiMotion(this,previewAmbient,20,v->{
                previewAmbient=Math.max(0f,Math.min(1f,v));
                super.invalidate();
            });
        }
        if(previewRecentMotion==null){
            previewRecentMotion=new UiMotion(this,previewRecent,22,v->{
                previewRecent=Math.max(0f,Math.min(1f,v));
                super.invalidate();
            });
        }
        if("ambient_transition".equals(key)){
            previewAmbientMotion.cancel();
            previewRecentMotion.to(0f);
            replayPreview();
            return;
        }
        if(wasNull||nowNull){
            previewAmbientMotion.snap(wantAmbient?1f:0f);
            previewRecentMotion.snap(wantRecent?1f:0f);
        }else{
            previewAmbientMotion.to(wantAmbient?1f:0f);
            previewRecentMotion.to(wantRecent?1f:0f);
        }
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
        else if(row==-1)setContentDescription(headerMenu.describeSelected());
        else if (rows.isEmpty()) setContentDescription("Marco TV，暂无可见卡片，按菜单键恢复隐藏的卡片");
        else if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty())
                setContentDescription(rows.get(row).title + "，" + rows.get(row).tiles.get(rows.get(row).selected).label);
        else setContentDescription("我的应用，暂无应用，按左键展开菜单添加应用");
    }

    void resetToApps() {
        mover.reset();
        edgeOpen=false;
        edge.snap(0);
        headerMenu.collapseToBrand();
        headerMenuExpand.snap(0);
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
        if (value && headerMenu.isExpanded()) {
            headerMenu.collapseToBrand();
            headerMenuExpand.snap(0);
        }
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
        int sel=headerMenu.selected();
        float left=sel<HomeHeaderMenu.SLOT_WIFI?headerPosition(headerContentLeft[sel]-HEADER_PILL_PAD_X):clockScene.left(host.appearance())-102;
        float right=sel<HomeHeaderMenu.SLOT_WIFI?headerPosition(headerContentRight[sel]+HEADER_PILL_PAD_X):clockScene.left(host.appearance())-38;
        if(headerAlpha.value==0){
            headerLeft.snap(left);
            headerRight.snap(right);
        }else{
            headerLeft.target(left);
            headerRight.target(right);
        }
        headerAlpha.target(row==-1?1:0);
        headerMenuExpand.target(row==-1&&headerMenu.isExpanded()?1:0);
        section.target(Math.max(0, row));
        for(int i=0;i<headerFocus.length;i++)headerFocus[i].target(row==-1&&sel==i?1:0);
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
        moving|=headerMenuExpand.step(dt);
        for(MotionSpring focus:headerFocus)moving|=focus.step(dt);
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
        if(overviewAnimation!=null){overviewAnimation.dispose();overviewAnimation=null;}
        if(previewAmbientMotion!=null){previewAmbientMotion.dispose();previewAmbientMotion=null;}
        if(previewRecentMotion!=null){previewRecentMotion.dispose();previewRecentMotion=null;}
        if(posterRevealMotion!=null){posterRevealMotion.dispose();posterRevealMotion=null;}
        clockScene.release();
        overviewRenderer.detach();
        recycleShadows();
        super.onDetachedFromWindow();
    }

    private float clampedEmphasis(Tile t) {
        return Math.max(0, Math.min(1, t.emphasis.value));
    }

    void ensureVisible() {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        if (r.tiles.isEmpty()) {
            r.scroll.target(0);
            return;
        }
        int sel = Math.max(0, Math.min(r.selected, r.tiles.size() - 1));
        float target = 0f;
        if (sel > 0) {
            float prevLeft = tileCenterX(r, sel - 1) - tileWidth(r.tiles.get(sel - 1)) * 0.5f;
            target = Math.max(0f, prevLeft - SHELF_START_X);
        }
        float selW = tileWidth(r.tiles.get(sel));
        float selRight = tileCenterX(r, sel) + selW * 0.5f;
        if (selRight - target > 1920f - SHELF_START_X) {
            target = Math.max(target, selRight - (1920f - SHELF_START_X));
        }
        r.scroll.target(target);
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
        if (!liveVideoReady || posterOverlayAlpha > 0.005f) {
            int posterAlpha = liveVideoReady ? Math.round(255 * posterOverlayAlpha) : 255;
            if (wallpaper != null) {
                float factor = Math.max(getWidth() / (float) wallpaper.getWidth(), getHeight() / (float) wallpaper.getHeight());
                float w = wallpaper.getWidth() * factor, h = wallpaper.getHeight() * factor;
                rect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
                paint.setAlpha(posterAlpha);
                canvas.drawBitmap(wallpaper, null, rect, paint);
                paint.setAlpha(255);
            } else if (posterAlpha >= 254) {
                canvas.drawColor(Color.BLACK);
            } else {
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(posterAlpha, 0, 0, 0));
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }
        canvas.save();
        canvas.translate((getWidth() - 1920 * scale) / 2, offsetY);
        canvas.scale(scale, scale);
        if(host.recents.visible){
            host.recents.draw(canvas,false,1f);
            canvas.restore();
            return;
        }
        if(previewKey!=null&&previewRecent>=0.995f){
            host.recents.draw(canvas,true,1f);
            canvas.restore();
            return;
        }
        float desktopVis = previewKey!=null ? (1f - previewRecent) : 1f;
        int dimAlpha = Math.round(ClockScene.dim(host.appearance(), ambient) * desktopVis);
        if (dimAlpha > 0) {
            paint.setColor(Color.argb(dimAlpha, 0, 0, 0));
            canvas.drawRect(0, 0, 1920, 1080, paint);
        }
        android.content.SharedPreferences appearance = host.appearance();
        int dimPct = appearance.getInt("wallpaper_dim", 10);
        boolean brightHeader = DesktopContrast.isBright(DesktopContrast.effectiveLuminance(wallTopLeftLum, dimPct));
        boolean brightTopRight = DesktopContrast.isBright(DesktopContrast.effectiveLuminance(wallTopRightLum, dimPct));
        desktopBrightShelf = DesktopContrast.isBright(DesktopContrast.effectiveLuminance(wallShelfLum, dimPct));
        // Only the real idle screen drifts; previews and phone previews stay put.
        if (desktopVis > 0.05f) {
            boolean weatherPreview = previewKey != null && (previewKey.startsWith("weather") || "ambient_weather".equals(previewKey));
            clockScene.setTopRightLuminance(wallTopRightLum);
            clockScene.setWeather(currentWeather, weatherPreview);
            clockScene.draw(canvas, appearance, ambient, previewKey == null && (host.phone == null || !host.phone.ambientPreview()));
        }
        contentAlpha *= desktopVis;
        if (contentAlpha > 0.005f) {
            float wifiX = clockScene.left(appearance) - 70;
            int muted = alphaColor(brightTopRight ? 0xC8141A24 : 0xC0FFFFFF, contentAlpha);
            float expand = clamp01(headerMenuExpand.value);
            float fit = headerFit();
            float barRight = headerCollapsedRight + (headerTotalRight - headerCollapsedRight) * expand;
            if (expand > .005f) {
                int bgSave = canvas.save();
                canvas.translate(HEADER_ANCHOR_X * (1 - fit), 0);
                canvas.scale(fit, 1);
                rect.set(HEADER_ANCHOR_X, 72, barRight, 154);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(alphaColor(0xCC141D29, contentAlpha * expand));
                canvas.drawRoundRect(rect, 26, 26, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(alphaColor(0x36FFFFFF, contentAlpha * expand));
                canvas.drawRoundRect(rect, 26, 26, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.restoreToCount(bgSave);
            }
            if (headerAlpha.value > .001f) {
                boolean inExpandedBar = expand > .2f && headerMenu.selected() < HomeHeaderMenu.SLOT_WIFI;
                boolean brightFocusedSlot = !inExpandedBar
                        && ((headerMenu.selected() < HomeHeaderMenu.SLOT_WIFI && brightHeader)
                        || (headerMenu.selected() == HomeHeaderMenu.SLOT_WIFI && brightTopRight));
                int pillColor = inExpandedBar ? 0x36FFFFFF : (brightFocusedSlot ? 0x22141A24 : 0x28FFFFFF);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(alphaColor(pillColor, contentAlpha * headerAlpha.value));
                rect.set(headerLeft.value, 76, headerRight.value, 150);
                canvas.drawRoundRect(rect, 22, 22, paint);
                if (inExpandedBar) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.2f);
                    paint.setColor(alphaColor(0x4CFFFFFF, contentAlpha * headerAlpha.value * expand));
                    canvas.drawRoundRect(rect, 22, 22, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
            int headerSave = canvas.save();
            canvas.translate(HEADER_ANCHOR_X * (1 - fit), 0);
            canvas.scale(fit, 1);
            paint.setTypeface(normal);
            paint.setTextSize(23);
            float labelBaseline = 113f - (paint.ascent() + paint.descent()) / 2f;
            float darkBrandWeight = brightHeader ? (1f - expand) : 0f;
            int brandRgb = blendColor(0xFFFFFFFF, DesktopContrast.INK_COLOR, darkBrandWeight) & 0x00FFFFFF;
            float brandFocus = clamp01(headerFocus[0].value);
            int brandColor = alphaColor((Math.round(218 + 37 * brandFocus) << 24) | brandRgb, contentAlpha);
            brandIcon(canvas, headerIconX[0], 113, brandColor, brandFocus);
            text(canvas, HomeHeaderMenu.BRAND_LABEL, headerTextX[0], labelBaseline, 23, bold, brandColor, Paint.Align.LEFT);
            int chevronColor = alphaColor((Math.round(145 + 95 * Math.max(brandFocus, expand)) << 24) | brandRgb, contentAlpha);
            chevronIcon(canvas, headerChevronX, 113, chevronColor, expand);
            if (host.phone != null && host.phone.active() && expand < 0.4f) {
                paint.setColor(alphaColor(0xFF9FE0C4, contentAlpha * (1f - expand / 0.4f)));
                canvas.drawCircle(headerIconX[0] + 16f, 95f, 4.2f, paint);
            }
            if (expand > .01f) {
                float itemAlpha = contentAlpha * clamp01((expand - 0.10f) / 0.90f);
                int clipSave = canvas.save();
                headerClipPath.reset();
                rect.set(HEADER_ANCHOR_X, 72, barRight, 154);
                headerClipPath.addRoundRect(rect, 26, 26, Path.Direction.CW);
                canvas.clipPath(headerClipPath);
                canvas.translate(-22f * (1f - expand), 0);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(alphaColor(0x2EFFFFFF, itemAlpha));
                canvas.drawLine(headerDividerX, 95, headerDividerX, 131, paint);
                paint.setStyle(Paint.Style.FILL);
                for (int i = 1; i < HomeHeaderMenu.LEFT_SLOT_COUNT; i++) {
                    float focus = clamp01(headerFocus[i].value);
                    int color = alphaColor((Math.round(206 + 49 * focus) << 24) | 0xFFFFFF, itemAlpha);
                    float ix = headerIconX[i], tx = headerTextX[i];
                    if (i == HomeHeaderMenu.SLOT_SETTINGS) {
                        gearIcon(canvas, ix, 113, color, focus);
                    } else if (i == HomeHeaderMenu.SLOT_CONNECT) {
                        connectIcon(canvas, ix, 112, color, focus);
                    } else if (i == HomeHeaderMenu.SLOT_MAGIC) {
                        magicIcon(canvas, ix, 112, color, focus);
                    } else {
                        allAppsIcon(canvas, ix, 113, color, focus);
                    }
                    text(canvas, HomeHeaderMenu.EXPANDED_LABELS[i - 1], tx, labelBaseline, 23, normal, color, Paint.Align.LEFT);
                }
                if (host.phone != null && host.phone.active()) {
                    float connectFocus = clamp01(headerFocus[HomeHeaderMenu.SLOT_CONNECT].value);
                    paint.setColor(alphaColor(0xFF9FE0C4, itemAlpha));
                    canvas.drawCircle(headerIconX[HomeHeaderMenu.SLOT_CONNECT] + 20 + 1.8f * connectFocus, 95 + 1.0f * connectFocus, 4.5f, paint);
                }
                canvas.restoreToCount(clipSave);
            }
            canvas.restoreToCount(headerSave);
            int topRightActive = alphaColor(brightTopRight ? DesktopContrast.INK_COLOR : Color.WHITE, contentAlpha);
            wifiIcon(canvas, Math.round(wifiX), 113, row == -1 && headerMenu.selected() == HomeHeaderMenu.SLOT_WIFI ? topRightActive : muted);
            if (mover.active()) shadowText(canvas, "左右移动  ·  确定放下  ·  返回取消", 960, 960, 26, bold, desktopBrightShelf ? DesktopContrast.INK_COLOR : Color.WHITE, Paint.Align.CENTER, 1f);
            if(host.playback!=null&&host.playback.item!=null)drawPlayback(canvas,contentAlpha);
            if (rows.isEmpty()) shadowText(canvas, host.allTiles.isEmpty() ? "正在读取应用…" : "没有可见卡片，按菜单键恢复", 112, 480, 34, normal, alphaColor(desktopBrightShelf ? DesktopContrast.INK_COLOR : Color.WHITE,
                    contentAlpha), Paint.Align.LEFT, contentAlpha);
            else {
                // Keep the moving shelves inside the content area, clear of the fixed clock.
                int shelfClip = canvas.save();
                canvas.clipRect(0, 285, 1920, 1068);
                for (int i = 0; i < rows.size(); i++) {
                    float distance = i - section.value;
                    if (Math.abs(distance) < 0.96f) {
                        float fade = Math.max(0f, 1f - Math.abs(distance) * 1.12f);
                        float opacity = contentAlpha * fade * fade;
                        if (opacity > 0.004f) {
                            canvas.save();
                            canvas.translate(edge.value*390,0);
                            drawRow(canvas, rows.get(i), shelfCenterY() + distance * 560, opacity*(1-edge.value*.35f));
                            canvas.restore();
                        }
                    }
                }
                canvas.restoreToCount(shelfClip);
                int next = Math.max(0, row) + 1;
                float rowH = row >= 0 && row < rows.size() ? rowMaxHeight(rows.get(row)) : cardHeight();
                float bottomLabelY = shelfCenterY() + rowH / 2f + 68f;
                float nextHintFade = Math.max(0f, Math.min(1f, (945f - bottomLabelY) / 65f));
                if (next < rows.size() && nextHintFade > 0.01f) {
                    Row r = rows.get(next);
                    float nextAlpha = contentAlpha * .34f * nextHintFade;
                    drawShelfIcon(canvas, r.orderKey, 130, 1000, nextAlpha);
                    shadowText(canvas, r.title, 166, 1011, 34, bold, alphaColor(desktopBrightShelf ? DesktopContrast.INK_COLOR : Color.WHITE, nextAlpha), Paint.Align.LEFT, nextAlpha);
                }
            }
            if(edge.value>.002f&&previewKey==null)drawEdge(canvas,contentAlpha);
        }
        if(previewKey!=null&&previewRecent>0.005f){
            host.recents.draw(canvas,true,previewRecent);
        }
        canvas.restore();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int alphaColor(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * clamp01(a)) << 24);
    }

    private static int blendColor(int from, int to, float t) {
        float k = clamp01(t);
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * k);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * k);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k);
        return Color.argb(a, r, g, b);
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

    private void shadowText(Canvas canvas, String text, float x, float y, float size, Typeface font, int color, Paint.Align align, float shadowOpacity) {
        if (shadowOpacity > 0.01f) {
            int shadow = DesktopContrast.isDarkInk(color) ? alphaColor(0xB8FFFFFF, shadowOpacity) : alphaColor(0x88000000, shadowOpacity);
            text(canvas, text, x, y + (DesktopContrast.isDarkInk(color) ? 1.5f : 2f), size, font, shadow, align);
        }
        text(canvas, text, x, y, size, font, color, align);
    }

    private void brandIcon(Canvas canvas,float x,float y,int color,float focus){
        float f=clamp01(focus);
        float alpha=Color.alpha(color)/255f;
        float spread=2.3f*f;
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        float zoom=1f+0.07f*f;
        canvas.scale(zoom,zoom);
        canvas.rotate(-14f+3f*f);
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // Official MarcoTV 3-layer Purple Stacked Cards brand mark
        // Rear card
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alphaColor(blendColor(0xAA9E7DF3,0xFF9E7DF3,f),alpha));
        rect.set(-6.5f+spread*0.8f,3.6f+spread,14.8f+spread*0.8f,13.0f+spread);
        canvas.drawRoundRect(rect,3.6f,3.6f,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(alphaColor(0x99E5D2FF,alpha));
        canvas.drawRoundRect(rect,3.6f,3.6f,paint);
        // Middle translucent lilac card
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alphaColor(blendColor(0xC8C7A9FF,0xE8D4BAFF,f),alpha));
        rect.set(-11.4f+spread*0.25f,-2.4f+spread*0.3f,13.6f+spread*0.25f,7.8f+spread*0.3f);
        canvas.drawRoundRect(rect,4.0f,4.0f,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        paint.setColor(alphaColor(0xCCFFF8FF,alpha));
        canvas.drawRoundRect(rect,4.0f,4.0f,paint);
        // Front ivory card
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alphaColor(blendColor(0xF0FCF7FE,0xFFFFFFFF,f),alpha));
        rect.set(-16.2f-spread*0.6f,-12.0f-spread*0.85f,11.4f-spread*0.6f,1.2f-spread*0.85f);
        canvas.drawRoundRect(rect,4.6f,4.6f,paint);
        if(DesktopContrast.isDarkInk(color)){
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(alphaColor(0x44141A24,alpha));
            canvas.drawRoundRect(rect,4.6f,4.6f,paint);
        }
        canvas.restore();
    }
    private void chevronIcon(Canvas canvas,float x,float y,int color,float expand){
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        canvas.rotate(180f*clamp01(expand));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2.4f);
        paint.setColor(color);
        iconPath.reset();
        iconPath.moveTo(-3.2f,-5.6f);
        iconPath.lineTo(2.6f,0f);
        iconPath.lineTo(-3.2f,5.6f);
        canvas.drawPath(iconPath,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }
    private void gearIcon(Canvas canvas,float x,float y,int color,float focus){
        float f=clamp01(focus);
        float alpha=Color.alpha(color)/255f;
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        canvas.rotate(24f*f);
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // Subtle inner glow when focused
        if(f>0.01f){
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alphaColor(0x30C4A8FF,alpha*f));
            canvas.drawCircle(0,0,10.5f,paint);
        }
        // 8 crisp radial teeth
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.0f);
        paint.setColor(color);
        for(int i=0;i<8;i++){
            double ang=i*Math.PI/4.0;
            float cos=(float)Math.cos(ang),sin=(float)Math.sin(ang);
            canvas.drawLine(cos*9.6f,sin*9.6f,cos*12.8f,sin*12.8f,paint);
        }
        // Outer gear ring + inner hub
        paint.setStrokeWidth(2.6f);
        canvas.drawCircle(0,0,8.2f,paint);
        paint.setStrokeWidth(2.2f);
        paint.setColor(blendColor(color,alphaColor(0xFFC4A8FF,alpha),f*0.65f));
        canvas.drawCircle(0,0,3.4f,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }
    private void magicIcon(Canvas canvas,float x,float y,int color,float focus){
        float f=clamp01(focus);
        float spread=2f*f;
        float alpha=Color.alpha(color)/255f;
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        magicPaint.setShader(null);
        magicPaint.setStrokeCap(Paint.Cap.ROUND);
        magicPaint.setStrokeJoin(Paint.Join.ROUND);
        // Back card (clean open stroke so it never muddies behind the front card)
        magicPaint.setStyle(Paint.Style.STROKE);
        magicPaint.setStrokeWidth(2.6f);
        magicPaint.setColor(alphaColor(DesktopContrast.isDarkInk(color)?0x9C141A24:0xBBFFFFFF,alpha));
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
        magicPaint.setColor(alphaColor(0xFFC4A8FF,alpha*(0.18f+0.18f*f)));
        canvas.drawRoundRect(rect,4.5f,4.5f,magicPaint);
        magicPaint.setStyle(Paint.Style.STROKE);
        magicPaint.setStrokeWidth(2.6f);
        magicPaint.setColor(color);
        canvas.drawRoundRect(rect,4.5f,4.5f,magicPaint);
        // Crisp 4-point star sparkle centered on front card
        float sx=4.5f+spread,sy=4f+spread;
        float sr=5.2f*(1f+0.16f*f),si=1.7f*(1f+0.10f*f);
        canvas.save();
        canvas.translate(sx,sy);
        canvas.rotate(90f*f);
        iconPath.reset();
        iconPath.moveTo(0,-sr);
        iconPath.lineTo(si,-si);
        iconPath.lineTo(sr,0);
        iconPath.lineTo(si,si);
        iconPath.lineTo(0,sr);
        iconPath.lineTo(-si,si);
        iconPath.lineTo(-sr,0);
        iconPath.lineTo(-si,-si);
        iconPath.close();
        magicPaint.setStyle(Paint.Style.FILL);
        magicPaint.setColor(color);
        canvas.drawPath(iconPath,magicPaint);
        canvas.restore();
        canvas.restore();
    }
    private void connectIcon(Canvas canvas,float x,float y,int color,float focus){
        float f=clamp01(focus);
        float alpha=Color.alpha(color)/255f;
        float tvX=-1.5f*f,tvY=-1.0f*f;
        float phoneX=1.8f*f,phoneY=1.0f*f;
        float gapX=phoneX-tvX,gapY=phoneY-tvY;
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        boolean darkHeader = DesktopContrast.isDarkInk(color);
        // Open-path TV display leaving clean space for the overlapping phone (no blurry clipRect)
        canvas.save();
        canvas.translate(tvX,tvY);
        paint.setColor(blendColor(color,alphaColor(darkHeader?0xDD141A24:0xDDFFFFFF,alpha),f*0.5f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        iconPath.reset();
        iconPath.moveTo(Math.min(8f,2f+gapX),7);
        iconPath.lineTo(-17,7);
        iconPath.quadTo(-20,7,-20,4);
        iconPath.lineTo(-20,-10);
        iconPath.quadTo(-20,-13,-17,-13);
        iconPath.lineTo(8,-13);
        iconPath.quadTo(11,-13,11,-10);
        iconPath.lineTo(11,Math.min(4f,-8f+gapY));
        canvas.drawPath(iconPath,paint);
        // Crisp TV stand
        canvas.drawLine(-7,7,-7,12,paint);
        canvas.drawLine(-13,12,-1,12,paint);
        // Subtle link waves blooming from the phone toward the TV screen on focus
        if(f>0.02f){
            float w1=clamp01(f*1.4f);
            float w2=clamp01((f-0.22f)/0.78f);
            paint.setStrokeWidth(2.0f);
            float cx=2.5f,cy=0.5f;
            if(w1>0.01f){
                float r1=3.2f+2.4f*w1;
                paint.setColor(alphaColor(darkHeader?0xFF6B46C1:0xFFD2BCFF,alpha*w1*0.92f));
                rect.set(cx-r1,cy-r1,cx+r1,cy+r1);
                canvas.drawArc(rect,195,60,false,paint);
            }
            if(w2>0.01f){
                float r2=6.8f+3.4f*w2;
                paint.setColor(alphaColor(darkHeader?0xFF6B46C1:0xFFD2BCFF,alpha*w2*0.78f));
                rect.set(cx-r2,cy-r2,cx+r2,cy+r2);
                canvas.drawArc(rect,195,60,false,paint);
            }
        }
        canvas.restore();
        // Crisp phone card shifting slightly forward on focus
        canvas.translate(phoneX,phoneY);
        if(f>0.01f){
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alphaColor(0x38B694F6,alpha*f));
            rect.set(5,-6,20,15);
            canvas.drawRoundRect(rect,3.8f,3.8f,paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setColor(color);
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
    private void allAppsIcon(Canvas canvas,float x,float y,int color,float focus){
        float f=clamp01(focus);
        float alpha=Color.alpha(color)/255f;
        float spacing=10f+1.8f*f;
        int accent=blendColor(color,alphaColor(DesktopContrast.isDarkInk(color)?0xFF6B46C1:0xFFC4A8FF,alpha),f);
        canvas.save();
        canvas.translate(Math.round(x),Math.round(y));
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for(int ry=-1;ry<=1;ry++){
            for(int rx=-1;rx<=1;rx++){
                boolean center=rx==0&&ry==0;
                float cx=rx*spacing,cy=ry*spacing;
                float half=center?3f+0.65f*f:3f;
                float corner=center?1.8f+0.5f*f:1.8f+0.3f*f;
                paint.setColor(center?accent:color);
                rect.set(cx-half,cy-half,cx+half,cy+half);
                canvas.drawRoundRect(rect,corner,corner,paint);
            }
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
        paint.setColor(alphaColor(desktopBrightShelf?0x22141A24:0x28FFFFFF,opacity));
        canvas.drawRoundRect(rect,10,10,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(alphaColor(desktopBrightShelf?0x42141A24:0x4EFFFFFF,opacity));
        rect.inset(.75f,.75f);
        canvas.drawRoundRect(rect,9.5f,9.5f,paint);
        paint.setStyle(Paint.Style.FILL);
        int glyphInk=desktopBrightShelf?DesktopContrast.INK_COLOR:Color.WHITE;
        if("order_tv".equals(orderKey)){
            // Modern 2x2 rounded app deck: 3 crisp ink/white tiles + 1 bright accent tile
            int white=alphaColor(glyphInk,opacity);
            int accent=alphaColor(desktopBrightShelf?0xFF1688D8:0xFF7AD8FF,opacity);
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
            paint.setColor(alphaColor(desktopBrightShelf?0xFFD98800:0xFFFFE082,opacity));
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
            paint.setColor(alphaColor(glyphInk,opacity));
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
            paint.setColor(alphaColor(glyphInk,opacity));
            float[] p={-8.5f,-2,4.5f};
            for(float py:p)for(float px:p){
                rect.set(px,py,px+4,py+4);
                canvas.drawRoundRect(rect,1.2f,1.2f,paint);
            }
        }
        canvas.restore();
    }
    private void icon(Canvas canvas, String glyph, float x, float y, float size, int color) {
        float u = size / 24f;
        canvas.save();
        canvas.translate(x, y);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        if ("\ue037".equals(glyph)) {
            // Play triangle
            iconPath.reset();
            iconPath.moveTo(-4.5f * u, -7.2f * u);
            iconPath.lineTo(7.5f * u, 0f);
            iconPath.lineTo(-4.5f * u, 7.2f * u);
            iconPath.close();
            canvas.drawPath(iconPath, paint);
        } else if ("\ue034".equals(glyph)) {
            // Pause bars
            iconBounds.set(-6f * u, -7f * u, -2f * u, 7f * u);
            canvas.drawRoundRect(iconBounds, 1.4f * u, 1.4f * u, paint);
            iconBounds.set(2f * u, -7f * u, 6f * u, 7f * u);
            canvas.drawRoundRect(iconBounds, 1.4f * u, 1.4f * u, paint);
        } else if ("\ue333".equals(glyph)) {
            // TV display + stand
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.8f, 2f * u));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            iconBounds.set(-9.5f * u, -7f * u, 9.5f * u, 5f * u);
            canvas.drawRoundRect(iconBounds, 2f * u, 2f * u, paint);
            canvas.drawLine(-4.5f * u, 8.2f * u, 4.5f * u, 8.2f * u, paint);
            paint.setStyle(Paint.Style.FILL);
        } else {
            // Apps 3x3 rounded grid fallback ("\ue5c3", "\ue859", etc.)
            float[] pts = {-7.5f, -1.75f, 4f};
            for (float py : pts) {
                for (float px : pts) {
                    iconBounds.set(px * u, py * u, (px + 3.5f) * u, (py + 3.5f) * u);
                    canvas.drawRoundRect(iconBounds, 1.1f * u, 1.1f * u, paint);
                }
            }
        }
        canvas.restore();
    }

    float cardWidth(){
        return 3.2f*Math.max(60,Math.min(160,host.appearance().getInt("card_width",100)));
    }
    float cardHeight(){
        return 1.8f*Math.max(70,Math.min(130,host.appearance().getInt("card_height",100)));
    }
    int cardDepth(){
        return Math.max(0,Math.min(100,host.appearance().getInt("card_depth",0)));
    }
    float tileWidth(Tile t){
        if(t!=null&&t.customWidth>0){
            return 3.2f*Math.max(CardStore.MIN_WIDTH,Math.min(CardStore.MAX_WIDTH,t.customWidth));
        }
        return cardWidth();
    }
    float tileHeight(Tile t){
        if(t!=null&&t.customHeight>0){
            return 1.8f*Math.max(CardStore.MIN_HEIGHT,Math.min(CardStore.MAX_HEIGHT,t.customHeight));
        }
        return cardHeight();
    }
    float tileDepth(Tile t){
        int d=t!=null&&t.customDepth>=0?Math.max(CardStore.MIN_DEPTH,Math.min(CardStore.MAX_DEPTH,t.customDepth)):cardDepth();
        return d/100f;
    }
    float tileRadius(Tile t){
        if(t!=null&&t.customRadius>=0){
            return Math.max(CardStore.MIN_RADIUS,Math.min(CardStore.MAX_RADIUS,t.customRadius));
        }
        return radius;
    }
    private float tileCenterX(Row r,int index){
        float cursor=SHELF_START_X;
        for(int i=0;i<index&&i<r.tiles.size();i++){
            cursor+=tileWidth(r.tiles.get(i))+CARD_GAP;
        }
        if(index>=0&&index<r.tiles.size()){
            cursor+=tileWidth(r.tiles.get(index))*0.5f;
        }
        return cursor;
    }
    private float rowMaxHeight(Row r){
        float maxH=cardHeight();
        if(r!=null){
            for(Tile t:r.tiles){
                float th=tileHeight(t);
                if(th>maxH)maxH=th;
            }
        }
        return maxH;
    }
    float shelfCenterY(){
        float half=cardHeight()/2f,position=Math.max(0,Math.min(100,host.appearance().getInt("card_row_position",75)))/100f;
        float topY=395f+half,bottomY=960f-half;
        return topY+(bottomY-topY)*position;
    }
    boolean inShelfTouchBand(float y){
        return y>shelfCenterY()-cardHeight()/2-50&&y<shelfCenterY()+cardHeight()/2+60;
    }

    private void drawRow(Canvas canvas, Row r, float y, float opacity) {
        float arrival=Math.max(0,Math.min(1,entrance.value));
        y+=30*(1-arrival);
        opacity*=.65f+.35f*arrival;
        float rowMaxH=rowMaxHeight(r);
        drawShelfIcon(canvas, r.orderKey, 130, y - rowMaxH/2-86, opacity);
        int shelfInk = desktopBrightShelf ? DesktopContrast.INK_COLOR : Color.WHITE;
        shadowText(canvas, r.title, 166, y - rowMaxH/2-75, 34, bold, alphaColor(shelfInk, opacity), Paint.Align.LEFT, opacity);
        if(r.tiles.isEmpty())shadowText(canvas,"暂无应用，按左键展开菜单添加",112,y,28,normal,alphaColor(desktopBrightShelf?0xCC141A24:0xD6FFFFFF,opacity),Paint.Align.LEFT,opacity);
        int raised = -1;
        for (int i = 0; i < r.tiles.size(); i++) if (raised < 0 || r.tiles.get(i).emphasis.value > r.tiles.get(raised).emphasis.value) raised = i;
        float cursor = SHELF_START_X;
        float raisedX = 0f;
        for (int i = 0; i < r.tiles.size(); i++) {
            Tile tile = r.tiles.get(i);
            float tw = tileWidth(tile);
            float cx = cursor + tw * 0.5f - r.scroll.value;
            cursor += tw + CARD_GAP;
            if (i == raised) raisedX = cx;
            else drawTile(canvas, tile, cx, y, opacity);
        }
        if (raised >= 0) drawTile(canvas, r.tiles.get(raised), raisedX, y, opacity);
    }

    private void drawTile(Canvas canvas, Tile tile, float x, float shelfY, float opacity) {
        float baseWidth = tileWidth(tile);
        float baseHeight = tileHeight(tile);
        if (x + baseWidth * 0.75f < -120 || x - baseWidth * 0.75f > 2040) return;
        float emphasis = clampedEmphasis(tile);
        float press = Math.max(0, Math.min(1, tile.press.value));
        float zoom = (1 + focusScale * emphasis) * (1 - .035f * press);
        float y = shelfY - 16 * emphasis;
        float contentZoom = zoom * Math.min(1f, Math.min(baseWidth / 320f, baseHeight / 180f));
        float w = baseWidth * zoom, h = baseHeight * zoom;
        if ((emphasis < 0.002f || emphasis > 0.998f) && press < 0.002f) {
            rect.set(Math.round(x - w / 2), Math.round(y - h / 2), Math.round(x + w / 2), Math.round(y + h / 2));
        } else {
            rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        }
        float baseRad = tileRadius(tile);
        float cardRadius = Math.min(baseRad * zoom, Math.min(w, h) * 0.5f);
        float depth = tileDepth(tile);
        float thickness = 18f * depth * zoom;
        if (surfaceStrength > 0 || depth > 0.005f) {
            // Smooth Gaussian-blurred tvOS floating elevation shadow (cached at 0.5x to avoid banding and per-frame blur cost).
            float sBase = opacity * Math.max(surfaceStrength, depth * 0.85f);
            Bitmap[] shadows = ensureShadows(baseWidth, baseHeight, baseRad);
            float pad = SHADOW_PAD * zoom;
            drawShadowBitmap(canvas, shadows[0], pad, (7f + 15f * emphasis + 14f * depth) * zoom + thickness * 0.65f, sBase * (0.18f + 0.62f * emphasis));
            drawShadowBitmap(canvas, shadows[1], pad, (3f + 4.5f * emphasis) * zoom + thickness, sBase * (0.38f + 0.32f * emphasis));
        }
        if (thickness > 0.5f) {
            surfaceBounds.set(rect.left, rect.top + thickness, rect.right, rect.bottom + thickness);
            int baseEdge = tile.tint != 0 ? blendColor(tile.tint, 0xFF060B12, 0.55f) : 0xFF0D1521;
            paint.setColor(alphaColor(baseEdge, opacity * 0.96f));
            canvas.drawRoundRect(surfaceBounds, cardRadius, cardRadius, paint);
            surfaceMatrix.setScale(surfaceBounds.width(), surfaceBounds.height());
            surfaceMatrix.postTranslate(surfaceBounds.left, surfaceBounds.top);
            depthSlabGradient.setLocalMatrix(surfaceMatrix);
            surfacePaint.setShader(depthSlabGradient);
            surfacePaint.setAlpha(Math.round(255 * opacity * (0.35f + 0.45f * depth)));
            canvas.drawRoundRect(surfaceBounds, cardRadius, cardRadius, surfacePaint);
            surfacePaint.setShader(null);
            surfaceBounds.inset(0.8f * zoom, 0.8f * zoom);
            surfacePaint.setStyle(Paint.Style.STROKE);
            surfacePaint.setStrokeWidth(1.2f * zoom);
            surfacePaint.setColor(alphaColor(0x46FFFFFF, opacity * (0.4f + 0.6f * emphasis)));
            canvas.drawRoundRect(surfaceBounds, Math.max(0, cardRadius - 0.8f * zoom), Math.max(0, cardRadius - 0.8f * zoom), surfacePaint);
            surfacePaint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(alphaColor(tile.tint != 0 ? tile.tint : tile.banner ? Color.BLACK : 0xFF243541, opacity));
        canvas.drawRoundRect(rect, cardRadius, cardRadius, paint);
        if (tile.artwork != null) {
            if (tile.banner) {
                if (tile.shader == null) tile.shader = new BitmapShader(tile.artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float fit = Math.max(rect.width() / tile.artwork.getWidth(), rect.height() / tile.artwork.getHeight()) * (1f + 0.028f * emphasis);
                artworkMatrix.setScale(fit, fit);
                artworkMatrix.postTranslate(rect.centerX() - tile.artwork.getWidth() * fit / 2, rect.centerY() - tile.artwork.getHeight() * fit / 2);
                tile.shader.setLocalMatrix(artworkMatrix);
                artworkPaint.setShader(tile.shader);
                artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawRoundRect(rect, cardRadius, cardRadius, artworkPaint);
                artworkPaint.setShader(null);
            } else {
                float size = 116 * contentZoom;
                iconBounds.set(rect.centerX() - size / 2, rect.centerY() - size / 2, rect.centerX() + size / 2, rect.centerY() + size / 2);
                artworkPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawBitmap(tile.artwork, null, iconBounds, artworkPaint);
            }
        } else if (tile.tint != 0) {
            icon(canvas, tile.glyph, rect.centerX(), rect.centerY() - 20 * contentZoom, 60 * contentZoom, alphaColor(0xB8FFFFFF, opacity));
            float coverMax = Math.max(100f, baseWidth - 85f);
            if (tile.coverLabel == null || Math.abs(tile.coverLabelWidth - coverMax) > 1f) {
                tile.coverLabelWidth = coverMax;
                tile.coverLabel = clippedLabel(tile.label, coverMax, 28);
            }
            text(canvas, tile.coverLabel, rect.centerX(), rect.centerY() + 48 * contentZoom, 28 * contentZoom, bold, alphaColor(Color.WHITE, opacity), Paint.Align.CENTER);
        } else icon(canvas, tile.kind == 1 ? "\ue333" : "\ue859", rect.centerX(), rect.centerY(), 112 * contentZoom, alphaColor(Color.WHITE, opacity));
        if (surfaceStrength > 0) {
            surfaceMatrix.setScale(rect.width(), rect.height());
            surfaceMatrix.postTranslate(rect.left, rect.top);
            surfaceGradient.setLocalMatrix(surfaceMatrix);
            surfacePaint.setShader(surfaceGradient);
            surfacePaint.setAlpha(Math.round(255 * opacity * surfaceStrength));
            canvas.drawRoundRect(rect, cardRadius, cardRadius, surfacePaint);
            if (emphasis > 0.01f) {
                sheenGradient.setLocalMatrix(surfaceMatrix);
                surfacePaint.setShader(sheenGradient);
                surfacePaint.setAlpha(Math.round(255 * opacity * surfaceStrength * emphasis));
                canvas.drawRoundRect(rect, cardRadius, cardRadius, surfacePaint);
            }
            surfacePaint.setShader(null);
            // Crisp tvOS inner glass bevel (subtle hairline at rest, gleaming specular rim on focus).
            float stroke = (1.1f + 1.15f * emphasis) * zoom;
            float inset = stroke * 0.55f;
            surfaceBounds.set(rect);
            surfaceBounds.inset(inset, inset);
            rimGradient.setLocalMatrix(surfaceMatrix);
            surfacePaint.setShader(rimGradient);
            surfacePaint.setStyle(Paint.Style.STROKE);
            surfacePaint.setStrokeWidth(stroke);
            surfacePaint.setAlpha(Math.round(255 * opacity * surfaceStrength * (0.28f + 0.72f * emphasis)));
            canvas.drawRoundRect(surfaceBounds, Math.max(0, cardRadius - inset), Math.max(0, cardRadius - inset), surfacePaint);
            surfacePaint.setShader(null);
            surfacePaint.setStyle(Paint.Style.FILL);
        }
        if (emphasis > .005f || labelOpacity > 0) {
            float labelMax = Math.max(120f, baseWidth - 20f);
            if (tile.displayLabel == null || Math.abs(tile.displayLabelWidth - labelMax) > 1f) {
                tile.displayLabelWidth = labelMax;
                tile.displayLabel = clippedLabel(tile.label, labelMax, 36);
            }
            float textAlpha = opacity * (labelOpacity + (1 - labelOpacity) * emphasis);
            shadowText(canvas, tile.displayLabel, Math.max(112, x - baseWidth / 2f), shelfY + baseHeight / 2 + thickness * 0.85f + 68 - 4 * emphasis, 36, bold,
                    alphaColor(desktopBrightShelf ? DesktopContrast.INK_COLOR : Color.WHITE, textAlpha), Paint.Align.LEFT, textAlpha);
        }
    }

    private Bitmap[] ensureShadows(float baseWidth, float baseHeight, float baseRadius) {
        int bw = Math.max(60, Math.round(baseWidth));
        int bh = Math.max(40, Math.round(baseHeight));
        int br = Math.max(0, Math.round(Math.min(baseRadius, Math.min(bw, bh) * 0.5f)));
        long key = (((long) bw) << 32) | (((long) bh) << 16) | (br & 0xFFFFL);
        Bitmap[] cached = shadowCache.get(key);
        if (cached != null && cached[0] != null && !cached[0].isRecycled()
                && cached[1] != null && !cached[1].isRecycled()) {
            return cached;
        }
        Bitmap[] pair = new Bitmap[]{
                renderShadowBitmap(bw, bh, br, 30f, 8f, 6f, 0x9A030712),
                renderShadowBitmap(bw, bh, br, 11f, 2f, 2f, 0x78030712)
        };
        shadowCache.put(key, pair);
        return pair;
    }

    private static Bitmap renderShadowBitmap(int baseWidth, int baseHeight, int baseRadius, float blurRadius,
            float insetX, float insetY, int color) {
        int bw = Math.max(1, Math.round((baseWidth + SHADOW_PAD * 2f) * SHADOW_SCALE));
        int bh = Math.max(1, Math.round((baseHeight + SHADOW_PAD * 2f) * SHADOW_SCALE));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, blurRadius * SHADOW_SCALE), BlurMaskFilter.Blur.NORMAL));
        RectF shape = new RectF(
                (SHADOW_PAD + insetX) * SHADOW_SCALE,
                (SHADOW_PAD + insetY) * SHADOW_SCALE,
                (SHADOW_PAD + baseWidth - insetX) * SHADOW_SCALE,
                (SHADOW_PAD + baseHeight - insetY) * SHADOW_SCALE);
        float r = Math.max(1f, (baseRadius - Math.min(insetX, insetY) * 0.5f) * SHADOW_SCALE);
        c.drawRoundRect(shape, r, r, p);
        return bmp;
    }

    private void drawShadowBitmap(Canvas canvas, Bitmap bmp, float pad, float dy, float alpha) {
        if (bmp == null || bmp.isRecycled() || alpha <= 0.005f) return;
        surfaceBounds.set(rect.left - pad, rect.top - pad + dy, rect.right + pad, rect.bottom + pad + dy);
        shadowPaint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * alpha))));
        canvas.drawBitmap(bmp, null, surfaceBounds, shadowPaint);
    }

    private void recycleShadows() {
        for (Bitmap[] pair : shadowCache.values()) {
            if (pair != null) {
                for (Bitmap b : pair) if (b != null && !b.isRecycled()) b.recycle();
            }
        }
        shadowCache.clear();
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
    private void executeHeaderAction(HomeHeaderMenu.Action action) {
        animateFocus();
        describeFocus();
        switch (action) {
            case OPEN_SETTINGS:
                host.settings();
                break;
            case OPEN_CONNECT:
                host.phone.show();
                break;
            case OPEN_MAGIC_DESKTOP:
                host.navigator.personalization("cards");
                break;
            case OPEN_APP_MANAGER:
                host.navigator.appManager();
                break;
            case OPEN_WIFI:
                host.network.show();
                break;
            default:
                break;
        }
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
            if (event.getRepeatCount() == 0) {
                headerMenu.onLeaveTopRow();
                animateFocus();
                host.settings();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (row == -1 && headerMenu.onBack()) {
                animateFocus();
                describeFocus();
                return true;
            }
            if (row != 0) {
                headerMenu.onLeaveTopRow();
                row = 0;
                ensureVisible();
                animateFocus();
                describeFocus();
            } else setIdle(true);
            return true;
        }
        if (row == -1) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                headerMenu.onLeaveTopRow();
                if (!rows.isEmpty() || host.playback.item != null) {
                    row = host.playback.item == null ? 0 : -2;
                }
            }
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) headerMenu.onLeft();
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) headerMenu.onRight();
            ensureVisible();
            animateFocus();
            describeFocus();
            invalidate();
            return true;
        }
        if (rows.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                row = -1;
                animateFocus();
                describeFocus();
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
                executeHeaderAction(headerMenu.onConfirm());
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
        if (y < 205 && Math.abs(x-(clockScene.left(host.appearance())-70)) < 65) {
            row=-1;
            executeHeaderAction(headerMenu.activateSlot(HomeHeaderMenu.SLOT_WIFI));
            return true;
        }
        if(y<175&&x<clockScene.left(host.appearance())-112){
            float local=HEADER_ANCHOR_X+(x-HEADER_ANCHOR_X)/headerFit();
            if(!headerMenu.isExpanded()){
                if(local>=HEADER_ANCHOR_X-16f&&local<=headerCollapsedRight+18f){
                    row=-1;
                    executeHeaderAction(headerMenu.activateSlot(HomeHeaderMenu.SLOT_BRAND));
                    return true;
                }
            }else{
                int entry=-1;
                if(local>=HEADER_ANCHOR_X-16f&&local<headerDividerX){
                    entry=HomeHeaderMenu.SLOT_BRAND;
                }else{
                    for(int i=1;i<HomeHeaderMenu.LEFT_SLOT_COUNT;i++){
                        float leftBound=i==1?headerDividerX:(headerContentRight[i-1]+headerContentLeft[i])*.5f;
                        float rightBound=i==HomeHeaderMenu.LEFT_SLOT_COUNT-1?headerTotalRight+24f:(headerContentRight[i]+headerContentLeft[i+1])*.5f;
                        if(local>=leftBound&&local<rightBound){
                            entry=i;
                            break;
                        }
                    }
                }
                if(entry>=0){
                    row=-1;
                    executeHeaderAction(headerMenu.activateSlot(entry));
                    return true;
                }
                headerMenu.collapseToBrand();
                animateFocus();
                describeFocus();
                return true;
            }
        }else if(headerMenu.isExpanded()){
            headerMenu.collapseToBrand();
            animateFocus();
            describeFocus();
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
            int index = -1;
            float cursor = SHELF_START_X;
            for (int i = 0; i < r.tiles.size(); i++) {
                float tw = tileWidth(r.tiles.get(i));
                float cx = cursor + tw * 0.5f - r.scroll.value;
                cursor += tw + CARD_GAP;
                if (x >= cx - tw * 0.5f - CARD_GAP * 0.5f && x <= cx + tw * 0.5f + CARD_GAP * 0.5f) {
                    index = i;
                    break;
                }
            }
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
