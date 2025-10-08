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
    let koinIos: KoinIOS

    override init() {
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
        koinIos = KoinIOS()
        super.init()
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

        // --- Standard SwiftUI & Compose UI Setup ---

        // Create the main application window.
        window = UIWindow(frame: UIScreen.main.bounds)

        // Create the root view for the SwiftUI part of the app.
        // MainView is a struct that wraps the Compose UIViewController.
        let mainView = MainView()

        // UIHostingController allows SwiftUI views to be used in a UIKit hierarchy.
        let hostingController = UIHostingController(rootView: mainView)

        // Set the hosting controller as the root view controller of the window.
        window?.rootViewController = hostingController

        // Make the window visible on the screen.
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
            .requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
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
        // Handler for a periodic background task (e.g., syncing data).
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "periodic-sync-task", using: nil) { task in
            print("iOS BGTask: Handling periodic-sync-task")
            // In a real app, you would perform the sync here.
            // We show a local notification for debugging purposes.
            self.showNotification(title: "Periodic Sync Task Completed", body: "A background task has been completed.")
            task.setTaskCompleted(success: true)
        }

        // Handler for a one-time background task.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "one-time-upload", using: nil) { task in
            print("iOS BGTask: Handling one-time-upload task")
            self.showNotification(title: "One-Time Upload Task Completed", body: "A background task has been completed.")
            task.setTaskCompleted(success: true)
        }

        // Handler for a long-running, processing-intensive background task.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "heavy-task-1", using: nil) { task in
            // Safely cast the task to a BGProcessingTask.
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            print("iOS BGTask: Handling heavy-task-1")
            self.showNotification(title: "Background Task", body: "Heavy task finished.")
            processingTask.setTaskCompleted(success: true)
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
    /**
     * Creates the UIViewController instance to be managed by SwiftUI.
     */
    func makeUIViewController(context: Context) -> UIViewController {
        let appDelegate = UIApplication.shared.delegate as! AppDelegate
        let scheduler = appDelegate.koinIos.getScheduler()
        let pushHandler = appDelegate.koinIos.getPushHandler()
        return MainViewControllerKt.MainViewController(scheduler: scheduler, pushHandler: pushHandler)
    }

    /**
     * Updates the presented UIViewController with new information.
     * In this case, there's nothing to update, so the body is empty.
     */
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}