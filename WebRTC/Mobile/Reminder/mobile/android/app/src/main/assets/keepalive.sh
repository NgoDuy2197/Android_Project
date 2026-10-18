#!/system/bin/sh
# keepalive.sh <package> <receiver-component> [interval-seconds]
# Runs as the shell user (adb / Shizuku), detached from the app, so it survives
# ColorOS force-stopping the app when it is swiped away. Whenever the package is
# in the "stopped" state (alarms/jobs cancelled) it sends an explicit broadcast
# with FLAG_INCLUDE_STOPPED_PACKAGES, which clears the stopped state and lets the
# app re-register its alarms. Exits when another copy replaces its pidfile.
PKG=$1; RCV=$2; INT=${3:-30}
PIDF=/data/local/tmp/keepalive_$PKG.pid
old=$(cat $PIDF 2>/dev/null); [ -n "$old" ] && kill $old 2>/dev/null
echo $$ > $PIDF
log -t KeepAlive "start $PKG pid=$$ every ${INT}s"
while [ "$(cat $PIDF 2>/dev/null)" = "$$" ]; do
  if dumpsys package $PKG | grep "User 0:" | grep -q "stopped=true"; then
    log -t KeepAlive "$PKG was force-stopped -> wake"
    am broadcast -f 0x20 -n $RCV -a $PKG.KEEPALIVE >/dev/null 2>&1
  fi
  sleep $INT
done
log -t KeepAlive "exit $PKG pid=$$"