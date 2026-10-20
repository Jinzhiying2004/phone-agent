#!/usr/bin/env python3
"""
热补丁打包：对比基线 classes 与当前源码编译结果，差分出补丁 dex。

用法: python3 tools/make_patch.py <appCode> <tag>
  appCode: 目标版本 versionCode（必须与 AndroidManifest 一致，且 baselines/<appCode>/ 存在）
  tag:     补丁标识，如 p1

输出: patches/patch-<appCode>-<tag>.zip（含 patch.dex + meta.json）

约束（热补丁只能覆盖 dex 变更）：
- res/、assets/、AndroidManifest.xml 有任何变化 → 拒绝打包，请整包安装
- 当前源码编译后与基线无 class 差异 → 提示无需打补丁
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_SDK", "/home/hatch/android-sdk")
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLAT = os.path.join(SDK, "platforms", "android-34", "android.jar")
os.environ["JAVA_HOME"] = "/home/hatch/build-tools/jdk17"
os.environ["PATH"] = os.environ["JAVA_HOME"] + "/bin:" + os.environ["PATH"]


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def snapshot():
    """当前 res/、assets/、AndroidManifest.xml 的文件哈希快照"""
    snap = {}
    for top in ("res", "assets"):
        d = os.path.join(ROOT, top)
        if not os.path.isdir(d):
            continue
        for dp, _, fns in os.walk(d):
            for fn in sorted(fns):
                p = os.path.join(dp, fn)
                snap[os.path.relpath(p, ROOT)] = sha256_file(p)
    snap["AndroidManifest.xml"] = sha256_file(
        os.path.join(ROOT, "AndroidManifest.xml"))
    return snap


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    app_code, tag = sys.argv[1], sys.argv[2]
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,20}", tag):
        print("tag 非法，只允许字母数字 . _ -");
        sys.exit(2)

    m = open(os.path.join(ROOT, "AndroidManifest.xml")).read()
    cur_code = re.search(r'versionCode="(\d+)"', m).group(1)
    if cur_code != app_code:
        print(f"AndroidManifest versionCode={cur_code}，与参数 {app_code} 不一致")
        sys.exit(1)

    base = os.path.join(ROOT, "baselines", app_code)
    if not os.path.isdir(os.path.join(base, "classes")):
        print(f"基线不存在：{base}/classes，请先用 build.sh 构建该版本")
        sys.exit(1)

    # 1. 资源/清单一致性检查：热补丁覆盖不了这些
    base_snap = json.load(open(os.path.join(base, "snap.json")))
    cur_snap = snapshot()
    if base_snap != cur_snap:
        diff_keys = [k for k in set(base_snap) | set(cur_snap)
                     if base_snap.get(k) != cur_snap.get(k)]
        print("检测到以下非 dex 变更，热补丁无法覆盖，请整包安装：")
        for k in sorted(diff_keys)[:10]:
            print("  -", k)
        sys.exit(1)
    print("资源/清单一致性检查通过（无非 dex 变更）")

    # 2. 全量编译当前源码
    tmp = tempfile.mkdtemp(prefix="patchbuild_")
    new_classes = os.path.join(tmp, "classes")
    os.makedirs(new_classes)
    srcs = []
    for top in ("src",):
        for dp, _, fns in os.walk(os.path.join(ROOT, top)):
            for fn in fns:
                if fn.endswith(".java"):
                    srcs.append(os.path.join(dp, fn))
    gen_dir = os.path.join(base, "gen")
    for dp, _, fns in os.walk(gen_dir):
        for fn in fns:
            if fn.endswith(".java"):
                srcs.append(os.path.join(dp, fn))
    r = subprocess.run(
        ["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-classpath", PLAT, "-d", new_classes] + srcs,
        capture_output=True, text=True)
    if r.returncode != 0:
        print("javac 失败：\n" + r.stderr[-3000:])
        sys.exit(1)
    print(f"编译完成，共 {len(srcs)} 个 java 文件")

    # 3. 差分：新增或内容变化的 class
    base_classes = os.path.join(base, "classes")
    changed = []
    for dp, _, fns in os.walk(new_classes):
        for fn in sorted(fns):
            if not fn.endswith(".class"):
                continue
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, new_classes)
            bp = os.path.join(base_classes, rel)
            if not os.path.exists(bp) or sha256_file(p) != sha256_file(bp):
                changed.append((p, rel))
    if not changed:
        print("与基线无 class 差异，无需打补丁")
        shutil.rmtree(tmp, ignore_errors=True)
        sys.exit(0)
    print(f"差异类 {len(changed)} 个：")
    for _, rel in changed[:15]:
        print("  ~", rel)
    if len(changed) > 15:
        print(f"  … 等共 {len(changed)} 个")

    # 4. d8 差分编译：变更类为 program，其余为 classpath（library，不进输出）
    dex_out = os.path.join(tmp, "dex")
    os.makedirs(dex_out)
    cmd = [os.path.join(BT, "d8"), "--min-api", "26", "--lib", PLAT,
           "--classpath", new_classes, "--output", dex_out,
           ] + [p for p, _ in changed]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("d8 失败：\n" + r.stderr[-3000:])
        sys.exit(1)
    patch_dex = os.path.join(dex_out, "classes.dex")
    if not os.path.exists(patch_dex):
        print("d8 未产出 classes.dex")
        sys.exit(1)

    # 5. 打包
    dex_sha = sha256_file(patch_dex)
    meta = {
        "appCode": int(app_code),
        "tag": tag,
        "sha256": dex_sha,
        "classes": [rel for _, rel in changed],
    }
    # v3.13 Issue 3：RSA 签名（私钥离线保管，缺失则拒绝打包）
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "patch_sign", os.path.join(ROOT, "tools", "patch_sign.py"))
        ps = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ps)
        meta["sig"] = ps.sign(tag, dex_sha)
        print("补丁签名完成")
    except Exception as e:
        print(f"签名失败（{e}），拒绝打包：热补丁必须签名")
        shutil.rmtree(tmp, ignore_errors=True)
        sys.exit(1)
    out_dir = os.path.join(ROOT, "patches")
    os.makedirs(out_dir, exist_ok=True)
    out_zip = os.path.join(out_dir, f"patch-{app_code}-{tag}.zip")
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(patch_dex, "patch.dex")
        z.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=2))
    size = os.path.getsize(out_zip)
    print(f"\n补丁已生成：{out_zip}（{size} 字节，sha256={dex_sha[:16]}…）")
    print(f"推送命令：python3 ~/workspace/push_patch.py {out_zip}")
    shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
