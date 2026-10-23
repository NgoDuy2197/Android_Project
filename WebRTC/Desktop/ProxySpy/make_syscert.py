"""
Đặt tên CA theo subject_hash kiểu cũ của OpenSSL (giống `openssl x509 -subject_hash_old`)
và ghi ra file <hash>.0 (PEM) để cài làm CA hệ thống trên Android. In ra <hash>.

Dùng: python make_syscert.py ca/mitmproxy-ca-cert.pem  [thư_mục_ra]
Chỉ cần thư viện `cryptography` (đã có sẵn trong .venv của ProxySpy).
"""
import hashlib
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import serialization


def main():
    pem_path = sys.argv[1] if len(sys.argv) > 1 else "ca/mitmproxy-ca-cert.pem"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "."
    raw = open(pem_path, "rb").read()
    # chấp nhận cả PEM lẫn DER (app ProxySpy trả CA dạng DER .crt)
    try:
        cert = x509.load_pem_x509_certificate(raw)
    except Exception:
        cert = x509.load_der_x509_certificate(raw)
    pem = cert.public_bytes(serialization.Encoding.PEM)
    # subject_hash_old = 4 byte đầu của MD5(DER của Subject Name), đọc little-endian.
    der = cert.subject.public_bytes()
    d = hashlib.md5(der).digest()
    h = d[0] | (d[1] << 8) | (d[2] << 16) | (d[3] << 24)
    name = "%08x" % h
    out = os.path.join(out_dir, name + ".0")
    with open(out, "wb") as f:
        f.write(pem)
    sys.stderr.write("tao %s\n" % out)
    print(name)


if __name__ == "__main__":
    main()
