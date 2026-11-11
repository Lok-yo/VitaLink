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
                     case when new.eta_minutes = 0 then ' (ya está con el paciente)'
                          when new.eta_minutes is not null then format(' (llega en ~%s min)', new.eta_minutes)
                          else '' end));
    elsif new.status = 'resolved' then
      insert into care_log(patient_id, author, device, kind, message)
      values (new.patient_id, coalesce(new.resolved_by, 'Alguien'), case when new.resolved_by = 'Abuela Rosa' then 'watch' else 'phone' end, 'alert', format('Resolvió: %s', new.title));
    elsif new.status = 'cancelled' then
      insert into care_log(patient_id, author, device, kind, message)
      values (new.patient_id, coalesce(new.resolved_by, 'Paciente'), 'watch', 'alert', format('Canceló: %s (falsa alarma)', new.title));
    end if;
  end if;
  return new;
end $$;
