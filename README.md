# 周账

把微信和支付宝的官方账单，变成「每周花了多少、花在哪」。

数据不出手机，不联网，不需要注册。

> **状态：v0.1，开发中。** 数据目前**未加密**，请先别用来存全年账单。

---

## 为什么做这个

现成的账单合并工具几乎都栽在同一件事上：**支付宝改了导出格式**。

2023 年之后「开具交易流水证明」给的是 12 列 CSV，而多数开源工具（含一些几千星的）仍写死旧版 17 列的表头和下标。它们不会报错，只会算出错误的数字——这比崩溃更糟。

微信这边也在 2026 年改成了 **xlsx**：时间列不是字符串，是 Excel 序列号 `46344.655…`；金额是数值不是 `¥32.00`。

这个项目从第一行代码就按真实格式写，并且拿真实账单验证过：微信 63 条、支付宝 91 条，都与账单文件自己声明的笔数一致。

---

## 现在能做什么

**导入**

| 输入 | 说明 |
|---|---|
| 微信 xlsx / csv | 自动跳过前 17 行说明；Excel 序列号时间正确还原 |
| 支付宝 csv（新 12 列） | GB18030 解码 |
| 支付宝 csv（旧 17 列） | GBK 解码，全角括号列名兼容 |
| 加密 zip | 提示输入密码，**内存解压不落盘** |

**清洗**（不洗的话支出会虚高 30% 以上）

- 转账、红包、提现、群收款 → 不计入消费
- 余额宝、零钱通、基金 → 不计入消费
- 花呗、借呗、信用卡还款 → 不计入消费
- 退款单独标记，不混进消费
- 按交易单号去重，同一份账单导两次不会翻倍

**分类**

关键词规则 + 支付宝自带的「交易分类」兜底（商户名猜不出来时，用它）。

**查看**

- 周视图：周三起止（可改），左右翻周，环比用**上周同期**而非完整上周
- 月视图：自然月统计，分类占比、Top 商户、环比上月

---

## 隐私

这个 App 的卖点是「数据不出设备」，所以把它做成了可验证的：

- **Manifest 不声明 `INTERNET` 权限** —— 系统层面就没有联网能力，在「应用信息 - 权限」里能自己看到
- 无统计 SDK、无广告 SDK、无崩溃上报
- zip 在内存里解压，不产生临时文件
- `allowBackup=false`，不进系统云备份

### 已知局限（不藏着）

- 数据库**尚未加密**（SQLCipher 在路线图里）
- 没有应用锁
- 分类规则较薄，未分类占比可能偏高
- 安卓无障碍/通知监听自动记账**不做**——那是随时会坏的方案

---

## 怎么导出账单

**微信**：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账 → 填邮箱 + 支付密码。加密压缩包，解压码由「微信支付」公众号推送。

**支付宝**：我的 → 账单 → 右上角「…」→ 开具交易流水证明 → 用于个人对账 → 邮箱收 zip。

---

## 构建

需要 JDK 17 和 Android SDK（compileSdk 34）。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建要下载 Gradle 和整套依赖。国内网络慢的话，可以把
> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`，
> 依赖仓库也可以加阿里云镜像。

装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
./gradlew testDebugUnitTest
```

解析层是**纯 Kotlin、零 Android 依赖**的，所以格式适配的全部逻辑都能在 JVM 上跑测试，不用开模拟器。这是保证正确率的关键。

15 个用例覆盖：Excel 序列号还原、GB18030 解码、脏引号容错、中性交易判定、千分位金额、周起始日计算。

想用真实账单自测，把它们放进 `samples/private/`（已在 .gitignore 里），测试会自动跑一遍。

---

## 结构

```
app/src/main/java/app/zhouzhang/
  core/          解析层，纯 Kotlin，零 Android 依赖
    Csv.kt       容错 CSV 读取（脏引号不吞行）
    Xlsx.kt      极简 xlsx 读取，不引 POI
    ZipUtil.kt   zip4j 流式解密，不落盘
    Parser.kt    格式识别 + 字段归一 + 清洗
  data/          SQLite
  ui/            Compose 主题
design/icon/     图标源文件与 Android 资源
tools/           图标生成、样本生成（可复现）
samples/         合成测试样本，不含任何真实账单
```

---

## 路线图

- [x] 解析内核（微信 xlsx/csv、支付宝新旧两版、加密 zip）
- [x] 周视图、月视图
- [ ] SQLCipher 加密 + 应用锁
- [ ] 分类规则学习：未分类商户一键归类并沉淀规则
- [ ] 导出（脱敏 CSV）
- [ ] 银行账单

---

## 致谢

思路借鉴了这些项目，代码独立实现，详见 [NOTICE](NOTICE)：

- [vogo/aliwepaystat](https://github.com/vogo/aliwepaystat) — 字段归一、内部流转判定
- [yann0917/alipay-wechat-merge](https://github.com/yann0917/alipay-wechat-merge) — 脏引号容错的教训
- [bingchuH/Bookkeeping](https://github.com/bingchuH/Bookkeeping) — 规则沉淀
- [mayswind/ezbookkeeping](https://github.com/mayswind/ezbookkeeping) — 转账语义建模

---

## License

[GPL-3.0](LICENSE)

选它的理由：这个 App 的承诺是「不联网、数据不出设备」，而这种承诺只能靠可审计来证明。GPL 保证分发出去的修改版也必须开源——防止有人拿它改一版塞进统计 SDK，再用同一套隐私话术骗人。
