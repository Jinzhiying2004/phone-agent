#!/usr/bin/env python3
# v3.12 Issue 10：CHANGELOG 工具化校验
# 用法：python3 tools/check_changelog.py
# 检查：1) 表格行版本号顺序  2) Manifest versionName 在表里  3) 无重复版本号行
import re
import sys

with open("CHANGELOG.md", encoding="utf-8") as f:
    lines = f.read().splitlines()
with open("AndroidManifest.xml", encoding="utf-8") as f:
    manifest = f.read()

ver = re.search(r'versionName="([^"]+)"', manifest).group(1)
code = re.search(r'versionCode="(\d+)"', manifest).group(1)

rows = []
for ln in lines:
    m = re.match(r"\|\s*([\d.]+)\s*\|\s*(\d+)\s*\|", ln)
    if m:
        rows.append((m.group(1), m.group(2), ln))

errs = []
# 1. Manifest 版本必须在表里
if not any(r[0] == ver and r[1] == code for r in rows):
    errs.append(f"Manifest {ver}/{code} 在 CHANGELOG 表中找不到")

# 2. 无重复 (version, code)
seen = set()
for v, c, _ in rows:
    if (v, c) in seen:
        errs.append(f"重复条目：{v}/{c}")
    seen.add((v, c))

# 3. versionCode 递增（按 code 排序应与出现顺序一致，忽略表头后的乱序）
codes = [int(c) for _, c, _ in rows]
if codes != sorted(codes):
    # 只警告，不报错（历史表允许v2.54写在v2.53前这类已存在的乱序）
    print("WARN: versionCode 非严格递增（历史遗留，新增行请保持递增）")

if errs:
    print("FAIL:")
    for e in errs:
        print(" -", e)
    sys.exit(1)
print(f"OK: CHANGELOG 校验通过（{len(rows)} 行，当前 {ver}/{code}）")
