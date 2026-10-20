#!/usr/bin/env python3
"""
v3.13 Issue 3：补丁签名工具。
用 ~/.patch_keys/patch_sign.jks 中的 RSA 私钥对 (tag:sha256) 做 SHA256withRSA 签名。
私钥永不进仓库。

用法: python3 tools/patch_sign.py <tag> <dex_sha256_hex>  -> 输出 base64 签名
"""
import base64
import os
import subprocess
import sys
import tempfile

KEYS_DIR = os.path.expanduser("~/.patch_keys")
JDK_BIN = os.path.expanduser("~/build-tools/jdk17/bin")
KEYTOOL = os.path.join(JDK_BIN, "keytool")
JKS = os.path.join(KEYS_DIR, "patch_sign.jks")
PASS_FILE = os.path.join(KEYS_DIR, "store.pass")


def sign(tag, dex_sha):
    payload = f"{tag}:{dex_sha.lower()}".encode("utf-8")
    with open(PASS_FILE) as f:
        storepass = f.read().strip()
    tmp = tempfile.mkdtemp(prefix="patchsign_")
    try:
        # 1. JKS -> PKCS12
        p12 = os.path.join(tmp, "k.p12")
        r = subprocess.run(
            [KEYTOOL, "-importkeystore",
             "-srckeystore", JKS, "-srcstorepass", storepass,
             "-srcalias", "patchsign",
             "-destkeystore", p12, "-deststoretype", "PKCS12",
             "-deststorepass", storepass],
            capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("keytool 转 PKCS12 失败: " + r.stderr[-500:])
        # 2. PKCS12 -> PEM 私钥
        pem = os.path.join(tmp, "k.pem")
        r = subprocess.run(
            ["openssl", "pkcs12", "-in", p12, "-nodes", "-nocerts",
             "-passin", f"pass:{storepass}"],
            capture_output=True, text=True)
        if r.returncode != 0 or "PRIVATE KEY" not in r.stdout:
            raise RuntimeError("openssl 提取私钥失败")
        open(pem, "w").write(r.stdout)
        # 3. openssl 签名
        payload_file = os.path.join(tmp, "payload.bin")
        open(payload_file, "wb").write(payload)
        sig_file = os.path.join(tmp, "sig.bin")
        r = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem,
             "-out", sig_file, payload_file],
            capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("openssl 签名失败: " + r.stderr[-500:])
        sig = open(sig_file, "rb").read()
        return base64.b64encode(sig).decode()
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    if not os.path.exists(JKS):
        print(f"私钥库不存在：{JKS}，先生成密钥对")
        sys.exit(1)
    print(sign(sys.argv[1], sys.argv[2]))
