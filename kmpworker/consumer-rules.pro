# KmpWorkManager — consumer ProGuard / R8 rules
# Applied automatically to any Android app that depends on this library.
#
# WHY THIS FILE EXISTS
# Workers are looked up by the class name stored in WorkManager's database and in
# KmpWorkManager's own persistence layer (inputJson, task metadata, chain definitions).
# If R8 renames or removes a Worker class, any task persisted under the old name becomes
# permanently unresolvable — a silent, hard-to-reproduce failure mode.

# ── Worker implementations ─────────────────────────────────────────────────────────────
# Keep all classes that extend the KmpWorkManager worker base classes so that:
#   1. Their simple names are stable across app updates (factory key lookup).
#   2. Their no-arg constructors survive stripping (WorkerProcessor generates `WorkerClass()`).

-keep class * extends dev.brewkits.kmpworkmanager.background.domain.AndroidWorker {
    <init>();
}

-keep class * extends dev.brewkits.kmpworkmanager.background.data.BaseKmpWorker {
    <init>();
}

# ── Generated factories ────────────────────────────────────────────────────────────────
# The KSP-generated factories are referenced by name at runtime via reflection-like
# lookup.  Keep them and their `providers` field so DI overrides survive shrinking.

-keep class dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated {
    public *;
}

# ── Serializable data classes ──────────────────────────────────────────────────────────
# kotlinx.serialization accesses field names at runtime.  Any data class that is
# serialized to / deserialized from disk must keep its field names intact.

-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep @kotlinx.serialization.Serializable class dev.brewkits.kmpworkmanager.** { *; }

# ── WorkManager integration ────────────────────────────────────────────────────────────
# WorkManager itself ships consumer rules, but adding this ensures our wrapper classes
# that implement ListenableWorker are not removed by aggressive stripping.

-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Suppress notes for intentional keep rules ─────────────────────────────────────────
-dontnote dev.brewkits.kmpworkmanager.**
