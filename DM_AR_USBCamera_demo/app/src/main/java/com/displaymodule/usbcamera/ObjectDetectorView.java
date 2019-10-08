package com.displaymodule.usbcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;
import android.view.ViewGroup;

import org.opencv.BuildConfig;
import org.opencv.android.FpsMeter;

public class ObjectDetectorView extends SurfaceView {

    protected String TAG = "ObjectDetectorView";

    protected Bitmap mBitmap;

    protected float mFrameWidth;

    protected float mFrameHeight;

    private float mScale;

    protected FpsMeter mFpsMeter;

    public ObjectDetectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ObjectDetectorView setFrameSize(float width, float height) {
        mFrameWidth = width;
        mFrameHeight = height;
        return this;
    }

    public ObjectDetectorView setBitmap(Bitmap data) {
        mBitmap = data;
        return this;
    }

    public ObjectDetectorView setFpsMeter(FpsMeter fpsMeter) {
        mFpsMeter = fpsMeter;
        return this;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if ((getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT) && (getLayoutParams().height == ViewGroup.LayoutParams.MATCH_PARENT))
            mScale = Math.min(((float)getHeight())/mFrameHeight, ((float)getWidth())/mFrameWidth);
        else
            mScale = 0;

        if (canvas != null) {
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            if (BuildConfig.DEBUG)
                Log.d(TAG, "mStretch value: " + mScale);

            if (mScale != 0) {
                canvas.drawBitmap(mBitmap, new Rect(0,0,mBitmap.getWidth(), mBitmap.getHeight()),
                        new Rect((int)((canvas.getWidth() - mScale*mBitmap.getWidth()) / 2),
                                (int)((canvas.getHeight() - mScale*mBitmap.getHeight()) / 2),
                                (int)((canvas.getWidth() - mScale*mBitmap.getWidth()) / 2 + mScale*mBitmap.getWidth()),
                                (int)((canvas.getHeight() - mScale*mBitmap.getHeight()) / 2 + mScale*mBitmap.getHeight())), null);
            } else {
                canvas.drawBitmap(mBitmap, new Rect(0,0,mBitmap.getWidth(), mBitmap.getHeight()),
                        new Rect((canvas.getWidth() - mBitmap.getWidth()) / 2,
                                (canvas.getHeight() - mBitmap.getHeight()) / 2,
                                (canvas.getWidth() - mBitmap.getWidth()) / 2 + mBitmap.getWidth(),
                                (canvas.getHeight() - mBitmap.getHeight()) / 2 + mBitmap.getHeight()), null);
            }

            if (mFpsMeter != null) {
                mFpsMeter.measure();
                mFpsMeter.draw(canvas, 20, 30);
            }
            getHolder().unlockCanvasAndPost(canvas);
        }
    }
}
