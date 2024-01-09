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
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.Toast;

import com.example.opencam.ImageSaver;
import com.example.opencam.R;
import com.example.opencam.databinding.FragmentCameraBinding;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

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

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

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
        cameraBinding.btnTake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                lockFocus();
            }
        });
        cameraBinding.btnVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isRecording) {
                    cameraBinding.btnVideo.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.video_default));
                    recorder.stop();
                    recorder.reset();
                    startPreview();
                    cameraBinding.meter.stop();
                    cameraBinding.meter.setVisibility(View.INVISIBLE);
                } else {
                    cameraBinding.btnVideo.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.video_recording));
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
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height);
            mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder.class), width, height);
            mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), width, height);
            mRotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();

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

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                option.getHeight() >= height && option.getWidth() >= width) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

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

    private void setupMediaRecorder() throws IOException {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(mVideoFileName);
        recorder.setVideoEncodingBitRate(1000000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        recorder.setOrientationHint(mRotation);
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

    static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size s1, Size s2) {
            return Long.signum((long) s1.getWidth() * s1.getHeight() -
                    (long) s2.getWidth() * s2.getHeight());
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