create table public.patients (
  id uuid primary key default gen_random_uuid(),
  full_name text not null,
  nickname text not null,
  age int,
  blood_type text,
  conditions text,
  emergency_phone text,
  timezone text not null default 'America/Hermosillo',
  home_lat double precision not null,
  home_lng double precision not null,
  home_label text not null default 'Casa',
  safe_radius_m int not null default 250,
  step_goal int not null default 4000,
  pair_code text unique,
  created_at timestamptz not null default now()
);

create table public.caregivers (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references public.patients on delete cascade,
  name text not null,
  relation text not null,
  color text not null default '#4F7CFF',
  phone text,
  device_id text unique,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);
create index caregivers_patient_idx on public.caregivers(patient_id);

create table public.vitals_live (
  patient_id uuid primary key references public.patients on delete cascade,
  bpm int,
  steps int not null default 0,
  battery int,
  charging boolean not null default false,
  lat double precision,
  lng double precision,
  distance_home_m int,
  in_safe_zone boolean not null default true,
  status text not null default 'ok' check (status in ('ok','attention','emergency')),
  activity text,
  source text not null default 'watch',
  sensor_mode text,
  updated_at timestamptz not null default now()
);

create table public.vitals_history (
  id bigint generated always as identity primary key,
  patient_id uuid not null references public.patients on delete cascade,
  bpm int,
  steps int,
  battery int,
  recorded_at timestamptz not null default now()
);
create index vitals_history_patient_time_idx on public.vitals_history(patient_id, recorded_at desc);

create table public.medications (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references public.patients on delete cascade,
  name text not null,
  dosage text not null,
  instructions text,
  schedule_times text[] not null default '{}',
  color text not null default '#7C5CFF',
  active boolean not null default true,
  created_by text,
  created_at timestamptz not null default now()
);
create index medications_patient_idx on public.medications(patient_id);

create table public.medication_doses (
  id uuid primary key default gen_random_uuid(),
  medication_id uuid not null references public.medications on delete cascade,
  patient_id uuid not null references public.patients on delete cascade,
  scheduled_for timestamptz not null,
  status text not null default 'pending' check (status in ('pending','snoozed','taken','skipped','missed')),
  snoozed_until timestamptz,
  snooze_count int not null default 0,
  confirmed_by text,
  confirmed_device text,
  confirmed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (medication_id, scheduled_for)
);
create index medication_doses_patient_time_idx on public.medication_doses(patient_id, scheduled_for);

create table public.alerts (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references public.patients on delete cascade,
  type text not null check (type in ('sos','fall','tachycardia','bradycardia','geofence','medication','battery')),
  severity text not null default 'critical' check (severity in ('info','warning','critical')),
  status text not null default 'active' check (status in ('active','acknowledged','resolved','cancelled')),
  title text not null,
  message text,
  bpm int,
  lat double precision,
  lng double precision,
  source text not null default 'watch',
  acknowledged_by text,
  acknowledged_at timestamptz,
  eta_minutes int,
  resolved_by text,
  resolved_at timestamptz,
  created_at timestamptz not null default now()
);
create index alerts_patient_time_idx on public.alerts(patient_id, created_at desc);

create table public.care_log (
  id bigint generated always as identity primary key,
  patient_id uuid not null references public.patients on delete cascade,
  author text not null,
  device text not null,
  kind text not null check (kind in ('medication','alert','note','vitals','system')),
  message text not null,
  created_at timestamptz not null default now()
);
create index care_log_patient_time_idx on public.care_log(patient_id, created_at desc);

-- Genera las tomas del día (idempotente) usando la zona horaria del paciente
create or replace function public.ensure_doses(p_patient uuid, p_day date default null)
returns int language plpgsql security definer set search_path = public as $$
declare
  tz text;
  d date;
  n int;
begin
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

create or replace function public.tg_medication_doses_today()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  perform ensure_doses(new.patient_id);
  return new;
end $$;

create trigger medications_ensure_doses
after insert or update of schedule_times, active on public.medications
for each row execute function public.tg_medication_doses_today();

-- Bitácora automática del lado del servidor: una sola entrada por cambio, venga de donde venga
create or replace function public.tg_log_dose()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  med record;
  hora text;
  tz text;
begin
  if new.status = old.status and coalesce(new.snoozed_until, 'epoch') = coalesce(old.snoozed_until, 'epoch') then
    return new;
  end if;
  select name, dosage into med from medications where id = new.medication_id;
  select timezone into tz from patients where id = new.patient_id;
  hora := to_char(new.scheduled_for at time zone tz, 'HH24:MI');
  if new.status = 'taken' then
    insert into care_log(patient_id, author, device, kind, message)
    values (new.patient_id, coalesce(new.confirmed_by, 'Alguien'), coalesce(new.confirmed_device, 'phone'), 'medication',
            format('Confirmó la toma de %s %s (%s)', med.name, med.dosage, hora));
  elsif new.status = 'snoozed' then
    insert into care_log(patient_id, author, device, kind, message)
    values (new.patient_id, coalesce(new.confirmed_by, 'Alguien'), coalesce(new.confirmed_device, 'watch'), 'medication',
            format('Pospuso %s %s hasta las %s', med.name, med.dosage, to_char(new.snoozed_until at time zone tz, 'HH24:MI')));
  elsif new.status = 'skipped' then
    insert into care_log(patient_id, author, device, kind, message)
    values (new.patient_id, coalesce(new.confirmed_by, 'Alguien'), coalesce(new.confirmed_device, 'phone'), 'medication',
            format('Omitió %s %s (%s)', med.name, med.dosage, hora));
  elsif new.status = 'missed' then
    insert into care_log(patient_id, author, device, kind, message)
    values (new.patient_id, 'VitaLink', 'cloud', 'medication',
            format('No se confirmó %s %s de las %s', med.name, med.dosage, hora));
  end if;
  return new;
end $$;

create trigger medication_doses_log
after update on public.medication_doses
for each row execute function public.tg_log_dose();

create or replace function public.tg_log_alert()
returns trigger language plpgsql security definer set search_path = public as $$
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
                     case when new.eta_minutes is not null then format(' (llega en ~%s min)', new.eta_minutes) else '' end));
    elsif new.status = 'resolved' then
      insert into care_log(patient_id, author, device, kind, message)
      values (new.patient_id, coalesce(new.resolved_by, 'Alguien'), 'phone', 'alert', format('Resolvió: %s', new.title));
    elsif new.status = 'cancelled' then
      insert into care_log(patient_id, author, device, kind, message)
      values (new.patient_id, coalesce(new.resolved_by, 'Paciente'), 'watch', 'alert', format('Canceló: %s (falsa alarma)', new.title));
    end if;
  end if;
  return new;
end $$;

create trigger alerts_log
after insert or update on public.alerts
for each row execute function public.tg_log_alert();

-- RLS habilitado con políticas abiertas para la demo (clave anon)
do $$
declare t text;
begin
  foreach t in array array['patients','caregivers','vitals_live','vitals_history','medications','medication_doses','alerts','care_log'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy "demo_all_%s" on public.%I for all to anon, authenticated using (true) with check (true)', t, t);
  end loop;
end $$;

-- Realtime (Postgres Changes)
alter publication supabase_realtime add table public.patients, public.caregivers, public.vitals_live, public.medications, public.medication_doses, public.alerts, public.care_log;
alter table public.alerts replica identity full;
alter table public.medication_doses replica identity full;
