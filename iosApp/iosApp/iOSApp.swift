import UIKit
import SwiftUI
import ComposeApp
import BackgroundTasks
import UserNotifications

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)

        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.delegate = self
        notificationCenter.requestAuthorization(options: [.alert, .badge, .sound]) { success, error in
            if success {
                print("Permissions granted for notifications.")
            } else if let error = error {
                print(error.localizedDescription)
            }
        }

        registerBackgroundTasks()

        window = UIWindow(frame: UIScreen.main.bounds)
        let mainView = MainView()
        let hostingController = UIHostingController(rootView: mainView)
        window?.rootViewController = hostingController
        window?.makeKeyAndVisible()

        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .badge])
    }

    // Helper function to show a notification
    func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        // Show the notification immediately
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error showing notification: \(error.localizedDescription)")
            }
        }
    }

    private func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "periodic-sync-task", using: nil) { task in
            print("iOS BGTask: Handling periodic-sync-task task")
            self.showNotification(title: "Background Sync", body: "Periodic sync task completed.")
            task.setTaskCompleted(success: true)
        }

        BGTaskScheduler.shared.register(forTaskWithIdentifier: "one-time-upload", using: nil) { task in
            print("iOS BGTask: Handling one-time-upload task")
            self.showNotification(title: "Background Task", body: "One-time upload task finished.")
            task.setTaskCompleted(success: true)
        }

        BGTaskScheduler.shared.register(forTaskWithIdentifier: "heavy-task-1", using: nil) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            print("iOS BGTask: Handling heavy-task-1 task")
            self.showNotification(title: "Background Task", body: "Heavy task finished.")
            processingTask.setTaskCompleted(success: true)
        }

        BGTaskScheduler.shared.register(forTaskWithIdentifier: WorkerTypes.shared.SYNC_WORKER, using: nil) { task in
            print("iOS BGTask: Handling SYNC_WORKER task")
            self.scheduleAppRefresh()
            task.setTaskCompleted(success: true)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: WorkerTypes.shared.UPLOAD_WORKER, using: nil) { task in
            print("iOS BGTask: Handling UPLOAD_WORKER task")
            task.setTaskCompleted(success: true)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: WorkerTypes.shared.HEAVY_PROCESSING_WORKER, using: nil) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            print("iOS BGTask: Handling HEAVY_PROCESSING_WORKER task")
            processingTask.setTaskCompleted(success: true)
        }
    }

    func scheduleAppRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: WorkerTypes.shared.SYNC_WORKER)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            print("iOS BGTask: Submitted periodic task successfully.")
        } catch {
            print("iOS BGTask: Could not schedule app refresh: \(error)")
        }
    }
}

struct MainView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
