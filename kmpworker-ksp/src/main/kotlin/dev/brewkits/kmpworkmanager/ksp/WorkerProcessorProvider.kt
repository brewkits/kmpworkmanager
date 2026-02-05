package dev.brewkits.kmpworkmanager.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Provider for WorkerProcessor
 * Registered via META-INF/services
 */
class WorkerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return WorkerProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
