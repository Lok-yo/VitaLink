create table public.code_attempts (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users on delete cascade,
  kind text not null check (kind in ('invite','device')),
  created_at timestamptz not null default now()
);
create index code_attempts_user_time_idx on public.code_attempts(user_id, created_at desc);
alter table public.code_attempts enable row level security;

create or replace function public.too_many_code_attempts(uid uuid, p_kind text)
returns boolean language sql stable security definer set search_path = public as $$
  select count(*) >= 10 from code_attempts
  where user_id = uid and kind = p_kind and created_at > now() - interval '1 hour'
$$;
revoke execute on function public.too_many_code_attempts(uuid, text) from public, anon, authenticated;

-- Devuelve el id del paciente o null; los fallos se registran (no se lanza error para no deshacer el registro)
drop function public.join_circle(text, text, text, text);
create function public.join_circle(p_code text, p_my_name text, p_my_relation text, p_my_color text)
returns uuid language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  p patients;
  existed boolean;
begin
  if uid is null then raise exception 'Necesitas iniciar sesión'; end if;
  if too_many_code_attempts(uid, 'invite') then raise exception 'Demasiados intentos con códigos incorrectos. Espera una hora'; end if;
  select * into p from patients where invite_code = regexp_replace(coalesce(p_code, ''), '\D', '', 'g');
  if p.id is null then
    insert into code_attempts(user_id, kind) values (uid, 'invite');
    return null;
  end if;
  select exists (select 1 from caregivers where patient_id = p.id and user_id = uid) into existed;
  insert into caregivers(patient_id, user_id, name, relation, color, role)
  values (p.id, uid, p_my_name, p_my_relation, coalesce(p_my_color, '#4F6BFF'), 'caregiver')
  on conflict (patient_id, user_id) do update set name = excluded.name, relation = excluded.relation, color = excluded.color;
  if not existed then
    insert into care_log(patient_id, author, device, kind, message)
    values (p.id, p_my_name, 'phone', 'system', format('Se unió al círculo de cuidado (%s)', p_my_relation));
  end if;
  return p.id;
end $$;

drop function public.claim_device(text, uuid);
create function public.claim_device(p_code text, p_patient uuid)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  me text;
begin
  if uid is null or not is_member(p_patient) then raise exception 'No perteneces a este círculo de cuidado'; end if;
  if too_many_code_attempts(uid, 'device') then raise exception 'Demasiados intentos con códigos incorrectos. Espera una hora'; end if;
  update device_pairings set patient_id = p_patient, claimed_by = uid, claimed_at = now()
  where code = regexp_replace(coalesce(p_code, ''), '\D', '', 'g')
    and completed_at is null and patient_id is null and expires_at > now();
  if not found then
    insert into code_attempts(user_id, kind) values (uid, 'device');
    return false;
  end if;
  select name into me from caregivers where patient_id = p_patient and user_id = uid;
  insert into care_log(patient_id, author, device, kind, message)
  values (p_patient, coalesce(me, 'Alguien'), 'phone', 'system', 'Vinculó un reloj al círculo de cuidado');
  return true;
end $$;

revoke execute on function public.join_circle(text, text, text, text) from public, anon;
revoke execute on function public.claim_device(text, uuid) from public, anon;
grant execute on function public.join_circle(text, text, text, text) to authenticated;
grant execute on function public.claim_device(text, uuid) to authenticated;
