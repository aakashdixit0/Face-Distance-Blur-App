package com.facedistanceblur;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.accessibility.AccessibilityEvent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlurAccessibilityService extends AccessibilityService {
    private static final String TAG = "BlurAccessibilityService";
    private static final float SAFE_DISTANCE_THRESHOLD = 0.6f; // Adjust based on testing
    
    private WindowManager windowManager;
    private View blurView;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private FaceDetector faceDetector;
    private ExecutorService cameraExecutor;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private boolean isBlurActive = false;
    private boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        initializeComponents();
    }

    private void initializeComponents() {
        // Initialize ML Kit Face Detector
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build();
        
        faceDetector = FaceDetection.getClient(options);
        
        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor();
        
        // Initialize background thread
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        
        // Initialize window manager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // This method is called when accessibility events occur
        // We don't need to do anything here for our use case
    }

    @Override
    public void onInterrupt() {
        stopFaceDetection();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (!isServiceRunning) {
            startFaceDetection();
            isServiceRunning = true;
        }
    }

    private void startFaceDetection() {
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);
            
            cameraProviderFuture.addListener(() -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    bindCameraUseCases();
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Error starting camera: " + e.getMessage());
                    Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera provider: " + e.getMessage());
            Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // Set up image analysis
        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new FaceDistanceAnalyzer());

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                new FakeLifecycleOwner(),
                CameraSelector.DEFAULT_FRONT_CAMERA,
                imageAnalysis
            );
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera use cases: " + e.getMessage());
            Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    private class FaceDistanceAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
            );

            faceDetector.process(image)
                    .addOnSuccessListener(faces -> {
                        processFaces(faces, imageProxy.getWidth(), imageProxy.getHeight());
                        imageProxy.close();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Face detection failed: " + e.getMessage());
                        imageProxy.close();
                    });
        }
    }

    private void processFaces(List<Face> faces, int imageWidth, int imageHeight) {
        if (faces.isEmpty()) {
            // No face detected, remove blur
            if (isBlurActive) {
                removeBlurOverlay();
            }
            return;
        }

        // Get the largest face (closest to camera)
        Face largestFace = faces.get(0);
        for (Face face : faces) {
            if (face.getBoundingBox().width() * face.getBoundingBox().height() >
                largestFace.getBoundingBox().width() * largestFace.getBoundingBox().height()) {
                largestFace = face;
            }
        }

        // Calculate face size ratio (larger = closer)
        float faceSizeRatio = (float) (largestFace.getBoundingBox().width() * 
                                      largestFace.getBoundingBox().height()) / 
                                   (imageWidth * imageHeight);

        // Check if face is too close
        if (faceSizeRatio > SAFE_DISTANCE_THRESHOLD) {
            if (!isBlurActive) {
                showBlurOverlay();
            }
        } else {
            if (isBlurActive) {
                removeBlurOverlay();
            }
        }
    }

    private void showBlurOverlay() {
        if (blurView != null) return;

        try {
            blurView = LayoutInflater.from(this).inflate(R.layout.blur_overlay, null);
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            
            windowManager.addView(blurView, params);
            isBlurActive = true;
            
            Log.d(TAG, "Blur overlay shown");
        } catch (Exception e) {
            Log.e(TAG, "Error showing blur overlay: " + e.getMessage());
        }
    }

    private void removeBlurOverlay() {
        if (blurView != null && windowManager != null) {
            try {
                windowManager.removeView(blurView);
                blurView = null;
                isBlurActive = false;
                Log.d(TAG, "Blur overlay removed");
            } catch (Exception e) {
                Log.e(TAG, "Error removing blur overlay: " + e.getMessage());
            }
        }
    }

    private void stopFaceDetection() {
        isServiceRunning = false;
        
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        
        if (faceDetector != null) {
            faceDetector.close();
        }
        
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
        
        removeBlurOverlay();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFaceDetection();
    }
}
