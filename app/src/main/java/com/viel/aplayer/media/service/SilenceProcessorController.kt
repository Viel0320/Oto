package com.viel.aplayer.media.service

import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.viel.aplayer.data.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 详尽的中文注释：
 * 静音跳过反射与监测控制器（SilenceProcessorController）。
 * 本类旨在将原本散落在 PlaybackService 内部的高风险 JVM 反射属性刺探算法、
 * 跳过静音最小时长实时热更新公式，以及 1000ms 后台轮询监测协程进行物理隔离与深度解耦。
 * 通过回调函数（Lambda）完成静音跳过事件通知，彻底消除了 Service 直接感知低级驱动刺探细节的弊端。
 */
@UnstableApi
class SilenceProcessorController {

    // 详尽的中文注释：缓存反射提取出的底层静音跳过处理器，以便轮询或更新其状态
    var customSilenceProcessor: SilenceSkippingAudioProcessor? = null

    /**
     * 详尽的中文注释：
     * 利用广度优先的反射扫描，自动从当前的 AudioSink 及其嵌套组件中搜寻并提取出 SilenceSkippingAudioProcessor 处理器。
     *
     * @param sink 媒体播放器底层的音频渲染器
     */
    fun findSilenceProcessorFromSink(sink: AudioSink?) {
        if (sink == null) return
        try {
            // 1. 尝试直接在第一层声明的字段中寻找
            val fields = sink.javaClass.declaredFields
            for (field in fields) {
                if (field.type == SilenceSkippingAudioProcessor::class.java) {
                    field.isAccessible = true
                    val processor = field.get(sink) as? SilenceSkippingAudioProcessor
                    if (processor != null) {
                        customSilenceProcessor = processor
                        Log.d("SilenceController", "Successfully extracted internal SilenceSkippingAudioProcessor from sink directly: ${field.name}")
                        return
                    }
                }
            }
            
            // 2. 如果第一层未寻得，遍历类中持有的非基础类型复杂对象（如 DefaultAudioProcessorChain 等）
            for (field in fields) {
                if (field.type.isPrimitive || field.type.name.startsWith("java.") || field.type.name.startsWith("android.")) {
                    continue
                }
                field.isAccessible = true
                val obj = field.get(sink) ?: continue
                
                // 若该字段为处理器数组，遍历查找
                if (obj is Array<*>) {
                    for (element in obj) {
                        if (element is SilenceSkippingAudioProcessor) {
                            customSilenceProcessor = element
                            Log.d("SilenceController", "Successfully extracted internal SilenceSkippingAudioProcessor from array field: ${field.name}")
                            return
                        }
                    }
                }
                
                // 深度遍历子对象中的所有声明字段
                val subFields = obj.javaClass.declaredFields
                for (subField in subFields) {
                    if (subField.type == SilenceSkippingAudioProcessor::class.java) {
                        subField.isAccessible = true
                        val processor = subField.get(obj) as? SilenceSkippingAudioProcessor
                        if (processor != null) {
                            customSilenceProcessor = processor
                            Log.d("SilenceController", "Successfully extracted internal SilenceSkippingAudioProcessor from sub-object ${field.name} -> ${subField.name}")
                            return
                        }
                    }
                }
            }
            Log.w("SilenceController", "No internal SilenceSkippingAudioProcessor found in AudioSink via reflection scan.")
        } catch (e: Exception) {
            Log.e("SilenceController", "Failed to extract internal SilenceSkippingAudioProcessor: ${e.message}", e)
        }
    }

    /**
     * 详尽的中文注释：
     * 利用高级反射技术，安全热更新底层静音跳过处理器的工作时长和判定帧参数，实现拖动 Slider 无感热应用。
     *
     * @param newDurationSeconds 判定的静音起步时长（秒）
     */
    fun updateSilenceProcessorDurationHot(newDurationSeconds: Float) {
        val processor = customSilenceProcessor ?: return
        try {
            val newDurationUs = (newDurationSeconds * 1_000_000L).toLong()
            
            // 1. 反射修改 minimumSilenceDurationUs 属性（核心最小时长基础字段，附带独立的 try-catch 保护）
            try {
                val durationField = processor.javaClass.getDeclaredField("minimumSilenceDurationUs")
                durationField.isAccessible = true
                durationField.setLong(processor, newDurationUs)
                Log.d("SilenceController", "Successfully hot-updated minimumSilenceDurationUs to $newDurationUs Us")
            } catch (_: NoSuchFieldException) {
                Log.w("SilenceController", "Field minimumSilenceDurationUs not found in SilenceSkippingAudioProcessor")
            } catch (e: Exception) {
                Log.e("SilenceController", "Failed to write minimumSilenceDurationUs: ${e.message}")
            }
            
            // 2. 反射读取 BaseAudioProcessor（父类）中的 inputAudioFormat，以便获取当前真实工作采样率
            val superclass = processor.javaClass.superclass
            var audioFormat: Any? = null
            if (superclass != null) {
                try {
                    val formatField = superclass.getDeclaredField("inputAudioFormat")
                    formatField.isAccessible = true
                    audioFormat = formatField.get(processor)
                } catch (_: NoSuchFieldException) {
                    Log.w("SilenceController", "Field inputAudioFormat not found in BaseAudioProcessor")
                } catch (e: Exception) {
                    Log.e("SilenceController", "Failed to read inputAudioFormat: ${e.message}")
                }
            }
            
            if (audioFormat != null) {
                // 3. 读取当前工作音频格式的采样率（sampleRate）与单帧字节数（bytesPerFrame）
                var sampleRate = 0
                var bytesPerFrame = 4 // 默认为双声道 16-bit PCM 字节大小 (2声道 * 2字节)
                
                try {
                    val sampleRateField = audioFormat.javaClass.getDeclaredField("sampleRate")
                    sampleRateField.isAccessible = true
                    sampleRate = sampleRateField.getInt(audioFormat)
                } catch (_: NoSuchFieldException) {
                    Log.w("SilenceController", "Field sampleRate not found in AudioFormat")
                } catch (e: Exception) {
                    Log.e("SilenceController", "Failed to read sampleRate: ${e.message}")
                }
                
                try {
                    val bytesPerFrameField = audioFormat.javaClass.getDeclaredField("bytesPerFrame")
                    bytesPerFrameField.isAccessible = true
                    bytesPerFrame = bytesPerFrameField.getInt(audioFormat)
                } catch (_: Exception) {
                    // 字段不存在时采用默认值 4，不抛出异常
                }
                
                if (sampleRate > 0) {
                    // 4. 计算判定时长对应的新判定帧数
                    val newSilenceFrames = ((sampleRate.toLong() * newDurationUs) / 1_000_000L).toInt()
                    
                    // 5. 尝试写入 minimumSilenceFrames（若在此 Media3 版本中存在）
                    try {
                        val framesField = processor.javaClass.getDeclaredField("minimumSilenceFrames")
                        framesField.isAccessible = true
                        framesField.setInt(processor, newSilenceFrames)
                        Log.d("SilenceController", "Successfully hot-updated minimumSilenceFrames to $newSilenceFrames")
                    } catch (_: NoSuchFieldException) {
                        // 静默忽略：当前 Media3 版本不包含 minimumSilenceFrames 字段
                    } catch (e: Exception) {
                        Log.e("SilenceController", "Failed to write minimumSilenceFrames: ${e.message}")
                    }
                    
                    // 6. 尝试重新计算并写入 maybeSilenceBufferSize 字段值（当前 Media3 版本内存判定容量的核心）
                    if (bytesPerFrame > 0) {
                        val newBufferSize = newSilenceFrames * bytesPerFrame
                        try {
                            val bufferSizeField = processor.javaClass.getDeclaredField("maybeSilenceBufferSize")
                            bufferSizeField.isAccessible = true
                            bufferSizeField.setInt(processor, newBufferSize)
                            Log.d("SilenceController", "Successfully hot-updated maybeSilenceBufferSize to $newBufferSize")
                        } catch (_: NoSuchFieldException) {
                            // 静默忽略：当前 Media3 版本不包含 maybeSilenceBufferSize 字段
                        } catch (e: Exception) {
                            Log.e("SilenceController", "Failed to write maybeSilenceBufferSize: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 用最外层 try-catch 兜底强力保护，确保即便反射发生灾难性未知异常，也绝不会让 App 崩溃，确保了极致的系统稳定性
            Log.e("SilenceController", "Failed to hot-update silence processor duration: ${e.message}", e)
        }
    }
}
