package com.glut.schedule.ui.pages

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_DEFAULT
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_PINGFENG
import com.glut.schedule.data.settings.classPeriodProfile
import java.time.LocalTime

internal data class ClassPeriodEditorState(
    val profile: ClassPeriodProfile,
    val overrides: Map<ClassPeriodProfile, List<ClassPeriod>>,
    val draft: List<ClassPeriod>,
    val saved: List<ClassPeriod>
) {
    val defaults: List<ClassPeriod>
        get() = defaultClassPeriods(profile)
    val isDirty: Boolean
        get() = draft != saved
    val modifiedSections: Set<Int>
        get() = draft.zip(defaults).mapNotNull { (period, default) ->
            period.section.takeIf {
                period.startsAt != default.startsAt || period.endsAt != default.endsAt
            }
        }.toSet()

    fun switchTo(target: ClassPeriodProfile): ClassPeriodEditorState =
        create(target, overrides)

    fun replaceDraft(periods: List<ClassPeriod>): ClassPeriodEditorState =
        copy(draft = periods)

    fun markSaved(periods: List<ClassPeriod>?): ClassPeriodEditorState {
        val updatedOverrides = if (periods == null) overrides - profile else overrides + (profile to periods)
        val effective = periods ?: defaultClassPeriods(profile)
        return copy(overrides = updatedOverrides, draft = effective, saved = effective)
    }

    companion object {
        fun create(
            profile: ClassPeriodProfile,
            overrides: Map<ClassPeriodProfile, List<ClassPeriod>>
        ): ClassPeriodEditorState {
            val effective = overrides[profile] ?: defaultClassPeriods(profile)
            return ClassPeriodEditorState(profile, overrides, effective, effective)
        }
    }
}

internal fun classPeriodSettingsLabels(campusType: CampusType): List<String> = when (campusType) {
    CampusType.GUILIN -> buildList {
        addAll((1..4).map { "第${it}节" })
        add("午1")
        add("午2")
        addAll((5..12).map { "第${it}节" })
    }
    CampusType.NANNING -> (1..11).map { "第${it}节" }
}

@Composable
fun ClassPeriodSettingsScreen(
    campusType: CampusType,
    guilinSubCampus: String,
    overrides: Map<ClassPeriodProfile, List<ClassPeriod>>,
    onSetPeriods: (ClassPeriodProfile, List<ClassPeriod>) -> Unit,
    onResetPeriods: (ClassPeriodProfile) -> Unit,
    onSetGuilinSubCampus: (String) -> Unit
) {
    val context = LocalContext.current
    val isGuilin = campusType == CampusType.GUILIN
    val selectedProfile = classPeriodProfile(campusType, guilinSubCampus)
    var editor by remember(campusType) {
        mutableStateOf(ClassPeriodEditorState.create(selectedProfile, overrides))
    }
    var validationMessage by remember(campusType) { mutableStateOf("") }
    var showSwitchConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<ClassPeriodProfile?>(null) }
    val labels = classPeriodSettingsLabels(campusType)

    // DataStore 回写期间保持当前编辑档案稳定，避免校区、默认值和星号跨帧错配。
    LaunchedEffect(selectedProfile, overrides) {
        if (!editor.isDirty && editor.profile == selectedProfile) {
            editor = ClassPeriodEditorState.create(selectedProfile, overrides)
        } else {
            editor = editor.copy(overrides = overrides)
        }
    }

    fun switchProfile(target: ClassPeriodProfile) {
        if (target == editor.profile) return
        if (editor.isDirty) {
            pendingProfile = target
            showSwitchConfirm = true
        } else {
            editor = editor.switchTo(target)
            onSetGuilinSubCampus(target.guilinSubCampusValue())
            validationMessage = ""
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = if (isGuilin) "桂林校区 · 14节" else "南宁分校 · 11节",
                color = Color(0xFF667085),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
            )
        }

        // 桂林校区显示雁山/屏风切换 pill 按钮
        if (isGuilin) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SubCampusPill(
                        label = "雁山校区",
                        selected = editor.profile == ClassPeriodProfile.GUILIN_YANSHAN,
                        onClick = { switchProfile(ClassPeriodProfile.GUILIN_YANSHAN) },
                        modifier = Modifier.weight(1f)
                    )
                    SubCampusPill(
                        label = "屏风校区",
                        selected = editor.profile == ClassPeriodProfile.GUILIN_PINGFENG,
                        onClick = { switchProfile(ClassPeriodProfile.GUILIN_PINGFENG) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        itemsIndexed(editor.draft, key = { _, period -> period.section }) { index, period ->
            val label = if (period.section in editor.modifiedSections) "${labels[index]}*" else labels[index]
            ClassPeriodRow(
                label = label,
                period = period,
                onStartSelected = { selected ->
                    editor = editor.replaceDraft(
                        editor.draft.replaceAt(index, period.copy(startsAt = selected))
                    )
                    validationMessage = ""
                },
                onEndSelected = { selected ->
                    editor = editor.replaceDraft(
                        editor.draft.replaceAt(index, period.copy(endsAt = selected))
                    )
                    validationMessage = ""
                },
                showPicker = { initial, onSelected ->
                    val time = runCatching { LocalTime.parse(initial) }.getOrDefault(LocalTime.NOON)
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onSelected("%02d:%02d".format(hour, minute)) },
                        time.hour,
                        time.minute,
                        true
                    ).show()
                }
            )
        }
        if (validationMessage.isNotBlank()) {
            item {
                Text(
                    text = validationMessage,
                    color = Color(0xFFDC2626),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        item {
            Column(
                modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!validateClassPeriods(editor.profile, editor.draft)) {
                            validationMessage = "请检查时间格式、先后顺序和相邻课时是否重叠"
                            return@Button
                        }
                        if (editor.draft == editor.defaults) {
                            onResetPeriods(editor.profile)
                            editor = editor.markSaved(null)
                        } else {
                            onSetPeriods(editor.profile, editor.draft)
                            editor = editor.markSaved(editor.draft)
                        }
                        Toast.makeText(context, "上课时间已保存", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F7DF6))
                ) {
                    Text("保存")
                }
                TextButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isGuilin) {
                            when (editor.profile) {
                                ClassPeriodProfile.GUILIN_PINGFENG -> "恢复屏风校区默认时间"
                                else -> "恢复雁山校区默认时间"
                            }
                        } else "恢复默认时间",
                        color = Color(0xFF667085)
                    )
                }
            }
        }
    }

    // 切换校区确认弹窗
    if (showSwitchConfirm && pendingProfile != null) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            title = { Text("切换校区") },
            text = {
                Text(
                    if (pendingProfile == ClassPeriodProfile.GUILIN_PINGFENG)
                        "切换到屏风校区将丢弃当前未保存的编辑，确定继续？"
                    else "切换到雁山校区将丢弃当前未保存的编辑，确定继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSwitchConfirm = false
                    pendingProfile?.let { target ->
                        editor = editor.switchTo(target)
                        onSetGuilinSubCampus(target.guilinSubCampusValue())
                        validationMessage = ""
                    }
                    pendingProfile = null
                }) { Text("确定", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSwitchConfirm = false
                    pendingProfile = null
                }) { Text("取消") }
            }
        )
    }

    // 恢复默认确认弹窗
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认时间") },
            text = {
                Text(
                    if (isGuilin) {
                        when (editor.profile) {
                            ClassPeriodProfile.GUILIN_PINGFENG -> "将替换为屏风校区默认上课时间，确定继续？"
                            else -> "将替换为雁山校区默认上课时间，确定继续？"
                        }
                    } else "将替换为南宁分校默认上课时间，确定继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    editor = editor.markSaved(null)
                    onResetPeriods(editor.profile)
                    validationMessage = ""
                    Toast.makeText(context, "已恢复默认时间", Toast.LENGTH_SHORT).show()
                }) { Text("确定", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 雁山/屏风校区切换 Pill 按钮 */
@Composable
private fun SubCampusPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Color(0xFF3F7DF6) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) null else BorderStroke(1.dp, Color(0xFFDDE2EA))
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF667085),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ClassPeriodRow(
    label: String,
    period: ClassPeriod,
    onStartSelected: (String) -> Unit,
    onEndSelected: (String) -> Unit,
    showPicker: (String, (String) -> Unit) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFFFFEFB),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF141821),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TimeValue(
                value = period.startsAt,
                onClick = { showPicker(period.startsAt, onStartSelected) }
            )
            Text("—", color = Color(0xFF98A2B3), modifier = Modifier.padding(horizontal = 8.dp))
            TimeValue(
                value = period.endsAt,
                onClick = { showPicker(period.endsAt, onEndSelected) }
            )
        }
    }
}

@Composable
private fun TimeValue(value: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xFFF0F4FC),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = value,
            color = Color(0xFF3F7DF6),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

private fun List<ClassPeriod>.replaceAt(index: Int, value: ClassPeriod): List<ClassPeriod> =
    toMutableList().also { it[index] = value }

private fun ClassPeriodProfile.guilinSubCampusValue(): String = when (this) {
    ClassPeriodProfile.GUILIN_PINGFENG -> GUILIN_SUB_CAMPUS_PINGFENG
    ClassPeriodProfile.GUILIN_YANSHAN -> GUILIN_SUB_CAMPUS_DEFAULT
    ClassPeriodProfile.NANNING -> error("南宁课时档案不支持桂林子校区切换")
}
