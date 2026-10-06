-- =====================================================================
-- Barangay SuperApp - storage for announcement images (2026-10-06)
--
-- Creates the public "announcement-images" bucket used by the web portal's announcement editor.
--   - anyone can VIEW the images (residents' phones load them by URL)
--   - only approved officials can upload, and only into their own barangay's folder
--     (<psgc_code>/<file>), so one barangay can't overwrite another's images
--   - images only, max 5 MB each
-- Run once in Supabase > SQL Editor. Safe to re-run.
-- =====================================================================
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('announcement-images', 'announcement-images', true, 5242880,
        array['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
on conflict (id) do update
  set public = true,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists announcement_images_read on storage.objects;
drop policy if exists announcement_images_insert on storage.objects;
drop policy if exists announcement_images_update on storage.objects;
drop policy if exists announcement_images_delete on storage.objects;

-- reading is public anyway; this also lets the upload API return the new file's details
create policy announcement_images_read on storage.objects for select
  using (bucket_id = 'announcement-images');

create policy announcement_images_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'announcement-images'
              and public.app_is_official()
              and (storage.foldername(name))[1] = public.app_my_psgc());

create policy announcement_images_update on storage.objects for update to authenticated
  using      (bucket_id = 'announcement-images' and public.app_is_official()
              and (storage.foldername(name))[1] = public.app_my_psgc())
  with check (bucket_id = 'announcement-images' and public.app_is_official()
              and (storage.foldername(name))[1] = public.app_my_psgc());

create policy announcement_images_delete on storage.objects for delete to authenticated
  using (bucket_id = 'announcement-images' and public.app_is_official()
         and (storage.foldername(name))[1] = public.app_my_psgc());

commit;

select id, public, file_size_limit, allowed_mime_types from storage.buckets where id = 'announcement-images';
