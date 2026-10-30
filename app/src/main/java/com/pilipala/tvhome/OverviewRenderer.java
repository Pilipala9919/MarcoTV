// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;

/** Draws the home scene, or the Magic Desktop preview that tilts it into a card beside the settings panel. */
final class OverviewRenderer {
    private final HomeView view;
    private final Camera overviewCamera=new Camera();
    private final Matrix overviewMatrix=new Matrix();
    private Bitmap overviewBitmap;
    private Canvas overviewCanvas;
    private BitmapShader overviewShader;
    private final Paint overviewPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final PreviewBackdrop overviewBackdrop=new PreviewBackdrop();
    private final Path previewCutout=new Path();

    OverviewRenderer(HomeView view) {
        this.view = view;
    }

    void detach() {
        overviewBackdrop.clear();
    }

    /** @param overview 0 for the plain home screen, 1 for the fully projected preview */
    void draw(Canvas canvas, float overview) {
        if(overview<=0){
            overviewBackdrop.clear();
            if(overviewBitmap!=null){
                overviewBitmap.recycle();
                overviewBitmap=null;
                overviewCanvas=null;
                overviewShader=null;
            }
            view.drawScene(canvas);
            return;
        }
        overviewBackdrop.draw(canvas,view.wallpaper,view.getWidth(),view.getHeight());
        float density=view.getResources().getDisplayMetrics().density;
        overviewCamera.save();
        overviewCamera.setLocation(0,0,-Math.max(12,view.getWidth()/72f));
        overviewCamera.rotateY(12*overview);
        overviewCamera.getMatrix(overviewMatrix);
        overviewCamera.restore();
        overviewMatrix.preTranslate(-view.getWidth()/2f,-view.getHeight()/2f);
        RectF bounds=new RectF(0,0,view.getWidth(),view.getHeight());
        overviewMatrix.mapRect(bounds);
        float available=Math.max(view.getWidth()*.25f,view.getWidth()-350*density);
        float targetScale=Math.min(available/bounds.width(),view.getHeight()*.78f/bounds.height());
        float size=1-(1-targetScale)*overview;
        overviewMatrix.postScale(size,size);
        float targetCenter=20*density+available/2;
        overviewMatrix.postTranslate(view.getWidth()/2f+(targetCenter-view.getWidth()/2f)*overview-bounds.centerX()*size,view.getHeight()/2f-bounds.centerY()*size);
        if(canvas.isHardwareAccelerated()){
            // Live previews stay on the GPU; the antialiased border softens the
            // projected outline without a full-screen intermediate render target.
            int transformed=canvas.save();
            canvas.concat(overviewMatrix);
            RectF frame=new RectF(0,0,view.getWidth(),view.getHeight());
            previewCutout.reset();
            previewCutout.addRoundRect(frame,8*density,8*density,Path.Direction.CW);
            int clipped=canvas.save();
            canvas.clipPath(previewCutout);
            view.drawScene(canvas);
            canvas.restoreToCount(clipped);
            overviewPaint.setShader(null);
            overviewPaint.setStyle(Paint.Style.STROKE);
            overviewPaint.setStrokeWidth(density);
            overviewPaint.setColor(0x609BB9CB);
            canvas.drawRoundRect(frame,8*density,8*density,overviewPaint);
            canvas.restoreToCount(transformed);
            return;
        }
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
        Matrix bufferMatrix=new Matrix();
        bufferMatrix.setScale(view.getWidth()/(float)bufferWidth,view.getHeight()/(float)bufferHeight);
        overviewShader.setLocalMatrix(bufferMatrix);
        int save=canvas.save();
        canvas.concat(overviewMatrix);
        RectF frame=new RectF(0,0,view.getWidth(),view.getHeight());
        overviewPaint.setShader(overviewShader);
        overviewPaint.setStyle(Paint.Style.FILL);
        overviewPaint.setColor(Color.WHITE);
        canvas.drawRoundRect(frame,8*density,8*density,overviewPaint);
        overviewPaint.setShader(null);
        overviewPaint.setStyle(Paint.Style.STROKE);
        overviewPaint.setStrokeWidth(density);
        overviewPaint.setColor(0x609BB9CB);
        canvas.drawRoundRect(frame,8*density,8*density,overviewPaint);
        canvas.restoreToCount(save);
    }
}
