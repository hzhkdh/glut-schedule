package com.glut.schedule.service.export

import android.graphics.Bitmap

/**
 * 绘制/截图结果在部分设备上可能是硬件位图；PNG 编码与 PDF 画布只接受软件位图。
 */
internal fun Bitmap.requireSoftwareBitmap(): Bitmap {
    if (config != Bitmap.Config.HARDWARE) return this
    return copy(Bitmap.Config.ARGB_8888, false)
        ?: throw IllegalStateException("无法将硬件位图转换为软件位图")
}
