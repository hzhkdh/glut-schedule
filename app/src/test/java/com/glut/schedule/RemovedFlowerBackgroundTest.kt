package com.glut.schedule

import java.io.File
import com.glut.schedule.ui.components.BuiltInScheduleBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedFlowerBackgroundTest {
    @Test
    fun freshInstallResetAndLegacySettingsResolveToMonet() {
        // 默认值与历史标识统一映射，不修改用户已经保存的外部图片 URI。
        val monet = BuiltInScheduleBackground.WOMAN_WITH_A_PARASOL
        assertEquals("莫奈《撑阳伞的女人》", monet.displayName)
        assertEquals("builtin://monet-woman-with-a-parasol", monet.storageValue)
        for (value in listOf("", "   ", "builtin://flower", monet.storageValue)) {
            assertEquals(monet, BuiltInScheduleBackground.fromStorageValue(value))
        }
        assertEquals(null, BuiltInScheduleBackground.fromStorageValue("content://gallery/personal"))
        assertEquals(null, BuiltInScheduleBackground.fromStorageValue("file:///downloaded-artwork.jpg"))
    }

    @Test
    fun defaultArtworkHasCorrectAssetNames() {
        // 不再用“花”的名称打包莫奈作品，避免后续维护时误删实际默认图。
        for (path in listOf(
            "src/main/res/drawable-nodpi/builtin_flower_background.jpg",
            "src/main/res/drawable-nodpi/builtin_flower_background_thumb.jpg"
        )) {
            assertFalse("应移除旧内置背景：$path", File(path).exists())
        }
        assertTrue(File("src/main/res/drawable-nodpi/builtin_monet_woman_with_a_parasol.jpg").isFile)
        assertTrue(File("src/main/res/drawable-nodpi/builtin_monet_woman_with_a_parasol_thumb.jpg").isFile)
    }
}
