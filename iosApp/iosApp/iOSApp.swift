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

        let scheduler = koinIos.getScheduler()
        let executor = koinIos.getSingleTaskExecutor()
        let chainExecutor = koinIos.getChainExecutor()

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
                print("iOS BGTask: Handler received task: \(task.identifier)")
                if taskId == "kmp_chain_executor_task" {
                    // Delegate chain batch execution to the library handler
                    IosBackgroundTaskHandler.shared.handleChainExecutorTask(
                        task: task,
                        chainExecutor: chainExecutor
                    )
                } else {
                    // Delegate single-task execution to the library handler.
                    // Reads worker metadata from file storage, executes, and auto-reschedules
                    // periodic tasks — no Swift boilerplate needed.
                    IosBackgroundTaskHandler.shared.handleSingleTask(
                        task: task,
                        scheduler: scheduler,
                        executor: executor
                    )
                }
            }
        }
        print("iOS BGTask: Registration completed. Total registered: \(AppDelegate.registeredTaskIds.count)")
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
