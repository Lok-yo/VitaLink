import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

// Registro sin correo de confirmación: crea la cuenta ya confirmada con la clave de servicio.
const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: false, error: "Método no permitido" }, 405);
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Solicitud inválida" }, 400);
  }
  const email = String(body.email ?? "").trim().toLowerCase();
  const password = String(body.password ?? "");
  const name = String(body.name ?? "").trim();

  if (name.length < 2) return json({ ok: false, error: "Escribe tu nombre" });
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email)) return json({ ok: false, error: "Ese correo no es válido" });
  if (password.length < 8) return json({ ok: false, error: "La contraseña debe tener al menos 8 caracteres" });

  const { error } = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    user_metadata: { name },
  });
  if (error) {
    const exists = /already|registered|exists/i.test(error.message);
    return json({ ok: false, error: exists ? "Ya existe una cuenta con ese correo" : error.message });
  }
  return json({ ok: true });
});
