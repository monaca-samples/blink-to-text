package com.virtuoworks.cordova.plugin.canvascamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanvasCamera extends CordovaPlugin implements CanvasCameraInterface {
    private static final String TAG = "CanvasCamera";
    private static final boolean LOGGING = false; //false to disable logging

    protected final String K_USE_KEY = "use";
    protected final String K_FPS_KEY = "fps";
    protected final String K_WIDTH_KEY = "width";
    protected final String K_HEIGHT_KEY = "height";
    protected final String K_CANVAS_KEY = "canvas";
    protected final String K_CAPTURE_KEY = "capture";
    protected final String K_FLASH_MODE_KEY = "flashMode";
    protected final String K_HAS_THUMBNAIL_KEY = "hasThumbnail";
    protected final String K_THUMBNAIL_RATIO_KEY = "thumbnailRatio";
    protected final String K_LENS_ORIENTATION_KEY = "cameraFacing";

    private static final int SEC_START_CAPTURE = 0;
    private static final int SEC_STOP_CAPTURE = 1;
    private static final int SEC_FLASH_MODE = 2;
    private static final int SEC_CAMERA_POSITION = 3;

    private final static String[] FILENAMES = {"fullsize", "thumbnail"};
    private final static String[] PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    protected int mFps;
    protected int mWidth;
    protected int mHeight;
    protected String mUse;
    protected int mCameraFacing;
    protected String mFlashMode;
    protected int mCanvasHeight;
    protected int mCanvasWidth;
    protected int mCaptureHeight;
    protected int mCaptureWidth;
    protected boolean mHasThumbnail;
    protected double mThumbnailRatio;

    private JSONArray mArgs;
    private CallbackContext mCurrentCallbackContext;
    private CallbackContext mStartCaptureCallbackContext;

    private File mDir;
    private int mFileId = 0;
    private int mDisplayOrientation = 0;

    private Camera mCamera;
    private int mOrientation;
    private int mCameraId = 0;
    private int mPreviewFormat;
    private int[] mPreviewFpsRange;
    private String mPreviewFocusMode;
    private Camera.Size mPreviewSize;
    private boolean mPreviewing = false;

    private Activity mActivity = null;
    private TextureView mTextureView = null;
    private CameraHandlerThread mThread = null;

    @Override
    public String getFilenameSuffix() {
        return TAG.toLowerCase();
    }

    public void setDefaultOptions() {}

    public void parseAdditionalOptions(JSONObject options) throws Exception {}

    public void addPluginResultDataOutput(byte[] imageRawJpegData, JSONObject pluginResultDataOutput) {}

    private final Camera.PreviewCallback mCameraPreviewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            Runnable renderFrame = new Runnable() {
                public void run() {
                    if (mPreviewing && data.length > 0) {
                        // Get display orientation.
                        int displayOrientation = getDisplayOrientation();
                        // Getting output file paths.
                        Map<String, File> files = getImageFilesPaths();

                        // JSON output for images.
                        JSONObject images = new JSONObject();

                        // Creating fullsize image.
                        byte[] fullsizeData = dataToJpeg(data, mPreviewSize.width, mPreviewSize.height);

                        fullsizeData = getResizedAndRotatedImage(fullsizeData, mCanvasWidth, mCanvasHeight, displayOrientation);

                        // JSON output for fullsize image
                        JSONObject fullsize = new JSONObject();

                        if (mUse != null) {
                            if ("data".equals(mUse)) {
                                String fullsizeDataToB64 = "data:image/jpeg;base64," + Base64.encodeToString(fullsizeData, Base64.DEFAULT);
                                try {
                                    fullsize.put("data", fullsizeDataToB64);
                                } catch (JSONException e) {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize.data  into JSON result : " + e.getMessage());
                                }
                            }
                            if ("file".equals(mUse) && saveImage(fullsizeData, files.get("fullsize"))) {
                                try {
                                    fullsize.put("file", files.get("fullsize").getPath());
                                } catch (JSONException e) {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize.path into JSON result : " + e.getMessage());
                                }
                            }

                            addPluginResultDataOutput(fullsizeData, fullsize);
                        }

                        if (fullsize.length() > 0) {
                            try {
                                images.put("fullsize", fullsize);

                                try {
                                    fullsize.put("rotation", displayOrientation);
                                } catch (JSONException e) {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize.rotation into JSON result : " + e.getMessage());
                                }

                                try {
                                    fullsize.put("orientation", getCurrentOrientationToString());
                                } catch (JSONException e) {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize.orientation into JSON result : " + e.getMessage());
                                }

                                try {
                                    fullsize.put("timestamp", (new java.util.Date()).getTime());
                                } catch (JSONException e) {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize.timestamp into JSON result : " + e.getMessage());
                                }

                            } catch (JSONException e) {
                                if (LOGGING)
                                    Log.e(TAG, "Cannot put data.output.images.fullsize into JSON result : " + e.getMessage());
                            }

                            if (mHasThumbnail) {
                                // Creating thumbnail image
                                byte[] thumbnailData = getResizedImage(fullsizeData, mThumbnailRatio);

                                // JSON output for thumbnail image
                                JSONObject thumbnail = new JSONObject();

                                if (mUse != null) {
                                    if ("data".equals(mUse)) {
                                        String thumbnailDataToB64 = "data:image/jpeg;base64," + Base64.encodeToString(thumbnailData, Base64.DEFAULT);
                                        try {
                                            thumbnail.put("data", thumbnailDataToB64);
                                        } catch (JSONException e) {
                                            if (LOGGING)
                                                Log.e(TAG, "Cannot put data.output.images.thumbnail.data into JSON result : " + e.getMessage());
                                        }
                                    }
                                    if ("file".equals(mUse) && saveImage(thumbnailData, files.get("thumbnail"))) {
                                        try {
                                            thumbnail.put("file", files.get("thumbnail").getPath());
                                        } catch (JSONException e) {
                                            if (LOGGING)
                                                Log.e(TAG, "Cannot put data.output.images.thumbnail.path into JSON result : " + e.getMessage());
                                        }
                                    }
                                }

                                if (thumbnail.length() > 0) {
                                    try {
                                        images.put("thumbnail", thumbnail);

                                        try {
                                            thumbnail.put("rotation", displayOrientation);
                                        } catch (JSONException e) {
                                            if (LOGGING)
                                                Log.e(TAG, "Cannot put data.output.images.thumbnail.rotation into JSON result : " + e.getMessage());
                                        }

                                        try {
                                            thumbnail.put("orientation", getCurrentOrientationToString());
                                        } catch (JSONException e) {
                                            if (LOGGING)
                                                Log.e(TAG, "Cannot put data.output.images.thumbnail.orientation into JSON result : " + e.getMessage());
                                        }

                                        try {
                                            thumbnail.put("timestamp", (new java.util.Date()).getTime());
                                        } catch (JSONException e) {
                                            if (LOGGING)
                                                Log.e(TAG, "Cannot put data.output.images.thumbnail.timestamp into JSON result : " + e.getMessage());
                                        }
                                    } catch (JSONException e) {
                                        if (LOGGING)
                                            Log.e(TAG, "Cannot put data.output.images.thumbnail into JSON result : " + e.getMessage());
                                    }
                                }
                            }

                            // JSON output
                            JSONObject output = new JSONObject();

                            try {
                                output.put("images", images);
                            } catch (JSONException e) {
                                if (LOGGING)
                                    Log.e(TAG, "Cannot put data.output.images into JSON result : " + e.getMessage());
                            }

                            if (mPreviewing) {
                                PluginResult result = new PluginResult(PluginResult.Status.OK, getPluginResultMessage("OK", output));
                                result.setKeepCallback(true);
                                mStartCaptureCallbackContext.sendPluginResult(result);
                            }
                        }
                    }
                }
            };

            cordova.getThreadPool().submit(renderFrame);
        }
    };

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mCamera = getCameraInstance();

            if (mCamera != null) {

                mTextureView.setVisibility(View.INVISIBLE);
                mTextureView.setAlpha(0);

                try {
                    setPreviewParameters();

                    mCamera.setPreviewTexture(surface);
                    mCamera.setDisplayOrientation(mDisplayOrientation);
                    mCamera.setErrorCallback(mCameraErrorCallback);
                    mCamera.setPreviewCallback(mCameraPreviewCallback);

                    mFileId = 0;

                    mCamera.startPreview();
                    mPreviewing = true;
                    if (LOGGING) Log.i(TAG, "Camera [" + mCameraId + "] started.");
                } catch (Exception e) {
                    mPreviewing = false;
                    if (LOGGING) Log.e(TAG, "Failed to init preview: " + e.getMessage());
                    stopCamera();
                }
            } else {
                mPreviewing = false;
                if (LOGGING) Log.w(TAG, "Could not get camera instance.");
            }
        }

        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Ignored, Camera does all the work for us
        }

        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            stopCamera();
            return true;
        }

        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // Invoked every time there's a new Camera preview frame
        }
    };

    private final Camera.ErrorCallback mCameraErrorCallback = new Camera.ErrorCallback() {
        @Override
        public void onError(int error, Camera camera) {
            switch (error) {
                case Camera.CAMERA_ERROR_EVICTED:
                    if (LOGGING)
                        Log.e(TAG, "Camera was disconnected due to use by higher priority user. (error code - " + error + ")");
                    break;
                case Camera.CAMERA_ERROR_UNKNOWN:
                    if (LOGGING)
                        Log.e(TAG, "Unspecified camera error. (error code - " + error + ")");
                    break;
                case Camera.CAMERA_ERROR_SERVER_DIED:
                    if (LOGGING)
                        Log.e(TAG, "Media server died. In this case, the application must release the Camera object and instantiate a new one. (error code - " + error + ")");
                    break;
                default:
                    if (LOGGING) Log.e(TAG, "Camera error callback : (error code - " + error + ")");
                    break;
            }

            try {
                if (startCamera()) {
                    if (LOGGING) Log.i(TAG, "Camera successfully restarted.");
                } else {
                    if (LOGGING) Log.w(TAG, "Could not restart camera.");
                }
            } catch (Exception e) {
                if (LOGGING)
                    Log.e(TAG, "Something happened while stopping camera : " + e.getMessage());
            }
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        mActivity = cordova.getActivity();
        mDir = mActivity.getExternalCacheDir();
        super.initialize(cordova, webView);
        deleteCachedImageFiles();
    }

    @Override
    public void onStart() {
        super.onStart();
        mOrientation = getCurrentOrientation();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mCamera != null) {
            stopCamera();
        }
        if (mTextureView != null) {
            removePreviewSurface();
        }
        mPreviewing = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cordova.getThreadPool().shutdownNow();
        deleteCachedImageFiles();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (mPreviewing && mTextureView != null) {
            startCamera();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mPreviewing && mTextureView != null && newConfig.orientation != mOrientation) {
            setCameraOrientation(newConfig.orientation);
        }
    }

    private void setCameraOrientation(int orientation) {
        mOrientation = orientation;
        if (LOGGING) Log.i(TAG, "Orientation changed.");
        if (startCamera()) {
            if (LOGGING) Log.i(TAG, "Camera successfully restarted.");
        } else {
            if (LOGGING) Log.w(TAG, "Could not restart camera.");
        }
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        mArgs = args;
        mCurrentCallbackContext = callbackContext;

        if (PermissionHelper.hasPermission(this, Manifest.permission.CAMERA) &&
                PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            if ("startCapture".equals(action)) {
                if (LOGGING) Log.i(TAG, "Starting async startCapture thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        startCapture(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            } else if ("stopCapture".equals(action)) {
                if (LOGGING) Log.i(TAG, "Starting async stopCapture thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        stopCapture(mCurrentCallbackContext);
                    }
                });
                return true;
            } else if ("flashMode".equals(action)) {
                if (LOGGING) Log.i(TAG, "Starting async flashMode thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        flashMode(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            } else if ("cameraPosition".equals(action)) {
                if (LOGGING) Log.i(TAG, "Starting async cameraPosition thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        cameraPosition(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            }
        } else {
            if ("startCapture".equals(action)) {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermissions(this, SEC_START_CAPTURE, PERMISSIONS);
                return true;
            } else if ("stopCapture".equals(action)) {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_STOP_CAPTURE, Manifest.permission.CAMERA);
                return true;
            } else if ("flashMode".equals(action)) {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_FLASH_MODE, Manifest.permission.CAMERA);
                return true;
            } else if ("cameraPosition".equals(action)) {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_CAMERA_POSITION, Manifest.permission.CAMERA);
                return true;
            }
        }

        return false;
    }

    private void deferPluginResultCallback(final CallbackContext callbackContext) {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
        pluginResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pluginResult);
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                if (LOGGING) Log.w(TAG, "Permission Denied !");
                mCurrentCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION, getPluginResultMessage("Permission Denied !")));
                return;
            }
        }

        if (LOGGING) Log.i(TAG, "Permission granted !");

        switch (requestCode) {
            case SEC_START_CAPTURE:
                if (LOGGING) Log.i(TAG, "Starting async startCapture thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        startCapture(mArgs, mCurrentCallbackContext);
                    }
                });
                break;
            case SEC_STOP_CAPTURE:
                if (LOGGING) Log.i(TAG, "Starting async stopCapture thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        stopCapture(mCurrentCallbackContext);
                    }
                });
                break;
            case SEC_FLASH_MODE:
                if (LOGGING) Log.i(TAG, "Starting async flashMode thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        flashMode(mArgs, mCurrentCallbackContext);
                    }
                });
                break;
            case SEC_CAMERA_POSITION:
                if (LOGGING) Log.i(TAG, "Starting async cameraPosition thread...");
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        cameraPosition(mArgs, mCurrentCallbackContext);
                    }
                });
                break;
            default:
                return;
        }
    }

    private synchronized void startCapture(CallbackContext callbackContext) {
        mStartCaptureCallbackContext = callbackContext;

        if (startCamera()) {
            deferPluginResultCallback(mStartCaptureCallbackContext);
            if (LOGGING) Log.i(TAG, "Capture started !");
        } else {
            mStartCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Unable to start capture.")));
        }
    }

    private synchronized void startCapture(JSONArray args, CallbackContext callbackContext) {

        mStartCaptureCallbackContext = callbackContext;

        // init parameters - default values
        setDefaults();

        // parse options
        try {
            parseOptions(args.getJSONObject(0));
        } catch (Exception e) {
            if (LOGGING) Log.e(TAG, "Options parsing error : " + e.getMessage());
            mStartCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION, getPluginResultMessage(e.getMessage())));
            return;
        }

        startCapture(mStartCaptureCallbackContext);
    }

    private synchronized void stopCapture(CallbackContext stopCaptureCallbackContext) {
        try {
            stopCamera();
            removePreviewSurface();
            if (LOGGING) Log.i(TAG, "Capture stopped.");
            stopCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, getPluginResultMessage("Capture stopped.")));
        } catch (Exception e) {
            if (LOGGING) Log.e(TAG, "Could not stop capture : " + e.getMessage());
            stopCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.IO_EXCEPTION, getPluginResultMessage(e.getMessage())));
        }
    }

    private synchronized void flashMode(JSONArray args, CallbackContext flashModeCallbackContext) {
        if (mCamera != null) {
            boolean isFlashModeOn;

            try {
                isFlashModeOn = args.getBoolean(0);
            } catch (Exception e) {
                if (LOGGING) Log.e(TAG, "Failed to set flash mode : " + e.getMessage());
                flashModeCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION, getPluginResultMessage(e.getMessage())));
                return;
            }

            mFlashMode = getFlashMode(isFlashModeOn);

            if (startCamera()) {
                if (mStartCaptureCallbackContext != null) {
                    if (LOGGING) Log.i(TAG, "Flash mode applied !");
                    flashModeCallbackContext.success(getPluginResultMessage("OK"));
                } else {
                    if (LOGGING)
                        Log.w(TAG, "Could not set flash mode. No capture callback available !");
                    flashModeCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not set flash mode. No capture callback available !")));
                }
            } else {
                if (LOGGING) Log.w(TAG, "Could not set flash mode. Could not start camera !");
                flashModeCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not set flash mode. Could not start camera !")));
            }
        } else {
            if (LOGGING) Log.w(TAG, "Could not set flash mode. No camera available !");
            flashModeCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not set flash mode. No camera available !")));
        }
    }

    private synchronized void cameraPosition(JSONArray args, CallbackContext cameraPositionCallbackContext) {
        if (mCamera != null) {
            String cameraPosition;

            try {
                cameraPosition = args.getString(0);
            } catch (Exception e) {
                if (LOGGING) Log.e(TAG, "Failed to switch camera : " + e.getMessage());
                cameraPositionCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION, getPluginResultMessage(e.getMessage())));
                return;
            }

            mCameraFacing = getCameraFacing(cameraPosition);

            if (startCamera()) {
                if (mStartCaptureCallbackContext != null) {
                    if (LOGGING) Log.i(TAG, "Camera switched !");
                    cameraPositionCallbackContext.success(getPluginResultMessage("OK"));
                } else {
                    if (LOGGING)
                        Log.w(TAG, "Could not switch camera. No capture callback available !");
                    cameraPositionCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not switch camera. No capture callback available !")));
                }
            } else {
                if (LOGGING) Log.w(TAG, "Could not switch camera. Could not start camera !");
                cameraPositionCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not switch camera. Could not start camera !")));
            }
        } else {
            if (LOGGING) Log.w(TAG, "Could not switch camera. No camera available !");
            cameraPositionCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Could not switch camera. No camera available !")));
        }
    }

    public void setDefaults() {
        mFps = 30;
        mWidth = 352;
        mHeight = 288;
        mCanvasWidth = 352;
        mCanvasHeight = 288;
        mCaptureWidth = 352;
        mCaptureHeight = 288;
        mHasThumbnail = false;
        mThumbnailRatio = 1 / 6;
        mCameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
        setDefaultOptions();
    }

    private boolean initPreviewSurface() {
        if (mActivity != null) {
            mTextureView = new TextureView(mActivity);
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
            WindowManager mW = (WindowManager) mActivity.getSystemService(Context.WINDOW_SERVICE);
            int screenWidth = mW.getDefaultDisplay().getWidth();
            int screenHeight = mW.getDefaultDisplay().getHeight();
            mActivity.addContentView(mTextureView, new ViewGroup.LayoutParams(screenWidth, screenHeight));
            if (LOGGING) Log.i(TAG, "Camera preview surface initialized.");
            return true;
        } else {
            if (LOGGING) Log.w(TAG, "Could not initialize preview surface.");
            return false;
        }
    }

    private void removePreviewSurface() {
        if (mTextureView != null) {
            try {
                ViewGroup parentViewGroup = (ViewGroup) mTextureView.getParent();
                if (parentViewGroup != null) {
                    parentViewGroup.removeView(mTextureView);
                }
                if (LOGGING) Log.i(TAG, "Camera preview surface removed.");
            } catch (Exception e) {
                if (LOGGING) Log.w(TAG, "Could not remove view : " + e.getMessage());
            }
        }
    }

    private boolean startCamera() {
        stopCamera();
        removePreviewSurface();
        if (checkCameraHardware(mActivity)) {
            mPreviewing = true;
            if (LOGGING) Log.i(TAG, "Initializing preview surface...");
            return initPreviewSurface();
        } else {
            mPreviewing = false;
            if (LOGGING) Log.w(TAG, "No camera detected !");
            return false;
        }
    }

    private void stopCamera() {
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);
                mCamera.release();
                mCamera = null;
                if (LOGGING) Log.i(TAG, "Camera [" + mCameraId + "] stopped.");
                mCameraId = 0;
            } catch (Exception e) {
                if (LOGGING)
                    Log.e(TAG, "Could not stop camera [" + mCameraId + "] : " + e.getMessage());
            }
        }
        mPreviewing = false;
    }

    private int getCameraRotation() {
        int degrees = getDisplayRotation();

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        int cameraRotationOffset = info.orientation;

        int cameraRotation;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            cameraRotation = (360 + cameraRotationOffset + degrees) % 360;
        } else {
            cameraRotation = (360 + cameraRotationOffset - degrees) % 360;
        }

        return cameraRotation;
    }

    private int getDisplayOrientation() {
        int degrees = getDisplayRotation();

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        int cameraRotationOffset = info.orientation;

        int displayOrientation;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (cameraRotationOffset + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;  // compensate the mirror
        } else {  // back-facing
            displayOrientation = (cameraRotationOffset - degrees + 360) % 360;
        }

        return displayOrientation;
    }

    private int getDisplayRotation() {
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();

        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break; // Natural orientation.
            case Surface.ROTATION_90:
                degrees = 90;
                break; // Landscape left.
            case Surface.ROTATION_180:
                degrees = 180;
                break; // Upside down.
            case Surface.ROTATION_270:
                degrees = 270;
                break; // Landscape right.
            default:
                degrees = 0;
                break;
        }

        return degrees;
    }

    private void setPreviewParameters() {
        if (mCamera != null) {
            // set display orientation
            mDisplayOrientation = getDisplayOrientation();
            Camera.Parameters parameters = mCamera.getParameters();
            // sets optimal preview size.
            mPreviewSize = getOptimalPreviewSize(parameters);
            if (mPreviewSize != null) {
                parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
                if (LOGGING)
                    Log.i(TAG, "Preview size is set to w : " + mPreviewSize.width + ", h : " + mPreviewSize.height + ".");
            }
            // sets camera rotation
            int mCameraRotation = getCameraRotation();
            parameters.setRotation(mCameraRotation);
            // sets optimal preview fps range.
            mPreviewFpsRange = getOptimalFrameRate(parameters);
            if (mPreviewFpsRange != null) {
                parameters.setPreviewFpsRange(mPreviewFpsRange[0], mPreviewFpsRange[1]);
                if (LOGGING)
                    Log.i(TAG, "Preview fps range is set to min : " + (mPreviewFpsRange[0] / 1000) + ", max : " + (mPreviewFpsRange[1] / 1000) + ".");
            }
            // sets optimal preview focus mode.
            mPreviewFocusMode = getOptimalFocusMode(parameters);
            if (mPreviewFocusMode != null) {
                parameters.setFocusMode(mPreviewFocusMode);
                if (LOGGING)
                    Log.i(TAG, "Preview focus mode is set to : " + mPreviewFocusMode + ".");
            }
            // sets flash mode
            mFlashMode = getOptimalFlashMode(parameters);
            if (mFlashMode != null) {
                parameters.setFlashMode(mFlashMode);
                if (LOGGING) Log.i(TAG, "Preview flash mode is set to : " + mFlashMode + ".");
            }
            // sets camera parameters
            mCamera.setParameters(parameters);
            // gets preview pixel format
            mPreviewFormat = parameters.getPreviewFormat();
        }
    }

    private int[] getOptimalFrameRate(Camera.Parameters params) {
        List<int[]> supportedRanges = params.getSupportedPreviewFpsRange();

        int[] optimalFpsRange = new int[]{30, 30};

        for (int[] range : supportedRanges) {
            optimalFpsRange = range;
            if (range[1] <= (mFps * 1000)) {
                break;
            }
        }

        return optimalFpsRange;
    }

    private String getOptimalFocusMode(Camera.Parameters params) {
        List<String> focusModes = params.getSupportedFocusModes();

        String result;

        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            result = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
        } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            result = Camera.Parameters.FOCUS_MODE_AUTO;
        } else {
            result = params.getSupportedFocusModes().get(0);
        }

        return result;
    }

    private String getOptimalFlashMode(Camera.Parameters parameters) {
        if (mFlashMode != null) {
            List<String> supportedFlashModes = parameters.getSupportedFlashModes();
            if (supportedFlashModes != null) {
                for (String str : supportedFlashModes) {
                    if (str.trim().contains(mFlashMode))
                        return mFlashMode;
                }
                return null;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    private Camera.Size getOptimalPreviewSize(Camera.Parameters parameters) {
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) mCaptureWidth / mCaptureHeight;
        if (sizes == null) return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = mCaptureHeight;

        // Try to find an size match aspect ratio and size
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    private boolean checkCameraHardware(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    private Camera getCameraInstance() {
        Camera camera = null;

        try {
            int cameraId;
            int cameraCount = Camera.getNumberOfCameras();
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();

            for (cameraId = 0; cameraId < cameraCount; cameraId++) {
                Camera.getCameraInfo(cameraId, cameraInfo);
                if (cameraInfo.facing == mCameraFacing) {
                    if (LOGGING) Log.i(TAG, "Trying to open camera : " + cameraId);
                    try {
                        mCameraId = cameraId;
                        //camera = Camera.open(cameraId);

                        if (mThread == null) {
                            mThread = new CameraHandlerThread();
                        }

                        synchronized (mThread) {
                            camera = mThread.openCamera(cameraId);
                        }

                        if (LOGGING) Log.i(TAG, "Camera [" + cameraId + "] opened.");
                        break;
                    } catch (RuntimeException e) {
                        if (LOGGING) Log.e(TAG, "Unable to open camera : " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGING) Log.e(TAG, "No available camera : " + e.getMessage());
        }

        return camera;
    }

    private synchronized Map<String, File> getImageFilesPaths() {
        Map<String, File> files = new HashMap<String, File>();

        if (mDir != null) {
            mFileId++;

            for (String fileName : FILENAMES) {
                if (mFileId > mFps) {
                    File prevFile = new File(mDir, String.valueOf(fileName.charAt(0)) + (mFileId - mFps) + "-" + getFilenameSuffix() + ".jpg");
                    if (prevFile.exists()) {
                        if (prevFile.delete()) {
                            if (LOGGING)
                                Log.v(TAG, "Previously cached file " + prevFile.getName() + " deleted !");
                        } else {
                            if (LOGGING)
                                Log.w(TAG, "Could not delete previous cached file " + prevFile.getName() + ".");
                        }
                    }
                }

                File curFile = new File(mDir, String.valueOf(fileName.charAt(0)) + mFileId + "-" + getFilenameSuffix() + ".jpg");
                if (curFile.exists()) {
                    if (curFile.delete()) {
                        if (LOGGING)
                            Log.v(TAG, "Current cached file " + curFile.getName() + " deleted !");
                    } else {
                        if (LOGGING)
                            Log.w(TAG, "Could not delete current cached file " + curFile.getName() + ".");
                    }
                }

                files.put(fileName, curFile);
            }
        }

        return files;
    }

    private void deleteCachedImageFiles() {
        if (mActivity != null && mDir != null) {
            if (LOGGING) Log.v(TAG, "Deleting cached files...");
            File[] filesList = mDir != null ? mDir.listFiles() : new File[0];
            for (File aFilesList : filesList) {
                if (aFilesList.isFile()) {
                    String fileName = aFilesList.getName();
                    int found = fileName.lastIndexOf("-" + getFilenameSuffix() + ".jpg");
                    if (found > 0) {
                        if (aFilesList.delete()) {
                            if (LOGGING) Log.v(TAG, "Cached file " + fileName + " deleted !");
                        } else {
                            if (LOGGING)
                                Log.w(TAG, "Could not delete cached file " + fileName + ".");
                        }
                    }
                }
            }
        }
    }

    private boolean saveImage(byte[] bytes, File file) {
        if (file != null && bytes.length > 0) {
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(file);
                output.write(bytes);
            } catch (FileNotFoundException e) {
                if (LOGGING) Log.e(TAG, "Could not find output file : " + e.getMessage());
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                if (LOGGING) Log.e(TAG, "Could not write output file : " + e.getMessage());
                return false;
            } finally {
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        if (LOGGING)
                            Log.e(TAG, "Could not close file output stream : " + e.getMessage());
                        return false;
                    }
                }
                return true;
            }
        } else {
            return false;
        }
    }

    private byte[] dataToJpeg(byte[] byteArray, int width, int height) {
        if (byteArray.length > 0) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // The second parameter is the actual image format
            YuvImage yuvImage = new YuvImage(byteArray, mPreviewFormat, width, height, null);
            // width and height define the size of the bitmap filled with the preview image
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            // returns the jpeg as bytes array
            return out.toByteArray();
        } else {
            return byteArray;
        }
    }

    private byte[] getResizedImage(byte[] byteArray, double ratio) {
        if (byteArray.length > 0) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);

            int targetWidth = (int) (bitmap.getWidth() * ratio);
            int targetHeight = (int) (bitmap.getHeight() * ratio);

            bitmap.recycle();

            if (targetWidth > 0 && targetHeight > 0) {
                return getResizedAndRotatedImage(byteArray, targetWidth, targetHeight, 0);
            } else {
                return byteArray;
            }
        } else {
            return byteArray;
        }
    }

    private byte[] getResizedAndRotatedImage(byte[] byteArray, int targetWidth, int targetHeight, int angle) {
        if (byteArray.length > 0) {
            // Sets bitmap factory options
            BitmapFactory.Options bOptions = new BitmapFactory.Options();
            // Set inJustDecodeBounds=true to check dimensions
            bOptions.inJustDecodeBounds = true;
            // Decode unscaled unrotated bitmap boundaries only
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, bOptions);

            if (targetWidth > 0 && targetHeight > 0) {
                // Calculate aspect ratio
                int[] widthHeight = calculateAspectRatio(bOptions.outWidth , bOptions.outHeight, targetWidth, targetHeight);

                int width = widthHeight[0];
                int height = widthHeight[1];

                bOptions.inSampleSize = 1;
                // Adjust inSampleSize
                if (bOptions.outHeight > height || bOptions.outWidth > width) {
                    final int halfOutHeight = bOptions.outHeight / 2;
                    final int halfOutWidth = bOptions.outWidth / 2;

                    while ((halfOutHeight / bOptions.inSampleSize) >= height
                            && (halfOutWidth / bOptions.inSampleSize) >= width) {
                        bOptions.inSampleSize *= 2;
                    }
                }
                // Set inJustDecodeBounds=false to get all pixels
                bOptions.inJustDecodeBounds = false;
                // Decode unscaled unrotated bitmap
                Bitmap bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, bOptions);
                // Create scaled bitmap
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);

                if (angle != 0 || mCameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    final Matrix matrix = new Matrix();

                    // Mirroring ?
                    if (mCameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        matrix.preScale(-1.0f, 1.0f);
                    }
                    // Rotation ?
                    if (angle != 0) {
                        // Rotation
                        matrix.postRotate(angle);
                    }

                    // Create rotated bitmap
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);
                }

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);

                // Recycling bitmap
                bitmap.recycle();

                return byteArrayOutputStream.toByteArray();
            } else {
                return byteArray;
            }
        } else {
            return byteArray;
        }
    }

    private int[] calculateAspectRatio(int origWidth, int origHeight, int targetWidth, int targetHeight) {
        int newWidth = targetWidth;
        int newHeight = targetHeight;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (int) (newWidth / (double) origWidth * origHeight);
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (int) (newHeight / (double) origHeight * origWidth);
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] widthHeight = new int[2];
        widthHeight[0] = newWidth;
        widthHeight[1] = newHeight;

        return widthHeight;
    }

    private void parseOptions(JSONObject options) throws Exception {
        if (options == null) {
            return;
        }

        // usage
        if (options.has(K_USE_KEY)) {
            mUse = options.getString(K_USE_KEY);
        }

        // flash mode
        if (options.has(K_FLASH_MODE_KEY)) {
            mFlashMode = getFlashMode(options.getBoolean(K_FLASH_MODE_KEY));
        }

        // lens orientation
        if (options.has(K_LENS_ORIENTATION_KEY)) {
            mCameraFacing = getCameraFacing(options.getString(K_LENS_ORIENTATION_KEY));
        }

        // fps
        if (options.has(K_FPS_KEY)) {
            mFps = options.getInt(K_FPS_KEY);
        }

        // width
        if (options.has(K_WIDTH_KEY)) {
            mWidth = mCaptureWidth = mCanvasWidth = options.getInt(K_WIDTH_KEY);
        }

        // height
        if (options.has(K_HEIGHT_KEY)) {
            mHeight = mCaptureHeight = mCanvasHeight = options.getInt(K_HEIGHT_KEY);
        }

        // hasThumbnail
        if (options.has(K_HAS_THUMBNAIL_KEY)) {
            mHasThumbnail = options.getBoolean(K_HAS_THUMBNAIL_KEY);
        }

        // thumbnailRatio
        if (options.has(K_THUMBNAIL_RATIO_KEY)) {
            mThumbnailRatio = options.getDouble(K_THUMBNAIL_RATIO_KEY);
        }

        // canvas
        if (options.has(K_CANVAS_KEY)) {
            JSONObject canvas = options.getJSONObject(K_CANVAS_KEY);
            if (canvas.has(K_WIDTH_KEY)) {
                mCanvasWidth = canvas.getInt(K_WIDTH_KEY);
            }
            if (canvas.has(K_HEIGHT_KEY)) {
                mCanvasHeight = canvas.getInt(K_HEIGHT_KEY);
            }
        }

        // capture
        if (options.has(K_CAPTURE_KEY)) {
            JSONObject capture = options.getJSONObject(K_CAPTURE_KEY);
            // resolution.width
            if (capture.has(K_WIDTH_KEY)) {
                mCaptureWidth = capture.getInt(K_WIDTH_KEY);
            }
            // resolution.height
            if (capture.has(K_HEIGHT_KEY)) {
                mCaptureHeight = capture.getInt(K_HEIGHT_KEY);
            }
        }

        // parsing additional options
        parseAdditionalOptions(options);
    }

    private JSONObject getPluginResultMessage(String message) {

        JSONObject output = new JSONObject();
        JSONObject images = new JSONObject();

        try {
            output.put("images", images);

            try {
                images.put("orientation", mDisplayOrientation);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.output.images.orientation into JSON result : " + e.getMessage());
            }
        } catch (JSONException e) {
            if (LOGGING)
                Log.e(TAG, "Cannot put data.output.images into JSON result : " + e.getMessage());
        }

        return getPluginResultMessage(message, output);
    }

    private JSONObject getPluginResultMessage(String message, JSONObject output) {

        JSONObject pluginResultMessage = new JSONObject();

        try {
            pluginResultMessage.put("message", message);
        } catch (JSONException e) {
            if (LOGGING) Log.e(TAG, "Cannot put data.message into JSON result : " + e.getMessage());
        }

        JSONObject options = new JSONObject();

        try {
            pluginResultMessage.put("options", options);

            try {
                options.put("width", mWidth);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.width into JSON result : " + e.getMessage());
            }

            try {
                options.put("height", mHeight);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.height into JSON result : " + e.getMessage());
            }

            try {
                options.put("fps", mFps);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.fps into JSON result : " + e.getMessage());
            }

            try {
                options.put("flashMode", getFlashModeAsBoolean(mFlashMode));
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.flashMode into JSON result : " + e.getMessage());
            }

            try {
                options.put("cameraFacing", getCameraFacingToString(mCameraFacing));
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.cameraFacing into JSON result : " + e.getMessage());
            }

            try {
                options.put("hasThumbnail", mHasThumbnail);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.hasThumbnail into JSON result : " + e.getMessage());
            }

            try {
                options.put("thumbnailRatio", mThumbnailRatio);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.thumbnailRatio into JSON result : " + e.getMessage());
            }

            JSONObject canvas = new JSONObject();

            try {
                options.put("canvas", canvas);

                try {
                    canvas.put("width", mCanvasWidth);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.options.canvas.width into JSON result : " + e.getMessage());
                }

                try {
                    canvas.put("height", mCanvasHeight);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.options.canvas.height into JSON result : " + e.getMessage());
                }

            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.canvas into JSON result : " + e.getMessage());
            }

            JSONObject capture = new JSONObject();

            try {
                options.put("capture", capture);

                try {
                    capture.put("width", mCaptureWidth);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.options.capture.width into JSON result : " + e.getMessage());
                }

                try {
                    capture.put("height", mCaptureHeight);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.options.capture.width into JSON result : " + e.getMessage());
                }

            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.options.capture into JSON result : " + e.getMessage());
            }

        } catch (JSONException e) {
            if (LOGGING) Log.e(TAG, "Cannot put data.options into JSON result : " + e.getMessage());
        }

        JSONObject preview = new JSONObject();

        try {
            pluginResultMessage.put("preview", preview);

            try {
                preview.put("started", mPreviewing);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.preview.started into JSON result : " + e.getMessage());
            }
            try {
                preview.put("format", getPreviewFormatToString(mPreviewFormat));
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.preview.format into JSON result : " + e.getMessage());
            }
            try {
                preview.put("focusMode", mPreviewFocusMode);
            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.preview.focusMode into JSON result : " + e.getMessage());
            }
            if (mPreviewSize != null) {
                try {
                    preview.put("width", mPreviewSize.width);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.preview.width into JSON result : " + e.getMessage());
                }

                try {
                    preview.put("height", mPreviewSize.height);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.preview.height into JSON result : " + e.getMessage());
                }
            }

            JSONObject camera = new JSONObject();

            try {
                preview.put("camera", camera);

                try {
                    camera.put("id", mCameraId);
                } catch (JSONException e) {
                    if (LOGGING)
                        Log.e(TAG, "Cannot put data.preview.camera.id into JSON result : " + e.getMessage());
                }

            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.preview.camera into JSON result : " + e.getMessage());
            }

            JSONObject fps = new JSONObject();

            try {
                preview.put("fps", fps);

                if (mPreviewFpsRange != null) {
                    try {
                        fps.put("min", mPreviewFpsRange[0] / 1000);
                    } catch (JSONException e) {
                        if (LOGGING)
                            Log.e(TAG, "Cannot put data.preview.fps.min into JSON result : " + e.getMessage());
                    }
                    try {
                        fps.put("max", mPreviewFpsRange[1] / 1000);
                    } catch (JSONException e) {
                        if (LOGGING)
                            Log.e(TAG, "Cannot put data.preview.fps.max into JSON result : " + e.getMessage());
                    }
                }

            } catch (JSONException e) {
                if (LOGGING)
                    Log.e(TAG, "Cannot put data.preview.fps into JSON result : " + e.getMessage());
            }

        } catch (JSONException e) {
            if (LOGGING) Log.e(TAG, "Cannot put data.preview into JSON result : " + e.getMessage());
        }

        try {
            pluginResultMessage.put("output", output);
        } catch (JSONException e) {
            if (LOGGING) Log.e(TAG, "Cannot put data.output into JSON result : " + e.getMessage());
        }

        return pluginResultMessage;
    }

    private int getCameraFacing(String option) {
        if ("front".equals(option)) {
            return Camera.CameraInfo.CAMERA_FACING_FRONT;
        } else {
            return Camera.CameraInfo.CAMERA_FACING_BACK;
        }
    }

    private String getCameraFacingToString(int constant) {
        if (constant == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return "front";
        } else {
            return "back";
        }
    }

    private String getFlashMode(boolean isFlashModeOn) {
        if (isFlashModeOn) {
            return Camera.Parameters.FLASH_MODE_TORCH;
        } else {
            return Camera.Parameters.FLASH_MODE_OFF;
        }
    }

    private boolean getFlashModeAsBoolean(String constant) {
        return constant != null && constant.equals(Camera.Parameters.FLASH_MODE_TORCH);
    }

    private int getCurrentOrientation() {
        if (mActivity != null) {
            return mActivity.getResources().getConfiguration().orientation;
        } else {
            return Configuration.ORIENTATION_UNDEFINED;
        }
    }

    private String getCurrentOrientationToString() {
        switch (mOrientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                return "landscape";
            case Configuration.ORIENTATION_PORTRAIT:
                return "portrait";
            default:
                return "unknown";
        }
    }

    private String getPreviewFormatToString(int previewFormat) {
        switch (previewFormat) {
            case ImageFormat.DEPTH16:
                return "DEPTH16";
            case ImageFormat.DEPTH_POINT_CLOUD:
                return "DEPTH_POINT_CLOUD";
            case ImageFormat.FLEX_RGBA_8888:
                return "FLEX_RGBA_8888";
            case ImageFormat.FLEX_RGB_888:
                return "FLEX_RGB_888";
            case ImageFormat.JPEG:
                return "JPEG";
            case ImageFormat.NV16:
                return "NV16";
            case ImageFormat.NV21:
                return "NV21";
            case ImageFormat.PRIVATE:
                return "PRIVATE";
            case ImageFormat.RAW10:
                return "RAW10";
            case ImageFormat.RAW12:
                return "RAW12";
            case ImageFormat.RAW_PRIVATE:
                return "RAW_PRIVATE";
            case ImageFormat.RAW_SENSOR:
                return "RAW_SENSOR";
            case ImageFormat.RGB_565:
                return "RGB_565";
            case ImageFormat.UNKNOWN:
                return "UNKNOWN";
            case ImageFormat.YUV_420_888:
                return "YUV_420_888";
            case ImageFormat.YUV_422_888:
                return "YUV_422_888";
            case ImageFormat.YUV_444_888:
                return "YUV_444_888";
            case ImageFormat.YUY2:
                return "YUY2";
            case ImageFormat.YV12:
                return "YV12";
            default:
                return "UNKNOWN";
        }
    }

    private static class CameraHandlerThread extends HandlerThread {
        private Camera mCamera = null;
        private Handler mHandler = null;

        CameraHandlerThread() {
            super("CameraHandlerThread");
            start();
            mHandler = new Handler(getLooper());
        }

        private synchronized void cameraOpened() {
            notify();
        }

        public Camera openCamera(final int cameraId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCamera = Camera.open(cameraId);
                        if (LOGGING) Log.i(TAG, "Camera [" + cameraId + "] opened.");
                    } catch (RuntimeException e) {
                        if (LOGGING) Log.e(TAG, "Unable to open camera : " + e.getMessage());
                    }
                    cameraOpened();
                }
            });

            try {
                wait();
            } catch (InterruptedException e) {
                if (LOGGING)
                    Log.w(TAG, "Camera opening thread wait was interrupted : " + e.getMessage());
            }

            return mCamera;
        }
    }
}
