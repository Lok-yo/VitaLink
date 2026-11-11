import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

// Vinculación del reloj: el reloj pide un código, un familiar lo reclama desde su teléfono
// (RPC claim_device) y entonces el reloj recibe una cuenta propia limitada a ese paciente.
const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode(): string {
  const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  return n.toString().padStart(6, "0");
}

function randomSecret(bytes = 32): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(bytes))).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function start() {
  await admin.from("device_pairings").delete().is("completed_at", null).lt("expires_at", new Date().toISOString());
  const secret = randomSecret();
  const secretHash = await sha256(secret);
  for (let attempt = 0; attempt < 6; attempt++) {
    const code = randomCode();
    const { data, error } = await admin
      .from("device_pairings")
      .insert({ code, secret_hash: secretHash })
      .select("id, code, expires_at")
      .single();
    if (!error && data) return json({ ok: true, pairing_id: data.id, code: data.code, secret, expires_at: data.expires_at });
    if (error && !/duplicate|unique/i.test(error.message)) return json({ ok: false, error: error.message }, 500);
  }
  return json({ ok: false, error: "No se pudo generar un código, intenta de nuevo" }, 500);
}

async function status(pairingId: string, secret: string) {
  const { data: row, error } = await admin.from("device_pairings").select("*").eq("id", pairingId).maybeSingle();
  if (error) return json({ ok: false, error: error.message }, 500);
  if (!row || row.secret_hash !== (await sha256(secret))) return json({ ok: false, error: "Vinculación no encontrada" }, 404);

  if (!row.patient_id) {
    const expired = new Date(row.expires_at).getTime() < Date.now();
    return json({ ok: true, status: expired ? "expired" : "waiting", expires_at: row.expires_at });
  }
  if (row.completed_at && Date.now() - new Date(row.completed_at).getTime() > 60 * 60 * 1000) {
    return json({ ok: false, error: "Esta vinculación ya se usó; genera un código nuevo" }, 410);
  }

  const { data: patient } = await admin.from("patients").select("id, nickname").eq("id", row.patient_id).single();
  const email = `reloj-${row.id}@watch.vitalink.app`;
  const password = randomSecret(24);
  let deviceUserId: string | null = row.device_user_id;

  if (!deviceUserId) {
    const { data: created, error: createError } = await admin.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
      user_metadata: { name: patient?.nickname ?? "Reloj", device: true },
    });
    if (createError || !created.user) return json({ ok: false, error: createError?.message ?? "No se pudo crear el reloj" }, 500);
    deviceUserId = created.user.id;
    const { error: memberError } = await admin.from("caregivers").upsert(
      {
        patient_id: row.patient_id,
        user_id: deviceUserId,
        name: patient?.nickname ?? "Paciente",
        relation: "Paciente",
        color: "#0E9F8E",
        role: "device",
      },
      { onConflict: "patient_id,user_id" },
    );
    if (memberError) return json({ ok: false, error: memberError.message }, 500);
    await admin.from("device_pairings")
      .update({ device_user_id: deviceUserId, completed_at: new Date().toISOString() })
      .eq("id", row.id);
  } else {
    // El reloj no recibió la respuesta anterior: se le entrega una contraseña nueva
    const { error: updateError } = await admin.auth.admin.updateUserById(deviceUserId, { password });
    if (updateError) return json({ ok: false, error: updateError.message }, 500);
  }

  return json({ ok: true, status: "paired", email, password, patient_id: row.patient_id, nickname: patient?.nickname });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: false, error: "Método no permitido" }, 405);
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Solicitud inválida" }, 400);
  }
  if (body.action === "start") return await start();
  if (body.action === "status") return await status(String(body.pairing_id ?? ""), String(body.secret ?? ""));
  return json({ ok: false, error: "Acción desconocida" }, 400);
});
