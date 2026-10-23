#!/system/bin/sh
# ProxySpy — cài CA thành CA hệ thống trên máy ROOT (Android 14+ dùng apex conscrypt),
# để app targetSdk cao / không debuggable (vd FIS = com.tdc.mfu) tin CA và MITM được.
#
# Dùng:
#   adb push root_system_ca.sh /data/local/tmp/
#   adb push <hash>.0 /data/local/tmp/            # file cert đã đặt tên theo subject_hash
#   adb shell su -c 'sh /data/local/tmp/root_system_ca.sh'
#   # rồi: adb shell am force-stop com.tdc.mfu   (mở lại app)
#
# Chạy lại khi reboot (bind-mount mất sau khi khởi động lại).

set -u
SRC=$(ls /data/local/tmp/*.0 2>/dev/null | head -1)
[ -z "$SRC" ] && { echo "[!] Chưa có file cert <hash>.0 trong /data/local/tmp"; exit 1; }

D=/data/local/tmp/ca-bundle
rm -rf "$D"; mkdir -p "$D"
# gộp toàn bộ CA hệ thống hiện có + CA của ProxySpy
cp /apex/com.android.conscrypt/cacerts/* "$D"/ 2>/dev/null
cp /system/etc/security/cacerts/*        "$D"/ 2>/dev/null
cp "$SRC" "$D"/
chown root:root "$D"/* 2>/dev/null
chmod 644 "$D"/*
chcon u:object_r:system_security_cacerts_file:s0 "$D"/* 2>/dev/null

# bind-mount đè lên kho CA (namespace toàn cục)
mount -o bind "$D" /apex/com.android.conscrypt/cacerts && echo "apex bind OK" || echo "[!] apex bind FAIL"
mount -o bind "$D" /system/etc/security/cacerts 2>/dev/null

# đẩy bind-mount vào namespace của zygote để app fork mới nhìn thấy
for pid in $(pidof zygote64 zygote 2>/dev/null); do
  nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" /apex/com.android.conscrypt/cacerts 2>/dev/null && echo "zygote $pid OK"
  nsenter --mount=/proc/"$pid"/ns/mnt -- mount -o bind "$D" /system/etc/security/cacerts 2>/dev/null
done

echo "Đã nạp $(basename "$SRC") vào kho CA hệ thống."
echo "Giờ FORCE-STOP rồi mở lại app, vd:  am force-stop com.tdc.mfu"
