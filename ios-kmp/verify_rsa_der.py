#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 iOS 侧的 RSA 公钥 DER 拼装（Swift `RSASPKI.swift` 的同一套字节逻辑）。

为什么要在 Windows 上验这个：
    `SecKeyCreateWithData` 只接受 DER 编码的 SubjectPublicKeyInfo，而教务给的是
    两个 base64 大整数（modulus / exponent）。拼错一个长度字节、少补一个 0x00，
    Security 框架只会返回 nil —— 在真机上表现就是「加密失败」，
    没有更具体的错误信息。这一步错了，云构建跑十次也定位不到。

验证方法（不需要 macOS）：
    1. 用 openssl 现场生成一把 2048 位密钥；
    2. 取出 modulus / exponent，模拟教务返回的 base64；
    3. 用与 Swift 完全相同的步骤拼 SPKI；
    4. 交给 `openssl rsa -pubin -inform DER` 解析 —— 解析成功且 modulus 一致，
       说明字节序列是合法且正确的 SPKI；
    5. 再用这把公钥做 PKCS#1 v1.5 加密，用私钥解回来比对原文，
       确认整条路径（含填充）确实可用。

用法： python ios-kmp/verify_rsa_der.py
"""
import base64
import os
import re
import shutil
import subprocess
import sys
import tempfile

# ---------------- 与 Swift 一一对应的 DER 拼装 ----------------

def normalize(v: bytes) -> bytes:
    """去掉高位 0x00（DER INTEGER 的符号位由编码器重新补）。对应 RSASPKI.normalize。"""
    i = 0
    while i < len(v) - 1 and v[i] == 0:
        i += 1
    return v[i:]


def der_length(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    out = []
    while n > 0:
        out.insert(0, n & 0xFF)
        n >>= 8
    return bytes([0x80 | len(out)]) + bytes(out)


def tlv(tag: int, body: bytes) -> bytes:
    return bytes([tag]) + der_length(len(body)) + body


def der_integer(raw: bytes) -> bytes:
    v = normalize(raw)
    if v[0] & 0x80:
        v = b"\x00" + v
    return tlv(0x02, v)


def subject_public_key_info(modulus: bytes, exponent: bytes) -> bytes:
    rsa_public_key = tlv(0x30, der_integer(modulus) + der_integer(exponent))
    # AlgorithmIdentifier: OID 1.2.840.113549.1.1.1 (rsaEncryption) + NULL
    algorithm = tlv(0x30, bytes([0x06, 0x09, 0x2A, 0x86, 0x48, 0x86,
                                 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00]))
    bit_string = tlv(0x03, b"\x00" + rsa_public_key)   # 0 个未使用位
    return tlv(0x30, algorithm + bit_string)


# ---------------- 最小 DER 解析器（独立于上面的构造代码） ----------------

def read_tlv(buf: bytes, i: int):
    tag = buf[i]
    i += 1
    first = buf[i]
    i += 1
    if first < 0x80:
        ln = first
    else:
        cnt = first & 0x7F
        ln = int.from_bytes(buf[i:i + cnt], "big")
        i += cnt
    return tag, buf[i:i + ln], i + ln


def parse_spki(der: bytes):
    """按 SubjectPublicKeyInfo 结构逐层拆开，返回 (modulus, exponent)。"""
    tag, body, end = read_tlv(der, 0)
    assert tag == 0x30, "外层不是 SEQUENCE"
    assert end == len(der), "外层长度与总长不一致（多或少字节）"

    tag, alg, rest = read_tlv(body, 0)
    assert tag == 0x30, "AlgorithmIdentifier 不是 SEQUENCE"
    tag, oid, _ = read_tlv(alg, 0)
    assert tag == 0x06, "AlgorithmIdentifier 里没有 OID"
    assert oid == bytes([0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]), \
        "OID 不是 rsaEncryption"

    # AlgorithmIdentifier 占用的字节数 = 1(tag) + 长度字节 + 内容
    consumed = 1 + len(der_length(len(alg))) + len(alg)
    tag, bitstr, end2 = read_tlv(body, consumed)
    assert tag == 0x03, "公钥不在 BIT STRING 里"
    assert end2 == len(body), "BIT STRING 之后还有多余内容"
    assert bitstr[0] == 0, "BIT STRING 的未使用位不为 0"

    tag, pub, _ = read_tlv(bitstr, 1)
    assert tag == 0x30, "RSAPublicKey 不是 SEQUENCE"
    tag, m, off = read_tlv(pub, 0)
    assert tag == 0x02, "modulus 不是 INTEGER"
    tag, e, _ = read_tlv(pub, off)
    assert tag == 0x02, "exponent 不是 INTEGER"
    return m, e


# ---------------- 测试 ----------------

def main():
    work = tempfile.mkdtemp(prefix="zsts-der-")
    failures = []

    def check(cond, label, extra=""):
        if cond:
            print("  ✓ " + label)
        else:
            failures.append(label + (" :: " + extra if extra else ""))
            print("  ✗ " + label + (" :: " + extra if extra else ""))

    try:
        key = os.path.join(work, "key.pem")
        subprocess.run(["openssl", "genrsa", "-out", key, "2048"],
                       check=True, capture_output=True)

        out = subprocess.run(["openssl", "rsa", "-in", key, "-noout", "-modulus"],
                             check=True, capture_output=True, text=True).stdout
        modulus_hex = out.strip().split("=", 1)[1]
        modulus = bytes.fromhex(modulus_hex)

        txt = subprocess.run(["openssl", "rsa", "-in", key, "-noout", "-text"],
                             check=True, capture_output=True, text=True).stdout
        exponent = b"\x01\x00\x01"  # 65537
        for line in txt.splitlines():
            if "publicExponent" in line:
                m = re.search(r"0x([0-9a-fA-F]+)", line)
                if m:
                    h = m.group(1)
                    if len(h) % 2:
                        h = "0" + h
                    exponent = bytes.fromhex(h)
                break

        print("openssl 生成的密钥：modulus %d 字节（%d 位），exponent %s"
              % (len(modulus), len(normalize(modulus)) * 8, exponent.hex()))

        print("\n① SPKI 结构自检")
        der = subject_public_key_info(modulus, exponent)
        m, e = parse_spki(der)
        check(normalize(m) == normalize(modulus), "解析回来的 modulus 与原值一致")
        check(normalize(e) == normalize(exponent), "解析回来的 exponent 与原值一致")
        check(len(der) == len(subject_public_key_info(modulus, exponent)), "重复构造结果稳定")

        print("\n② 前导 0x00 的容错（教务实测常带一个符号位）")
        with_one = b"\x00" + modulus
        with_two = b"\x00\x00" + modulus
        check(subject_public_key_info(with_one, exponent) == der, "多一个 0x00 时结果不变")
        check(subject_public_key_info(with_two, exponent) == der, "多两个 0x00 时结果不变")

        print("\n③ 交给 openssl 解析（真正的第三方校验）")
        der_path = os.path.join(work, "pub.der")
        with open(der_path, "wb") as f:
            f.write(der)
        res = subprocess.run(["openssl", "rsa", "-pubin", "-inform", "DER",
                              "-in", der_path, "-noout", "-modulus"],
                             capture_output=True, text=True)
        check(res.returncode == 0, "openssl 能解析这段 DER",
              res.stderr.strip().splitlines()[-1] if res.stderr else "")
        if res.returncode == 0:
            got = res.stdout.strip().split("=", 1)[1].upper()
            check(got == modulus_hex.upper(), "openssl 读出的 modulus 与原始一致")

        print("\n④ PKCS#1 v1.5 加密 → 私钥解密（确认填充与长度都对）")
        msg = "20260001".encode()
        msg_path = os.path.join(work, "msg.bin")
        enc_path = os.path.join(work, "enc.bin")
        dec_path = os.path.join(work, "dec.bin")
        with open(msg_path, "wb") as f:
            f.write(msg)
        enc = subprocess.run(["openssl", "pkeyutl", "-encrypt", "-pubin",
                              "-inkey", der_path, "-keyform", "DER",
                              "-pkeyopt", "rsa_padding_mode:pkcs1",
                              "-in", msg_path, "-out", enc_path],
                             capture_output=True, text=True)
        check(enc.returncode == 0, "用我们的 DER 公钥加密成功", enc.stderr.strip())
        if enc.returncode == 0:
            cipher = open(enc_path, "rb").read()
            check(len(cipher) == len(normalize(modulus)),
                  "密文长度等于模长（%d 字节）" % len(normalize(modulus)),
                  "实际 %d" % len(cipher))
            dec = subprocess.run(["openssl", "pkeyutl", "-decrypt", "-inkey", key,
                                  "-in", enc_path, "-out", dec_path],
                                 capture_output=True, text=True)
            check(dec.returncode == 0, "私钥解密成功", dec.stderr.strip())
            if dec.returncode == 0:
                check(open(dec_path, "rb").read() == msg, "解密后的明文与原文一致")

        print("\n⑤ base64 往返（模拟教务返回的字段形态）")
        b64m = base64.b64encode(modulus).decode()
        b64e = base64.b64encode(exponent).decode()
        check(base64.b64decode(b64m) == modulus, "modulus base64 往返一致")
        padded = b64e.rstrip("=")
        check(base64.b64decode(padded + "=" * (-len(padded) % 4)) == exponent,
              "补 '=' 后 exponent 仍可解码（Swift 侧做了同样的补齐）")

    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if failures:
        print("失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("全部通过：这套 DER 拼装能产出合法 SPKI，且 PKCS#1 v1.5 全链路可用。")


if __name__ == "__main__":
    main()
