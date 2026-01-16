package dev.brewkits.kmpworkmanager.sample

actual object DemoConfig {
    actual fun getApproachName(): String = "Manual"

    actual fun getApproachDescription(): String =
        "No DI framework • Zero dependencies • Direct WorkerManagerInitializer.initialize()"
}
