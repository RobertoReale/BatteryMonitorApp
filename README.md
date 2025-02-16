# Battery Monitor & Cycle Estimator

An intelligent Android battery monitoring application that provides accurate predictions and emergency shutdown warnings using real-time analytics and adaptive machine learning techniques.

## Features

- **Battery Health Monitoring**: Tracks battery percentage, voltage, and temperature in real-time
- **Cycle Estimation**: Predicts battery life cycles based on charge/discharge patterns
- **Smart Alerts**: Provides timely warnings before critical battery conditions
- **Adaptive Learning**: Self-improves prediction accuracy based on device behavior
- **Temperature-Aware**: Adjusts predictions based on device temperature
- **Reliable Background Operation**: Implements robust service recovery mechanisms
- **Low Resource Impact**: Optimized for minimal battery and memory usage

## Technical Implementation

### Core Components

- **ImprovedBatteryCycleEstimator**: Advanced estimation using weighted algorithms
- **PredictionLearning**: Adaptive system that learns from prediction accuracy
- **BatteryMonitorService**: Foreground service for continuous monitoring
- **BatteryViewModel**: Manages UI updates and data processing
- **WorkManager Integration**: Ensures service reliability

### Key Features

```kotlin
// Sophisticated battery cycle counting with adaptive learning
class ImprovedBatteryCycleEstimator {
    fun updateBatteryStatus(
        currentLevel: Int,
        temperature: Double,
        voltage: Int,
        isCharging: Boolean
    )
    
    fun predictTimeToShutdown(): ShutdownPrediction
}

// Adaptive prediction learning system
class PredictionLearning {
    fun recordWarningStart(
        predictedMinutes: Double,
        voltage: Int,
        temperature: Double,
        batteryLevel: Int
    )
    
    fun recordWarningCancelled()
    fun recordActualShutdown()
}
```

## Installation

### Quick Install
1. Go to the [Releases](https://github.com/yourusername/BatteryMonitorApp/releases) page
2. Download the latest APK file
3. Install the APK on your Android device
   - Make sure to enable "Install from Unknown Sources" in your device settings if required

### Development Setup

### Prerequisites
- Android Studio Arctic Fox or newer
- Minimum SDK: Android 21 (5.0)
- Target SDK: Android 33 (13)
- Kotlin version: 1.8.0+

### Clone & Build

```sh
git clone https://github.com/yourusername/BatteryMonitorApp.git
cd BatteryMonitorApp
```

### Dependencies

```gradle
dependencies {
    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1'
    implementation 'androidx.work:work-runtime-ktx:2.8.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1'
    implementation 'com.google.android.material:material:1.9.0'
}
```

### Required Permissions

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Usage

1. **Start Monitoring**
   ```kotlin
   val serviceIntent = Intent(context, BatteryMonitorService::class.java)
   ContextCompat.startForegroundService(context, serviceIntent)
   ```

2. **Access Predictions**
   ```kotlin
   val viewModel: BatteryViewModel by viewModels()
   viewModel.prediction.observe(this) { prediction ->
       // Handle prediction updates
   }
   ```

3. **Customize Thresholds**
   ```kotlin
   object Constants {
       const val LOW_BATTERY_PERCENTAGE = 20
       const val LOW_VOLTAGE_THRESHOLD = 3200
       const val HIGH_TEMPERATURE_THRESHOLD = 45
   }
   ```

## Performance Impact

- Battery Usage: < 1% per day
- Memory Usage: ~10MB RAM
- Storage: ~2MB for history and learning data
- CPU: Minimal (adaptive polling)

## How Prediction Learning Works

The app implements an adaptive learning system that:
- Records when shutdown warnings are triggered
- Tracks whether warnings were accurate or premature
- Adjusts future predictions based on historical accuracy
- Maintains separate learning profiles for different temperature ranges
- Persists learned patterns between app restarts

## Future Improvements

- Room database implementation for efficient data storage
- Device-specific voltage threshold calibration
- Enhanced machine learning prediction model with multi-factor analysis
- Additional battery health metrics
- Power management API integration
- Prediction accuracy statistics and visualization

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/Enhancement`)
3. Commit changes (`git commit -m 'Add Enhancement'`)
4. Push to branch (`git push origin feature/Enhancement`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see [LICENSE.md](LICENSE.md)

## Support

For support, please open an issue in the GitHub repository.

## Version History

### 1.1.0 (Current)
- Added adaptive prediction learning system
- Improved prediction accuracy through historical data analysis
- Enhanced temperature compensation
- More reliable shutdown warnings
- Expanded battery analytics

### 1.0.0
- Initial release
- Basic battery monitoring
- Temperature-aware battery cycle estimation
- Emergency shutdown warnings
- Background service with WorkManager recovery
- Real-time voltage and temperature tracking

---

**Note**: Prediction accuracy improves over time as the app learns from your device's behavior patterns. Initial predictions become significantly more accurate after 24-48 hours of usage.
