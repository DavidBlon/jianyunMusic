package com.ncm.app.ui.screens.legal

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ncm.app.ui.theme.DarkBg
import com.ncm.app.ui.theme.GlassSurfaceStrong
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.miniPlayerSafeBottomPadding
import io.noties.markwon.Markwon

const val DISCLAIMER_MARKDOWN = """
# 免责声明

**生效日期：2026年7月29日**

欢迎使用简云音乐。请您在使用前认真阅读并理解本声明。您继续使用本软件，即表示您已经知悉本软件的功能、服务来源及相关风险。

## 一、非官方关联声明

本软件为独立开发的软件，与网易云音乐、杭州网易云音乐科技有限公司、网易公司及其关联主体不存在隶属、授权、合作、代理、赞助或其他官方关系。

“网易云音乐”等名称、商标、标识以及相关音乐、专辑封面、歌手头像、文字资料和其他内容，其权利归原权利人所有。本软件中出现相关名称和内容，仅用于说明软件功能及展示依法取得的信息，不代表获得相关权利人的认可或授权。

## 二、音乐内容与使用限制

本软件仅提供音乐信息检索、播放控制及相关技术功能，不享有音乐作品、录音制品、歌词、专辑封面、歌手图片等内容的版权。

用户应当确保其对所访问、播放或使用的内容具有合法使用权限，并遵守相关法律法规、版权要求以及内容平台的用户协议。

本软件不得用于破解会员权益、绕过付费限制、侵犯版权、批量下载传播音乐或其他违法违规用途。因用户不当使用本软件产生的责任，由用户依法承担；但依法应由软件开发者承担的责任不因此免除。

## 三、网易云账号与会员

用户可以根据自身情况使用网易云音乐会员权益。网易云音乐账号、会员状态、歌曲可播放范围及相关服务均由网易云音乐平台管理，本软件无法控制或保证。

因网易云音乐平台规则、接口、版权范围、会员政策、账号状态或技术措施发生变化，可能导致部分功能暂时或永久无法使用。

请勿向他人泄露账号、验证码、Cookie 或其他登录凭证。因用户主动泄露、违规共享账号或者在非可信环境中操作造成的损失，由相应责任方依法承担。

## 四、第三方音源与卡密

“聆澜音源”及其他备用音源由独立第三方服务商提供和运营，本软件开发者并非该音源的经营者、销售者或实际服务提供者。

用户可以自行决定是否购买和配置第三方音源卡密。购买并非使用本软件的强制条件；未购买或未输入卡密的用户，仍可以关闭提示并使用软件中的其他可用功能。

本软件安装包不预置开发者个人卡密。只有用户主动输入并通过验证后，软件才会保存和使用该卡密。卡密验证及音源请求过程中，必要信息可能被发送至对应的第三方音源服务。

购买第三方音源仅代表获得该服务商商品说明范围内的服务资格，不代表获得网易云音乐会员、音乐版权或绕过付费及版权限制的权利。

第三方音源的价格、有效期限、稳定性、适用范围、退款政策及售后服务均由相应服务商负责。“理论永久”等商品描述由第三方商家提供，不应理解为本软件对服务期限作出的永久保证。

## 五、第三方商城、优惠码与推广关系

本软件可能提供前往第三方商城 [sumnera.shop.shiqianjiang.cn](https://sumnera.shop.shiqianjiang.cn/) 的跳转入口，方便有需要的用户自行了解或购买“聆澜音源”等商品。

该商城由第三方独立经营。本软件开发者不参与商品收款、订单处理、卡密发放、退款或售后服务，也不掌握用户在商城注册、登录及支付过程中提交的信息。

优惠码 `SUMNERA-8JDAZH9G` 仅用于符合第三方商城规则的订单优惠，不属于音源卡密，不能用于直接激活音源。优惠是否有效、优惠金额和适用商品，以商城结算页面的实际显示为准。

**推广披露：本软件开发者与该第三方商城存在推广合作关系。当用户使用上述优惠码购买商品时，本软件开发者会获得第三方提供的推广佣金。该推广关系不会增加用户的商品价格，也不影响用户自愿决定是否购买。获得推广佣金不代表本软件开发者是商品销售者、音源服务提供者或售后责任主体。**

用户购买前应认真查看商品详情、服务期限、退款政策及商家联系方式。因第三方商品或服务产生问题时，用户应首先联系对应商城或音源服务商处理。

## 六、服务可用性

由于网络环境、设备兼容性、服务器维护、第三方接口调整、版权变化、政策变化、不可抗力或其他无法合理控制的原因，本软件及相关音源可能出现连接失败、播放失败、数据错误、功能中断或停止服务等情况。

软件开发者将尽合理努力维护软件功能，但不对所有歌曲均可播放、所有接口持续有效、音源永久稳定或者服务绝不中断作出保证。

对于软件开发者依法无法预见、无法避免且无法控制的第三方服务中断或不可抗力事件，责任按照适用法律及实际过错情况确定。

## 七、信息与数据说明

本软件可能处理实现登录、播放、搜索、收藏、卡密验证和设置保存所必需的数据。相关数据处理应遵循合法、正当、必要和最小范围原则。

卡密及部分软件设置可能保存在用户设备本地，并在验证或使用音源时传输至相应服务。请用户妥善保管自己的设备、账号及卡密。

本声明不能替代《隐私政策》。如软件涉及账号登录、设备信息、网络日志、Cookie 或其他个人信息处理，应当以软件另行展示的《隐私政策》为准。

## 八、责任边界

在法律允许的范围内，因用户违反法律法规、相关平台规则、本声明或者超出正常用途使用软件而产生的责任，由相应责任方依法承担。

本声明不排除或限制用户依法享有的消费者权益，也不免除软件开发者因故意、重大过失、侵犯人身权益、违法处理个人信息或其他依法不得免除的责任。

如本声明中的部分条款被认定无效，不影响其他条款继续有效。

## 九、未成年人保护

未成年人应在监护人指导和同意下使用本软件及购买相关服务。未成年人进行付费购买前，应当取得监护人明确同意。

## 十、声明更新

本声明可能根据软件功能、服务内容或法律法规变化进行更新。发生重要变化时，将通过软件内提示等合理方式告知用户。
"""

@Composable
fun DisclaimerScreen(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DisclaimerHeader(onBack = onBack)
        DisclaimerMarkdown(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    top = 12.dp,
                    end = 20.dp,
                    bottom = miniPlayerSafeBottomPadding()
                )
        )
    }
}

@Composable
fun DisclaimerDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = GlassSurfaceStrong,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "免责声明",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    TextButton(onClick = onDismiss) {
                        Text("返回", color = Green500)
                    }
                }
                DisclaimerMarkdown(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DisclaimerHeader(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextPrimary
            )
        }
        Text(
            "免责声明",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
    }
}

@Composable
private fun DisclaimerMarkdown(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textColor = TextSecondary.toArgb()
    val linkColor = Green500.toArgb()
    val markwon = remember(context.applicationContext) {
        Markwon.create(context.applicationContext)
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { viewContext ->
                TextView(viewContext).apply {
                    setTextColor(textColor)
                    setLinkTextColor(linkColor)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setLineSpacing(0f, 1.18f)
                    movementMethod = LinkMovementMethod.getInstance()
                    highlightColor = Color.Transparent.toArgb()
                }
            },
            update = { textView ->
                textView.setTextColor(textColor)
                textView.setLinkTextColor(linkColor)
                markwon.setMarkdown(textView, DISCLAIMER_MARKDOWN.trimIndent())
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
