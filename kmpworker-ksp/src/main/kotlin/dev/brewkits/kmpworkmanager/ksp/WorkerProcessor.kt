package dev.brewkits.kmpworkmanager.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
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

            val workerName = (annotation.arguments
                .firstOrNull { it.name?.asString() == "name" }
                ?.value as? String)
                ?.ifEmpty { null }
                ?: classDecl.simpleName.asString()

            val bgTaskId = annotation.arguments
                .firstOrNull { it.name?.asString() == "bgTaskId" }
                ?.value as? String
                ?: ""

            val info = WorkerInfo(
                name = workerName,
                className = classDecl.toClassName(),
                bgTaskId = bgTaskId
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

        val providersInit = CodeBlock.builder().apply {
            add("mutableMapOf(\n")
            workers.forEach { add("    %S to { %T() as %T },\n", it.name, it.className, androidWorkerClass) }
            add(")")
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
                            ParameterizedTypeName.run {
                                Map::class.asClassName().parameterizedBy(
                                    String::class.asClassName(),
                                    LambdaTypeName.get(returnType = androidWorkerClass.copy(nullable = true))
                                ).let {
                                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(
                                        String::class.asClassName(),
                                        LambdaTypeName.get(returnType = androidWorkerClass.copy(nullable = true))
                                    )
                                }
                            }
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
            workers.forEach { add("    %S to { %T() as %T },\n", it.name, it.className, iosWorkerClass) }
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
    val bgTaskId: String
)
