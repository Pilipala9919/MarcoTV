// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;

/**
 * Projects the home scene into the 3D Magic Desktop preview card beside the right-hand controls.
 * When attached to DesktopStage, 3D perspective and rounded clipping run natively on GPU
 * View/RenderNode properties (zero per-frame clipPath or EVEN_ODD mask tessellation).
 */
final class OverviewRenderer {
    private final HomeView view;
    private DesktopStage stage;
    private final Camera overviewCamera=new Camera();
    private final Matrix overviewMatrix=new Matrix();
    private Bitmap overviewBitmap;
    private Canvas overviewCanvas;
    private BitmapShader overviewShader;
    private final Paint overviewPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private PreviewBackdrop overviewBackdrop=new PreviewBackdrop();
    private final RectF bounds=new RectF(),frame=new RectF();
    private final Matrix bufferMatrix=new Matrix();

    OverviewRenderer(HomeView view) {
        this.view = view;
    }

    void attachStage(DesktopStage stage) {
        this.stage = stage;
    }

    boolean hasStage() {
        return stage != null;
    }

    void detach() {
        overviewBackdrop.clear();
        if(overviewBitmap!=null)overviewBitmap.recycle();
        overviewBitmap=null;
        overviewCanvas=null;
        overviewShader=null;
    }

    void backdrop(PreviewBackdrop prepared){
        overviewBackdrop.clear();
        overviewBackdrop=prepared;
        if(stage!=null)stage.invalidateBackdrop();
    }

    void drawBackdrop(Canvas canvas){
        overviewBackdrop.draw(canvas,view.wallpaper,view.getWidth(),view.getHeight());
    }

    /** Updates overviewMatrix and applies GPU-native 3D transform to DesktopStage without forcing HomeView redraws. */
    void applyStage(float overview){
        if(overview<=0.001f){
            overviewMatrix.reset();
            if(overviewBitmap!=null){
                overviewBitmap.recycle();
                overviewBitmap=null;
                overviewCanvas=null;
                overviewShader=null;
            }
            if(stage!=null)stage.applyOverview(0f,1f,0f,0f,0f,1f);
            return;
        }
        int vw=view.getWidth(),vh=view.getHeight();
        if(vw<=0||vh<=0)return;
        float density=view.getResources().getDisplayMetrics().density;
        overviewCamera.save();
        overviewCamera.setLocation(0,0,-Math.max(12,vw/72f));
        overviewCamera.rotateY(12*overview);
        overviewCamera.getMatrix(overviewMatrix);
        overviewCamera.restore();
        overviewMatrix.preTranslate(-vw/2f,-vh/2f);
        bounds.set(0,0,vw,vh);
        overviewMatrix.mapRect(bounds);
        float available=Math.max(vw*.25f,vw-364*density);
        float targetScale=Math.min(available/bounds.width(),vh*.78f/bounds.height());
        float size=1-(1-targetScale)*overview;
        overviewMatrix.postScale(size,size);
        float targetCenter=24*density+available/2;
        float tx=(targetCenter-vw/2f)*overview-bounds.centerX()*size;
        float ty=-bounds.centerY()*size;
        overviewMatrix.postTranslate(vw/2f+tx,vh/2f+ty);
        if(stage!=null){
            float radius=40f*density*overview;
            float cameraDist=Math.max(1280f*density,vw*1.4f);
            stage.applyOverview(overview,size,tx,ty,radius,cameraDist);
        }
    }

    /** @param overview 0 for the plain home screen, 1 for the fully projected preview */
    void draw(Canvas canvas, float overview) {
        if(stage!=null){
            view.drawScene(canvas);
            return;
        }
        if(overview<=0){
            // Keep the tiny prepared blur across editor visits; the wallpaper loader replaces it.
            if(overviewBitmap!=null){
                overviewBitmap.recycle();
                overviewBitmap=null;
                overviewCanvas=null;
                overviewShader=null;
            }
            view.drawScene(canvas);
            return;
        }
        applyStage(overview);
        overviewBackdrop.draw(canvas,view.wallpaper,view.getWidth(),view.getHeight());
        float density=view.getResources().getDisplayMetrics().density;
        float radius=40*density*overview;
        int bufferWidth=Math.min(1280,view.getWidth()),bufferHeight=Math.max(1,Math.round(view.getHeight()*bufferWidth/(float)view.getWidth()));
        if(overviewBitmap==null||overviewBitmap.getWidth()!=bufferWidth||overviewBitmap.getHeight()!=bufferHeight){
            if(overviewBitmap!=null)overviewBitmap.recycle();
            overviewBitmap=Bitmap.createBitmap(bufferWidth,bufferHeight,Bitmap.Config.ARGB_8888);
            view.previewDirty=true;
            overviewCanvas=new Canvas(overviewBitmap);
            overviewShader=new BitmapShader(overviewBitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        }
        if(view.previewDirty){
            overviewBitmap.eraseColor(Color.TRANSPARENT);
            int bufferSave=overviewCanvas.save();
            overviewCanvas.scale(bufferWidth/(float)view.getWidth(),bufferHeight/(float)view.getHeight());
            view.drawScene(overviewCanvas);
            overviewCanvas.restoreToCount(bufferSave);
            view.previewDirty=false;
        }
        bufferMatrix.setScale(view.getWidth()/(float)bufferWidth,view.getHeight()/(float)bufferHeight);
        overviewShader.setLocalMatrix(bufferMatrix);
        int save=canvas.save();
        canvas.concat(overviewMatrix);
        frame.set(0,0,view.getWidth(),view.getHeight());
        overviewPaint.setShader(overviewShader);
        overviewPaint.setStyle(Paint.Style.FILL);
        overviewPaint.setColor(Color.WHITE);
        canvas.drawRoundRect(frame,radius,radius,overviewPaint);
        overviewPaint.setShader(null);
        overviewPaint.setStyle(Paint.Style.STROKE);
        overviewPaint.setStrokeWidth(1.2f*density);
        overviewPaint.setColor(0x709BB9CB);
        canvas.drawRoundRect(frame,radius,radius,overviewPaint);
        canvas.restoreToCount(save);
    }
}
