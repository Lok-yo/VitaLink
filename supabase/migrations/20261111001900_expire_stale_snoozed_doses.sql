create or replace function public.mark_missed_doses()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update medication_doses set status = 'missed'
  where (status = 'pending' and scheduled_for < now() - interval '60 minutes')
     or (status = 'snoozed' and coalesce(snoozed_until, scheduled_for) < now() - interval '60 minutes');
  get diagnostics n = row_count;
  -- también prepara las tomas de hoy para todos los pacientes
  perform ensure_doses(id) from patients;
  return n;
end $$;
revoke execute on function public.mark_missed_doses() from public, anon, authenticated;
select public.mark_missed_doses();
