package com.example.opencam.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.graphics.ImageFormat;
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
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import com.example.opencam.ImageSaver;
import com.example.opencam.R;
import com.example.opencam.databinding.FragmentCameraBinding;
import com.example.opencam.utils.CameraUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraFragment extends Fragment {

    public static final String TAG = "OpenCam";
    public static final int REQUEST_PERMISSION = 100;
    public static final int STATE_PREVIEW = 0;
    public static final int STATE_WAIT_LOCK = 1;
    private int mCaptureState = STATE_PREVIEW;
    FragmentCameraBinding cameraBinding;
    boolean allPermissionGranted = true;
    private static final String[] PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
    };
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private CameraManager cameraManager;
    private String cameraId;
    protected CameraDevice cameraDevice;
    private CameraCaptureSession mPreviewCaptureSession;
    protected CaptureRequest.Builder previewRequestBuilder;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private boolean mEnableStabilization = false;

    private File mImageFile;
    private File mImageFolder;
    private String mImageFileName;

    private Size mImageSize;
    private ImageReader mImageReader;

    private MediaRecorder recorder;

    private boolean isRecording = false;

    private Size mPreviewSize;

    private Size mVideoSize;

    private File mVideoFile;
    private File mVideoFolder;
    private String mVideoFileName;
    
    private int mRotation;

    private boolean mFlashState = false;

    private ArrayList<String> mAvailableStabilizationModes = new ArrayList<>();

    private static final Map<Integer, String> stabilityMap = new HashMap<>();

    static {
        stabilityMap.put(0, "OFF");
        stabilityMap.put(1, "ON");
        stabilityMap.put(2, "PREVIEW_STABILIZATION");
    }

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final int PICTURE = 1;
    private static final int RECORD = 2;
    private int CAMERA_STATUS = PICTURE;

    private CameraDevice.StateCallback mOpenCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (isRecording) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                startRecord();
                recorder.start();
                cameraBinding.meter.setBase(SystemClock.elapsedRealtime());
                cameraBinding.meter.setVisibility(View.VISIBLE);
                cameraBinding.meter.start();
            } else {
                startPreview();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    @SuppressLint("MissingPermission")
    private ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                // Check if all permissions are granted
                boolean allPermissionsGranted = true;
                for (Boolean isGranted : permissions.values()) {
                    if (!isGranted) {
                        allPermissionsGranted = false;
                        break;
                    }
                }
                if (allPermissionsGranted) {
                    try {
                        cameraManager.openCamera(cameraId, mOpenCameraStateCallback, null);
                    } catch (CameraAccessException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    Toast.makeText(getContext(), "can't open camera", Toast.LENGTH_SHORT).show();
                }
            });



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        cameraBinding = FragmentCameraBinding.inflate(inflater, container, false);
        return cameraBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        createImageFolder();
        createVideoFolder();

        recorder = new MediaRecorder();

        cameraBinding.texture.setSurfaceTextureListener(textureListener);
        cameraBinding.btnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (CAMERA_STATUS == PICTURE) {
                    cameraBinding.btnSwitch.setBackgroundResource(R.drawable.baseline_camera_alt_24);
                    cameraBinding.btnCapture.setBackgroundResource(R.drawable.baseline_video_camera_back_24);
                    CAMERA_STATUS = RECORD;
                } else if (CAMERA_STATUS == RECORD) {
                    cameraBinding.btnSwitch.setBackgroundResource(R.drawable.baseline_video_camera_back_24);
                    cameraBinding.btnCapture.setBackgroundResource(R.drawable.baseline_camera_alt_24);
                    CAMERA_STATUS = PICTURE;
                } else {
                    return;
                }
            }
        });
        cameraBinding.btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (CAMERA_STATUS == RECORD) {
                    if (isRecording) {
                        recorder.stop();
                        recorder.reset();
                        startPreview();
                        cameraBinding.meter.stop();
                        cameraBinding.meter.setVisibility(View.INVISIBLE);
                    } else {
                        try {
                            createVideoFileName();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        startRecord();
                        recorder.start();
                        cameraBinding.meter.setBase(SystemClock.elapsedRealtime());
                        cameraBinding.meter.setVisibility(View.VISIBLE);
                        cameraBinding.meter.start();
                    }
                    isRecording = !isRecording;
                } else {
                    lockFocus();
                }

            }
        });
        cameraBinding.btnSetting.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });
        cameraBinding.btnFlash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mFlashState = !mFlashState;
                if (mFlashState) {
                    previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    cameraBinding.btnFlash.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.baseline_flashlight_on_24));
                } else {
                    previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                    cameraBinding.btnFlash.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.baseline_flashlight_off_24));
                }
                updatePreview();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (cameraBinding.texture.isAvailable()) {
            openCamera(cameraBinding.texture.getWidth(), cameraBinding.texture.getHeight());
        } else {
            cameraBinding.texture.setSurfaceTextureListener(textureListener);
        }
        startBackgroundThread();
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;
    }

    private void openCamera(int width, int height) {
        cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = cameraManager.getCameraIdList()[0];
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mPreviewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height);
            mVideoSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(MediaRecorder.class), width, height);
            mImageSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), width, height);
            mRotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();

            // setAspectRatioTextureView(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // check available stabilization modes
            int[] stabilizationModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            for (int mode : stabilizationModes) {
                mAvailableStabilizationModes.add(stabilityMap.get(mode));
            }
            int[] opticalStabilizationModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            // set ImageReader
            mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 1);
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    mBackgroundHandler.post(new ImageSaver(mImageReader.acquireNextImage(), mImageFile));
                }
            }, mBackgroundHandler);

            // check permissions
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMultiplePermissionsLauncher.launch(PERMISSIONS);
            } else {
                cameraManager.openCamera(cameraId, mOpenCameraStateCallback, null);
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void startStillCaptureRequest() {
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            previewRequestBuilder.addTarget(mImageReader.getSurface());
            previewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(mRotation));
            mPreviewCaptureSession.capture(previewRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    try {
                        mImageFile = createImageFileName();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(getContext(), "Saved:" + mImageFileName, Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startRecord() {
        try {
            setupMediaRecorder();
            SurfaceTexture previewTexture = cameraBinding.texture.getSurfaceTexture();
            previewTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(previewTexture);
            Surface recorderSurface = recorder.getSurface();
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.addTarget(recorderSurface);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    try {
                        cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            }, null);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void startPreview() {
        try {
            SurfaceTexture previewTexture = cameraBinding.texture.getSurfaceTexture();
            previewTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(previewTexture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    mPreviewCaptureSession = cameraCaptureSession;
                    try {
                        mPreviewCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(getActivity(), "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        try {
            mPreviewCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };

    private void createImageFolder() {
        mImageFolder = getActivity().getExternalFilesDir("IMG");
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs();
        }
    }

    private File createImageFileName() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prefix = "IMG_" + timeStamp;
        File imageFile = File.createTempFile(prefix, ".jpg", mImageFolder);
        mImageFileName = imageFile.getAbsolutePath();
        return imageFile;
    }

    private void createVideoFolder() {
        mVideoFolder = getActivity().getExternalFilesDir("VIDEO");
        if (!mVideoFolder.exists()) {
            mVideoFolder.mkdirs();
        }
    }

    private File createVideoFileName() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prefix = "VIDEO_" + timeStamp;
        File videoFile = File.createTempFile(prefix, ".mp4", mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }

    private void setAspectRatioTextureView(int ResolutionWidth , int ResolutionHeight )
    {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int DSI_height = displayMetrics.heightPixels;
        int DSI_width = displayMetrics.widthPixels;

        int newWidth = DSI_width;
        int newHeight;
        if (ResolutionWidth > ResolutionHeight){
            newHeight = ((DSI_width * ResolutionWidth) / ResolutionHeight);

        }else {
            newHeight = ((DSI_width * ResolutionHeight) / ResolutionWidth);
        }
        updateTextureViewSize(newWidth,newHeight);

    }

    private void updateTextureViewSize(int viewWidth, int viewHeight) {
        Log.d(TAG, "TextureView Width : " + viewWidth + " TextureView Height : " + viewHeight);
        cameraBinding.texture.setLayoutParams(new FrameLayout.LayoutParams(viewWidth, viewHeight));
    }

    private void setupMediaRecorder() throws IOException {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(mVideoFileName);
        recorder.setVideoEncodingBitRate(10000000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        recorder.setOrientationHint(ORIENTATIONS.get(mRotation));
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.prepare();
    }

    private void lockFocus() {
        mCaptureState = STATE_WAIT_LOCK;
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            mPreviewCaptureSession.capture(previewRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                    switch (mCaptureState) {
                        case STATE_PREVIEW:
                            break;
                        case STATE_WAIT_LOCK:
                            mCaptureState = STATE_PREVIEW;
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == result.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                afState == result.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                startStillCaptureRequest();
                            }
                            break;
                    }
                }
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    process(result);
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }



    @Override
    public void onPause() {
        super.onPause();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    @Override
    public void onDestroy() {
        cameraBinding = null;
        super.onDestroy();
    }
}