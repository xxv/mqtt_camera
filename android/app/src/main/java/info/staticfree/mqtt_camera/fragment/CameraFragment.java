/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package info.staticfree.mqtt_camera.fragment;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v13.app.FragmentCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import info.staticfree.mqtt_camera.R;
import info.staticfree.mqtt_camera.image.ImagePublisher;
import info.staticfree.mqtt_camera.mqtt.MqttRemote;
import info.staticfree.mqtt_camera.util.SizeUtil;
import info.staticfree.mqtt_camera.view.AutoFitTextureView;

@SuppressWarnings("MagicNumber")
public class CameraFragment extends Fragment
        implements FragmentCompat.OnRequestPermissionsResultCallback,
        MqttRemote.RemoteControlCamera {

    @Nullable
    private MqttRemote mqttRemote;
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "MQTTCamera";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String cameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView textureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession captureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice cameraDevice;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size previewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release();
            CameraFragment.this.cameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            CameraFragment.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            CameraFragment.this.cameraDevice = null;
            Toast.makeText(getActivity().getApplication(), "Camera error", Toast.LENGTH_LONG)
                    .show();
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread backgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler backgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader imageReader;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener onImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (mqttRemote != null) {
                backgroundHandler.post(new ImagePublisher(reader.acquireNextImage(), mqttRemote,
                        "image"));
            }
        }
    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder previewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #previewRequestBuilder}
     */
    private CaptureRequest previewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #captureCallback
     */
    private int state = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean flashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int sensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private final CameraCaptureSession.CaptureCallback captureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (state) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (autoFocus) {
                        if (afState == null) {
                            captureStillPicture();
                        } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                state = STATE_PICTURE_TAKEN;
                                captureStillPicture();
                            } else {
                                runPrecaptureSequence();
                            }
                        }
                    } else {
                        state = STATE_PREVIEW;
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
            process(result);
        }
    };
    private boolean autoFocus;
    private boolean showPreview = true;

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(String text) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(v -> takePicture());
        view.findViewById(R.id.focus).setOnClickListener(v -> refocus());
        textureView = (AutoFitTextureView) view.findViewById(R.id.camera_surface);
    }

    @Override
    public void refocus() {
        lockFocus();
    }

    @Override
    public void setAutoFocus(boolean autoFocus) {
        this.autoFocus = autoFocus;

        View view = getView();
        if (view != null) {
            view.findViewById(R.id.focus).setEnabled(!autoFocus);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mqttRemote = new MqttRemote(getActivity(), this);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }

        if (mqttRemote != null) {
            mqttRemote.onResume();
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();

        if (mqttRemote != null) {
            mqttRemote.onPause();
        }
    }

    private void requestCameraPermission() {
        if (FragmentCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA },
                    REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new SizeUtil.CompareSizesByArea());
                imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                imageReader.setOnImageAvailableListener(
                        onImageAvailableListener, backgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (sensorOrientation == 90 || sensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (sensorOrientation == 0 || sensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = SizeUtil.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(
                            previewSize.getWidth(), previewSize.getHeight());
                } else {
                    textureView.setAspectRatio(
                            previewSize.getHeight(), previewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                flashSupported = available == null ? false : available;

                this.cameraId = cameraId;

                return;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera", e);
        } catch (@SuppressWarnings("ProhibitedExceptionCaught") NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }

        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        CameraManager manager =
                (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Error stopping background thread", e);
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder
                    = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                if (autoFocus) {
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                } else {
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_AUTO);
                                }

                                // Flash is automatically enabled when necessary.
                                setAutoFlash(previewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest,
                                        captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error creating preview session", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session", e);
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    @Override
    public void takePicture() {
        if (autoFocus) {
            lockFocus();
        } else {
            captureStillPicture();
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK;
            captureSession.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error locking focus", e);
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #captureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE;
            captureSession.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error running pre-capture sequence", e);
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #captureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        try {
            Activity activity = getActivity();
            if (null == activity || null == cameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            if (autoFocus) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
            setAutoFlash(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {

                    if (autoFocus) {
                        unlockFocus();
                    }

                    if (showPreview) {
                        textureView.setVisibility(View.VISIBLE);
                        restartPreview();
                    } else {
                        state = STATE_PREVIEW;
                        textureView.setVisibility(View.INVISIBLE);
                    }
                }
            };

            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error capturing image", e);
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(previewRequestBuilder);
            captureSession.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error unlocking focus", e);
        }
    }

    private void restartPreview() {
        try {
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW;
            captureSession.setRepeatingRequest(previewRequest, captureCallback,
                    backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error unlocking focus", e);
        }
    }

    @Override
    public void setDimScreen(boolean dimScreen) {
        WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
        if (dimScreen) {
            lp.screenBrightness = 0;
        } else {
            lp.screenBrightness = -1;
        }
        getActivity().getWindow().setAttributes(lp);
    }

    @Override
    public void setDisablePreview(boolean disablePreview) {
        showPreview = !disablePreview;
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
        }
    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> FragmentCompat.requestPermissions(parent,
                                    new String[] { Manifest.permission.CAMERA },
                                    REQUEST_CAMERA_PERMISSION))
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> {
                                Activity activity = parent.getActivity();
                                if (activity != null) {
                                    activity.finish();
                                }
                            })
                    .create();
        }
    }
}
