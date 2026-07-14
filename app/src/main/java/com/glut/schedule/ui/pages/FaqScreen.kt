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
        "为什么课表没有数据显示？",
        "可能的原因：1) 尚未导入课表，请先到「导入课表」登录教务系统；2) 登录会话已过期（Cookie 失效），需重新登录；3) 当前周次暂无课程安排。"
    )),
    FaqListItem.Entry(FaqItem(
        "应用需要联网吗？离线能用吗？",
        "已导入的课表、成绩等数据支持离线查看。但刷新数据、导入课表、登录教务系统等操作需要连接网络。"
    )),
    FaqListItem.Entry(FaqItem(
        "南宁分校和桂林本部的功能有差异吗？",
        "核心功能（课表导入、成绩查询、考试安排、学期概览等）两个校区完全一致。差异在于：南宁成绩表结构不同（13 列 vs 22 列），选课属性从个人教学计划精确推断，含「慕课」的课程自动归为任选。"
    )),
    FaqListItem.Entry(FaqItem(
        "为什么有些节假日没有显示？",
        "只显示本学期范围内的节假日。如果节假日在学期开始之前或学期结束之后，则不会出现在列表中。"
    )),
    FaqListItem.Entry(FaqItem(
        "刷新成绩/教学计划等失败怎么办？",
        "最常见的原因是教务系统会话已过期（Cookie 失效）。请到「导入课表」重新登录教务系统，完成后再试。如果仍失败，可能是网络异常或教务服务器繁忙，可稍后重试。\n\n终极方案：清除应用数据（系统设置 → 应用 → 桂系一站式 → 清除数据），重新导入并登录教务。"
    )),
    FaqListItem.Entry(FaqItem(
        "更新逻辑",
        "App 会通过更新源检查是否有新版本：优先读取 Cloudflare Pages 上的 update.json，获取最新版本号、更新说明和 APK 下载地址；如果主更新源暂时不可用，会尝试备用检查通道。只有检测到远端版本高于当前版本时，才会在「关于」页显示红点并弹出更新提示。\n\n安装包下载地址由 update.json 中的 downloadUrl 决定，目前托管在 Cloudflare Pages，通常比直接从 GitHub 下载更稳定。若检查或下载失败，一般是网络波动或更新源暂时不可访问，稍后重新进入「关于」页点击当前版本即可再次检查。"
    )),

    // ── 数据解读 ──
    FaqListItem.Header("数据解读"),
    FaqListItem.Entry(FaqItem(
        "教学计划怎么看？上面的数据是什么意思？",
        "教学计划展示的是你的专业培养方案中各课组的学分完成情况。每行包含课组名称、属性（必修/限选/任选）、学分要求、已获学分、以及是否合格。它可以帮助你了解还有哪些学分未修够。"
    )),
    FaqListItem.Entry(FaqItem(
        "「等级考级」和「考试成绩」有什么区别？",
        "「考试成绩」是校内课程期末考试的分数和绩点；「等级考级」是国家等级考试（如大学英语四六级、普通话水平测试等）的成绩。两者来源不同，需分别刷新获取。"
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
        "调用提莫（timor.tech）免费节假日 API 获取全年法定节假日数据。为了减少不必要的网络请求，数据会缓存到手机本地，同一年内不再重复获取。"
    )),
    FaqListItem.Entry(FaqItem(
        "调课一览的数据是怎么来的？",
        "在导入课表时，系统会从课表页面底部解析调课/补课信息（原时间、原教室 → 补课时间、补课教室），并保存到本地。之后在「学期概览」中按补课周次分组展示。"
    )),

    // ── 新功能 ──
    FaqListItem.Header("新功能"),
    FaqListItem.Entry(FaqItem(
        "“专业成绩”是怎么计算的？",
        "专业成绩由应用在本机根据教学计划和成绩单计算，并不是学校直接返回的最终测评结果。应用按一个学年统计，即当年秋季和次年春季，使用“课程百分制成绩 × 教学计划学分”的加权平均值。\n\n默认统计培养计划中的必修和限选课程，排除体育、公共选修/任选、补修、重修/重学、辅修和双学位等课程。等级制成绩会按规则折算，补考、缓考、作弊或缺考按页面说明处理。显示“暂未计入”通常表示教学计划中存在该课程，但成绩单里尚未找到可用于计算的成绩。\n\n该结果仅供个人参考。奖学金、推免、综测等正式认定应以学院和学校公布的规则及结果为准。"
    )),
    FaqListItem.Entry(FaqItem(
        "“财务”菜单能做什么？可以直接缴费吗？",
        "财务菜单用于查询学校财务系统中的待缴项目、收费项目、交易记录、缴费明细、电子票据和学分结算等信息，目前仅支持桂林本部。查询结果会缓存在本机，金额默认隐藏，可点击顶部眼睛图标临时显示。\n\n应用本身不执行支付。需要缴费时会引导你打开学校财务官网，请在确认域名和金额后完成操作。财务平台账号、密码和登录状态与教务、体测系统相互独立，并通过 HTTPS 直接提交给学校财务系统。"
    )),
    FaqListItem.Entry(FaqItem(
        "“体测成绩”菜单包含哪些功能？",
        "体测菜单可查询最新体测结果、历年成绩以及各项目评分标准。体测平台使用独立账号体系，即使已经登录教务系统，也可能仍需输入体测平台密码和验证码。\n\n查询结果会缓存在本机，方便后续查看。体测查询目前需要经过项目中转服务，具体数据流和风险请阅读“隐私安全”中的说明。应用展示结果仅供参考，正式成绩以学校体测平台及相关部门认定为准。"
    )),

    // ── 隐私安全 ──
    FaqListItem.Header("隐私安全"),
    FaqListItem.Entry(FaqItem(
        "我的数据保存在哪里？维护者能看到吗？",
        "课表、考试、成绩、教学计划、财务查询结果和体测缓存主要保存在手机的应用私有目录中，不会自动同步到项目维护者的账号或云端。本应用未接入广告、用户行为统计、Firebase Analytics、Crashlytics 或 Sentry 等数据收集服务，维护者通常无法直接查看你手机里的本地数据。\n\n但需要特别说明：教务与财务功能会连接学校对应系统；体测功能目前需要通过本项目配置的 HTTPS 中转服务（glut-api.999314.xyz）访问体测平台。使用体测功能时，学号、体测密码、验证码、登录 Cookie 和体测结果会经过该中转服务。服务运营方在技术上处于数据处理链路中，因此我们不能承诺其“绝对无法接触”这些数据。如果你不能接受这一点，请不要使用体测查询功能。\n\n更新检查、通知和节假日功能还会访问项目更新源、GitHub 以及节假日接口。这些请求不会主动附带你的课表、成绩、财务数据或学校账号，但网络服务通常仍可能获得 IP 地址、请求时间和设备网络信息。"
    )),
    FaqListItem.Entry(FaqItem(
        "学号、密码和成绩是否加密？",
        "教务、体测和财务账号密码使用 Android 系统密钥保护的加密存储保存在本机；体测和财务登录 Cookie 同样使用加密存储。教务 Cookie 以及课表、成绩、教学计划、体测结果、财务结果等业务缓存保存在 Android 应用私有目录中，但并非所有缓存都逐项加密。\n\nAndroid 的应用沙箱会阻止普通应用直接读取这些文件，项目也关闭了系统云备份，并从备份规则中排除了数据库和配置文件。但如果设备已 Root、系统被恶意软件控制、调试权限被滥用或手机本身失窃解锁，本地数据仍存在被读取的可能。请为手机设置可靠的锁屏密码，不要在不可信设备上保存账号。"
    )),
    FaqListItem.Entry(FaqItem(
        "登录和查询过程中会不会泄露？",
        "财务功能通过 HTTPS 直接连接学校财务系统；体测功能通过 HTTPS 连接项目中转服务。部分旧版教务系统接口仍只提供 HTTP 连接，无法获得与 HTTPS 相同的传输保护，因此我们不能保证所有教务请求在传输途中都完全不可被截获或篡改。\n\n建议尽量使用可信网络，避免在陌生公共 Wi-Fi 下登录，并不要与他人分享验证码、Cookie、带有学号的截图或调试日志。学校系统、中转服务、网络运营商和设备系统自身的安全问题不完全由本应用控制。"
    )),
    FaqListItem.Entry(FaqItem(
        "怎样删除保存的数据？",
        "可在应用「设置」中使用「重置全部数据」，清除教务、体测和财务账号、登录状态以及本地课表、成绩和缓存；也可以在 Android 系统设置中进入「应用 → 桂系一站式 → 存储」，清除应用数据。卸载应用通常也会删除其私有数据。\n\n在 GitHub Issues、邮件或聊天中反馈问题时，请先遮挡学号、姓名、成绩、身份证号、Cookie、验证码和账单信息。GitHub Issues 属于公开页面，不适合提交包含个人信息的截图或日志。"
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
