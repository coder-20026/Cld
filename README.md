# 📍 CoordExtractor — Android Screen Coordinate Extractor

> **Production-ready Android app** jo screen ke bottom-right area se GPS coordinates detect karta hai aur clean format me convert karta hai.

---

## 🎯 Features

| Feature | Details |
|---------|---------|
| 🎈 Floating Overlay | Draggable FAB, sabhi screens pe visible |
| 📸 Screen Capture | MediaProjection API — high performance |
| 🔍 Smart ROI | Sirf bottom-right 30% scan karta hai |
| 🤖 ML Kit OCR | On-device, offline text recognition |
| 🧠 Regex Parser | `22.1234N 71.1234E` → `22.1234,71.1234` |
| ⚡ Real-time Mode | Continuous detection with throttling |
| 🗺️ Maps Integration | Direct Google Maps open |
| 📋 One-tap Copy | Clipboard pe copy |

---

## 🚀 Quick Start (GitHub → APK)

### Option A: GitHub Actions (Recommended)

```bash
# 1. GitHub pe new repo banao
# 2. Is project ka code push karo
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/CoordExtractor.git
git push -u origin main

# 3. GitHub → Actions tab → workflow automatically chalega
# 4. APK download karo: Actions → Latest run → Artifacts
```

### Option B: Local Android Studio

```bash
# Prerequisites: Android Studio Hedgehog+, JDK 17, Android SDK 34

# 1. Project kholo
File → Open → CoordExtractor folder select karo

# 2. Gradle sync hone do (auto-download dependencies)

# 3. Build APK
Build → Build Bundle(s)/APK(s) → Build APK(s)

# 4. APK: app/build/outputs/apk/debug/app-debug.apk
```

### Option C: Command Line

```bash
# Make sure ANDROID_HOME is set
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

# Build
chmod +x gradlew
./gradlew assembleDebug

# APK location
ls app/build/outputs/apk/debug/
```

---

## 📱 App Usage

### First-time Setup
1. App open karo
2. **"Enable Floating Button"** tap karo
3. **"Draw over other apps"** permission grant karo
4. **Screen capture** permission grant karo (MediaProjection dialog)
5. Floating GPS button appear hoga ✅

### Coordinate Extraction
1. Kisi bhi screen pe navigate karo jahan coordinates dikhte hain
2. **Floating button tap karo**
3. App bottom-right area scan karega
4. Result card me coordinates dikhenge:
   - Raw OCR text
   - Matched pattern
   - Final `lat,lon` format
5. **Copy** ya **Open Maps** button use karo

### Real-time Mode
- Main screen pe **Real-time Mode toggle** ON karo
- Floating button tap karo — continuous scanning start
- Sirf jab value change ho tab update hoga (throttling: 1.2s)
- Button dubara tap karo → stop

### Remove Floating Button
- Floating button ko **long press** karo (0.7s)
- Button fade out hoga aur service stop hogi

---

## 🏗️ Architecture (MVVM + Clean)

```
com.coordextractor/
├── MainActivity.kt              # Entry point, permission flow
│
├── capture/
│   └── CaptureManager.kt        # MediaProjection screen capture
│
├── data/
│   └── repository/
│       └── OCRRepository.kt     # ML Kit Text Recognition wrapper
│
├── domain/
│   └── TextParser.kt            # Regex extraction + coordinate conversion
│
├── presentation/
│   └── viewmodel/
│       └── MainViewModel.kt     # StateFlow, UI state management
│
├── processing/
│   └── ImageProcessor.kt        # Bitmap crop + preprocessing
│
└── service/
    └── FloatingService.kt       # Overlay FAB + result card + capture flow
```

---

## 🔧 Tech Stack

```
Language:       Kotlin 1.9.22
Min SDK:        API 26 (Android 8.0)
Target SDK:     API 34 (Android 14)
Architecture:   MVVM + Clean Architecture
Async:          Kotlin Coroutines + StateFlow
OCR:            Google ML Kit Text Recognition v16
UI:             Material Design 3
Overlay:        WindowManager TYPE_APPLICATION_OVERLAY
Capture:        MediaProjection + ImageReader + VirtualDisplay
Build:          Gradle 8.6 + AGP 8.2.2
CI/CD:          GitHub Actions
```

---

## 📐 Coordinate Formats Supported

| Input Format | Output |
|-------------|--------|
| `22.1234N 71.1234E` | `22.1234,71.1234` |
| `22.1234N71.1234E` | `22.1234,71.1234` |
| `22.1234°N 71.1234°E` | `22.1234,71.1234` |
| `22.1234S 71.1234W` | `-22.1234,-71.1234` |
| `22.1234S 71.1234E` | `-22.1234,71.1234` |

---

## 🔐 Permissions

| Permission | Purpose |
|-----------|---------|
| `SYSTEM_ALERT_WINDOW` | Floating overlay button draw karna |
| `FOREGROUND_SERVICE` | Background service run karna |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ ke liye |
| MediaProjection | Runtime pe screen capture |

---

## ⚙️ GitHub Actions CI/CD

Workflow file: `.github/workflows/android-build.yml`

**Triggers:** push to `main`/`master`, pull requests, manual dispatch

**Steps:**
1. ✅ Checkout code
2. ☕ Setup JDK 17 (Temurin)
3. 📦 Gradle cache restore
4. 🔧 Install Gradle 8.6 + generate wrapper
5. 📱 Setup Android SDK
6. 🏗️ `./gradlew assembleDebug`
7. 📤 Upload APK artifact (30 days retention)

**Download APK:** GitHub → Actions → Latest workflow run → Artifacts section

---

## 🐛 Troubleshooting

**"Overlay permission required"**
→ Settings → Apps → CoordExtractor → Display over other apps → Allow

**"Capture failed"**
→ App restart karo, MediaProjection permission dubara grant karo

**"No coordinates found"**
→ Ensure coordinates screen ke **bottom-right** area me hain
→ Text clearly visible hona chahiye (not too small)

**GitHub Actions build fail**
→ Check Java version: must be 17
→ Verify Android SDK is properly set up
→ Check Gradle cache didn't corrupt: delete cache and retry

---

## 📄 License

```
MIT License — Free to use, modify, and distribute.
```

---

*Built with ❤️ using Kotlin, ML Kit, Material Design 3*
