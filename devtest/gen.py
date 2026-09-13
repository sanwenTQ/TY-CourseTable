# -*- coding: utf-8 -*-
"""造两份「教务系统风格」的课表：.xls(BIFF8) 与 .xlsx，外加一份伪装成 xls 的 HTML 表格"""
import xlwt, openpyxl, os

DAYS = ["星期一","星期二","星期三","星期四","星期五","星期六","星期日"]
# (节次, 星期索引, 课程, 周次, 场地, 教师)
DATA = [
 (1, 3, "商务英语", "1-16", "沙河二教203", "马丽慧"),
 (1, 4, "概率论与数理统计", "1-16", "沙河主教215M", "李冬红"),
 (3, 0, "大学体育（3）", "1-16", "体育场", "李强"),
 (3, 1, "政治经济学", "1-16", "沙河主教216M", "张志敏"),
 (3, 2, "概率论与数理统计", "1-16", "沙河主教216M", "李冬红"),
 (3, 4, "马克思主义基本原理", "1-7", "学院楼4号楼报告厅107", "王淼"),
 (7, 0, "中国财政史", "1-16", "学院楼7号楼115M", "马金华"),
 (7, 1, "金融学", "1-16", "沙河主教218M", "贾玉革"),
 (7, 2, "形势与政策（3）", "3-6", "千人礼堂", "肖宁"),
 (7, 4, "中国税制", "3-16", "沙河二教210", "李贞"),
 (12,0, "日本语言与文化（1）", "1-16", "沙河主教108M", "李蕊"),
 (12,2, "税收学", "1-16", "沙河主教105M", "徐涛"),
]
def cell(c):
    return "%s(%d-%d节)%s周/场地:%s/教师:%s" % (
        c[2], c[0], c[0]+1 if c[0]!=12 else 13, c[3], c[4], c[5])

rows = []
rows.append(["2026-2027学年第1学期课程表"])
rows.append(["节次"] + DAYS)
for p in [1,2,3,4,5,7,8,9,10,11,12,13]:
    row = ["第%d节" % p] + [""]*7
    for c in DATA:
        if c[0] == p:
            row[1+c[1]] = cell(c)
    rows.append(row)

# ---- .xls
wb = xlwt.Workbook(encoding='utf-8')
ws = wb.add_sheet('课表')
for r, row in enumerate(rows):
    for cc, v in enumerate(row):
        ws.write(r, cc, v)
wb.save('kebiao.xls')

# ---- .xlsx
wb2 = openpyxl.Workbook()
ws2 = wb2.active
for r, row in enumerate(rows):
    for cc, v in enumerate(row):
        ws2.cell(row=r+1, column=cc+1, value=v)
wb2.save('kebiao.xlsx')

# ---- 伪装成 .xls 的 HTML 表格
html = ['<html><head><meta charset="gb2312"></head><body><table border=1>']
for row in rows:
    html.append('<tr>' + ''.join('<td>%s</td>' % (v or '&nbsp;') for v in row) + '</tr>')
html.append('</table></body></html>')
open('kebiao_html.xls','wb').write('\n'.join(html).encode('gb2312'))
print('generated:', [f for f in os.listdir('.') if f.startswith('kebiao')])
