package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.ui.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** 展示缓存身份前必须核对来源，不能把其他来源姓名带入底栏。 */
class DrawerIdentityTest {
    @Test fun sourceMismatchHidesCachedName() {
        assertEquals("暂无身份信息", drawerIdentity(CampusType.GRADUATE, "https://jw.glut.edu.cn/academic/", "匿名").name)
        assertEquals("匿名", drawerIdentity(CampusType.NANNING, "http://jw.glutnn.cn/academic/", "匿名").name)
        assertEquals("暂无身份信息", drawerIdentity(CampusType.GUILIN, "", "匿名").name)
    }
    @Test fun footerActionsDoNotRepeatInMenu() {
        assertFalse(DrawerItem.Settings in otherDrawerItems)
        assertFalse(DrawerItem.FAQ in otherDrawerItems)
        assertTrue(DrawerItem.Notice in otherDrawerItems)
        assertTrue(DrawerItem.About in otherDrawerItems)
    }
}
