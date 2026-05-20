package com.viel.aplayer.library.orchestrator


/**
 * 流水线步骤接口契约
 * 
 * @param I 输入的数据类型 (Input)
 * @param O 处理完毕后返回的数据类型 (Output)
 * 
 * 为每一次改动添加详尽的中文注释：
 * 利用 Kotlin 泛型参数的 in（逆变，输入）与 out（协变，输出），
 * 强制每个步骤只能处理属于自己职责的入参与出参，从编译器层面彻底避免职责越界。
 */
internal interface ImportStep<in I, out O> {
    
    // 该步骤的物理名称，用于进度统计和追踪调试
    val stepName: String
    
    /**
     * 在协程异步执行的具体工位逻辑
     * 
     * 为每一次改动添加详尽的中文注释：
     * 修改为 internal 限制包内可见性，确保不会向其他 module 暴露，
     * 同时也顺利解决了由于引用 internal 类型导致 public 泄露的编译报错。
     */
    suspend fun execute(input: I, context: ImportContext): StepResult<O>
}

/**
 * 导入工位执行返回的包装状态密封类
 */
internal sealed interface StepResult<out T> {
    
    // 工位执行成功，携带输出类型 T 的具体数据
    data class Success<out T>(val data: T) : StepResult<T>
    
    // 工位执行失败，携带具体的 throwable 异常以及对初学者极友好的中文错误说明
    data class Failure(val throwable: Throwable, val errorMessage: String) : StepResult<Nothing>
}