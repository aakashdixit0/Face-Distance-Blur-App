package com.facedistanceblur;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private Button btnStartService;
    private Button btnStopService;
    private Button btnLogout;
    private TextView tvStatus;
    private TextView tvInstructions;
    private ImageButton btnSettings;
    
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> accessibilityPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply theme from preferences
        boolean isDark = getSharedPreferences("UserPrefs", MODE_PRIVATE).getBoolean("isDarkTheme", false);
        if (isDark) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        }
        super.onCreate(savedInstanceState);
        
        // Check if user is logged in
        if (!isUserLoggedIn()) {
            // Redirect to login activity
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        
        setContentView(R.layout.activity_main);

        initActivityResultLaunchers();
        initViews();
        setupClickListeners();
        checkPermissions();
    }

    private void initActivityResultLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
                }
                updateUI();
            }
        );

        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> updateUI()
        );

        accessibilityPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> updateUI()
        );
    }

    private void initViews() {
        btnStartService = findViewById(R.id.btnStartService);
        btnStopService = findViewById(R.id.btnStopService);
        btnLogout = findViewById(R.id.btnLogout);
        tvStatus = findViewById(R.id.tvStatus);
        tvInstructions = findViewById(R.id.tvInstructions);
        btnSettings = findViewById(R.id.btnSettings);
    }

    private void setupClickListeners() {
        btnStartService.setOnClickListener(v -> startFaceDistanceMonitoring());
        btnStopService.setOnClickListener(v -> stopFaceDistanceMonitoring());
        btnLogout.setOnClickListener(v -> logout());
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    private void checkPermissions() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
        }
        
        if (!hasOverlayPermission()) {
            requestOverlayPermission();
        }
        
        if (!hasAccessibilityPermission()) {
            requestAccessibilityPermission();
        }
        
        updateUI();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
               == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasAccessibilityPermission() {
        String enabledServices = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;
        String flatName1 = getPackageName() + "/.BlurAccessibilityService";
        String flatName2 = getPackageName() + "/" + BlurAccessibilityService.class.getName();
        return enabledServices.contains(flatName1) || enabledServices.contains(flatName2);
    }

    private void requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        overlayPermissionLauncher.launch(intent);
    }

    private void requestAccessibilityPermission() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        accessibilityPermissionLauncher.launch(intent);
    }

    private void startFaceDistanceMonitoring() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "Please grant all required permissions first", Toast.LENGTH_LONG).show();
            return;
        }

        // For accessibility service, we just need to enable it in settings
        // The service will start automatically when enabled
        Toast.makeText(this, "Please enable the accessibility service in settings", Toast.LENGTH_LONG).show();
        
        // Open accessibility settings
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        
        tvStatus.setText("Service enabled - monitoring active");
        btnStartService.setEnabled(false);
        btnStopService.setEnabled(true);
    }

    private void stopFaceDistanceMonitoring() {
        // For accessibility service, we need to disable it in settings
        Toast.makeText(this, "Please disable the accessibility service in settings", Toast.LENGTH_LONG).show();
        
        // Open accessibility settings
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        
        tvStatus.setText("Service disabled");
        btnStartService.setEnabled(true);
        btnStopService.setEnabled(false);
    }

    private boolean hasAllPermissions() {
        return hasCameraPermission() && hasOverlayPermission() && hasAccessibilityPermission();
    }

    private void updateUI() {
        if (hasAllPermissions()) {
            tvInstructions.setText("All permissions granted. You can start the service.");
            btnStartService.setEnabled(true);
        } else {
            tvInstructions.setText("Please grant all required permissions to use the app.");
            btnStartService.setEnabled(false);
        }
        btnStopService.setEnabled(false);
    }

    private boolean isUserLoggedIn() {
        return getSharedPreferences("UserPrefs", MODE_PRIVATE)
            .getBoolean("isLoggedIn", false);
    }

    private void logout() {
        // Clear login state
        getSharedPreferences("UserPrefs", MODE_PRIVATE)
            .edit()
            .clear()
            .apply();

        // Navigate to login activity
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }


}
