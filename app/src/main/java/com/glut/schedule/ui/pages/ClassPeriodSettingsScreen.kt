package com.glut.schedule.ui.pages

import android.app.TimePickerDialog
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import java.time.LocalTime

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
    periods: List<ClassPeriod>,
    onSetPeriods: (List<ClassPeriod>) -> Unit,
    onResetPeriods: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember(campusType, periods) {
        mutableStateOf(
            periods.takeIf { validateClassPeriods(campusType, it) }
                ?: defaultClassPeriods(campusType)
        )
    }
    var validationMessage by remember(campusType, periods) { mutableStateOf("") }
    val labels = classPeriodSettingsLabels(campusType)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = if (campusType == CampusType.GUILIN) {
                    "桂林校区 · 14节"
                } else {
                    "南宁分校 · 11节"
                },
                color = Color(0xFF667085),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
            )
        }
        itemsIndexed(draft, key = { _, period -> period.section }) { index, period ->
            ClassPeriodRow(
                label = labels[index],
                period = period,
                onStartSelected = { selected ->
                    draft = draft.replaceAt(index, period.copy(startsAt = selected))
                    validationMessage = ""
                },
                onEndSelected = { selected ->
                    draft = draft.replaceAt(index, period.copy(endsAt = selected))
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
                        if (!validateClassPeriods(campusType, draft)) {
                            validationMessage = "请检查时间格式、先后顺序和相邻课时是否重叠"
                            return@Button
                        }
                        if (draft == defaultClassPeriods(campusType)) {
                            onResetPeriods()
                        } else {
                            onSetPeriods(draft)
                        }
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F7DF6))
                ) {
                    Text("保存")
                }
                TextButton(
                    onClick = {
                        draft = defaultClassPeriods(campusType)
                        validationMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("恢复默认时间", color = Color(0xFF667085))
                }
            }
        }
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
