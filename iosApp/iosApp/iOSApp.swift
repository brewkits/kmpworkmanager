import UIKit
import SwiftUI
import ComposeApp
import BackgroundTasks
import UserNotifications

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    // The main window for the application.
    var window: UIWindow?

    // --- Koin & KMP Integration ---
    var koinIos: KoinIOS!

    override init() {
        super.init()

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
        registerBackgroundTasks()

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

    //================================================================
    // MARK: - Push Notification Handling
    //================================================================

    /**
     * Starts the registration process for remote push notifications.
     * 1. Sets the delegate for handling notification-related events.
     * 2. Requests authorization from the user to display alerts, play sounds, and update the badge.
     * 3. If permission is granted, it registers the app with Apple Push Notification service (APNs).
     */
    func registerForPushNotifications(application: UIApplication) {
        // Set this AppDelegate as the delegate for the notification center
        // to handle incoming notifications.
        UNUserNotificationCenter.current().delegate = self

        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
                print(" KMP_PUSH_IOS: Permission granted: \(granted)")
                if let error = error {
                    print(" KMP_PUSH_IOS: Error requesting permission: \(error.localizedDescription)")
                    return
                }
                // If the user denies permission, we cannot proceed.
                guard granted else { return }

                // Registration for remote notifications must be done on the main thread.
                DispatchQueue.main.async {
                    application.registerForRemoteNotifications()
                }
            }
    }

    /**
     * Delegate callback, invoked when the app successfully registers with APNs and receives a device token.
     * @param deviceToken A unique, opaque token that identifies the device to APNs.
     */
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        // Convert the binary deviceToken into a hexadecimal string for easy storage and transmission.
        let tokenParts = deviceToken.map { data in String(format: "%02.2hhx", data) }
        let token = tokenParts.joined()
        print(" KMP_PUSH_IOS: Device Token: \(token)")

        // Pass the token to the shared KMP code via the PushNotificationHandler.
        // This allows the common module to handle sending the token to your backend server.
        let pushHandler = koinIos.getPushHandler()
        pushHandler.sendTokenToServer(token: token)
    }

    /**
     * Delegate callback, invoked when the app fails to register with APNs.
     */
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print(" KMP_PUSH_IOS: Failed to register for remote notifications: \(error.localizedDescription)")
    }

    /**
     * Delegate callback for UNUserNotificationCenter.
     * Called when a notification is delivered to a foreground app.
     */
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Get the payload dictionary from the notification.
        let userInfo = notification.request.content.userInfo
        print(" KMP_PUSH_IOS: Received push while in foreground: \(userInfo)")

        // Convert the payload to a [String: String] map and pass it to the KMP handler
        // to execute shared business logic.
        if let payload = userInfo as? [String: Any] {
            let stringPayload = payload.mapValues { "\($0)" }
            let pushHandler = koinIos.getPushHandler()
            pushHandler.handlePushPayload(payload: stringPayload)
        }

        // Specify how the notification should be presented to the user (e.g., show a banner, play a sound).
        completionHandler([.banner, .sound, .badge])
    }

    /**
     * Delegate callback for UIApplication.
     * Called when a remote notification arrives and the app is in the background or terminated.
     * This is the entry point for handling silent push notifications.
     */
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable : Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {

        print(" KMP_PUSH_IOS: didReceiveRemoteNotification called.")

        print(" KMP_PUSH_IOS: Received push while in background: \(userInfo)")

        // Convert the payload and pass it to the KMP handler for processing.
        if let payload = userInfo as? [String: Any] {
            let stringPayload = payload.mapValues { "\($0)" }
            let pushHandler = koinIos.getPushHandler()
            pushHandler.handlePushPayload(payload: stringPayload)
        }

        // Show a local notification to the user.
        if let customData = userInfo["customData"] as? [String: Any],
           let message = customData["message"] as? String {
            self.showNotification(title: "Background Push", body: message)
        }

        let scheduler = koinIos.getScheduler()
        let trigger = TaskTriggerHelperKt.createTaskTriggerOneTime(initialDelayMs: 5000)
        let constraints = TaskTriggerHelperKt.createConstraints()
        scheduler.enqueue(
            id: "task-from-push-\(UUID().uuidString)",
            trigger: trigger,
            workerClassName: "one-time-upload",
            constraints: constraints,
            inputJson: nil,
            policy: .replace
        ) { result, error in
            if let error = error {
                print(" KMP_PUSH_IOS: Failed to schedule task from push. Error: \(error)")
                completionHandler(.failed)
            } else {
                print(" KMP_PUSH_IOS: Successfully scheduled task from push. Result: \(result!)")
                completionHandler(.newData)
            }
        }
    }

    /**
     * Registers handlers for each background task identifier listed in Info.plist.
     * This code runs when the app launches to tell the system what to do when a task is triggered.
     */
    private func registerBackgroundTasks() {
        let taskIds = ["kmp_chain_executor_task", "periodic-sync-task", "one-time-upload", "heavy-task-1", "network-task"]

        taskIds.forEach { taskId in
            BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
                print("iOS BGTask: Generic handler received task: \(task.identifier)")
                if (taskId == "kmp_chain_executor_task") {
                    self.handleChainExecutorTask(task: task)
                } else {
                    self.handleSingleTask(task: task)
                }
            }
        }
    }

    private func handleSingleTask(task: BGTask) {
        let taskId = task.identifier
        let userDefaults = UserDefaults.standard

        // Define an expiration handler
        task.expirationHandler = {
            print("iOS BGTask: Task \(taskId) expired.")
            task.setTaskCompleted(success: false)
        }

        // Check if it's a periodic task
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

        // Execute the task using the KMP SingleTaskExecutor
        let executor = koinIos.getSingleTaskExecutor()
        executor.executeTask(workerClassName: workerName, input: inputJson) { (success, error) in
            if let error = error {
                print("iOS BGTask: Task \(taskId) failed with error: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
                return
            }

            let result = success?.boolValue ?? false
            print("iOS BGTask: Task \(taskId) finished with success: \(result)")

            // If it was a periodic task, re-schedule it.
            if let meta = periodicMeta, meta["isPeriodic"] == "true" {
                print("iOS BGTask: Re-scheduling periodic task \(taskId).")
                let scheduler = self.koinIos.getScheduler()
                let intervalMs = Int64(meta["intervalMs"] ?? "0") ?? 0
                let requiresNetwork = (meta["requiresNetwork"] ?? "false") == "true"
                let requiresCharging = (meta["requiresCharging"] ?? "false") == "true"
                let isHeavyTask = (meta["isHeavyTask"] ?? "false") == "true"

                let constraints = Constraints(requiresNetwork: requiresNetwork, requiresUnmeteredNetwork: false, requiresCharging: requiresCharging, allowWhileIdle: false, qos: .background, isHeavyTask: isHeavyTask)
                let trigger = TaskTriggerPeriodic(intervalMs: intervalMs, flexMs: nil)

                scheduler.enqueue(id: taskId, trigger: trigger, workerClassName: workerName, constraints: constraints, inputJson: inputJson, policy: .replace) { _, _ in
                    // The re-scheduling is best-effort.
                }
            }

            task.setTaskCompleted(success: result)
        }
    }

    // --- ADDED: Handler logic for the KMP Chain Executor Task ---
    private func handleChainExecutorTask(task: BGTask) {
        print("iOS BGTask: Handling KMP Chain Executor Task")

        // Get the KMP ChainExecutor from Koin
        let chainExecutor = koinIos.getChainExecutor()

        // Define an expiration handler
        task.expirationHandler = {
            print("iOS BGTask: KMP Chain Executor Task expired.")
            task.setTaskCompleted(success: false)
        }

        // Schedule the next task if needed
        let scheduleNext: () -> Void = {
            if chainExecutor.getChainQueueSize() > 0 {
                print("iOS BGTask: More chains in queue. Rescheduling executor task.")
                let request = BGProcessingTaskRequest(identifier: "kmp_chain_executor_task")
                request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
                request.requiresNetworkConnectivity = true
                try? BGTaskScheduler.shared.submit(request)
            }
        }

        // Execute the next chain from the KMP queue
        chainExecutor.executeNextChainFromQueue { (success, error) in
            if let error = error {
                print("iOS BGTask: KMP Chain execution failed with error: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
                scheduleNext() // Schedule next even if this one failed
                return
            }

            let result = success?.boolValue ?? false
            print("iOS BGTask: KMP Chain execution finished with success: \(result)")
            task.setTaskCompleted(success: result)
            scheduleNext()
        }
    }

    /**
     * Helper function to show a local notification for debugging.
     */
    func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        // A nil trigger means the notification will be shown immediately.
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error showing local notification: \(error.localizedDescription)")
            }
        }
    }
}

/**
 * A SwiftUI View that wraps the Compose Multiplatform UIViewController.
 * This acts as a bridge between the SwiftUI and Compose worlds.
 */
struct MainView: UIViewControllerRepresentable {
    let koinIos: KoinIOS

    /**
     * Creates the UIViewController instance to be managed by SwiftUI.
     */
    func makeUIViewController(context: Context) -> UIViewController {
        print("📱 MainView: makeUIViewController called")
        print("📱 MainView: koinIos = \(koinIos)")

        let scheduler = koinIos.getScheduler()
        print("📱 MainView: scheduler = \(scheduler)")

        let pushHandler = koinIos.getPushHandler()
        print("📱 MainView: pushHandler = \(pushHandler)")

        print("📱 MainView: About to call MainViewController from Kotlin")
        let viewController = MainViewControllerKt.MainViewController(scheduler: scheduler, pushHandler: pushHandler)
        print("📱 MainView: MainViewController created: \(viewController)")

        // Debug: Set background color to verify view controller is displayed
        viewController.view.backgroundColor = .red
        print("📱 MainView: View background set to RED for debugging")

        return viewController
    }

    /**
     * Updates the presented UIViewController with new information.
     * In this case, there's nothing to update, so the body is empty.
     */
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}