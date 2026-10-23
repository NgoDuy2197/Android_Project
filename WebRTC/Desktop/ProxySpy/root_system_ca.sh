#!/system/bin/sh
# ProxySpy — cai CA thanh CA he thong tren may ROOT (Android 14+ dung apex conscrypt),
# de app targetSdk cao / khong debuggable tin CA va MITM duoc.
#
# Dung:
#   adb push root_system_ca.sh /data/local/tmp/
#   adb push <hash>.0 /data/local/tmp/            # file cert da dat ten theo subject_hash_old
#   adb shell su -c 'sh /data/local/tmp/root_system_ca.sh [package_dich]'
#   # package_dich (tuy chon): tiem CA thang vao app DANG CHAY -> giu phien dang nhap,
#   #   KHONG can dong app. Vd:  sh root_system_ca.sh com.example.app
#
# AN TOAN / IDEMPOTENT:
#   - Go overlay CU truoc khi doc kho CA goc  -> khong bao gio copy nham tu bundle da bi de.
#   - Chi mount khi kho CA goc con day (>=100) va bundle moi tang so luong -> khong the nuot store.
#   - Chay lai nhieu lan / sau khi da mount san deu an toan.
# Chay lai khi reboot (bind-mount mat sau khi khoi dong lai).

set -u
SRC=$(ls /data/local/tmp/*.0 2>/dev/null | head -1)
[ -z "$SRC" ] && { echo "[!] Chua co file cert <hash>.0 trong /data/local/tmp"; exit 1; }

D=/data/local/tmp/ca-bundle
TGT_APEX=/apex/com.android.conscrypt/cacerts
TGT_SYS=/system/etc/security/cacerts

# go het overlay cu tren 1 muc tieu (global ns + tat ca zygote ns)
unmount_all() {
  t="$1"; i=0
  while grep -q " $t " /proc/self/mountinfo 2>/dev/null && [ $i -lt 20 ]; do
    umount -l "$t" 2>/dev/null || break; i=$((i+1))
  done
  for pid in $(pidof zygote64 zygote webview_zygote app_zygote 2>/dev/null); do
    j=0
    while nsenter --mount=/proc/"$pid"/ns/mnt -- grep -q " $t " /proc/self/mountinfo 2>/dev/null && [ $j -lt 20 ]; do
      nsenter --mount=/proc/"$pid"/ns/mnt -- umount -l "$t" 2>/dev/null || break; j=$((j+1))
    done
  done
}

# 1) Go overlay cu -> doc duoc KHO CA GOC that (tranh bug copy tu bundle cu)
unmount_all "$TGT_APEX"
unmount_all "$TGT_SYS"

# 2) Kiem tra kho CA goc con day khong
SYS_N=$(ls "$TGT_APEX" 2>/dev/null | wc -l)
echo "CA goc hien co: $SYS_N"
if [ "$SYS_N" -lt 100 ]; then
  echo "[!] Kho CA goc chi con $SYS_N (<100) -> dang bi hong. DUNG lai, KHONG mount de tranh pha them."
  echo "    Hay 'adb reboot' roi chay lai script nay."
  exit 1
fi

# 3) Gom CA goc + cert ProxySpy vao bundle
rm -rf "$D"; mkdir -p "$D"
cp "$TGT_APEX"/* "$D"/ 2>/dev/null
cp "$TGT_SYS"/*  "$D"/ 2>/dev/null
cp "$SRC" "$D"/
chown root:root "$D"/* 2>/dev/null
chmod 644 "$D"/*
chcon u:object_r:system_security_cacerts_file:s0 "$D"/* 2>/dev/null

NEW_N=$(ls "$D" | wc -l)
echo "Bundle moi: $NEW_N CA (= goc + ProxySpy)"
if [ "$NEW_N" -le "$SYS_N" ]; then
  echo "[!] Bundle khong tang so CA -> co loi. DUNG, khong mount."
  exit 1
fi

# 4) Bind-mount de len kho CA (global ns + zygote ns de app fork moi nhin thay)
mount -o bind "$D" "$TGT_APEX" && echo "apex bind OK" || echo "[!] apex bind FAIL"
mount -o bind "$D" "$TGT_SYS" 2>/dev/null
for pid in $(pidof zygote64 zygote webview_zygote app_zygote 2>/dev/null); do
  nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" "$TGT_APEX" 2>/dev/null && echo "zygote $pid OK"
  nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" "$TGT_SYS" 2>/dev/null
done

# 5) Tiem thang vao app DICH dang chay (neu truyen package) -> giu phien dang nhap, khong can dong app
PKG="${1:-}"
if [ -n "$PKG" ]; then
  for pid in $(pidof "$PKG" 2>/dev/null); do
    nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" "$TGT_APEX" 2>/dev/null && echo "tiem vao $PKG (pid $pid) OK"
    nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" "$TGT_SYS" 2>/dev/null
  done
fi

echo "Da nap $(basename "$SRC") vao kho CA he thong ($NEW_N CA)."
if [ -n "$PKG" ]; then
  echo "Da tiem vao $PKG dang chay -> KHONG can dong app, giu nguyen phien dang nhap."
  echo "Chi can thao tac moi trong app (vd mo lai mini-app/trang) de no tao ket noi moi."
else
  echo "Meo: chay lai voi ten package de khoi dong lai app, vd:  sh $0 com.example.app"
fi
