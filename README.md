# IsekiChadet - Production Verification Android App

## Overview

**IsekiChadet** is a robust Android application designed for high-precision production line verification. It streamlines the manufacturing process by ensuring that physical chassis numbers match their corresponding digital Kanban records. 

By combining QR code scanning with AI-powered Optical Character Recognition (OCR), the app eliminates manual data entry errors and enforces strict production sequence rules through real-time API integration with the central `iseki_chadet` server.

## Key Features

### 1. Dual-Path Verification Workflow
*   **Kanban QR Scanning**: Rapidly ingest production data (Sequence Number, Production Date, Chassis ID) via QR code scanning.
*   **Physical Chassis OCR**: Capture physical chassis numbers directly from the hardware using **Google ML Kit**.
*   **Real-Time Matching**: Automated comparison between scanned Kanban data and physical chassis data with instant "OK/NG" (No Good) visual indicators.

### 2. Intelligent OCR Normalization
*   **Custom Normalization Engine**: Specialized logic to handle common computer vision misinterpretations (e.g., correcting '1' to 'I', 'O' to '0', and 'S' to '5') based on expected patterns.
*   **Fuzzy Prefix Matching**: Uses Levenshtein distance algorithms to correctly identify production line prefixes (e.g., `ISKI4550`, `MF1E`) even when image quality is sub-optimal.

### 3. Production Gatekeeping
*   **Prerequisite Validation**: Before any data can be submitted, the app verifies with the backend server that all production prerequisites are met, preventing out-of-sequence errors.
*   **Bad Badge Blocking**: Automatically disables submission if the prerequisite check fails or if the scan result is "NG".

### 4. Record Management
*   **History Dashboard**: View a filterable list of production records by date.
*   **Evidence Capturing**: Automatically handles photo evidence for NG records.
*   **Automatic Cleanup**: Efficiently manages device storage by cleaning up temporary image files after successful server synchronization.

## Technology Stack

### Core Mobile
*   **Platform**: Android (Target SDK 35)
*   **Language**: [Kotlin](https://kotlinlang.org/)
*   **UI Framework**: Jetpack Compose & Material Design 3

### Integrated Libraries
*   **AI/OCR**: [Google ML Kit (Text Recognition)](https://developers.google.com/ml-kit/vision/text-recognition)
*   **Scanning**: [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
*   **Networking**: [OkHttp 3](https://square.github.io/okhttp/) with Logging Interceptor
*   **JSON Processing**: Native Android/Kotlin JSON libraries
*   **Image Handling**: Androidx ExifInterface for handling camera sensor rotations

## Project Structure

```text
IsekiChadet/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/isekichadet/
│   │   │   ├── MainActivity.kt        # Dashboard & Record History
│   │   │   ├── RecordActivity.kt      # Core Scanning & OCR Logic
│   │   │   └── RecordAdapter.kt       # RecyclerView Adapter for History
│   │   ├── res/                       # Layouts, Drawables, and Themes
│   │   └── AndroidManifest.xml        # Permission & Activity Declarations
│   └── build.gradle.kts               # Android dependencies & Build Config
```

## Installation & Setup

1.  **Environment**
    *   Install [Android Studio](https://developer.android.com/studio) (Koala or newer recommended).
    *   Ensure Android SDK 35 is installed.

2.  **Configuration**
    *   Open `RecordActivity.kt` and update the `CHECK_PREREQUISITES_URL` companion object constant with your local/production server IP.
    *   Open `MainActivity.kt` and update the API URL in `loadRecords`.

3.  **Build**
    ```bash
    ./gradlew assembleDebug
    ```

4.  **Permissions**
    The app requires:
    *   `CAMERA`: For QR scanning and OCR.
    *   `INTERNET`: For backend synchronization.

## Usage

1.  **Dashboard**: Open the app to see a list of today's production records. Use the date picker to browse history.
2.  **Verify**: Switch to the **Record** tab.
3.  **Scan Kanban**: Tap "Scan QR" to read the Kanban card.
4.  **Scan Chassis**: Tap "Capture Image" to perform OCR on the physical chassis.
5.  **Submit**: If the status is "✅ OK", tap "Submit" to sync with the server.

## License

This project is proprietary.
