#!/usr/bin/env bash
# Prueba de humo del modelo de seguridad contra Supabase (crea cuentas de prueba temporales).
# Uso: scripts/auth-smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source <(grep -E "^SUPABASE_(URL|KEY)=" supabase.properties | sed 's/^/export /')
U="$SUPABASE_URL"; K="$SUPABASE_KEY"; TAG="$(date +%s)"
pass() { echo "  ✔ $1"; }
fail() { echo "  ✘ $1"; exit 1; }
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

signup() { curl -s -X POST "$U/functions/v1/sign-up" -H "apikey: $K" -H "Content-Type: application/json" -d "{\"name\":\"$1\",\"email\":\"$2\",\"password\":\"Prueba-$TAG\"}"; }
login() { curl -s -X POST "$U/auth/v1/token?grant_type=password" -H "apikey: $K" -H "Content-Type: application/json" -d "{\"email\":\"$1\",\"password\":\"$2\"}" | j "d['access_token']"; }
rest() { local tok="$1"; shift; curl -s "$@" -H "apikey: $K" -H "Authorization: Bearer $tok" -H "Content-Type: application/json"; }

echo "1. Registro sin confirmación de correo"
A="prueba.familia.$TAG@gmail.com"; B="prueba.extrano.$TAG@gmail.com"
[ "$(signup Ana "$A" | j "d['ok']")" = "True" ] && pass "cuenta de Ana creada" || fail "registro"
[ "$(signup Ana "$A" | j "d['error']")" = "Ya existe una cuenta con ese correo" ] && pass "correo duplicado rechazado" || fail "duplicado"
signup Beto "$B" >/dev/null
TA="$(login "$A" "Prueba-$TAG")"; TB="$(login "$B" "Prueba-$TAG")"
[ -n "$TA" ] && pass "Ana inicia sesión de inmediato" || fail "login"

echo "2. Crear paciente (Ana queda como dueña)"
P="$(rest "$TA" -X POST "$U/rest/v1/rpc/create_patient" -d '{"p_full_name":"Prueba","p_nickname":"Abuelo Prueba","p_age":80,"p_blood_type":null,"p_conditions":null,"p_emergency_phone":null,"p_home_lat":29.0892,"p_home_lng":-110.9613,"p_home_label":"Casa","p_safe_radius_m":250,"p_timezone":"America/Hermosillo","p_my_name":"Ana","p_my_relation":"Hija","p_my_color":"#4F6BFF","p_sample_meds":true}')"
PID="$(echo "$P" | j "d['id']")"; CODE="$(echo "$P" | j "d['invite_code']")"
pass "paciente $PID · invitación $CODE"
[ "$(rest "$TA" "$U/rest/v1/medications?select=id&patient_id=eq.$PID" | j "len(d)")" = "4" ] && pass "Ana ve sus 4 medicinas" || fail "lectura dueña"

echo "3. Aislamiento"
[ "$(rest "$K" "$U/rest/v1/patients?select=id" | j "len(d)")" = "0" ] && pass "anónimo no ve pacientes" || fail "anon ve datos"
[ "$(rest "$TB" "$U/rest/v1/medications?select=id&patient_id=eq.$PID" | j "len(d)")" = "0" ] && pass "un extraño no ve las medicinas" || fail "extraño ve datos"
OUT="$(rest "$TB" -X POST "$U/rest/v1/alerts" -d "{\"patient_id\":\"$PID\",\"type\":\"sos\",\"title\":\"falso\"}")"
echo "$OUT" | grep -q "row-level security" && pass "un extraño no puede crear alertas" || fail "extraño escribe: $OUT"
OUT="$(rest "$TB" -X POST "$U/rest/v1/rpc/claim_device" -d "{\"p_code\":\"000000\",\"p_patient\":\"$PID\"}")"
echo "$OUT" | grep -q "No perteneces" && pass "un extraño no puede vincular relojes" || fail "claim extraño: $OUT"

echo "3b. Límite de intentos para adivinar códigos"
C="prueba.adivino.$TAG@gmail.com"; signup Carlos "$C" >/dev/null; TC="$(login "$C" "Prueba-$TAG")"
for i in $(seq 1 10); do rest "$TC" -X POST "$U/rest/v1/rpc/join_circle" -d "{\"p_code\":\"00000$((i % 10))\",\"p_my_name\":\"Carlos\",\"p_my_relation\":\"Vecino\",\"p_my_color\":\"#000000\"}" >/dev/null; done
OUT="$(rest "$TC" -X POST "$U/rest/v1/rpc/join_circle" -d "{\"p_code\":\"$CODE\",\"p_my_name\":\"Carlos\",\"p_my_relation\":\"Vecino\",\"p_my_color\":\"#000000\"}")"
echo "$OUT" | grep -q "Demasiados intentos" && pass "tras 10 códigos falsos, ni el correcto funciona por una hora" || fail "rate limit: $OUT"

echo "4. Unirse con código de invitación"
rest "$TB" -X POST "$U/rest/v1/rpc/join_circle" -d "{\"p_code\":\"$CODE\",\"p_my_name\":\"Beto\",\"p_my_relation\":\"Nieto\",\"p_my_color\":\"#E8618C\"}" >/dev/null
[ "$(rest "$TB" "$U/rest/v1/medications?select=id&patient_id=eq.$PID" | j "len(d)")" = "4" ] && pass "Beto entra con el código y ya ve las medicinas" || fail "join"

echo "5. Vinculación del reloj"
S="$(curl -s -X POST "$U/functions/v1/device-pairing" -H "apikey: $K" -H "Content-Type: application/json" -d '{"action":"start"}')"
PAIR="$(echo "$S" | j "d['pairing_id']")"; SEC="$(echo "$S" | j "d['secret']")"; WCODE="$(echo "$S" | j "d['code']")"
pass "el reloj muestra el código $WCODE"
st() { curl -s -X POST "$U/functions/v1/device-pairing" -H "apikey: $K" -H "Content-Type: application/json" -d "{\"action\":\"status\",\"pairing_id\":\"$PAIR\",\"secret\":\"$1\"}"; }
[ "$(st "$SEC" | j "d['status']")" = "waiting" ] && pass "esperando a la familia" || fail "status"
[ "$(st "malo" | j "d['ok']")" = "False" ] && pass "secreto incorrecto rechazado" || fail "secreto"
rest "$TA" -X POST "$U/rest/v1/rpc/claim_device" -d "{\"p_code\":\"$WCODE\",\"p_patient\":\"$PID\"}" >/dev/null
R="$(st "$SEC")"; [ "$(echo "$R" | j "d['status']")" = "paired" ] && pass "Ana escribe el código y el reloj queda vinculado" || fail "paired: $R"
TW="$(login "$(echo "$R" | j "d['email']")" "$(echo "$R" | j "d['password']")")"
[ "$(rest "$TW" "$U/rest/v1/patients?select=nickname" | j "d[0]['nickname']")" = "Abuelo Prueba" ] && pass "el reloj inicia sesión y ve sólo a su paciente" || fail "reloj login"

echo "Todo OK. Para limpiar, ejecuta en el SQL Editor el bloque \"Borrar cuentas de prueba\" del README."
