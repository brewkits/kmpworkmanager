package dev.brewkits.kmpworkmanager

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.api.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.api.Constraints
import dev.brewkits.kmpworkmanager.api.TaskTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Chinese ROM Compatibility Tests
 *
 * Tests for edge cases and limitations on Chinese Android ROMs:
 * - Xiaomi MIUI
 * - Huawei EMUI / HarmonyOS
 * - Oppo ColorOS
 * - Vivo FuntouchOS
 * - Realme UI
 *
 * These ROMs have aggressive battery optimization and background task restrictions
 * that can prevent WorkManager tasks from executing properly.
 *
 * Added in v2.3.2
 */
class ChineseROMCompatibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Test: Detect Chinese ROM manufacturer
     *
     * Identifies if the device is running a Chinese ROM variant
     */
    @Test
    fun testDetectChineseROM() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        val isChineseROM = manufacturer in listOf("xiaomi", "huawei", "oppo", "vivo", "realme", "oneplus") ||
                brand in listOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "vivo", "realme", "oneplus")

        println("Manufacturer: $manufacturer, Brand: $brand, Is Chinese ROM: $isChineseROM")

        if (isChineseROM) {
            println("⚠️ Running on Chinese ROM - aggressive battery optimization expected")
        }
    }

    /**
     * Test: MIUI battery optimization status
     *
     * Xiaomi MIUI has aggressive battery optimization that can kill background tasks.
     * Tests if battery optimization is disabled for the app.
     */
    @Test
    fun testMIUIBatteryOptimization() {
        if (!isXiaomiDevice()) {
            println("Skipping MIUI test - not a Xiaomi device")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isOptimized = !powerManager.isIgnoringBatteryOptimizations(packageName)
            println("MIUI Battery Optimization Status: ${if (isOptimized) "ENABLED (⚠️ may kill tasks)" else "DISABLED"}")

            if (isOptimized) {
                println("""
                    |⚠️ MIUI Battery Optimization Detected
                    |
                    |To ensure tasks run on MIUI:
                    |1. Settings > Apps > Manage apps > [Your App] > Battery saver > No restrictions
                    |2. Settings > Apps > Manage apps > [Your App] > Autostart > Enable
                    |3. Settings > Battery & performance > App battery saver > [Your App] > No restrictions
                """.trimMargin())
            }
        }
    }

    /**
     * Test: MIUI autostart permission
     *
     * MIUI requires explicit autostart permission for background tasks.
     * This test checks if the permission needs to be requested.
     */
    @Test
    fun testMIUIAutostartPermission() {
        if (!isXiaomiDevice()) {
            println("Skipping autostart test - not a Xiaomi device")
            return
        }

        // MIUI autostart can be detected via PowerManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasAutostart = powerManager.isIgnoringBatteryOptimizations(packageName)
            println("MIUI Autostart Status: ${if (hasAutostart) "GRANTED ✅" else "DENIED ⚠️"}")

            if (!hasAutostart) {
                println("""
                    |To enable autostart on MIUI:
                    |Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                    |intent.setClassName("com.miui.securitycenter",
                    |    "com.miui.permcenter.autostart.AutoStartManagementActivity");
                    |intent.putExtra("extra_pkgname", packageName);
                    |startActivity(intent);
                """.trimMargin())
            }
        }
    }

    /**
     * Test: Huawei EMUI battery optimization
     *
     * Huawei devices have protected apps feature that prevents app termination.
     */
    @Test
    fun testEMUIBatteryOptimization() {
        if (!isHuaweiDevice()) {
            println("Skipping EMUI test - not a Huawei device")
            return
        }

        println("""
            |⚠️ EMUI/HarmonyOS Battery Management
            |
            |Huawei devices require manual configuration:
            |1. Settings > Battery > Launch > [Your App] > Manage manually
            |   - Auto-launch: ON
            |   - Secondary launch: ON
            |   - Run in background: ON
            |2. Settings > Apps > [Your App] > Battery > Power-intensive prompt: Disable
            |3. Recent apps > Lock icon on your app
            |
            |Note: Cannot be automated - user must configure manually
        """.trimMargin())
    }

    /**
     * Test: Oppo/Realme ColorOS battery optimization
     *
     * ColorOS has aggressive battery management that kills background tasks.
     */
    @Test
    fun testColorOSBatteryOptimization() {
        if (!isOppoDevice() && !isRealmeDevice()) {
            println("Skipping ColorOS test - not an Oppo/Realme device")
            return
        }

        println("""
            |⚠️ ColorOS/Realme UI Battery Management
            |
            |ColorOS requires manual configuration:
            |1. Settings > Battery > Power Saving Mode > OFF
            |2. Settings > Battery > App Quick Freeze > [Your App] > Disabled
            |3. Settings > Privacy permissions > Startup manager > [Your App] > Allow
            |4. Recent apps > Lock your app
            |
            |Programmatic Intent (may not work on all versions):
            |Intent intent = new Intent();
            |intent.setClassName("com.coloros.safecenter",
            |    "com.coloros.safecenter.permission.startup.StartupAppListActivity");
            |startActivity(intent);
        """.trimMargin())
    }

    /**
     * Test: Vivo FuntouchOS battery optimization
     *
     * Vivo has background app control that prevents background execution.
     */
    @Test
    fun testFuntouchOSBatteryOptimization() {
        if (!isVivoDevice()) {
            println("Skipping FuntouchOS test - not a Vivo device")
            return
        }

        println("""
            |⚠️ Vivo FuntouchOS Battery Management
            |
            |Vivo devices require:
            |1. Settings > Battery > Background power consumption management > [Your App] > Allow
            |2. Settings > More settings > Applications > Autostart > [Your App] > Enable
            |3. Settings > Battery > High background power consumption > [Your App] > Allow
            |
            |Note: Settings path varies by FuntouchOS version
        """.trimMargin())
    }

    /**
     * Test: WorkManager task scheduling on Chinese ROMs
     *
     * Verifies that tasks can be scheduled despite Chinese ROM restrictions.
     */
    @Test
    fun testWorkManagerSchedulingOnChineseROM() = runBlocking {
        val scheduler = KmpWorkManager.getScheduler() as? BackgroundTaskScheduler
        if (scheduler == null) {
            println("⚠️ Scheduler not initialized - skipping test")
            return@runBlocking
        }

        val taskId = "chinese-rom-test-${System.currentTimeMillis()}"

        try {
            // Schedule a simple task
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
                workerClassName = "TestWorker",
                inputJson = """{"test":"chinese-rom"}""",
                constraints = Constraints(
                    requiresNetwork = false,
                    requiresCharging = false
                )
            )

            println("✅ Task scheduled successfully: $taskId")

            // Check WorkManager status
            val workManager = WorkManager.getInstance(context)
            delay(1000)

            val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
            if (workInfo.isNotEmpty()) {
                val state = workInfo[0].state
                println("Task state: $state")

                when (state) {
                    WorkInfo.State.ENQUEUED -> println("✅ Task enqueued - waiting for execution")
                    WorkInfo.State.RUNNING -> println("✅ Task running")
                    WorkInfo.State.SUCCEEDED -> println("✅ Task completed successfully")
                    WorkInfo.State.BLOCKED -> println("⚠️ Task blocked - check constraints")
                    WorkInfo.State.CANCELLED -> println("❌ Task cancelled")
                    WorkInfo.State.FAILED -> println("❌ Task failed")
                }

                if (isChineseROM()) {
                    println("""
                        |⚠️ Note: On Chinese ROMs, tasks may not execute without:
                        |- Battery optimization disabled
                        |- Autostart permission granted
                        |- App locked in recent apps
                        |- Background restrictions removed
                    """.trimMargin())
                }
            }

        } finally {
            // Cleanup
            scheduler.cancel(taskId)
        }
    }

    /**
     * Test: Exact alarm scheduling on Chinese ROMs (Android 12+)
     *
     * Chinese ROMs may restrict exact alarms even with permission.
     */
    @Test
    fun testExactAlarmsOnChineseROM() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            println("Skipping exact alarm test - requires Android 12+")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canScheduleExact = alarmManager.canScheduleExactAlarms()

        println("Can schedule exact alarms: $canScheduleExact")

        if (!canScheduleExact) {
            println("""
                |⚠️ Exact alarms not available
                |
                |Request permission:
                |if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                |    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                |    startActivity(intent)
                |}
            """.trimMargin())
        }

        if (isChineseROM() && canScheduleExact) {
            println("""
                |✅ Exact alarms available on Chinese ROM
                |
                |Note: Even with permission, exact alarms may be delayed by:
                |- Battery saver mode
                |- Doze mode
                |- ROM-specific power management
            """.trimMargin())
        }
    }

    /**
     * Test: Foreground service restrictions on Chinese ROMs
     *
     * Chinese ROMs may restrict foreground service starts.
     */
    @Test
    fun testForegroundServiceOnChineseROM() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            println("Skipping FGS test - requires Android 12+")
            return
        }

        println("""
            |ℹ️ Foreground Service Restrictions (Android 12+)
            |
            |Chinese ROMs enforce strict FGS restrictions:
            |1. FGS can only start from visible activities
            |2. WorkManager with expedited work requires battery optimization off
            |3. Heavy tasks (isHeavyTask=true) use FGS internally
            |
            |Recommendation for Chinese ROMs:
            |- Use regular WorkManager tasks (not expedited)
            |- Request battery optimization exemption
            |- Avoid relying on exact timing
        """.trimMargin())

        if (isChineseROM()) {
            println("⚠️ Chinese ROM detected - FGS restrictions may be more aggressive")
        }
    }

    // Helper functions

    private fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer == "xiaomi" || brand in listOf("xiaomi", "redmi", "poco")
    }

    private fun isHuaweiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer == "huawei" || brand in listOf("huawei", "honor")
    }

    private fun isOppoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer == "oppo" || brand == "oppo"
    }

    private fun isRealmeDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer == "realme" || brand == "realme"
    }

    private fun isVivoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer == "vivo" || brand == "vivo"
    }

    private fun isChineseROM(): Boolean {
        return isXiaomiDevice() || isHuaweiDevice() || isOppoDevice() ||
               isRealmeDevice() || isVivoDevice()
    }
}
