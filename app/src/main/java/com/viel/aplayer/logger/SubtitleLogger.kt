package com.viel.aplayer.logger

import android.util.Log

/**
 * 字幕解析日志 Logger。
 * 统一收纳 SubtitleParser 中与字幕文件解析入口和解析结果相关的日志输出。
 * 使用统一 TAG "Subtitle"，方便在 Logcat 中过滤字幕解析相关的诊断信息。
 */
internal object SubtitleLogger {

    private const val TAG = "Subtitle"

    /**
     * 记录开始解析字幕文件的事件。
     *
     * @param extension 字幕文件扩展名 (如 srt, ass, lrc 等)
     */
    fun logParseStart(extension: String) {
        Log.d(TAG, "Parsing $extension")
    }

    /**
     * 记录字幕解析完成的结果。
     *
     * @param lineCount 解析出的字幕行数
     */
    fun logParseResult(lineCount: Int) {
        Log.d(TAG, "Parsed $lineCount lines")
    }
}
