-- =====================================================================
-- Create id-photos bucket for account verification
-- =====================================================================
begin;
set local client_min_messages = warning;

insert into storage.buckets (id, name, public)
values ('id-photos', 'id-photos', true)
on conflict (id) do update set public = true;

drop policy if exists id_photos_read on storage.objects;
drop policy if exists id_photos_insert on storage.objects;
drop policy if exists id_photos_update on storage.objects;
drop policy if exists id_photos_delete on storage.objects;

-- allow anyone (or at least authenticated) to read id photos since they are used by officials to verify
create policy id_photos_read on storage.objects for select
  using (bucket_id = 'id-photos');

create policy id_photos_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'id-photos');

create policy id_photos_update on storage.objects for update to authenticated
  using      (bucket_id = 'id-photos')
  with check (bucket_id = 'id-photos');

create policy id_photos_delete on storage.objects for delete to authenticated
  using      (bucket_id = 'id-photos');

commit;
