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
import androidx.compose.foundation.lazy.items
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

private sealed class FaqListItem {
    data class Header(val title: String) : FaqListItem()
    data class Entry(val item: FaqItem) : FaqListItem()
}

private val faqList = listOf(
    // ── 常见问题 ──
    FaqListItem.Header("常见问题"),
    FaqListItem.Entry(FaqItem(
        "应用需要联网吗？离线能用吗？",
        "已导入的课表、成绩等数据支持离线查看。但刷新数据、导入课表、登录教务系统等操作需要连接网络。"
    )),
    FaqListItem.Entry(FaqItem(
        "南宁分校和桂林本部的功能有差异吗？",
        "课表导入、成绩查询、考试安排、学期概览等核心功能在两个校区基本一致。但部分功能目前南宁未实现（体测成绩、财务）"
    )),
    FaqListItem.Entry(FaqItem(
        "为什么有些节假日没有显示？",
        "只显示本学期范围内的节假日。如果节假日在学期开始之前或学期结束之后，则不会出现在列表中。"
    )),
    FaqListItem.Entry(FaqItem(
        "刷新成绩/教学计划等失败怎么办？",
        "一般来说 cookie 过期会走静默登入（南宁无法静默）最可能的原因是教务登录密码发生了更改。请到「导入课表」重新登录教务系统即可。如果仍失败，可能是网络异常或学校系统繁忙、异常，可稍后重试。\n\n另外，“成绩” 的刷新会受到你本人是否完成评教与否来 阻止或放行 返回数据，这是教务的行为，不是 app 的行为。"
    )),
    FaqListItem.Entry(FaqItem(
        "更新逻辑",
        "应用会从项目更新源查询最新版本号、更新说明和安装包地址；主更新源暂时不可用时，会尝试备用通道。只有远端版本高于当前版本，才会在「关于」页显示红点并弹出更新提示。\n\n如果检查或下载失败，通常是网络波动或更新源暂时不可访问。稍后重新进入「关于」页，点击当前版本即可再次检查。"
    )),
    FaqListItem.Entry(FaqItem(
        "“财务”菜单能做什么？可以直接缴费吗？",
        "财务菜单用于查询学校财务系统中的待缴项目、收费项目、交易记录、缴费明细、电子票据和学分结算等信息，目前仅支持桂林本部。查询结果会缓存在本机，金额默认隐藏，可点击顶部眼睛图标临时显示。\n\n应用本身不执行支付。需要缴费时会引导你打开学校财务官网，请核对网址和金额后再操作。财务账号、密码和登录状态与教务、体测系统相互独立，并通过 HTTPS 直接提交给学校财务系统。忘记密码时可从登录页直接打开学校的重置页面。"
    )),
    FaqListItem.Entry(FaqItem(
        "“体测成绩”菜单包含哪些功能？",
        "体测菜单可以查询最新体测结果、历年成绩和各项目评分标准。体测平台使用独立账号，即使已经登录教务系统，也仍需输入体测平台密码和验证码。\n\n应用通过 HTTPS 直接连接学校体测系统，账号、密码、验证码、登录状态和成绩不会经过项目维护者的服务器。"
    )),

    // ── 数据解读 ──
    FaqListItem.Header("数据解读"),
    FaqListItem.Entry(FaqItem(
        "教学计划怎么看？上面的数据是什么意思？",
        "教学计划展示的是你的专业培养方案中各课组的学分完成情况。每行包含课组名称、属性（必修/限选/任选）、学分要求、已获学分、以及是否合格。它可以帮助你了解还有哪些学分未修够。"
    )),
    FaqListItem.Entry(FaqItem(
        "考试成绩里的 GPA 是怎么计算的？",
        "采用桂林理工大学官方的学分加权公式：GPA = Σ(必修课学分 × 绩点) ÷ Σ(必修课学分)。仅统计选课属性为「必修」的课程，限选和任选课不参与计算。点击底部 GPA 卡片旁边的 ℹ️ 图标可查看详细说明。"
    )),
    FaqListItem.Entry(FaqItem(
        "课程名后面的蓝色/橙色/绿色小标签是什么？",
        "是课程的选课属性徽章：蓝色 = 必修、橙色 = 限选、绿色 = 任选。数据来自教务系统的成绩表，南宁分校则由个人教学计划精确匹配。GPA 只统计标注为蓝色的必修课。"
    )),
    FaqListItem.Entry(FaqItem(
        "学期起止日期是怎么确定的？",
        "每次登录教务系统时，系统自动从教务获取本学期的开学日期和结束日期，无需手动设置。"
    )),
    FaqListItem.Entry(FaqItem(
        "学期余额的百分比是怎么计算的？",
        "按天数精确计算：已过百分比 =（今天 − 学期开始日）÷（学期结束日 − 学期开始日）× 100%。比按周计算更精确，能体现本周已经过了几天的粒度。"
    )),
    FaqListItem.Entry(FaqItem(
        "节假日数据是从哪里来的？",
        "应用从公开的节假日服务获取全年法定节假日数据。为了减少不必要的联网，数据会缓存在手机本地，同一年内不再重复获取。"
    )),
    FaqListItem.Entry(FaqItem(
        "调课一览的数据是怎么来的？",
        "在导入课表时，系统会从课表页面底部解析调课/补课信息（原时间、原教室 → 补课时间、补课教室），并保存到本地。之后在「学期概览」中按补课周次分组展示。"
    )),

    FaqListItem.Entry(FaqItem(
        "“专业成绩”是怎么计算的？",
        "专业成绩由应用在本机根据教学计划和成绩单计算，并不是学校直接返回的最终测评结果。应用按一个学年统计，即当年秋季和次年春季，使用“课程百分制成绩 × 教学计划学分”的加权平均值。\n\n默认统计培养计划中的必修和限选课程，排除体育、公共选修/任选、补修、重修/重学、辅修和双学位等课程。等级制成绩会按规则折算，补考、缓考、作弊或缺考按页面说明处理。显示“暂未计入”通常表示教学计划中存在该课程，但成绩单里尚未找到可用于计算的成绩。\n\n该结果仅供个人参考。奖学金、推免、综测等正式认定应以学院和学校公布的规则及结果为准。\n\n各学院的政策文件可能都不一样，目前只引用了计算机科学与工程学院的政策文件，其他学院不一定适用！"
    )),

    // ── 隐私安全 ──
    FaqListItem.Header("隐私安全"),
    FaqListItem.Entry(FaqItem(
        "我的数据保存在哪里？维护者能看到吗？",
        "课表、考试、成绩、教学计划、财务查询结果和体测缓存主要保存在手机的应用私有空间，不会自动同步到项目维护者的账号或服务器。应用没有接入广告、使用情况统计或自动上传错误信息的服务，因此维护者没有后台入口查看你手机里的课表、成绩、账单或体测数据。\n\n登录或刷新时，应用必须把必要信息发送给对应的学校系统。财务和体测都由手机直接连接学校系统，不会经过项目维护者的服务器；学校系统和网络服务仍可能按自身规则记录访问地址、请求时间等基本信息。版本检查、通知和节假日查询会访问各自的数据来源，但不会附带你的学校账号、密码或成绩。"
    )),
    FaqListItem.Entry(FaqItem(
        "学号、密码和成绩是否加密？",
        "教务、体测和财务账号密码使用手机系统提供的加密能力保存在本机；体测和财务的登录状态也会加密保存。教务登录状态以及课表、成绩、教学计划、体测结果、财务结果等缓存位于应用私有空间，但并非每一项缓存都单独加密。\n\n正常情况下，其他普通应用不能直接读取这些文件，项目也关闭了系统云备份，并排除了数据库和配置文件。但如果手机被破解或取得最高权限、被恶意软件控制、调试权限被滥用，或者手机失窃后被解锁，本地数据仍可能被读取。请设置可靠的锁屏密码，不要在不可信设备上保存账号。"
    )),
    FaqListItem.Entry(FaqItem(
        "登录和查询过程中会不会泄露？",
        "财务和体测功能都通过 HTTPS 直接连接学校系统。部分旧版教务系统页面仍只提供 HTTP 连接，无法获得与 HTTPS 相同的传输保护，因此不能保证所有教务请求在传输途中都不被截获或篡改。\n\n建议使用可信网络，避免在陌生公共无线网络下登录，也不要与他人分享验证码、登录状态、带有学号的截图。学校系统、网络运营商和手机系统自身的安全问题不完全由本应用控制，所以任何应用都无法承诺数据绝对不会泄露。"
    )),
    FaqListItem.Entry(FaqItem(
        "怎样删除保存的数据？",
        "可在应用「设置」中使用「重置全部数据」，清除教务、体测和财务账号、登录状态以及本地课表、成绩和缓存；也可以在手机系统设置中进入「应用 → 桂系一站式 → 存储」，清除应用数据。卸载应用通常也会删除其私有数据。\n\n通过公开的问题反馈页面、邮件或聊天反馈时，请先遮挡学号、姓名、成绩、和账单信息。公开的问题反馈页面任何人都可能看到，不适合提交包含个人信息的截图或日志。"
    )),
    // ── 关于项目 ──
    FaqListItem.Header("关于项目"),
    FaqListItem.Entry(FaqItem(
        "应用收费吗？",
        "完全免费，无广告。本应用旨在为用户提供便利，不会以任何形式收费。"
    )),
    FaqListItem.Entry(FaqItem(
        "参考了什么项目？",
        "参考了三个项目：\nhttps://github.com/nano71/GlutAssistantN\nhttps://github.com/flylai/GlutAssistant\nhttps://github.com/Jacknic/glut\n\n感谢前辈大佬，致敬开源！"
    ))
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
            items(faqList) { listItem ->
                when (listItem) {
                    is FaqListItem.Header -> {
                        Text(
                            listItem.title,
                            color = Color(0xFF3F7DF6),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
                        )
                    }
                    is FaqListItem.Entry -> {
                        FaqCard(item = listItem.item)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
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
