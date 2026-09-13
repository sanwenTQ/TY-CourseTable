#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 HarmonyOS Sans SC 子集化成 App 内嵌字体（ASCII + GB2312 常用字 + 界面用字）。
   缺失的生僻字由 Android 自动回退系统字体。"""
import os, sys, zipfile

try:
    from fontTools import subset
    from fontTools.ttLib import TTFont
except ImportError:
    print('NO_FONTTOOLS')
    sys.exit(2)

SRC = sys.argv[1]
DST = sys.argv[2]
EXTRA = sys.argv[3:] if len(sys.argv) > 3 else []
TARGET_KB = int(os.environ.get('TARGET_KB', '0') or 0)

def gb2312_chars():
    chars = set()
    # GB2312 一二级汉字区
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode('gb2312'))
            except Exception:
                pass
    # 常用标点 / 全角
    for hi in range(0xA1, 0xAA):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode('gb2312'))
            except Exception:
                pass
    return chars

def main():
    chars = set(gb2312_chars())
    chars |= set(chr(c) for c in range(0x20, 0x7F))
    chars |= set('　·—…‘’“”！？、。，；：（）【】《》￥℃°×÷±§№→←↑↓★☆●○◆◇■□▲△▽▼♡♥')
    chars |= set('第周星期节次时间上午中午下午晚上今日明开学年月日时分秒天气课程教师地点校区考核方式备注学分总学时安排其他休息晚饭午休')
    for e in EXTRA:
        chars |= set(e)
    text = ''.join(sorted(chars))
    tmp = DST + '.txt'
    open(tmp, 'w', encoding='utf-8').write(text)

    args = [
        SRC,
        '--text-file=' + tmp,
        '--output-file=' + DST,
        '--layout-features=',            # 去掉 GSUB/GPOS，体积更小
        '--no-hinting',
        '--desubroutinize',
        '--drop-tables+=DSIG',
        '--name-IDs=*',
        '--recalc-bounds',
        '--notdef-outline',
    ]
    subset.main(args)
    os.remove(tmp)
    print(os.path.basename(DST), os.path.getsize(DST) // 1024, 'KB', len(chars), 'glyphs')

if __name__ == '__main__':
    main()
