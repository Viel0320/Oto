package com.viel.aplayer.library.orchestrator.draftmodels

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity

/**
 * 表示导入流水线中生成的导入执行命令，用于最终的数据持久化写入操作。
 */
sealed interface ImportCommand {
    /**
     * 创建一本完整、没有冲突且准备就绪的有声书草稿。
     */
    data class CreateReadyBook(val draft: BookDraft) : ImportCommand

    /**
     * 更新一本已存在的有声书的元数据及其章节和文件信息。
     */
    data class UpdateExistingBook(val bookId: String, val draft: BookDraft) : ImportCommand

    // Rescans only refresh file claim visibility; book metadata is written when a book is created.
    /**
     * 重新扫描时，仅更新已存在有声书关联的物理文件的可见性或所有权认领状态。
     * 有声书本身的元数据是在首次创建时写入的，不在此步骤重复覆盖。
     */
    data class RefreshExistingBook(val bookId: String, val files: List<BookFileEntity>) : ImportCommand

    /**
     * 创建一个待处理的扫描动作记录（例如由于文件冲突、元数据冲突等导致的需要人工介入的动作）。
     */
    data class CreatePendingAction(val action: PendingScanActionEntity) : ImportCommand

    /**
     * 记录导入过程中的失败错误信息。
     */
    data class RecordFailure(val failure: ImportFailure) : ImportCommand
}

/**
 * 有声书的书籍草稿数据包，聚合了书籍实体、关联的物理文件实体以及章节实体。
 */
data class BookDraft(
    /**
     * 书籍本身的核心元数据实体（如书名、作者、封面路径等）。
     */
    val book: BookEntity,

    /**
     * 该有声书包含的物理文件实体列表（包含音频文件、描述文件等）。
     * 物理文件所有权关系由 BookFile 实体进行维护。
     */
    val files: List<BookFileEntity>,

    // Source semantics live on Book.sourceType; manifest/audio ownership lives in BookFile.
    /**
     * 书籍的章节实体列表。
     * 书籍的数据源语义存储在 BookEntity 的 sourceType 属性中。
     */
    val chapters: List<ChapterEntity>
)

/**
 * 导入失败记录的详细结构，用于追溯扫描导入异常。
 */
data class ImportFailure(
    /**
     * 导致失败的源文件或目录的统一资源标识符 (URI)。
     */
    val sourceUri: String,

    /**
     * 失败的业务描述或说明信息。
     */
    val message: String,

    /**
     * 可选的底层异常或错误的追踪消息。
     */
    val throwableMessage: String? = null
)

/**
 * 导入流水线单次扫描运行的综合结果，用于反馈给界面展示或扫描会话统计。
 */
data class ImportRunResult(
    /**
     * 当前扫描会话的唯一标识 ID。
     */
    val scanId: String,

    /**
     * 成功准备好导入的全新书籍命令列表。
     */
    val readyImports: List<ImportCommand.CreateReadyBook>,

    /**
     * 仅刷新物理文件信息的已存在书籍命令列表。
     */
    val refreshedBooks: List<ImportCommand.RefreshExistingBook>,

    /**
     * 生成的待人工确认或待处理的扫描操作命令列表（如文件冲突、覆盖确认等）。
     */
    val pendingActions: List<ImportCommand.CreatePendingAction>,

    /**
     * 导入失败的错误记录命令列表。
     */
    val failures: List<ImportCommand.RecordFailure>
) {
    /**
     * 此次扫描新发现的、可直接导入的书籍总数。
     */
    val discoveredCount: Int get() = readyImports.size

    // Names are persisted for ScanResultDialog item details.
    /**
     * 此次新发现并可以导入的所有书籍名称列表，主要用于在扫描结果对话框中展示详情。
     */
    val discoveredNames: List<String> get() = readyImports.map { it.draft.book.title }

    /**
     * 所有待处理操作的描述消息列表。
     */
    val pendingNames: List<String> get() = pendingActions.map { it.action.message }

    /**
     * 处于 “部分新增书籍” 状态的待处理操作的描述消息列表。
     */
    val partialNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }
        .map { it.action.message }

    /**
     * 处于 “更新已存在书籍” 状态的待处理操作的描述消息列表。
     */
    val updateExistingNames: List<String> get() = pendingActions
        .filter { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }
        .map { it.action.message }

    /**
     * 导入失败的书籍源文件名和失败原因的格式化消息列表。
     */
    val failureNames: List<String> get() = failures.map { "${it.failure.sourceUri.substringAfterLast('/')}: ${it.failure.message}" }

    // Pending stats back ScanSession and the scan-complete dialog.
    /**
     * 冲突类待处理操作的总数，支持扫描会话和完成对话框的数据统计。
     */
    val conflictCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.CONFLICT }

    /**
     * 更新已存在书籍类的待处理操作总数。
     */
    val updateExistingCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.UPDATE_EXISTING }

    /**
     * 部分新增书籍类的待处理操作总数。
     */
    val partialNewBookCount: Int get() = pendingActions.count { it.action.type == AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK }

    /**
     * 此次扫描发生的失败总数。
     */
    val failureCount: Int get() = failures.size
}