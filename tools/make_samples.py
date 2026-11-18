#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成贴近真实导出的账单样本，用于验证解析器。

刻意包含的坑：
  - 微信表头前有 11 行说明文字和分隔线
  - 支付宝新版是 GB18030、旧版是 GBK
  - 商品说明里带逗号和引号
  - 中性交易（转账/提现/余额宝转入）
  - 已全额退款、退款成功
  - 金额带 ¥ 符号和千分位
"""

import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "samples"
OUT.mkdir(exist_ok=True)
random.seed(20261118)

TODAY = datetime(2026, 11, 18, 15, 30)

WECHAT_MERCHANTS = [
    ("星巴克咖啡", "拿铁大杯", "餐饮", 32.00),
    ("美团外卖", "美团外卖订单", "餐饮", 28.50),
    ("瑞幸咖啡", "生椰拿铁", "餐饮", 16.90),
    ("肯德基", "宅急送订单", "餐饮", 45.00),
    ("滴滴出行", "快车行程", "交通", 23.40),
    ("上海地铁", "乘车码扣费", "交通", 4.00),
    ("全家便利店", "日用商品", "购物", 18.60),
    ("盒马鲜生", "生鲜蔬菜", "购物", 86.30),
    ("京东商城", "充电宝", "购物", 129.00),
    ("中国移动", "话费充值", "通讯", 100.00),
    ("万达影城", "电影票 2 张", "娱乐", 78.00),
    ("老百姓大药房", "感冒药", "医疗", 36.50),
]

ALIPAY_MERCHANTS = [
    ("肯德基（人民广场店）", "扫码付款-肯德基", "餐饮", 39.00),
    ("饿了么", "外卖订单-黄焖鸡", "餐饮", 22.80),
    ("喜茶", "多肉葡萄", "餐饮", 29.00),
    ("高德打车", "出租车费用", "交通", 31.20),
    ("中国石化", "加油 92#", "交通", 300.00),
    ("淘宝网", "冬季加绒卫衣", "购物", 158.00),
    ("天猫超市", "抽纸 24 包", "购物", 59.90),
    ("优衣库", "HEATTECH 内衣", "购物", 99.00),
    ("国家电网", "电费缴纳", "居家", 186.40),
    ("爱奇艺", "黄金会员月卡", "娱乐", 25.00),
    ("携程旅行", "酒店预订-杭州", "娱乐", 468.00),
    ("中国联通", "宽带续费", "通讯", 600.00),
]


def wechat_rows(n=46):
    """前 5 行是固定的边界样本，不靠随机——否则测试会时有时无。"""
    base = TODAY - timedelta(days=20)
    rows = [
        [base + timedelta(hours=1), "转账", "李雷", "转账备注：AA 饭钱", "不计收支",
         "¥88.00", "零钱", "对方已收钱", "4200000000000000000000000001", "1001", "/"],
        [base + timedelta(hours=2), "微信红包", "韩梅梅", "恭喜发财", "不计收支",
         "¥66.00", "零钱", "已存入零钱", "4200000000000000000000000002", "1002", "/"],
        [base + timedelta(hours=3), "零钱提现", "招商银行(1234)", "零钱提现", "不计收支",
         "¥500.00", "零钱", "提现成功", "4200000000000000000000000003", "1003", "/"],
        [base + timedelta(hours=4), "商户消费", "某咖啡", "拿铁", "支出",
         "¥32.00", "零钱", "已全额退款", "4200000000000000000000000004", "1004", "/"],
        [base + timedelta(hours=5), "商户消费", "某电商", '订单"A-1", 含运费', "支出",
         "¥1,280.00", "招商银行(1234)", "支付成功", "4200000000000000000000000005", "1005", "/"],
    ]

    t = base + timedelta(days=1)
    for i in range(n - len(rows)):
        t = t + timedelta(hours=random.randint(5, 30), minutes=random.randint(0, 59))
        if t > TODAY:
            t = TODAY - timedelta(minutes=random.randint(5, 300))
        kind_roll = random.random()
        if kind_roll < 0.16:
            rows.append([t, "转账", "王五", "转账备注", "不计收支",
                         "¥%.2f" % random.choice([88.00, 120.00, 45.00]), "零钱",
                         "对方已收钱", "42001%020d" % i, "2001%d" % i, "/"])
        elif kind_roll < 0.24:
            rows.append([t, "商户消费", "某电商", '订单"B-%d", 含运费' % i, "支出",
                         "¥1,%03d.00" % (100 + i), "零钱", "支付成功",
                         "42002%020d" % i, "2002%d" % i, "/"])
        else:
            name, item, _, amt = random.choice(WECHAT_MERCHANTS)
            rows.append([t, "商户消费", name, item, "支出", "¥%.2f" % amt,
                         random.choice(["零钱", "零钱通", "招商银行(1234)"]),
                         "支付成功", "42003%020d" % i, "2003%d" % i, "/"])
    return rows


def write_wechat(path):
    header = [
        "微信支付账单明细", "", "", "", "", "", "", "", "", "", "",
    ]
    meta = [
        ["微信昵称：[周周]"],
        ["起始时间：[2026-10-22 00:00:00] 终止时间：[2026-11-18 23:59:59]"],
        ["导出类型：[全部]"],
        ["导出时间：[2026-11-18 16:20:00]"],
        [""],
        ["共 46 笔记录"],
        ["收入：0 笔 0.00 元"],
        ["支出：34 笔 6,482.30 元"],
        ["中性交易：12 笔 2,014.88 元"],
        ["注：本账单仅展示微信支付相关交易"],
        ["----------------------微信支付账单明细列表--------------------"],
    ]
    cols = ["交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
            "支付方式", "当前状态", "交易单号", "商户单号", "备注"]

    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for m in meta:
            w.writerow(m + [""] * 10)
        w.writerow(cols)
        for r in wechat_rows():
            r[0] = r[0].strftime("%Y-%m-%d %H:%M:%S")
            w.writerow(r)


def alipay_rows(n=42):
    """同样固定写入边界样本：理财、还款、退款、收入、带引号的商品名。"""
    base = TODAY - timedelta(days=18)
    rows = [
        [base + timedelta(hours=1), "转账", "王小明", "w***@example.com", "转账给王小明", "不计收支",
         "200.00", "余额", "交易成功", "2026000000000000000000000001", "T2001", ""],
        [base + timedelta(hours=2), "余额宝-自动转入", "余额宝", "", "余额宝自动转入", "不计收支",
         "1000.00", "余额", "交易成功", "2026000000000000000000000002", "T2002", ""],
        [base + timedelta(hours=3), "信用还款", "花呗", "", "花呗自动还款", "不计收支",
         "1,532.00", "余额宝", "交易成功", "2026000000000000000000000003", "T2003", ""],
        [base + timedelta(hours=4), "日用百货", "某超市", "", "抽纸 24 包", "支出",
         "39.90", "花呗", "退款成功", "2026000000000000000000000004", "T2004", ""],
        [base + timedelta(hours=5), "转账", "张伟", "z***@example.com", "收到转账", "收入",
         "500.00", "余额", "交易成功", "2026000000000000000000000005", "T2005", ""],
        [base + timedelta(hours=6), "餐饮美食", "某餐厅", "", '套餐"A", 含饮料', "支出",
         "88.00", "余额宝", "交易成功", "2026000000000000000000000006", "T2006", "带引号的商品名"],
        # 商户名和商品名都不含关键词，只能靠「交易分类」兜底
        [base + timedelta(hours=7), "日用百货", "无人零售", "gou***@qinggouyun.com", "智能柜购物", "支出",
         "7.50", "花呗", "交易成功", "2026000000000000000000000007", "T2007", ""],
    ]

    rng = random.Random(20261119)
    t = base + timedelta(days=1)
    for i in range(n - len(rows)):
        t = t + timedelta(hours=rng.randint(6, 30), minutes=rng.randint(0, 59))
        if t > TODAY:
            t = TODAY - timedelta(minutes=rng.randint(5, 300))
        name, item, _, amt = rng.choice(ALIPAY_MERCHANTS)
        rows.append([t, "餐饮美食" if "餐" in item or "茶" in item else "日用百货",
                     name, "", item, "支出", "%.2f" % amt,
                     rng.choice(["余额宝", "花呗", "招商银行储蓄卡(1234)"]),
                     "交易成功", "2026%022d" % i, "T30%d" % i, ""])
    return rows


def write_alipay_new(path):
    cols = ["交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支",
            "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注"]
    with open(path, "w", encoding="gb18030", newline="") as f:
        w = csv.writer(f)
        w.writerow(["支付宝交易明细查询"])
        w.writerow(["账号：[138****8888]"])
        w.writerow(["起始时间：[2026-10-22 00:00:00]    终止时间：[2026-11-18 23:59:59]"])
        w.writerow(["导出时间：[2026-11-18 16:20:00]"])
        w.writerow(["-" * 60])
        w.writerow(cols)
        for r in alipay_rows():
            r[0] = r[0].strftime("%Y-%m-%d %H:%M:%S")
            w.writerow(r)


def write_alipay_old(path):
    cols = ["交易号", "商家订单号", "交易创建时间", "付款时间", "最近修改时间",
            "交易来源地", "类型", "交易对方", "商品名称", "金额（元）", "收/支",
            "交易状态", "服务费（元）", "成功退款（元）", "备注", "资金状态"]
    with open(path, "w", encoding="gbk", newline="") as f:
        w = csv.writer(f)
        w.writerow(["支付宝交易记录明细查询"])
        w.writerow(["账号：[138****8888]"])
        w.writerow(cols)
        t = TODAY - timedelta(days=3)
        for i in range(8):
            t2 = t + timedelta(hours=7 * i)
            w.writerow([
                "2026%018d" % i, "M%d" % i,
                t2.strftime("%Y-%m-%d %H:%M:%S"), t2.strftime("%Y-%m-%d %H:%M:%S"),
                t2.strftime("%Y-%m-%d %H:%M:%S"), "支付宝网站", "即时到账交易",
                "肯德基", "扫码付款-肯德基", "39.00", "支出", "交易成功",
                "0.00", "0.00", "", "已支出",
            ])


def write_wechat_xlsx(path):
    """复刻微信新版 xlsx：时间列是 Excel 序列号，金额是数值而非字符串。"""
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    rows = wechat_rows(30)

    meta = [
        "微信支付账单明细",
        "微信昵称：[某某]",
        "起始时间：[2026-10-22 00:00:00] 终止时间：[2026-11-18 23:59:59]",
        "导出类型：[全部账单]",
        "导出时间：[2026-11-18 16:30:30]",
        None,
        f"共{len(rows)}笔记录",
        "收入：4笔 1,200.00元",
        "支出：20笔 1,884.03元",
        "中性交易：6笔 1,118.11元",
        "注：",
        "1. 充值/提现/理财通购买/零钱通存取/信用卡还款等交易，将计入中性交易",
        "2. 若交易记录明细无有效内容，则代表该时间段内此微信号无交易",
        "3. 本明细仅供个人对账使用",
        "4. 本账单中所有时间均为UTC+08:00时间",
        None,
        "----------------------微信支付账单明细列表--------------------",
    ]
    for i, v in enumerate(meta, start=1):
        if v is not None:
            ws.cell(row=i, column=1, value=v)

    header = ["交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
              "支付方式", "当前状态", "交易单号", "商户单号", "备注"]
    for c, name in enumerate(header, start=1):
        ws.cell(row=18, column=c, value=name)

    for i, r in enumerate(rows, start=19):
        ws.cell(row=i, column=1, value=r[0])  # datetime -> Excel 序列号
        for c in range(2, 6):
            ws.cell(row=i, column=c, value=r[c - 1])
        amt = float(r[5].replace("¥", "").replace(",", ""))
        ws.cell(row=i, column=6, value=amt)   # 数值，不是 "¥32.00"
        for c in range(7, 12):
            ws.cell(row=i, column=c, value=r[c - 1])

    wb.save(path)


if __name__ == "__main__":
    write_wechat(OUT / "微信支付账单_2026-11.csv")
    write_wechat_xlsx(OUT / "微信支付账单_xlsx样本.xlsx")
    write_alipay_new(OUT / "支付宝交易明细_2026-11.csv")
    write_alipay_old(OUT / "支付宝交易明细_旧版_2026-11.csv")
    for p in sorted(OUT.iterdir()):
        print(f"{p.name:40s} {p.stat().st_size:>8,} bytes")
