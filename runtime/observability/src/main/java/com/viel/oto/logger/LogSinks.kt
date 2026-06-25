package com.viel.oto.logger

/**
 * Tagged warning/error sink for data-layer diagnostics.
 *
 * Data components use this interface when they need SecureLog semantics without depending on the concrete
 * Android Log adapter object. The tag remains caller-owned so each gateway keeps its existing diagnostic identity.
 */
interface DiagnosticLogSink {
    fun warn(tag: String, message: String, error: Throwable? = null)

    fun error(tag: String, message: String, error: Throwable? = null)
}

/**
 * Workflow-scoped log sink for lifecycle and background-task diagnostics.
 *
 * The interface mirrors the small severity surface shared by scan and playback workflow loggers, letting data
 * gateways depend on observability contracts while DI selects the workflow-specific adapter.
 */
interface WorkflowLogSink {
    fun info(message: String)

    fun debug(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)
}

/**
 * SecureLog-backed diagnostic sink.
 */
object SecureDiagnosticLogSink : DiagnosticLogSink {
    override fun warn(tag: String, message: String, error: Throwable?) {
        SecureLog.warn(tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        SecureLog.error(tag, message, error)
    }
}

/**
 * ScanWorkflowLogger-backed workflow sink.
 */
object ScanWorkflowLogSink : WorkflowLogSink {
    override fun info(message: String) {
        ScanWorkflowLogger.info(message)
    }

    override fun debug(message: String) {
        ScanWorkflowLogger.debug(message)
    }

    override fun warn(message: String, error: Throwable?) {
        ScanWorkflowLogger.warn(message, error)
    }

    override fun error(message: String, error: Throwable?) {
        ScanWorkflowLogger.error(message, error)
    }
}

/**
 * PlaybackWorkflowLogger-backed workflow sink.
 */
object PlaybackWorkflowLogSink : WorkflowLogSink {
    override fun info(message: String) {
        PlaybackWorkflowLogger.info(message)
    }

    override fun debug(message: String) {
        PlaybackWorkflowLogger.debug(message)
    }

    override fun warn(message: String, error: Throwable?) {
        PlaybackWorkflowLogger.warn(message, error)
    }

    override fun error(message: String, error: Throwable?) {
        PlaybackWorkflowLogger.error(message, error)
    }
}

/**
 * Silent workflow sink for unit tests and adapters that intentionally suppress workflow diagnostics.
 */
object NoOpWorkflowLogSink : WorkflowLogSink {
    override fun info(message: String) = Unit

    override fun debug(message: String) = Unit

    override fun warn(message: String, error: Throwable?) = Unit

    override fun error(message: String, error: Throwable?) = Unit
}
