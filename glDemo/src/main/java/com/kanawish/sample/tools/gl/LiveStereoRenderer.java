/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kanawish.sample.tools.gl;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import com.kanawish.glepisodes.R;
import com.kanawish.sample.tools.domain.CameraManager;
import com.kanawish.sample.tools.domain.DebugData;
import com.kanawish.sample.tools.model.GeometryData;
import com.kanawish.sample.tools.model.GeometryRenderer;
import com.kanawish.gl.utils.FileUtils;
import com.kanawish.sample.tools.utils.OrientationUtils;
import com.kanawish.sample.tools.utils.RxUtils;
import com.kanawish.sample.tools.utils.ShaderCompileException;
import com.kanawish.sample.tools.utils.SimpleGLUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;

/**
 *
 * Adapted from the Cardboard sample application, this class is designed for
 * rapid fragment shader development.
 * <p/>
 * Aiming for a set of base features that allow to build a Cardboard VR Shadertoy-like platform,
 * but we also want for users of the library to be able to adapt this to build full fledged VR apps.
 *
 */
public class LiveStereoRenderer implements GvrView.StereoRenderer {

    // BENCHMARKS / DEBUG
    private PublishSubject<DebugData> debugOutputPublishSubject;
    private DebugData debugDataRight;
    private DebugData debugDataLeft;

    private PublishSubject<Float> fpsSubject;
    private Disposable movingAverageDisposable;

    private boolean compileError = false;

    private long benchLastTime = 0;
    private long benchCurrentTime = 0;
    private float benchMs = 0;


    // SECTION: Android specific / resources
    private final Context context; // Mostly needed to load textures. See about moving this in a manager.
    private final boolean isReverseLandscape;


    // TODO: Re-implement a simplified version of original scheme.
    // SECTION: Playback Control
    enum State { PLAYING, PAUSED, STOPPED }
    private State state;
    private long currentTime = 0; // sequence 'read-head' position
    private long lastClock; // last clock recorded, on "play()" or at last frame render when "PLAYING".


    // SECTION: Camera Control
    // GOAL: Provide with a way to navigate the space with regular controls
    private final CameraManager cameraManager;
    private float[] cameraRotation = new float[] {0,0,0};
    private float[] cameraTranslation = new float[] {0,0,0};


    // SECTION: 3d Model Entities
    private GeometryRenderer geometry;


    // SECTION: GL States
    private static final float Z_NEAR = 3f;//0.1f;
    private static final float Z_FAR = 7f;//100.0f;
    private static final float CAMERA_Z = 0.01f;

    private float[] headView = new float[16];
    private final float[] camera = new float[16];

    private final float[] pMatrix = new float[16];


    // TODO: Use dagger to make things cleaner / easier.
    public LiveStereoRenderer(Context context, CameraManager cameraManager) {

        this.context = context;

        this.isReverseLandscape = OrientationUtils.isReverseLandscape(context);

        // Load initial shaders.
        String instancedVertexShader = null;
        String instancedFragmentShader = null;

        try {
            instancedVertexShader = FileUtils.loadStringFromAsset(context, "shaders/_300.instanced.v4.vs");
            instancedFragmentShader = FileUtils.loadStringFromAsset(context, "shaders/_300.default.v4.fs");
        } catch (IOException e) {
            Timber.e(e,"Error to load shaders from disk.");
            throw new RuntimeException(e);
        }

        geometry = new GeometryRenderer(instancedVertexShader,instancedFragmentShader);

        this.cameraManager = cameraManager;
        initCameraManager(cameraManager);

        initDebugDataPublisher();
    }

    private void initDebugDataPublisher() {
        // Observable event streams for debug info (Headtracking)
        // TODO: DebugData is really a rough way to go. Emit Coordinate objects eventually instead?
        debugOutputPublishSubject = PublishSubject.create();
        debugDataRight = new DebugData(); // Default to RIGHT
        debugDataLeft = new DebugData();
        debugDataLeft.setType(DebugData.Type.LEFT);

        // Regular calls to benchmarkFps() will publish the info we need at the next step.
        fpsSubject = PublishSubject.create();

        // We use fpsSubject as a source, and average it over the last 50 results.
        Observable<Float> movingAverage = RxUtils.movingAverage(fpsSubject, 50);
        movingAverageDisposable = movingAverage
            .sample(500, TimeUnit.MILLISECONDS)
            .subscribe(new Consumer<Float>() {
                @Override
                public void accept(Float average) {
                    debugDataRight.setFps(average);
                    debugDataLeft.setFps(average);
                }
            });
    }

    private void initCameraManager(CameraManager cameraManager) {
        cameraManager
            .cameraDataObservable()
            .subscribe(
                    new Consumer<CameraManager.CameraData>() {
                        @Override
                        public void accept(CameraManager.CameraData cameraData) {
                            // Copy the arrays.
                            float[] newRot = cameraData.getCameraRotation();
                            System.arraycopy(newRot, 0, cameraRotation, 0, newRot.length);
                            float[] newTrans = cameraData.getCameraTranslation();
                            System.arraycopy(newTrans, 0, cameraTranslation, 0, newTrans.length);
                        }
                    });
    }

    /**
     * <p>Creates the buffers we use to store information about the 3D world.</p>
     *
     * <p>OpenGL doesn't use Java arrays, it needs data in a format it can understand.
     * Hence ByteBuffers.</p>
     *
     * @param config The EGL configuration used when creating the surface.
     */
    @Override
    public void onSurfaceCreated(EGLConfig config) {
        Timber.i("onSurfaceCreated()");

        // Initialize some default textures, much like Shadertoy's approach.
        int[] textureDataHandles = {
            SimpleGLUtils.loadTexture(context, R.drawable.tex03),
            SimpleGLUtils.loadTexture(context, R.drawable.tex12),
            SimpleGLUtils.loadTexture(context, R.drawable.tex16),
        };

        // To keep the sample code organized, and future proof it a bit for when we'll want to
        // construct more complex scenes, I've created a custom class called "Geometry".

        // 1.- Buffers are data structures we use to pass data into the pipeline. 
        geometry.initBuffers();
        // 2.- We initialize the OpenGL program.
        try {
            geometry.initGlProgram();
        } catch (ShaderCompileException e) {
            // We need at least the starting shaders to compile, otherwise we'll fail fast.
            throw new RuntimeException(e);
        }
        // 3.- Once the program is ready, we can start initializing handles...
        geometry.initHandles();

        geometry.setTextureDataHandles(textureDataHandles);

        SimpleGLUtils.checkGlErrorRTE("Geometry initialized");

        // OpenGL Pipeline configuration
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Timber.i("onSurfaceChanged");

        // In a non-stereo renderer, we typically would assign resolution to various items.
    }

    /**
     * Prepares OpenGL ES before we draw a frame.
     *
     * @param headTransform The head transformation in the new frame.
     */
    @Override
    public void onNewFrame(HeadTransform headTransform) {
        // Updates this.benchFps so we can report on it. See onDrawEye()
        benchmarkFps();

        SimpleGLUtils.checkGlErrorCE("onReadyToDraw");

        // Set the camera position (aka View matrix)
        Matrix.setLookAtM(camera, 0,
                0, 0, 1f, // CAMERA_Z, // used to be 4, // eye xyz
                0f, 0f, 0f, // center xyz
                0.0f, 1.0f, 0.0f); // up vector xyz ...

        // We don't use this in our example, but it has uses.
        headTransform.getHeadView(headView, 0);

        // Here we can set general frame attributes.

        // TODO: Make it dynamic.
        geometry.setLightPos3fv(new float[]{0, 20, -3});
    }

    int frameCount = 0 ;

    /**
     * Draws a frame for an eye.
     *
     * @param eye The eye to render. Includes all required transformations.
     */
    @Override
    public void onDrawEye(Eye eye) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Quick patch for backpressure problem.
        if( frameCount++ % 60 == 0 ) generateDebugOutput(eye);

        // Get a perspective ratio for the eye view.
        float ratio = (float) eye.getViewport().width / eye.getViewport().height;
        // Setup pMatrix, our 'projection matrix'
        // left & right, bottom & top, near & far
        Matrix.frustumM(pMatrix, 0, -ratio, ratio, -1, 1, 3, 200);

        // Calculate the projection and view transformation
        float[] vMatrix = new float[16];

        // TODO: Likely going to be a good idea to review the camera / model projection ops here.
        Matrix.multiplyMM(vMatrix, 0, eye.getEyeView(), 0, camera, 0);
        // Temporarily disabling the touch-camera movement,
//        Matrix.rotateM(pMatrix, 0, cameraRotation[1] * 0.05f, 1.0f, 0.0f, 0.0f);
//        Matrix.rotateM(pMatrix, 0, cameraRotation[0] * 0.05f, 0.0f, 1.0f, 0.0f);
//        Matrix.translateM(pMatrix, 0, cameraTranslation[0], cameraTranslation[1], cameraTranslation[2]);

        geometry.setResolution2fv(eye.getViewport().width, eye.getViewport().height);

        geometry.setEyeViewMatrix4fv(eye.getEyeView());
        // TODO: Re-enable light-pos and time?
//        geometry.setLightPos3fv();
//        geometry.setTime1f();

        geometry.update(pMatrix, vMatrix);
        geometry.draw();
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
    }

    @Override
    public void onRendererShutdown() {
        Timber.i("onRendererShutdown");
        // TODO: Check if this actually makes sense.
        debugOutputPublishSubject.onComplete();
        fpsSubject.onComplete();

        movingAverageDisposable.dispose();
    }

    // TODO: Quick and dirty delegation to test/debug.

    /**
     * Live update the geometry data, queue the change.
     * @param newGeometryData
     * @return if data was queued or not.
     */
    public void updateGeometryData(GeometryData newGeometryData) {
        geometry.updateGeometryData(newGeometryData);
    }

    /**
     * Live updates the shader, if compile fails, stays with old version.
     * <p/>
     * Need to make sure we run this in the right thread.
     */
    public void updateVertexShader(String newVertexShaderCode) {
        compileError = false ;
        try {
            geometry.setVertexShaderCode(newVertexShaderCode);
            geometry.initGlProgram();
        } catch (ShaderCompileException e) {
            Timber.d(e,"Couldn't compile shader, will stay with previous version.");
            compileError = true ;
            return;
        }
        geometry.initHandles();
    }

    /**
     * Live updates the fragment shader, if compile fails, stays with old version.
     * <p/>
     * Need to make sure we run this in the right thread.
     */
    public void updateFragmentShader(String newFragmentShaderCode) {
        compileError = false ;
        try {
            geometry.setFragmentShaderCode(newFragmentShaderCode);
            geometry.initGlProgram();
        } catch (ShaderCompileException e) {
            Timber.d(e,"Couldn't compile shader, will stay with previous version.");
            compileError = true ;
            return;
        }
        geometry.initHandles();
    }

    /**
     * Subscribe to get debug data stream.
     *
     * @param onNext called with updates.
     * @return subscription, can be useful for lifecycle management.
     */
    // TODO: Revisit / refactor
    public Disposable subscribe(Consumer<? super DebugData> onNext) {
        return debugOutputPublishSubject.subscribe(onNext);
    }

    public PublishSubject<DebugData> getDebugOutputPublishSubject() {
        return debugOutputPublishSubject;
    }

    public void pause() {
        state = State.PAUSED;
    }

    public void play() {
        state = State.PLAYING;
        lastClock = SystemClock.uptimeMillis();
    }

    public void seekTo(long time) {
        currentTime = time;
    }

    private void generateDebugOutput(Eye eye) {
        float[] ev = eye.getEyeView();
        if (eye.getType() == Eye.Type.RIGHT) {
            debugDataRight.setLine1(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[0], ev[1], ev[2], ev[3]));
            debugDataRight.setLine2(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[4], ev[5], ev[6], ev[7]));
            debugDataRight.setLine3(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[8], ev[9], ev[10], ev[11]));
            debugDataRight.setLine4(String.format("R %1.2f, %1.2f, %1.2f, %1.2f", ev[12], ev[13], ev[14], ev[15]));

            debugDataRight.setCompileError(compileError);

            debugOutputPublishSubject.onNext(debugDataRight);
        } else {
            debugDataLeft.setLine1(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[0], ev[1], ev[2], ev[3]));
            debugDataLeft.setLine2(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[4], ev[5], ev[6], ev[7]));
            debugDataLeft.setLine3(String.format("%1.2f, %1.2f, %1.2f, %1.2f", ev[8], ev[9], ev[10], ev[11]));
            debugDataLeft.setLine4(String.format("%1.2f, %1.2f, %1.2f, %1.2f L", ev[12], ev[13], ev[14], ev[15]));

            debugDataLeft.setCompileError(compileError);

            debugOutputPublishSubject.onNext(debugDataLeft);
        }
    }

    private void benchmarkFps() {
        benchCurrentTime = SystemClock.elapsedRealtime();
        benchMs = (float) ( (benchCurrentTime - benchLastTime) );
        benchLastTime = benchCurrentTime;
        fpsSubject.onNext(benchMs);
    }

}
