// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;

/**
 * Projects the home scene into the 3D Magic Desktop preview card beside the right-hand controls.
 * 3D perspective and rounded clipping run natively on GPU View/RenderNode properties via DesktopStage.
 */
final class OverviewRenderer {
    static final float TILT_DEGREES = 6.5f;
    private final HomeView view;
    private DesktopStage stage;
    private final Camera overviewCamera=new Camera();
    private final Matrix overviewMatrix=new Matrix();
    private PreviewBackdrop overviewBackdrop=new PreviewBackdrop();
    private final RectF bounds=new RectF();

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
            if(stage!=null)stage.applyOverview(0f,1f,0f,0f,0f,1f);
            return;
        }
        int vw=view.getWidth(),vh=view.getHeight();
        if(vw<=0||vh<=0)return;
        float density=view.getResources().getDisplayMetrics().density;
        float cameraDist=Math.max(4800f*density,vw*5.0f);
        float cameraZ=-cameraDist/(72f*Math.max(1f,density));
        overviewCamera.save();
        overviewCamera.setLocation(0,0,cameraZ);
        overviewCamera.rotateY(TILT_DEGREES*overview);
        overviewCamera.getMatrix(overviewMatrix);
        overviewCamera.restore();
        overviewMatrix.preTranslate(-vw/2f,-vh/2f);
        bounds.set(0,0,vw,vh);
        overviewMatrix.mapRect(bounds);
        // Right Magic Desktop panel takes 276dp + 20dp right margin = 296dp; keep balanced margins left (44dp) & right (44dp).
        float leftMargin=44f*density;
        float available=Math.max(vw*.25f,vw-384f*density);
        float targetScale=Math.min(available/bounds.width(),vh*.74f/bounds.height());
        float size=1-(1-targetScale)*overview;
        overviewMatrix.postScale(size,size);
        float targetCenter=leftMargin+available/2f;
        float tx=(targetCenter-vw/2f)*overview-bounds.centerX()*size;
        float ty=-bounds.centerY()*size;
        overviewMatrix.postTranslate(vw/2f+tx,vh/2f+ty);
        if(stage!=null){
            float radius=36f*density*overview;
            stage.applyOverview(overview,size,tx,ty,radius,cameraDist);
        }
    }

    /** @param overview 0 for the plain home screen, 1 for the fully projected preview */
    void draw(Canvas canvas, float overview) {
        view.drawScene(canvas);
    }
}
