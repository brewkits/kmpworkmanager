package dev.brewkits.kmpworkmanager.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * KSP processor for @Worker annotation.
 *
 * Generates:
 * - `AndroidWorkerFactoryGenerated` — for classes extending AndroidWorker
 * - `IosWorkerFactoryGenerated`     — for classes extending IosWorker, also implements
 *   `BgTaskIdProvider` when any worker has a non-empty `bgTaskId`
 *
 * The generated factories use a `providers: MutableMap<String, () -> WorkerType?>` so
 * consumers can override individual entries for DI (e.g. Koin, Hilt) without forking the
 * generated file.
 */
class WorkerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("WorkerProcessor KSP is executing. Finding symbols...")
        val workerSymbols = resolver
            .getSymbolsWithAnnotation("dev.brewkits.kmpworkmanager.annotations.Worker")
            .filterIsInstance<KSClassDeclaration>()

        if (!workerSymbols.iterator().hasNext()) return emptyList()

        val androidWorkers = mutableListOf<WorkerInfo>()
        val iosWorkers = mutableListOf<WorkerInfo>()

        workerSymbols.forEach { classDecl ->
            val annotation = classDecl.annotations.first {
                it.shortName.asString() == "Worker"
            }

            val explicitName = (annotation.arguments
                .firstOrNull { it.name?.asString() == "name" }
                ?.value as? String)
                ?.ifEmpty { null }

            if (explicitName == null) {
                // Using simpleName as factory key is fragile: a class rename or ProGuard
                // obfuscation changes the key, silently breaking worker lookup for any
                // tasks already persisted under the old name.
                // Fix: add @Worker(name = "StableIdentifier") to prevent breakage on rename.
                logger.warn(
                    "@Worker on ${classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()} " +
                    "has no explicit `name`. The factory key will be the simple class name " +
                    "(\"${classDecl.simpleName.asString()}\"). " +
                    "Rename or ProGuard obfuscation will silently break persisted tasks. " +
                    "Add @Worker(name = \"${classDecl.simpleName.asString()}\") to fix this.",
                    classDecl
                )
            }

            val workerName = explicitName ?: classDecl.simpleName.asString()

            val bgTaskId = annotation.arguments
                .firstOrNull { it.name?.asString() == "bgTaskId" }
                ?.value as? String
                ?: ""

            @Suppress("UNCHECKED_CAST")
            val aliases = (annotation.arguments
                .firstOrNull { it.name?.asString() == "aliases" }
                ?.value as? List<*>)
                ?.filterIsInstance<String>()
                ?: emptyList()

            val info = WorkerInfo(
                name = workerName,
                className = classDecl.toClassName(),
                bgTaskId = bgTaskId,
                aliases = aliases
            )

            val superTypes = classDecl.superTypes.map { it.resolve().declaration.simpleName.asString() }
            when {
                superTypes.contains("AndroidWorker") -> androidWorkers.add(info)
                superTypes.contains("IosWorker")     -> iosWorkers.add(info)
                else -> logger.warn(
                    "@Worker on ${classDecl.simpleName.asString()} doesn't extend AndroidWorker or IosWorker — skipped"
                )
            }
        }

        if (androidWorkers.isNotEmpty()) generateAndroidFactory(androidWorkers)
        if (iosWorkers.isNotEmpty())     generateIosFactory(iosWorkers)

        logger.info(
            "KmpWorker KSP: generated factories for " +
                "${androidWorkers.size} Android + ${iosWorkers.size} iOS workers"
        )
        return emptyList()
    }

    // ── Android factory ────────────────────────────────────────────────────────

    private fun generateAndroidFactory(workers: List<WorkerInfo>) {
        val packageName = "dev.brewkits.kmpworkmanager.generated"
        val workerFactoryClass = ClassName("dev.brewkits.kmpworkmanager.background.domain", "AndroidWorkerFactory")
        val androidWorkerClass = ClassName("dev.brewkits.kmpworkmanager.background.domain", "AndroidWorker")
        val concurrentHashMapClass = ClassName("java.util.concurrent", "ConcurrentHashMap")

        // ConcurrentHashMap: providers may be read by worker threads concurrently with
        // app-startup writes (DI container overrides). mutableMapOf() (LinkedHashMap) is
        // not thread-safe for concurrent reads during structural modification.
        val providersInit = CodeBlock.builder().apply {
            add("%T<String, () -> %T?>().apply {\n", concurrentHashMapClass, androidWorkerClass)
            workers.forEach { worker ->
                add("    put(%S) { %T() as %T }\n", worker.name, worker.className, androidWorkerClass)
                // Alias entries resolve to the same factory lambda as the canonical name
                worker.aliases.forEach { alias ->
                    add("    put(%S) { %T() as %T }  // alias for %S\n", alias, worker.className, androidWorkerClass, worker.name)
                }
            }
            add("}")
        }.build()

        val fileSpec = FileSpec.builder(packageName, "AndroidWorkerFactoryGenerated")
            .addFileComment(AUTO_GENERATED_HEADER.format(workers.size, "Android"))
            .addType(
                TypeSpec.classBuilder("AndroidWorkerFactoryGenerated")
                    .addSuperinterface(workerFactoryClass)
                    .addKdoc(
                        "Auto-generated Android worker factory.\n\n" +
                        "Override individual [providers] entries to supply workers from a DI container:\n" +
                        "```kotlin\n" +
                        "AndroidWorkerFactoryGenerated().also {\n" +
                        "    it.providers[\"SyncWorker\"] = { get<SyncWorker>() }\n" +
                        "}\n" +
                        "```"
                    )
                    .addProperty(
                        PropertySpec.builder(
                            "providers",
                            ClassName("java.util.concurrent", "ConcurrentHashMap").parameterizedBy(
                                String::class.asClassName(),
                                LambdaTypeName.get(returnType = androidWorkerClass.copy(nullable = true))
                            )
                        )
                            .initializer(providersInit)
                            .build()
                    )
                    .addFunction(
                        FunSpec.builder("createWorker")
                            .addModifiers(KModifier.OVERRIDE)
                            .addParameter("workerClassName", String::class)
                            .returns(androidWorkerClass.copy(nullable = true))
                            .addStatement("return providers[workerClassName]?.invoke()")
                            .build()
                    )
                    .build()
            )
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(false))
        logger.info("Generated: $packageName.AndroidWorkerFactoryGenerated")
    }

    // ── iOS factory ────────────────────────────────────────────────────────────

    private fun generateIosFactory(workers: List<WorkerInfo>) {
        val packageName = "dev.brewkits.kmpworkmanager.generated"
        val iosWorkerFactoryClass = ClassName("dev.brewkits.kmpworkmanager.background.data", "IosWorkerFactory")
        val iosWorkerClass = ClassName("dev.brewkits.kmpworkmanager.background.data", "IosWorker")
        val bgTaskIdProviderClass = ClassName("dev.brewkits.kmpworkmanager.background.domain", "BgTaskIdProvider")

        val requiredIds = workers.map { it.bgTaskId }.filter { it.isNotEmpty() }
        val hasBgTaskIds = requiredIds.isNotEmpty()

        val providersInit = CodeBlock.builder().apply {
            add("mutableMapOf(\n")
            workers.forEach { worker ->
                add("    %S to { %T() as %T },\n", worker.name, worker.className, iosWorkerClass)
                worker.aliases.forEach { alias ->
                    add("    %S to { %T() as %T },  // alias for %S\n", alias, worker.className, iosWorkerClass, worker.name)
                }
            }
            add(")")
        }.build()

        val typeBuilder = TypeSpec.classBuilder("IosWorkerFactoryGenerated")
            .addSuperinterface(iosWorkerFactoryClass)
            .addKdoc(
                "Auto-generated iOS worker factory.\n\n" +
                "Implements [BgTaskIdProvider]: `kmpWorkerModule()` automatically validates\n" +
                "all declared BGTask IDs against `Info.plist` at startup.\n\n" +
                "Override individual [providers] entries to supply workers from a DI container:\n" +
                "```kotlin\n" +
                "IosWorkerFactoryGenerated().also {\n" +
                "    it.providers[\"SyncWorker\"] = { get<SyncWorker>() }\n" +
                "}\n" +
                "```"
            )

        if (hasBgTaskIds) {
            typeBuilder.addSuperinterface(bgTaskIdProviderClass)
            typeBuilder.addProperty(
                PropertySpec.builder("requiredBgTaskIds", ParameterizedTypeName.run {
                    ClassName("kotlin.collections", "Set").parameterizedBy(String::class.asClassName())
                })
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("setOf(${requiredIds.joinToString { "\"$it\"" }})")
                    .addKdoc(
                        "BGTask IDs declared via `@Worker(bgTaskId = ...)` for validation\n" +
                        "against `Info.plist → BGTaskSchedulerPermittedIdentifiers`."
                    )
                    .build()
            )
        }

        typeBuilder
            .addProperty(
                PropertySpec.builder(
                    "providers",
                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(
                        String::class.asClassName(),
                        LambdaTypeName.get(returnType = iosWorkerClass.copy(nullable = true))
                    )
                )
                    .initializer(providersInit)
                    .build()
            )
            .addFunction(
                FunSpec.builder("createWorker")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("workerClassName", String::class)
                    .returns(iosWorkerClass.copy(nullable = true))
                    .addStatement("return providers[workerClassName]?.invoke()")
                    .build()
            )

        val fileSpec = FileSpec.builder(packageName, "IosWorkerFactoryGenerated")
            .addFileComment(AUTO_GENERATED_HEADER.format(workers.size, "iOS"))
            .addType(typeBuilder.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(false))
        logger.info(
            "Generated: $packageName.IosWorkerFactoryGenerated " +
                "(${if (hasBgTaskIds) "with BgTaskIdProvider: $requiredIds" else "no bgTaskIds"})"
        )
    }

    companion object {
        private val AUTO_GENERATED_HEADER = """
            AUTO-GENERATED by KSP WorkerProcessor — do not edit manually.
            Regenerate by rebuilding the project.
            Generated from %d @Worker annotated %s classes.
        """.trimIndent()
    }
}

private data class WorkerInfo(
    val name: String,
    val className: ClassName,
    val bgTaskId: String,
    val aliases: List<String> = emptyList()
)
