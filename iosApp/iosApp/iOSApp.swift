import UIKit
import SwiftUI
import ComposeApp
import BackgroundTasks
import UserNotifications

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    // Static set so BGTask handlers are registered at most once per process lifetime.
    // BGTaskScheduler crashes if the same identifier is registered twice.
    private static var registeredTaskIds: Set<String> = []

    // The main window for the application.
    var window: UIWindow?

    // --- Koin & KMP Integration ---
    var koinIos: KoinIOS!

    override init() {
        super.init()

        // Bridge Swift's compile-time simulator check to Kotlin/Native via env var.
        // Must run BEFORE Koin init so NativeTaskScheduler reads it at construction time.
        #if targetEnvironment(simulator)
        setenv("KMP_IS_SIMULATOR", "1", 1)
        #endif

        // Initialize Koin AFTER super.init()
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
        koinIos = KoinIOS()

        NotificationCenter.default.addObserver(self, selector: #selector(showNotificationFromKMP), name: NSNotification.Name("showNotification"), object: nil)
    }

    @objc func showNotificationFromKMP(notification: NSNotification) {
        if let userInfo = notification.userInfo,
           let title = userInfo["title"] as? String,
           let body = userInfo["body"] as? String {
            showNotification(title: title, body: body)
        }
    }

    /**
     * The entry point of the application after it has launched.
     * This is where initial setup and configuration occur.
     */
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        // Register handlers for background tasks defined in Info.plist.
        // This tells the OS what code to execute when a background task is triggered.
        self.performSafeRegistration()

        // --- Setup for Remote Push Notifications ---
        // Start the registration process for remote push notifications.
        registerForPushNotifications(application: application)

        // --- Traditional UIKit Window Setup (NO Scene Delegate) ---

        // Create the main application window
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = .systemBackground

        // Create Compose UIViewController
        let scheduler = koinIos.getScheduler()
        let pushHandler = koinIos.getPushHandler()
        let composeViewController = MainViewControllerKt.MainViewController(scheduler: scheduler, pushHandler: pushHandler)

        // Set as root view controller and make window visible
        window?.rootViewController = composeViewController
        window?.makeKeyAndVisible()

        // Return true to indicate that the app has launched successfully.
        return true
    }

    private func performSafeRegistration() {
        guard let taskIds = Bundle.main.infoDictionary?["BGTaskSchedulerPermittedIdentifiers"] as? [String] else {
            print("iOS BGTask: No BGTaskSchedulerPermittedIdentifiers found in Info.plist")
            return
        }
        
        for taskId in taskIds {
            // Guard: BGTaskScheduler throws NSInternalInconsistencyException if the same
            // identifier is registered more than once in the same process. This can happen
            // when ios-deploy / lldb relaunches the app without killing the process.
            guard !AppDelegate.registeredTaskIds.contains(taskId) else {
                print("iOS BGTask: Skipping already-registered task: \(taskId)")
                continue
            }
            AppDelegate.registeredTaskIds.insert(taskId)
            BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
                print("iOS BGTask: Generic handler received task: \(task.identifier)")
                if taskId == "kmp_chain_executor_task" {
                    self.handleChainExecutorTask(task: task)
                } else {
                    self.handleSingleTask(task: task)
                }
            }
        }
        print("iOS BGTask: Registration attempt completed. Total registered: \(AppDelegate.registeredTaskIds.count)")
    }

    //================================================================
    // MARK: - Push Notification Handling
    //================================================================

    func registerForPushNotifications(application: UIApplication) {
        UNUserNotificationCenter.current().delegate = self

        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
                print(" KMP_PUSH_IOS: Permission granted: \(granted)")
                if let error = error {
                    print(" KMP_PUSH_IOS: Error requesting permission: \(error.localizedDescription)")
                    return
                }
                guard granted else { return }
                DispatchQueue.main.async {
                    application.registerForRemoteNotifications()
                }
            }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let tokenParts = deviceToken.map { data in String(format: "%02.2hhx", data) }
        let token = tokenParts.joined()
        print(" KMP_PUSH_IOS: Device Token: \(token)")
        let pushHandler = koinIos.getPushHandler()
        pushHandler.sendTokenToServer(token: token)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print(" KMP_PUSH_IOS: Failed to register for remote notifications: \(error.localizedDescription)")
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let userInfo = notification.request.content.userInfo
        print(" KMP_PUSH_IOS: Received push while in foreground: \(userInfo)")

        if let payload = userInfo as? [String: Any] {
            let stringPayload = payload.mapValues { "\($0)" }
            let pushHandler = koinIos.getPushHandler()
            pushHandler.handlePushPayload(payload: stringPayload)
        }

        if #available(iOS 14.0, *) {
            completionHandler([.banner, .sound, .badge])
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable : Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {

        print(" KMP_PUSH_IOS: didReceiveRemoteNotification called.")
        print(" KMP_PUSH_IOS: Received push while in background: \(userInfo)")

        if let payload = userInfo as? [String: Any] {
            let stringPayload = payload.mapValues { "\($0)" }
            let pushHandler = koinIos.getPushHandler()
            pushHandler.handlePushPayload(payload: stringPayload)
        }

        if let customData = userInfo["customData"] as? [String: Any],
           let message = customData["message"] as? String {
            self.showNotification(title: "Background Push", body: message)
        }

        let scheduler = koinIos.getScheduler()
        let trigger = TaskTriggerHelperKt.createTaskTriggerOneTime(initialDelayMs: 5000)
        let constraints = TaskTriggerHelperKt.createConstraints()
        Task {
            do {
                let result = try await scheduler.enqueue(
                    id: "task-from-push-\(UUID().uuidString)",
                    trigger: trigger,
                    workerClassName: "one-time-upload",
                    constraints: constraints,
                    inputJson: nil,
                    policy: .replace
                )
                print(" KMP_PUSH_IOS: Successfully scheduled task from push. Result: \(result)")
                completionHandler(.newData)
            } catch {
                print(" KMP_PUSH_IOS: Failed to schedule task from push. Error: \(error)")
                completionHandler(.failed)
            }
        }
    }

    private func handleSingleTask(task: BGTask) {
        let taskId = task.identifier
        let userDefaults = UserDefaults.standard

        task.expirationHandler = {
            print("iOS BGTask: Task \(taskId) expired.")
            task.setTaskCompleted(success: false)
        }

        let periodicMeta = userDefaults.dictionary(forKey: "kmp_periodic_meta_" + taskId) as? [String: String]
        let taskMeta = userDefaults.dictionary(forKey: "kmp_task_meta_" + taskId) as? [String: String]

        let workerClassName: String?
        let inputJson: String?

        if let meta = periodicMeta, meta["isPeriodic"] == "true" {
            workerClassName = meta["workerClassName"]
            inputJson = meta["inputJson"]
        } else if let meta = taskMeta {
            workerClassName = meta["workerClassName"]
            inputJson = meta["inputJson"]
        } else {
            print("iOS BGTask: No metadata found for task \(taskId). Cannot execute.")
            task.setTaskCompleted(success: false)
            return
        }

        guard let workerName = workerClassName, !workerName.isEmpty else {
            print("iOS BGTask: Worker class name is missing for task \(taskId).")
            task.setTaskCompleted(success: false)
            return
        }

        if let latestStr = taskMeta?["windowLatest"], let latestMs = Double(latestStr) {
            let nowMs = Date().timeIntervalSince1970 * 1000
            if nowMs > latestMs {
                let overdueSeconds = Int((nowMs - latestMs) / 1000)
                print("⚠️ iOS BGTask: DEADLINE_MISSED — Windowed task '\(taskId)' ran \(overdueSeconds)s past its 'latest' deadline. Skipping worker execution to prevent stale work.")
                task.setTaskCompleted(success: false)
                return
            }
        }

        let executor = koinIos.getSingleTaskExecutor()
        Task {
            do {
                let workerResult = try await executor.executeTask(workerClassName: workerName, input: inputJson, timeoutMs: 25000)
                let resultString = String(describing: type(of: workerResult))
                let result = resultString.contains("Success")
                print("iOS BGTask: Task \(taskId) finished with success: \(result) (type: \(resultString))")

                if let meta = periodicMeta, meta["isPeriodic"] == "true" {
                    print("iOS BGTask: Re-scheduling periodic task \(taskId).")
                    let scheduler = self.koinIos.getScheduler()
                    let intervalMs = Int64(meta["intervalMs"] ?? "0") ?? 0
                    let requiresNetwork = (meta["requiresNetwork"] ?? "false") == "true"
                    let requiresCharging = (meta["requiresCharging"] ?? "false") == "true"
                    let isHeavyTask = (meta["isHeavyTask"] ?? "false") == "true"

                    let constraints = Constraints(requiresNetwork: requiresNetwork, requiresUnmeteredNetwork: false, requiresCharging: requiresCharging, allowWhileIdle: false, qos: .background, isHeavyTask: isHeavyTask, backoffPolicy: .exponential, backoffDelayMs: 30000)
                    let trigger = TaskTriggerPeriodic(intervalMs: intervalMs, flexMs: nil)

                    Task {
                        do {
                            _ = try await scheduler.enqueue(id: taskId, trigger: trigger, workerClassName: workerName, constraints: constraints, inputJson: inputJson, policy: .replace)
                            print("iOS BGTask: Successfully re-scheduled periodic task \(taskId).")
                        } catch {
                            print("iOS BGTask: Failed to re-schedule periodic task \(taskId): \(error)")
                        }
                    }
                }

                task.setTaskCompleted(success: result)
            } catch {
                print("iOS BGTask: Task \(taskId) failed with error: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
            }
        }
    }

    private func handleChainExecutorTask(task: BGTask) {
        print("📦 iOS BGTask: Handling KMP Chain Executor Task (Batch Mode)")
        let chainExecutor = koinIos.getChainExecutor()

        task.expirationHandler = {
            print("⏰ iOS BGTask: KMP Chain Executor Task expired - initiating graceful shutdown")
            Task {
                do {
                    try await chainExecutor.requestShutdown()
                    print("✅ iOS BGTask: Graceful shutdown completed")
                } catch {
                    print("❌ iOS BGTask: Graceful shutdown failed: \(error)")
                }
                task.setTaskCompleted(success: false)
            }
        }

        let scheduleNext: () async -> Void = {
            do {
                let remainingChains = try await chainExecutor.getChainQueueSize()
                let count = Int(truncating: remainingChains as NSNumber)
                if count > 0 {
                    print("📦 iOS BGTask: \(count) chain(s) remaining. Rescheduling executor task.")
                    let request = BGProcessingTaskRequest(identifier: "kmp_chain_executor_task")
                    request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
                    request.requiresNetworkConnectivity = true
                    try? BGTaskScheduler.shared.submit(request)
                } else {
                    print("✅ iOS BGTask: All chains processed. Queue is empty.")
                }
            } catch {
                print("❌ iOS BGTask: Failed to get chain queue size: \(error)")
            }
        }

        Task {
            do {
                let initialQueueSize = try await chainExecutor.getChainQueueSize()
                let queueCount = Int(truncating: initialQueueSize as NSNumber)
                print("📦 iOS BGTask: Chain queue size: \(queueCount)")
                try await chainExecutor.resetShutdownState()
                let executedCount = try await chainExecutor.executeChainsInBatch(maxChains: 3, totalTimeoutMs: 50_000)
                let count = Int(truncating: executedCount as NSNumber)
                print("✅ iOS BGTask: Batch execution completed - \(count) chain(s) executed out of \(queueCount)")
                task.setTaskCompleted(success: true)
                await scheduleNext()
            } catch {
                print("❌ iOS BGTask: Batch execution failed with error: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
                await scheduleNext() 
            }
        }
    }

    func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error showing local notification: \(error.localizedDescription)")
            }
        }
    }
}
