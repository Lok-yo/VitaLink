#!/usr/bin/env bash
# Compila ambos módulos, instala cada APK en su emulador, concede permisos y abre las apps.
# Uso: scripts/deploy.sh [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2" ]; then
  export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"
fi

# Detecta qué emulador es teléfono (sdk_gphone) y cuál reloj (sdk_gwear)
PHONE="$(adb devices -l | awk '/sdk_gphone/ {print $1; exit}')"
WATCH="$(adb devices -l | awk '/sdk_gwear/ {print $1; exit}')"
[ -n "$PHONE" ] || { echo "No hay emulador de teléfono (sdk_gphone) conectado"; exit 1; }
[ -n "$WATCH" ] || { echo "No hay emulador de reloj (sdk_gwear) conectado"; exit 1; }
echo "Teléfono: $PHONE · Reloj: $WATCH"

if [ "${1:-}" != "--no-build" ]; then
  ./gradlew assembleDebug --console=plain -q
fi

adb -s "$PHONE" install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s "$WATCH" install -r wear/build/outputs/apk/debug/wear-debug.apk

adb -s "$PHONE" shell pm grant com.vitalink.mobile android.permission.POST_NOTIFICATIONS || true
for p in BODY_SENSORS ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do
  adb -s "$WATCH" shell pm grant com.vitalink.wear "android.permission.$p" || true
done

adb -s "$PHONE" shell am start -n com.vitalink.mobile/.MainActivity
adb -s "$WATCH" shell am start -n com.vitalink.wear/.MainActivity
echo "Listo. Logs en vivo: adb -s $PHONE logcat -s VitaLink-Sync  |  adb -s $WATCH logcat -s VitaLink-Sync VitaLink-Watch"
