KMP TaskManager 🚀

A robust, cross-platform framework for scheduling, managing, and executing background tasks consistently on Android and iOS, built entirely with Kotlin Multiplatform.

> Note: This project serves as a canonical example of how to build a sophisticated abstraction layer to solve the fundamental differences in background task handling
between Android and iOS.

![Build Status (https://img.shields.io/badge/build-passing-brightgreen)](https://your-ci-link.com)
![License: Apache 2.0 (https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![Version (https://img.shields.io/badge/version-1.0.0-blue.svg)](https://semver.org/)

KMP TaskManager is more than just a demo application. It's a foundational framework designed to provide a single, unified API that allows developers to define and manage
complex background jobs without writing platform-specific logic. It solves the "Control vs. Opportunism" problem by leveraging the power of native APIs on each operating
system.

  ---

✨ Key Features

Cross-Platform API
* Unified API: Provides a common interface in commonMain for scheduling periodic, one-off, and time-sensitive tasks.
* `expect/actual` Mechanism: Leverages KMP's expect/actual pattern and Dependency Injection (Koin) to seamlessly provide native implementations.
* Shared Business Logic: All logic regarding "when" and "what" to execute is written once in pure Kotlin.

Android Platform Power
* WorkManager Integration: Utilizes Android Jetpack's WorkManager to ensure tasks have guaranteed execution, even if the app is closed or the device reboots.
* Rich Constraint Support: Allows for defining execution conditions such as network status, device charging state, battery level, etc.
* Long-Running Task Support: Supports tasks that need more than 10 minutes to complete via ForegroundService.
* Exact Scheduling: Integrates AlarmManager to handle time-critical tasks like reminders.

Full iOS Compliance
* BackgroundTasks Framework Integration: Uses BGTaskScheduler (from iOS 13+) to register tasks with the OS, strictly adhering to Apple's power management rules.
* `BGAppRefreshTask` Support: Ideal for short, quick content refresh tasks.
* `BGProcessingTask` Support: For larger maintenance and processing jobs, allowing the OS to optimize for battery and performance (e.g., when the device is charging and on
  Wi-Fi).
* Push-Triggered Execution: Can be "woken up" by silent APNs to execute tasks triggered by a server event.

  ---

🏗️ Architecture Overview

The project is built on Kotlin Multiplatform's abstraction layer architecture, which allows for a clean separation of shared logic from platform-specific implementations.

1. Common Layer (`commonMain`): This is the heart of the framework.
  * Defines the interface or expect class (e.g., BackgroundTaskScheduler) for background tasks.
  * Contains all shared business logic, data models, and common Koin dependency injection configuration.
  * The user interface (UI) can also be shared here using Compose Multiplatform.

2. Platform Layer (`androidMain` & `iosMain`):
  * Provides the actual implementations for the expect declarations in the common layer.
  * `androidMain`: Uses WorkManager and AlarmManager to fulfill requests from the common layer.
  * `iosMain`: Uses BGTaskScheduler to submit task requests to iOS.

Interaction Flow (Example: Scheduling a Periodic Task)
1. Request from Common Layer: A ViewModel or UseCase in commonMain calls the backgroundTaskScheduler.schedulePeriodicRefresh() function.
2. Dependency Injection: Koin provides an instance of BackgroundTaskScheduler that is appropriate for the currently running platform (Android or iOS).
3. Platform Execution:
  * On Android: The actual implementation creates a PeriodicWorkRequest and enqueues it with WorkManager.
  * On iOS: The actual implementation creates a BGAppRefreshTaskRequest and submits it to BGTaskScheduler.
4. OS Execution: The respective operating system then decides the best time to run the task based on its internal rules and conditions.

  ---

🛠️ Tech Stack

* Language: Kotlin.
* Core Framework: Kotlin Multiplatform (KMP), Compose Multiplatform.
* Architecture: Clean Architecture, expect/actual, Dependency Injection.
* Asynchronous Programming: Coroutines & Flow.
* Background APIs: Android WorkManager, Android AlarmManager, iOS BackgroundTasks Framework.
* Dependency Injection: Koin.
* Build System: Gradle with Kotlin DSL.

  ---

📖 How to Use (For Developers)

To integrate and use KMP TaskManager in your application.

Step 1: Define Your Task

In commonMain, you need to define the logic for your task. This logic will be invoked from the platform-specific entry points.

Step 2: Schedule the Task

In your commonMain code (e.g., in a ViewModel), inject the BackgroundTaskScheduler and call the function to schedule your task.

    1 // In commonMain
    2 class HomeViewModel(
    3     private val backgroundTaskScheduler: BackgroundTaskScheduler
    4 ) : ViewModel() {
    5 
    6     fun scheduleHourlyDataSync() {
    7         // Schedule a task to run periodically every hour
    8         backgroundTaskScheduler.schedulePeriodicTask(
    9             taskId = "hourly_sync",
10             intervalInHours = 1
11         )
12     }
13 }

Step 3: Provide Platform Implementations

Ensure that your androidMain and iosMain modules provide the necessary actual implementations to interact with the native OS APIs.

  ---

🚀 Detailed Build Instructions

To build and run this project from source code, you will need the following environment:

Prerequisites
* Android Studio: Iguana | 2023.2.1 or newer.
* Xcode: 15.0 or newer (to run on iOS).
* JDK: JDK 17.
* Kotlin Multiplatform Mobile plugin in Android Studio.

Configuration Steps
1. Clone the repository:

1     git clone https://your-repository-url.git
2     cd KMPTaskManager

2. Configure `local.properties`:
  * Create a local.properties file in the project's root directory.
  * Ensure this file contains the path to your Android SDK, for example:
    1         sdk.dir=/Users/your-user/Library/Android/sdk

Build and Run
1. Open the project in Android Studio.
2. Wait for the Gradle Sync to complete.
3. To run on Android:
  * Select an Android device (physical or emulator).
  * Select the composeApp configuration and press Run.
4. To run on iOS:
  * Open the iosApp/iosApp.xcodeproj file in Xcode.
  * Select a Simulator or a real iOS device.
  * Press Run in Xcode.
5. Build via the command line:
   1     # Build an APK file for Android
   2     ./gradlew assembleDebug

  ---

🧭 Future Development Direction (Optional)

* Chaining API: Build an API in commonMain to allow for chaining tasks (sequentially or in parallel), similar to WorkManager.beginWith().
* More Trigger Types: Add support for other triggers, such as "on storage low" or "on Bluetooth connection".
* Debug UI: Implement a debug screen within the app to view the list of pending tasks and their current status.

  ---

©️ Copyright

Copyright © 2025 Your Company/Your Name. All Rights Reserved.