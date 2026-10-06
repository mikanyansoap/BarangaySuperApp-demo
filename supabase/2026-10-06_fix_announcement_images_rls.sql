begin;

-- Create the bucket properly if it doesn't exist
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('announcement-images', 'announcement-images', true, 5242880,
        array['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
on conflict (id) do update
  set public = true,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- Drop existing policies
drop policy if exists announcement_images_read on storage.objects;
drop policy if exists announcement_images_insert on storage.objects;
drop policy if exists announcement_images_update on storage.objects;
drop policy if exists announcement_images_delete on storage.objects;

-- Recreate policies, this time allowing super admins to manage 'nationwide' folder
create policy announcement_images_read on storage.objects for select
  using (bucket_id = 'announcement-images');

create policy announcement_images_insert on storage.objects for insert to authenticated
  with check (
    bucket_id = 'announcement-images'
    and (
      (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())
      or
      (public.app_is_super_admin() and (storage.foldername(name))[1] = 'nationwide')
    )
  );

create policy announcement_images_update on storage.objects for update to authenticated
  using (
    bucket_id = 'announcement-images'
    and (
      (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())
      or
      (public.app_is_super_admin() and (storage.foldername(name))[1] = 'nationwide')
    )
  )
  with check (
    bucket_id = 'announcement-images'
    and (
      (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())
      or
      (public.app_is_super_admin() and (storage.foldername(name))[1] = 'nationwide')
    )
  );

create policy announcement_images_delete on storage.objects for delete to authenticated
  using (
    bucket_id = 'announcement-images'
    and (
      (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())
      or
      (public.app_is_super_admin() and (storage.foldername(name))[1] = 'nationwide')
    )
  );

commit;
