package com.glut.schedule.ui.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class FaqItem(
    val question: String,
    val answer: String
)

private val faqItems = listOf(
    FaqItem(
        "为什么课表没有数据显示？",
        "可能的原因：1) 尚未导入课表，请先到「导入课表」登录教务系统；2) 登录会话已过期（Cookie 失效），需重新登录；3) 当前周次暂无课程安排。"
    ),
    FaqItem(
        "如何切换桂林 / 南宁校区？",
        "在「导入课表」页面顶部可以切换校区。不同校区的课时安排略有不同，系统会根据所选校区自动适配。"
    ),
    FaqItem(
        "可以多台设备同步数据吗？",
        "目前不支持。所有课表、成绩等数据仅存储在手机本地，不会上传到云端。"
    ),
    FaqItem(
        "我的学号和密码安全吗？应用会收集个人信息吗？",
        "学号和密码经过加密存储在手机本地，仅用于登录教务系统获取数据。本应用不会收集、上传任何个人信息到第三方服务器，我们无法访问你的数据。"
    ),
    FaqItem(
        "应用收费吗？",
        "完全免费，无广告。本应用仅为桂工同学提供便利，不会以任何形式收费。"
    ),
    FaqItem(
        "应用需要联网吗？离线能用吗？",
        "已导入的课表、成绩等数据支持离线查看。但刷新数据、导入课表、登录教务系统等操作需要连接网络。"
    ),
    FaqItem(
        "教学计划怎么看？上面的数据是什么意思？",
        "教学计划展示的是你的专业培养方案中各课组的学分完成情况。每行包含课组名称、属性（必修/限选/任选）、学分要求、已获学分、以及是否合格。它可以帮助你了解还有哪些学分未修够。"
    ),
    FaqItem(
        "「等级考级」和「考试成绩」有什么区别？",
        "「考试成绩」是校内课程期末考试的分数和绩点；「等级考级」是国家等级考试（如大学英语四六级、普通话水平测试、计算机等级考试等）的成绩。两者来源不同，需分别刷新获取。"
    ),
    FaqItem(
        "参考了什么项目？",
        "参考了三个项目：\nhttps://github.com/nano71/GlutAssistantN\nhttps://github.com/flylai/GlutAssistant\nhttps://github.com/Jacknic/glut\n\n感谢前辈大佬，他们的时代可没有 AI ！"
    ),
    FaqItem(
        "这个app是自己手搓的吗？",
        "并非，本人无任何代码能力、开发经验以及工程能力，全部的分析、构建、编译、推送等均由 ClaudeCode 完成。利用 Subagents 能力并行分析三个项目的获取教务逻辑，取长补短融合进该项目。"
    ),
    FaqItem(
        "为什么想到开发这个项目？",
        "1. 受够了易班经常崩溃、操作路径长的缺点（打开易班app → 找到 in桂工 → 登入验证 → 终于看到了课表！）\n2. 商店的课表app是商业软件，有开屏广告\n3. 最近 vibe coding 大火，鄙人也当一下弄潮儿！体验由 idea 到产出落地的感觉\n4. 融合更多的教务接口，例如考试安排、成绩查看、简易教学计划等集于一体，给桂工学子带来一丝丝的便利"
    ),
    FaqItem(
        "节假日数据是从哪里来的？",
        "调用提莫（timor.tech）免费节假日 API 获取全年法定节假日数据。为了减少不必要的网络请求，数据会缓存到手机本地，同一年内不再重复获取。"
    ),
    FaqItem(
        "调课一览的数据是怎么来的？",
        "在导入课表时，系统会从课表页面底部解析调课/补课信息（原时间、原教室 → 补课时间、补课教室），并保存到本地。之后在「学期概览」中按补课周次分组展示。"
    ),
    FaqItem(
        "学期起止日期是怎么确定的？",
        "每次登录教务系统时，系统自动从教务获取本学期的开学日期和结束日期，无需手动设置。"
    ),
    FaqItem(
        "学期余额的百分比是怎么计算的？",
        "按天数精确计算：已过百分比 =（今天 − 学期开始日）÷（学期结束日 − 学期开始日）× 100%。比按周计算更精确，能体现本周已经过了几天的粒度。"
    ),
    FaqItem(
        "为什么有些节假日没有显示？",
        "只显示本学期范围内的节假日。如果节假日在学期开始之前或学期结束之后，则不会出现在列表中。"
    ),
    FaqItem(
        "考试成绩里的 GPA 是怎么计算的？",
        "采用桂林理工大学官方的学分加权公式：GPA = Σ(必修课学分 × 绩点) ÷ Σ(必修课学分)。仅统计选课属性为「必修」的课程，限选和任选课不参与计算。点击底部 GPA 卡片旁边的 ℹ️ 图标可查看详细说明。"
    ),
    FaqItem(
        "课程名后面的蓝色/橙色/绿色小标签是什么？",
        "是课程的选课属性徽章：蓝色 = 必修、橙色 = 限选、绿色 = 任选。数据来自教务系统的成绩表，南宁分校则由个人教学计划精确匹配。GPA 只统计标注为蓝色的必修课。"
    ),
    FaqItem(
        "为什么成绩按学年分组显示，而不是按学期？",
        "一个完整学年 = 秋季学期 + 次年春季学期。按学年分组可以让大一、大二等学年的成绩一目了然，避免同一年级的两个学期被拆散。卡片顶部有学年 chip 可快速切换。"
    ),
    FaqItem(
        "南宁分校和桂林本部的功能有差异吗？",
        "核心功能（课表导入、成绩查询、考试安排、学期概览等）两个校区完全一致。差异在于：南宁成绩表结构不同（13 列 vs 22 列），选课属性从个人教学计划精确推断，含「慕课」的课程自动归为任选。"
    ),
    FaqItem(
        "刷新成绩/教学计划等失败怎么办？",
        "最常见的原因是教务系统会话已过期（Cookie 失效）。请到「导入课表」重新登录教务系统，完成后再试。如果仍失败，可能是网络异常或教务服务器繁忙，可稍后重试。\n\n终极方案：清除应用数据（系统设置 → 应用 → 桂工课表 → 清除数据），重新导入并登录教务。"
    )
)

@Composable
fun FaqScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F4EF))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            itemsIndexed(faqItems) { index, item ->
                FaqCard(item = item)
                if (index < faqItems.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FaqCard(item: FaqItem) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFFFFEFB),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    item.question,
                    color = Color(0xFF141821),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }

            if (expanded) {
                HorizontalDivider(color = Color(0xFFEDE8DE))
                Text(
                    item.answer,
                    color = Color(0xFF667085),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}
