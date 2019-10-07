package com.displaymodule.usbcamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.displaymodule.usbcamera.utils.camera.USBCamera;
import com.displaymodule.usbcamera.utils.camera.USBCameraHelper;
import com.displaymodule.usbcamera.utils.camera.USBCameraListener;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.FpsMeter;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The activity demonstrate the use of OpenCV with USBCamera.
 */
public class MainActivity extends AppCompatActivity {

    // region Variables

    private boolean mVisible;

    private View mControlsView;

    private View mPreviewView;

    private USBCameraHelper usbcameraHelper;

    private static final String[] classNames = {"background",
            "aeroplane", "bicycle", "bird", "boat",
            "bottle", "bus", "car", "cat", "chair",
            "cow", "diningtable", "dog", "horse",
            "motorbike", "person", "pottedplant",
            "sheep", "sofa", "train", "tvmonitor"};

    private Net net;

    private CameraBridgeViewBase mOpenCvCameraView;

    protected int mFrameWidth;

    protected int mFrameHeight;

    protected float mScale = 0;

    protected FpsMeter mFpsMeter = null;

    private Mat[] mFrameChain;

    private boolean mCameraFrameReady = false;

    private int mChainIdx = 0;

    private int mPreviewFormat = ImageFormat.NV21;

    protected UsbCameraFrame[] mCameraFrame;

    private Thread mThread;

    private boolean mStopThread;

    private Bitmap mCacheBitmap;

    // endregion Variables

    // region CONSTANTS

    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private static final String TAG = "MainActivity";

    /**
     * Whether or not the system UI should be auto-hidden after
     * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
     */
    private static final boolean AUTO_HIDE = true;

    /**
     * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
     * user interaction before hiding the system UI.
     */
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;

    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;

    // endregion CONSTANTS

    // region Implements Runnables

    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            mControlsView.setVisibility(View.VISIBLE);
        }
    };

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mPreviewView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };

    /**
     * The CameraWork is getting the frame and send to creating labels and modified the video.
     */
    protected class CameraWorker implements Runnable {

        @Override
        public void run() {
            do {
                boolean hasFrame = false;
                synchronized (MainActivity.this) {
                    try {
                        while (!mCameraFrameReady && !mStopThread) {
                            MainActivity.this.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mCameraFrameReady)
                    {
                        mChainIdx = 1 - mChainIdx;
                        mCameraFrameReady = false;
                        hasFrame = true;
                    }
                }

                if (!mStopThread && hasFrame) {
                    if (!mFrameChain[1 - mChainIdx].empty())
                        deliverAndDrawFrame(mCameraFrame[1 - mChainIdx]);
                }
            } while (!mStopThread);

            Log.d(TAG, "Finish processing thread");
        }

        /**
         *
         * @param frame
         */
        private void deliverAndDrawFrame(CameraBridgeViewBase.CvCameraViewFrame frame) {
            Mat modified;

            if (mCvCameraViewListener != null) {
                modified = mCvCameraViewListener.onCameraFrame(frame);
            } else {
                modified = frame.rgba();
            }
        }
    }

    // endregion Implements Runnables

    // region UsbCameraFrame implements CameraBridgeViewBase.CvCameraViewFrame

    private class UsbCameraFrame implements CameraBridgeViewBase.CvCameraViewFrame {

        private Mat mYuvFrameData;
        private Mat mRgba;
        private int mWidth;
        private int mHeight;

        public UsbCameraFrame(Mat Yuv420sp, int width, int height) {
            super();
            mWidth = width;
            mHeight = height;
            mYuvFrameData = Yuv420sp;
            mRgba = new Mat();
        }

        @Override
        public Mat rgba() {
            if (mPreviewFormat == ImageFormat.NV21)
                Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);
            else
                throw new IllegalArgumentException("Preview Format can be NV21");

            return mRgba;
        }

        @Override
        public Mat gray() {
            return mYuvFrameData.submat(0, mHeight, 0, mWidth);
        }

        public void release() {
            mRgba.release();
        }
    }

    // endregion UsbCameraFrame

    // region Handlers

    private final Handler mHideHandler = new Handler();

    // endregion Handlers

    // region Touch listener

    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS);
            }
            return false;
        }
    };

    // endregion Touch listener

    // region USBCamera listener - The is listener called after USBCamera is connected

    private final USBCameraListener mUSBCameraListener = new USBCameraListener() {

        /**
         * The handler is called after USB camera connected, with a sequence of below:
         *
         * 1. Calls onConnected callback
         * 2. Open camera (USBCamera.openCamera)
         * 3. onCameraOpened
         *
         * USBCameraHelper::ConnectCallback
         *  => onConnected
         *  => openCamera
         *  => onCameraOpened
         *
         * USBCameraListener
         *  => onCameraOpened
         *
         * @param camera
         * @param cameraId camera ID (not used right now)
         * @param displayOrientation preview rotation
         * @param isMirror display mirror or not
         */
        @Override
        public void onCameraOpened(USBCamera camera, int cameraId, int displayOrientation, boolean isMirror) {
            Log.i(TAG, "onCameraOpened");

            prepare(camera);

            // Getting the frames and draw frames
            runThreadOfCameraWorker();

            mCvCameraViewListener.onCameraViewStarted(
                    camera.getPreviewSize().width, camera.getPreviewSize().height);
        }

        @Override
        public void onPreview(byte[] data) {
            Log.i(TAG, "onPreview");
        }

        @Override
        public void onCameraClosed() {
            Log.i(TAG, "onCameraClosed");
        }

        @Override
        public void onCameraError(Exception e) {
            Log.i(TAG, "onCameraError: " + e.getMessage());
        }

        @Override
        public void onCameraConfigurationChanged(int cameraID, int displayOrientation) {
            Log.i(TAG, "onCameraConfigurationChanged");
            //todo something
        }

        private void prepare(USBCamera camera) {
            Log.d(TAG, "Prepare frame parameters.");
            com.displaymodule.libuvccamera.Size previewSize = camera.getPreviewSize();

            mFrameWidth = previewSize.width;
            mFrameHeight = previewSize.height;

            // int size = mFrameWidth * mFrameHeight;

            if (mFpsMeter != null) {
                mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
            }

            mFrameChain = new Mat[2];
            mFrameChain[0] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);
            mFrameChain[1] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);

            mCameraFrame = new UsbCameraFrame[2];
            mCameraFrame[0] = new UsbCameraFrame(mFrameChain[0], mFrameWidth, mFrameHeight);
            mCameraFrame[1] = new UsbCameraFrame(mFrameChain[1], mFrameWidth, mFrameHeight);

            mCameraFrameReady = false;
            mPreviewFormat = ImageFormat.NV21;
        }

        /**
         * Start thread will be getting the frames.
         */
        private void runThreadOfCameraWorker() {
            Log.d(TAG, "Starting processing thread");
            mStopThread = false;
            mThread = new Thread(new CameraWorker());
            mThread.start();
        }
    };

    // endregion USBCamera listener

    // region Implements CameraBridgeViewBase.CvCameraViewListener2

    private CameraBridgeViewBase.CvCameraViewListener2 mCvCameraViewListener = new CameraBridgeViewBase.CvCameraViewListener2() {
        @Override
        public void onCameraViewStarted(int width, int height) {
            loadMobileNetSSDNetwork();
        }

        @Override
        public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
            return detectAndDrawLabeledFrame(inputFrame);
        }

        @Override
        public void onCameraViewStopped() { }
    };

    // endregion Implements CameraBridgeViewBase.CvCameraViewListener2

    // region OpenCV BaseLoaderCallback - Initialize OpenCV manager

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.d(TAG, "OpenCV loaded successfully");
                    //mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    // endregion OpenCV BaseLoaderCallback

    // region Initialize USBCamera

    /**
     * Initialize USBCamera
     *
     * The camera will get ready and call the connect callback
     */
    private void initializeUSBCamera() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        usbcameraHelper = new USBCameraHelper.Builder()
                .Context(this.getApplication())
                .previewViewSize(new Point(mPreviewView.getMeasuredWidth(), mPreviewView.getMeasuredHeight()))
                .rotation(getWindowManager().getDefaultDisplay().getRotation())
                .isMirror(false)
                .previewOn(mPreviewView)
                .cameraListener(mUSBCameraListener)
                .build();

        usbcameraHelper.init();
        usbcameraHelper.start();
    }

    // endregion Initialize USBCamera

    // region Initialize OpenCV library

    private void initializeOpenCV() {
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    // endregion Initialize OpenCV library

    // region Overrides AppCompatActivity - implements Activity component lifecycle methods

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mPreviewView = findViewById(R.id.texture_preview);

        // Set up the user interaction to manually show or hide the system UI.
        mPreviewView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById(R.id.dummy_button).setOnTouchListener(mDelayHideTouchListener);
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initializeUSBCamera();
        }

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    public void onResume() {
        super.onResume();
        initializeOpenCV();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (usbcameraHelper != null) {
            usbcameraHelper.release();
            usbcameraHelper = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            boolean isAllGranted = true;

            for (int grantResult : grantResults) {
                isAllGranted &= (grantResult == PackageManager.PERMISSION_GRANTED);
            }

            if (isAllGranted) {
                initializeUSBCamera();
                if (usbcameraHelper != null) {
                    usbcameraHelper.start();
                }
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // endregion Overrides AppCompatActivity

    // region Helpers

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(this, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    private void toggle() {
        Toast.makeText(this,R.string.dummy_content,Toast.LENGTH_LONG).show();
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mPreviewView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    /**
     * Upload file to storage and return a path.
     */
    private static String getPath(String file, Context context) {
        AssetManager assetManager = context.getAssets();
        BufferedInputStream inputStream = null;

        try {
            // Read data from assets.
            inputStream = new BufferedInputStream(assetManager.open(file));
            byte[] data = new byte[inputStream.available()];

            inputStream.read(data);
            inputStream.close();

            // Create copy file in storage.
            File outFile = new File(context.getFilesDir(), file);
            FileOutputStream os = new FileOutputStream(outFile);

            os.write(data);
            os.close();

            // Return a path to file which may be read in common way.
            return outFile.getAbsolutePath();

        } catch (IOException ex) {
            Log.i(TAG, "Failed to upload a file");
        }
        return "";
    }

    /**
     * A network is defined by its design (.prototxt) and its weights (.caffemodel).
     *
     * The caffe implementation of MobileNet-SSD detection network, with pretrained weights on
     * VOC0712 and mAP=0.727 (https://github.com/chuanqi305/MobileNet-SSD).
     */
    private void loadMobileNetSSDNetwork() {
        String proto = getPath("MobileNetSSD_deploy.prototxt", this);
        String weights = getPath("MobileNetSSD_deploy.caffemodel", this);
        net = Dnn.readNetFromCaffe(proto, weights);
        Log.i(TAG, "Network loaded successfully");
    }

    private Mat detectAndDrawLabeledFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        final int IN_WIDTH = 300;
        final int IN_HEIGHT = 300;
        final float WH_RATIO = (float)IN_WIDTH / IN_HEIGHT;
        final double IN_SCALE_FACTOR = 0.007843;
        final double MEAN_VAL = 127.5;
        final double THRESHOLD = 0.2;

        // Get a new frame
        Mat frame = inputFrame.rgba();

        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB);

        // Forward image through network.
        Mat blob = Dnn.blobFromImage(frame, IN_SCALE_FACTOR,
                new Size(IN_WIDTH, IN_HEIGHT),
                new Scalar(MEAN_VAL, MEAN_VAL, MEAN_VAL), false);
        net.setInput(blob);

        Mat detections = net.forward();

        int cols = frame.cols();
        int rows = frame.rows();

        Size cropSize;

        if ((float)cols / rows > WH_RATIO) {
            cropSize = new Size(rows * WH_RATIO, rows);
        } else {
            cropSize = new Size(cols, cols / WH_RATIO);
        }

        int y1 = (int)(rows - cropSize.height) / 2;
        int y2 = (int)(y1 + cropSize.height);
        int x1 = (int)(cols - cropSize.width) / 2;
        int x2 = (int)(x1 + cropSize.width);

        Mat subFrame = frame.submat(y1, y2, x1, x2);
        cols = subFrame.cols();
        rows = subFrame.rows();
        detections = detections.reshape(1, (int)detections.total() / 7);

        for (int i = 0; i < detections.rows(); ++i) {
            double confidence = detections.get(i, 2)[0];
            if (confidence > THRESHOLD) {
                int classId = (int)detections.get(i, 1)[0];
                int xLeftBottom = (int)(detections.get(i, 3)[0] * cols);
                int yLeftBottom = (int)(detections.get(i, 4)[0] * rows);
                int xRightTop   = (int)(detections.get(i, 5)[0] * cols);
                int yRightTop   = (int)(detections.get(i, 6)[0] * rows);

                // Draw rectangle around detected object.
                Imgproc.rectangle(subFrame, new org.opencv.core.Point(xLeftBottom, yLeftBottom),
                        new org.opencv.core.Point(xRightTop, yRightTop),
                        new Scalar(0, 255, 0));
                String label = classNames[classId] + ": " + confidence;
                int[] baseLine = new int[1];
                Size labelSize = Imgproc.getTextSize(label, Core.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseLine);

                // Draw background for label.
                Imgproc.rectangle(subFrame, new org.opencv.core.Point(xLeftBottom, yLeftBottom - labelSize.height),
                        new org.opencv.core.Point(xLeftBottom + labelSize.width, yLeftBottom + baseLine[0]),
                        new Scalar(255, 255, 255), Core.FILLED);

                // Write class name and confidence.
                Imgproc.putText(subFrame, label, new org.opencv.core.Point(xLeftBottom, yLeftBottom),
                        Core.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 0, 0));
            }
        }

        return frame;
    }

    // endregion Helpers
}
