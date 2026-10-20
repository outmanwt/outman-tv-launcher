package com.outman.tvlauncher

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout

/** Fits video without stretching; informational overlays continue to fill the container. */
class AspectVideoFrame(context: Context) : FrameLayout(context) {
    var videoRatio: Float = 16f / 9f
        set(value) { if(value>0 && value.isFinite()) {field=value;requestLayout()} }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val width=(measuredWidth-paddingLeft-paddingRight).coerceAtLeast(1)
        val height=(measuredHeight-paddingTop-paddingBottom).coerceAtLeast(1)
        val videoWidth: Int
        val videoHeight: Int
        if(width.toFloat()/height>videoRatio) {videoHeight=height;videoWidth=(height*videoRatio).toInt()}
        else {videoWidth=width;videoHeight=(width/videoRatio).toInt()}
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            if(child is SurfaceView) {
                (child.layoutParams as LayoutParams).gravity=Gravity.CENTER
                child.measure(View.MeasureSpec.makeMeasureSpec(videoWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(videoHeight,View.MeasureSpec.EXACTLY))
            }
        }
    }
}
