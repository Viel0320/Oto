package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.data.LibraryRootEntity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileInventoryScanner
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 物理文件存量扫描分步类
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本工位在底层异步线程执行对指定有声书库根目录的遍历。
 * 它调用已有的 FileInventoryScanner，收集所有 CUE、M3U8、音频文件及同级封面图像，
 * 并整理输出为 FileInventory 对象，供流水线的后续解析工位直接读取。
 */
internal class InventoryScanStep(
    private val context: Context
) : ImportStep<List<LibraryRootEntity>, FileInventory> {

    override val stepName: String = "InventoryScanStep"

    override suspend fun execute(
        input: List<LibraryRootEntity>,
        context: ImportContext
    ): StepResult<FileInventory> = runCatching {
        // 调用底层的物理文件检索器扫描所有指定的库根目录
        val scanner = FileInventoryScanner(this.context)
        val inventory = scanner.scan(input)
        StepResult.Success(inventory)
    }.getOrElse { throwable ->
        StepResult.Failure(throwable, "物理文件扫描失败，详情: ${throwable.localizedMessage}")
    }
}
