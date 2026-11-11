-- Empezar de cero: cada familia crea su propio paciente
truncate table public.care_log, public.alerts, public.medication_doses, public.medications,
  public.vitals_history, public.vitals_live, public.caregivers, public.patients restart identity cascade;

alter table public.patients rename column pair_code to invite_code;
alter table public.patients add column created_by uuid references auth.users on delete set null;

-- caregivers pasa a ser la membresía del círculo de cuidado
alter table public.caregivers drop constraint if exists caregivers_device_id_key;
alter table public.caregivers drop column device_id;
alter table public.caregivers add column user_id uuid not null references auth.users on delete cascade;
alter table public.caregivers add column role text not null default 'caregiver' check (role in ('owner','caregiver','device'));
alter table public.caregivers add constraint caregivers_patient_user_key unique (patient_id, user_id);
create index caregivers_user_idx on public.caregivers(user_id);

-- Vinculación de relojes por código (sólo la usan funciones con privilegios)
create table public.device_pairings (
  id uuid primary key default gen_random_uuid(),
  code text not null,
  secret_hash text not null,
  patient_id uuid references public.patients on delete cascade,
  claimed_by uuid references auth.users on delete set null,
  device_user_id uuid references auth.users on delete set null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '10 minutes',
  claimed_at timestamptz,
  completed_at timestamptz
);
create unique index device_pairings_open_code_idx on public.device_pairings(code) where completed_at is null;
alter table public.device_pairings enable row level security;

create or replace function public.is_member(p uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from caregivers where patient_id = p and user_id = auth.uid())
$$;

-- Reglas de acceso por círculo
do $$
declare t text;
begin
  foreach t in array array['patients','caregivers','vitals_live','vitals_history','medications','medication_doses','alerts','care_log'] loop
    execute format('drop policy if exists "demo_all_%s" on public.%I', t, t);
  end loop;
  foreach t in array array['vitals_live','vitals_history','medications','medication_doses','alerts','care_log'] loop
    execute format('create policy "members_all_%s" on public.%I for all to authenticated using (public.is_member(patient_id)) with check (public.is_member(patient_id))', t, t);
  end loop;
end $$;

create policy "members_read_patients" on public.patients for select to authenticated using (public.is_member(id));
create policy "members_update_patients" on public.patients for update to authenticated using (public.is_member(id)) with check (public.is_member(id));

create policy "members_read_caregivers" on public.caregivers for select to authenticated using (public.is_member(patient_id));
create policy "self_update_caregiver" on public.caregivers for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "self_leave_caregiver" on public.caregivers for delete to authenticated using (user_id = (select auth.uid()));

-- Realtime privado: sólo miembros del círculo pueden escuchar o enviar en care-<patient_id>
create policy "vitalink_members_receive" on realtime.messages for select to authenticated
  using (case when realtime.topic() ~ '^care-[0-9a-f-]{36}$' then public.is_member(substr(realtime.topic(), 6)::uuid) else false end);
create policy "vitalink_members_send" on realtime.messages for insert to authenticated
  with check (case when realtime.topic() ~ '^care-[0-9a-f-]{36}$' then public.is_member(substr(realtime.topic(), 6)::uuid) else false end);

-- ensure_doses: sólo miembros (o procesos internos sin usuario, como pg_cron)
create or replace function public.ensure_doses(p_patient uuid, p_day date default null)
returns int language plpgsql security definer set search_path = public as $$
declare
  tz text;
  d date;
  n int;
begin
  if auth.uid() is not null and not is_member(p_patient) then return 0; end if;
  select timezone into tz from patients where id = p_patient;
  if tz is null then return 0; end if;
  d := coalesce(p_day, (now() at time zone tz)::date);
  insert into medication_doses(medication_id, patient_id, scheduled_for)
  select m.id, m.patient_id, ((d + t::time) at time zone tz)
  from medications m, unnest(m.schedule_times) t
  where m.patient_id = p_patient and m.active
  on conflict (medication_id, scheduled_for) do nothing;
  get diagnostics n = row_count;
  return n;
end $$;

create or replace function public.create_patient(
  p_full_name text, p_nickname text, p_age int, p_blood_type text, p_conditions text, p_emergency_phone text,
  p_home_lat double precision, p_home_lng double precision, p_home_label text, p_safe_radius_m int, p_timezone text,
  p_my_name text, p_my_relation text, p_my_color text, p_sample_meds boolean default false)
returns public.patients language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  p patients;
  code text;
begin
  if uid is null then raise exception 'Necesitas iniciar sesión'; end if;
  if coalesce(trim(p_nickname), '') = '' then raise exception 'Escribe cómo le dicen a tu familiar'; end if;
  loop
    code := lpad((floor(random() * 1000000))::int::text, 6, '0');
    exit when not exists (select 1 from patients where invite_code = code);
  end loop;
  insert into patients(full_name, nickname, age, blood_type, conditions, emergency_phone, timezone,
                       home_lat, home_lng, home_label, safe_radius_m, invite_code, created_by)
  values (coalesce(nullif(trim(p_full_name), ''), trim(p_nickname)), trim(p_nickname), p_age, nullif(trim(p_blood_type), ''),
          nullif(trim(p_conditions), ''), nullif(trim(p_emergency_phone), ''), coalesce(nullif(p_timezone, ''), 'America/Hermosillo'),
          p_home_lat, p_home_lng, coalesce(nullif(trim(p_home_label), ''), 'Casa'), coalesce(p_safe_radius_m, 250), code, uid)
  returning * into p;
  insert into caregivers(patient_id, user_id, name, relation, color, role)
  values (p.id, uid, p_my_name, p_my_relation, coalesce(p_my_color, '#4F6BFF'), 'owner');
  insert into vitals_live(patient_id, steps, lat, lng, distance_home_m, source)
  values (p.id, 0, p.home_lat, p.home_lng, 0, 'none');
  insert into care_log(patient_id, author, device, kind, message)
  values (p.id, p_my_name, 'phone', 'system', format('Creó el círculo de cuidado de %s', p.nickname));
  if p_sample_meds then
    insert into medications(patient_id, name, dosage, instructions, schedule_times, color, created_by) values
      (p.id, 'Losartán', '50 mg', 'Con un vaso de agua', '{08:00,20:00}', '#7C5CFF', p_my_name),
      (p.id, 'Metformina', '850 mg', 'Después de comer', '{09:00,15:00}', '#FF8A3D', p_my_name),
      (p.id, 'Vitamina D3', '1000 UI', 'Con el almuerzo', '{13:00}', '#F5B700', p_my_name),
      (p.id, 'Atorvastatina', '20 mg', 'Antes de dormir', '{21:30}', '#18A0FB', p_my_name);
  end if;
  return p;
end $$;

-- join_circle y claim_device se reemplazan en la migración code_attempt_rate_limit
create or replace function public.join_circle(p_code text, p_my_name text, p_my_relation text, p_my_color text)
returns public.patients language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  p patients;
  existed boolean;
begin
  if uid is null then raise exception 'Necesitas iniciar sesión'; end if;
  select * into p from patients where invite_code = regexp_replace(coalesce(p_code, ''), '\D', '', 'g');
  if p.id is null then raise exception 'No encontramos ese código de invitación'; end if;
  select exists (select 1 from caregivers where patient_id = p.id and user_id = uid) into existed;
  insert into caregivers(patient_id, user_id, name, relation, color, role)
  values (p.id, uid, p_my_name, p_my_relation, coalesce(p_my_color, '#4F6BFF'), 'caregiver')
  on conflict (patient_id, user_id) do update set name = excluded.name, relation = excluded.relation, color = excluded.color;
  if not existed then
    insert into care_log(patient_id, author, device, kind, message)
    values (p.id, p_my_name, 'phone', 'system', format('Se unió al círculo de cuidado (%s)', p_my_relation));
  end if;
  return p;
end $$;

create or replace function public.claim_device(p_code text, p_patient uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  me text;
begin
  if uid is null or not is_member(p_patient) then raise exception 'No perteneces a este círculo de cuidado'; end if;
  update device_pairings set patient_id = p_patient, claimed_by = uid, claimed_at = now()
  where code = regexp_replace(coalesce(p_code, ''), '\D', '', 'g')
    and completed_at is null and patient_id is null and expires_at > now();
  if not found then raise exception 'Código de reloj inválido o vencido'; end if;
  select name into me from caregivers where patient_id = p_patient and user_id = uid;
  insert into care_log(patient_id, author, device, kind, message)
  values (p_patient, coalesce(me, 'Alguien'), 'phone', 'system', 'Vinculó un reloj al círculo de cuidado');
end $$;

-- Bitácora de alertas: el dispositivo se deduce del rol del autor
create or replace function public.tg_log_alert()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  dev text;
begin
  if tg_op = 'INSERT' then
    insert into care_log(patient_id, author, device, kind, message)
    values (new.patient_id, case when new.source = 'watch' then 'Reloj' else 'Simulador' end, new.source, 'alert',
            format('ALERTA: %s', new.title));
  elsif new.status is distinct from old.status then
    if new.status = 'acknowledged' then
      insert into care_log(patient_id, author, device, kind, message)
      values (new.patient_id, coalesce(new.acknowledged_by, 'Alguien'), 'phone', 'alert',
              format('Atiende la emergencia: %s%s', new.title,
                     case when new.eta_minutes = 0 then ' (ya está con el paciente)'
                          when new.eta_minutes is not null then format(' (llega en ~%s min)', new.eta_minutes)
                          else '' end));
    else
      select case when role = 'device' then 'watch' else 'phone' end into dev
      from caregivers where patient_id = new.patient_id and name = new.resolved_by limit 1;
      if new.status = 'resolved' then
        insert into care_log(patient_id, author, device, kind, message)
        values (new.patient_id, coalesce(new.resolved_by, 'Alguien'), coalesce(dev, 'phone'), 'alert', format('Resolvió: %s', new.title));
      elsif new.status = 'cancelled' then
        insert into care_log(patient_id, author, device, kind, message)
        values (new.patient_id, coalesce(new.resolved_by, 'Paciente'), coalesce(dev, 'watch'), 'alert', format('Canceló: %s (falsa alarma)', new.title));
      end if;
    end if;
  end if;
  return new;
end $$;

-- Permisos: nada para anónimos, RPCs sólo para usuarios con sesión
revoke execute on function public.tg_log_alert() from public, anon, authenticated;
revoke execute on function public.ensure_doses(uuid, date) from public, anon;
revoke execute on function public.is_member(uuid) from public, anon;
revoke execute on function public.create_patient(text, text, int, text, text, text, double precision, double precision, text, int, text, text, text, text, boolean) from public, anon;
revoke execute on function public.join_circle(text, text, text, text) from public, anon;
revoke execute on function public.claim_device(text, uuid) from public, anon;
grant execute on function public.ensure_doses(uuid, date) to authenticated;
grant execute on function public.is_member(uuid) to authenticated;
grant execute on function public.create_patient(text, text, int, text, text, text, double precision, double precision, text, int, text, text, text, text, boolean) to authenticated;
grant execute on function public.join_circle(text, text, text, text) to authenticated;
grant execute on function public.claim_device(text, uuid) to authenticated;
