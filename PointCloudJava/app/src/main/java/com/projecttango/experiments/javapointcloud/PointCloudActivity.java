/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.experiments.javapointcloud;
import com.google.atap.tango.ux.UxExceptionEvent;
import com.google.atap.tango.ux.UxExceptionEventListener;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import com.google.atap.tango.ux.TangoUx;
import com.google.atap.tango.ux.TangoUx.StartParams;
import com.google.atap.tango.ux.TangoUxLayout;
import com.projecttango.tangoutils.TangoPoseUtilities;


import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.rajawali3d.surface.RajawaliSurfaceView;

import java.nio.FloatBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * Main Activity class for the Point Cloud Sample. Handles the connection to the {@link Tango}
 * service and propagation of Tango XyzIj data to OpenGL and Layout views. OpenGL rendering logic is
 * delegated to the {@link PointCloudRajawaliRenderer} class.
 */
public class PointCloudActivity extends Activity implements OnClickListener {

    private static final String TAG = PointCloudActivity.class.getSimpleName();
    private static final int SECS_TO_MILLISECS = 1000;
    private Tango mTango;
    private TangoConfig mConfig;

    private PointCloudRajawaliRenderer mRenderer;

    private TextView mDeltaTextView;
    private TextView mPoseCountTextView;
    private TextView mPoseTextView;
    private TextView mQuatTextView;
    private TextView mPoseStatusTextView;
    private TextView mTangoEventTextView;
    private TextView mPointCountTextView;
    private TextView mTangoServiceVersionTextView;
    private TextView mApplicationVersionTextView;
    private TextView mAverageZTextView;
    private TextView mFrequencyTextView;

    private Button mFirstPersonButton;
    private Button mThirdPersonButton;
    private Button mTopDownButton;

    private int mCount;
    private int mPreviousPoseStatus;
    private int mPointCount;
    private float mDeltaTime;
    private float mPosePreviousTimeStamp;
    private float mXyIjPreviousTimeStamp;
    private float mCurrentTimeStamp;
    private float mPointCloudFrameDelta;
    private float mAverageDepth;
    private boolean mIsTangoServiceConnected;
    private TangoPoseData mPose;
    private PointCloudManager mPointCloudManager;
    private TangoUx mTangoUx;

    private static final int UPDATE_INTERVAL_MS = 100;
    private Object mUiPoseLock = new Object();
    private Object mUiDepthLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jpoint_cloud);
        setTitle(R.string.app_name);

        mTango = new Tango(this);
        mConfig = setupTangoConfig(mTango);
        setupTextViewsAndButtons(mConfig);

        int maxDepthPoints = mConfig.getInt("max_point_cloud_elements");
        mPointCloudManager = new PointCloudManager(maxDepthPoints);
        mRenderer = setupGLViewAndRenderer(mPointCloudManager);
        mTangoUx = setupTangoUxAndLayout();
        mIsTangoServiceConnected = false;
        startUIThread();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTangoUx.stop();
        try {
            mTango.disconnect();
            mIsTangoServiceConnected = false;
        } catch (TangoErrorException e) {
            Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartParams params = new StartParams();
        mTangoUx.start(params);
        if (!mIsTangoServiceConnected) {
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                    Tango.TANGO_INTENT_ACTIVITYCODE);
        }
        Log.i(TAG, "onResumed");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE) {
            Log.i(TAG, "Triggered");
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.motiontrackingpermission, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            try {
                setTangoListeners();
            } catch (TangoErrorException e) {
                Toast.makeText(this, R.string.TangoError, Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Toast.makeText(getApplicationContext(), R.string.motiontrackingpermission,
                        Toast.LENGTH_SHORT).show();
            }
            try {
                mTango.connect(mConfig);
                mIsTangoServiceConnected = true;
            } catch (TangoOutOfDateException outDateEx) {
                if (mTangoUx != null) {
                    mTangoUx.showTangoOutOfDate();
                }
            } catch (TangoErrorException e) {
                Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT)
                        .show();
            }
            setupExtrinsics();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.first_person_button:
            mRenderer.setFirstPersonView();
            break;
        case R.id.third_person_button:
            mRenderer.setThirdPersonView();
            break;
        case R.id.top_down_button:
            mRenderer.setTopDownView();
            break;
        default:
            Log.w(TAG, "Unrecognized button click.");
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mRenderer.onTouchEvent(event);
        return true;
    }

    private void setTangoListeners() {
        // Configure the Tango coordinate frame pair
        final ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // Passing in the pose data to UX library produce exceptions.
                if (mTangoUx != null) {
                    mTangoUx.updatePoseStatus(pose.statusCode);
                }
                // Make sure to have atomic access to Tango Pose Data so that
                // render loop doesn't interfere while Pose call back is updating
                // the data.
                synchronized (mUiPoseLock) {
                    mPose = pose;
                    // Calculate the delta time from previous pose.
                    mDeltaTime = (float) (pose.timestamp - mPosePreviousTimeStamp)
                            * SECS_TO_MILLISECS;
                    mPosePreviousTimeStamp = (float) pose.timestamp;
                    if (mPreviousPoseStatus != pose.statusCode) {
                        mCount = 0;
                    }
                    mCount++;
                    mPreviousPoseStatus = pose.statusCode;
                }
                mRenderer.updateDevicePose(pose);
            }

            @Override
            public void onXyzIjAvailable(final TangoXyzIjData xyzIj) {
                if (mTangoUx != null) {
                    mTangoUx.updateXyzCount(xyzIj.xyzCount);
                }
                mPointCloudManager.updateCallbackBufferAndSwap(xyzIj.xyz, xyzIj.xyzCount);
                TangoPoseData pointCloudPose = mTango.getPoseAtTime(mCurrentTimeStamp,
                        framePairs.get(0));
                mRenderer.updatePointCloudPose(pointCloudPose);

                // Make sure to have atomic access to TangoXyzIjData so that
                // UI loop doesn't interfere while onXYZijAvailable callback is updating
                // the mPoint cloud data.
                synchronized (mUiDepthLock) {
                    mCurrentTimeStamp = (float) xyzIj.timestamp;
                    mPointCloudFrameDelta = (mCurrentTimeStamp - mXyIjPreviousTimeStamp)
                            * SECS_TO_MILLISECS;
                    mXyIjPreviousTimeStamp = mCurrentTimeStamp;
                    mAverageDepth = getAveragedDepth(xyzIj.xyz);
                    try {
                        mPointCount = xyzIj.xyzCount;
                    } catch (TangoErrorException e) {
                        Toast.makeText(getApplicationContext(), R.string.TangoError,
                                Toast.LENGTH_SHORT).show();
                    } catch (TangoInvalidException e) {
                        Toast.makeText(getApplicationContext(), R.string.TangoError,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                if (mTangoUx != null) {
                    mTangoUx.updateTangoEvent(event);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mTangoEventTextView.setText(event.eventKey + ": " + event.eventValue);
                    }
                });
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    /**
     * Create a separate thread to update Log information on UI at the specified interval of
     * UPDATE_INTERVAL_MS. This function also makes sure to have access to the mPose atomically.
     */
    private void startUIThread() {
        new Thread(new Runnable() {
            final DecimalFormat threeDec = new DecimalFormat("0.000");

            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(UPDATE_INTERVAL_MS);
                        // Update the UI with TangoPose information
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mUiPoseLock) {
                                    if (mPose == null) {
                                        return;
                                    }
                                    String translationString = TangoPoseUtilities.getTranslationString(mPose, threeDec);
                                    String quaternionString = TangoPoseUtilities.getQuaternionString(mPose, threeDec);
                                    String status = TangoPoseUtilities.getStatusString(mPose);
                                    mPoseTextView.setText(translationString);
                                    mQuatTextView.setText(quaternionString);
                                    mPoseCountTextView.setText(Integer.toString(mCount));
                                    mDeltaTextView.setText(threeDec.format(mDeltaTime));
                                    mPoseStatusTextView.setText(status);
                                }
                                synchronized (mUiDepthLock) {
                                    // Display number of points in the mPoint cloud
                                    mPointCountTextView.setText(Integer.toString(mPointCount));
                                    mFrequencyTextView.setText(""
                                            + threeDec.format(mPointCloudFrameDelta));
                                    mAverageZTextView.setText("" + threeDec.format(mAverageDepth));
                                }
                            }
                        });
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    private void setupExtrinsics() {
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        TangoPoseData imuTColorCameraPose = mTango.getPoseAtTime(0.0,framePair);

        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH;
        TangoPoseData imuTDepthCameraPose = mTango.getPoseAtTime(0.0,framePair);

        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_DEVICE;
        TangoPoseData imuTDevicePose = mTango.getPoseAtTime(0.0,framePair);

        mRenderer.setupExtrinsics(imuTDevicePose, imuTColorCameraPose, imuTDepthCameraPose);
    }

    /*
   * This is an advanced way of using UX exceptions. In most cases developers can just use the in
   * built exception notifications using the Ux Exception layout. In case a developer doesn't want
   * to use the default Ux Exception notifications, he can set the UxException listener as shown
   * below.
   * In this example we are just logging all the ux exceptions to logcat, but in a real app,
   * developers should use these exceptions to contextually notify the user and help direct the
   * user in using the device in a way Tango service expects it.
   */
    private UxExceptionEventListener mUxExceptionListener = new UxExceptionEventListener() {

        @Override
        public void onUxExceptionEvent(UxExceptionEvent uxExceptionEvent) {
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_LYING_ON_SURFACE){
                Log.i(TAG, "Device lying on surface ");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_DEPTH_POINTS){
                Log.i(TAG, "Very few depth points in mPoint cloud " );
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_FEATURES){
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_INCOMPATIBLE_VM){
                Log.i(TAG, "Device not running on ART");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOTION_TRACK_INVALID){
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOVING_TOO_FAST){
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_OVER_EXPOSED){
                Log.i(TAG, "Camera Over Exposed");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_TANGO_SERVICE_NOT_RESPONDING){
                Log.i(TAG, "TangoService is not responding ");
            }
            if(uxExceptionEvent.getType() == UxExceptionEvent.TYPE_UNDER_EXPOSED){
                Log.i(TAG, "Camera Under Exposed " );
            }

        }
    };

    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_CURRENT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        return config;
    }

    /**
     * Sets Text views to display statistics of Poses being received. This also sets the buttons
     * used in the UI.
     */
    private void setupTextViewsAndButtons(TangoConfig config){
        mPoseTextView = (TextView) findViewById(R.id.pose);
        mQuatTextView = (TextView) findViewById(R.id.quat);
        mPoseCountTextView = (TextView) findViewById(R.id.posecount);
        mDeltaTextView = (TextView) findViewById(R.id.deltatime);
        mTangoEventTextView = (TextView) findViewById(R.id.tangoevent);
        mPoseStatusTextView = (TextView) findViewById(R.id.status);
        mPointCountTextView = (TextView) findViewById(R.id.pointCount);
        mTangoServiceVersionTextView = (TextView) findViewById(R.id.version);
        mApplicationVersionTextView = (TextView) findViewById(R.id.appversion);
        mAverageZTextView = (TextView) findViewById(R.id.averageZ);
        mFrequencyTextView = (TextView) findViewById(R.id.frameDelta);

        mFirstPersonButton = (Button) findViewById(R.id.first_person_button);
        mFirstPersonButton.setOnClickListener(this);
        mThirdPersonButton = (Button) findViewById(R.id.third_person_button);
        mThirdPersonButton.setOnClickListener(this);
        mTopDownButton = (Button) findViewById(R.id.top_down_button);
        mTopDownButton.setOnClickListener(this);

        PackageInfo packageInfo;
        try {
            packageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            mApplicationVersionTextView.setText(packageInfo.versionName);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }

        // Display the version of Tango Service
        String serviceVersion = config.getString("tango_service_library_version");
        mTangoServiceVersionTextView.setText(serviceVersion);
    }

    /**
     * Sets Rajawalisurface view and its renderer. This is ideally called only once in onCreate.
     */
    private PointCloudRajawaliRenderer setupGLViewAndRenderer(PointCloudManager pointCloudManager){
        PointCloudRajawaliRenderer renderer = new PointCloudRajawaliRenderer(this, pointCloudManager);
        RajawaliSurfaceView glView = (RajawaliSurfaceView) findViewById(R.id.gl_surface_view);
        glView.setEGLContextClientVersion(2);
        glView.setSurfaceRenderer(renderer);
        return renderer;
    }

    /**
     * Sets up TangoUX layout and sets its listener.
     */
    private TangoUx setupTangoUxAndLayout(){
        TangoUxLayout uxLayout = (TangoUxLayout) findViewById(R.id.layout_tango);
        TangoUx tangoUx = new TangoUx(this);
        tangoUx.setLayout(uxLayout);
        tangoUx.setUxExceptionEventListener(mUxExceptionListener);
        return  tangoUx;
    }

    /**
     * Calculates the average depth from a point cloud buffer
     * @param pointCloudBuffer
     * @return Average depth.
     */
    private float getAveragedDepth(FloatBuffer pointCloudBuffer){
        mPointCount = pointCloudBuffer.capacity() / 3;
        float totalZ = 0;
        float averageZ = 0;
        for (int i = 0; i < pointCloudBuffer.capacity() - 3; i = i + 3) {
            totalZ = totalZ + pointCloudBuffer.get(i + 2);
        }
        if (mPointCount != 0)
            averageZ = totalZ / mPointCount;
        return  averageZ;
    }

}
