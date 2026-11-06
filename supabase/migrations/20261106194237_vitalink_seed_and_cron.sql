insert into public.patients (id, full_name, nickname, age, blood_type, conditions, emergency_phone, timezone, home_lat, home_lng, home_label, safe_radius_m, step_goal, pair_code)
values ('a1e5d000-0000-4000-a000-000000000001', 'Rosa María Hernández', 'Abuela Rosa', 79, 'O+', 'Hipertensión · Diabetes tipo 2', '+526621234567', 'America/Hermosillo', 29.0892, -110.9613, 'Casa de Rosa', 250, 4000, 'ROSA79');

insert into public.caregivers (patient_id, name, relation, color, phone) values
('a1e5d000-0000-4000-a000-000000000001', 'Mariana', 'Hija', '#E8618C', '+526629876543'),
('a1e5d000-0000-4000-a000-000000000001', 'Dr. Salas', 'Médico', '#2BB673', '+526621112233');

insert into public.vitals_live (patient_id, bpm, steps, battery, lat, lng, distance_home_m, in_safe_zone, status, activity, source, sensor_mode)
values ('a1e5d000-0000-4000-a000-000000000001', 72, 1240, 86, 29.0892, -110.9613, 0, true, 'ok', 'reposo', 'seed', 'simulado');

insert into public.medications (patient_id, name, dosage, instructions, schedule_times, color, created_by) values
('a1e5d000-0000-4000-a000-000000000001', 'Losartán', '50 mg', 'Con un vaso de agua', '{08:00,20:00}', '#7C5CFF', 'Mariana'),
('a1e5d000-0000-4000-a000-000000000001', 'Metformina', '850 mg', 'Después de comer', '{09:00,15:00}', '#FF8A3D', 'Dr. Salas'),
('a1e5d000-0000-4000-a000-000000000001', 'Vitamina D3', '1000 UI', 'Con el almuerzo', '{13:00}', '#F5B700', 'Mariana'),
('a1e5d000-0000-4000-a000-000000000001', 'Atorvastatina', '20 mg', 'Antes de dormir', '{21:30}', '#18A0FB', 'Dr. Salas');

insert into public.care_log (patient_id, author, device, kind, message)
values ('a1e5d000-0000-4000-a000-000000000001', 'VitaLink', 'cloud', 'system', 'Se creó el círculo de cuidado de Abuela Rosa');

create or replace function public.mark_missed_doses()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update medication_doses set status = 'missed'
  where status = 'pending' and scheduled_for < now() - interval '60 minutes'
    and scheduled_for > now() - interval '1 day';
  get diagnostics n = row_count;
  -- también prepara las tomas de hoy para todos los pacientes
  perform ensure_doses(id) from patients;
  return n;
end $$;

create extension if not exists pg_cron;
select cron.schedule('vitalink-missed-doses', '* * * * *', $$select public.mark_missed_doses()$$);
