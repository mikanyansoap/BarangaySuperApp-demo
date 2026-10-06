-- =====================================================================
-- Barangay SuperApp - super admin (db_admin) + nationwide posts (2026-10-06)
--
-- Run once in Supabase > SQL Editor (after the earlier 2026-10-05 / 2026-10-06 files). Safe to re-run.
--
-- Roles after this:
--   db_admin  = super admin. Sees and manages EVERY barangay: reports, requests, announcements,
--               emergency contacts, all users and officials (roles, approval, barangay), barangays list.
--               Does not need a psgc_code. Can post NATIONWIDE announcements / contacts
--               (psgc_code = NULL -> shown to residents of every barangay).
--   official  = (any role that is not resident / db_admin) manages only their own barangay.
--   resident  = mobile app user.
-- =====================================================================
begin;
set local client_min_messages = warning;

-- ---------------------------------------------------------------------
-- 1. Helpers
-- ---------------------------------------------------------------------
create or replace function public.app_is_super_admin()
returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
     where id = auth.uid()
       and lower(coalesce(role::text, '')) in ('db_admin', 'super_admin', 'superadmin')
       and lower(coalesce(account_status::text, 'approved')) not in ('pending', 'rejected', 'unapproved')
  );
$$;

-- "official" = approved, not a resident (db_admin counts too)
create or replace function public.app_is_official()
returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
     where id = auth.uid()
       and lower(coalesce(role::text, 'resident')) <> 'resident'
       and lower(coalesce(account_status::text, 'approved')) not in ('pending', 'rejected', 'unapproved')
  );
$$;

-- true when the signed-in user may manage rows of barangay `code`
create or replace function public.app_can_manage(code text)
returns boolean
language sql stable security definer set search_path = public
as $$
  select public.app_is_super_admin()
      or (public.app_is_official() and code is not null and code = public.app_my_psgc());
$$;

-- ---------------------------------------------------------------------
-- 2. Profiles guard: super admin may change anyone's role / approval / barangay
-- ---------------------------------------------------------------------
create or replace function public.trg_profiles_guard()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if tg_op = 'INSERT' then
    new.role := 'resident';
    new.account_status := 'pending';
    return new;
  end if;

  if uid is null then
    return new;                         -- SQL editor / service key
  end if;

  if public.app_is_super_admin() then
    if uid = old.id and lower(coalesce(new.role::text, '')) not in ('db_admin', 'super_admin', 'superadmin') then
      raise exception 'You cannot remove your own super admin role';
    end if;
    return new;
  end if;

  if new.role is distinct from old.role then
    raise exception 'Only a super admin can change a role';
  end if;

  if new.account_status is distinct from old.account_status then
    if not (uid <> old.id
            and public.app_is_official()
            and lower(coalesce(old.role::text, 'resident')) = 'resident'
            and old.psgc_code = public.app_my_psgc()) then
      raise exception 'Only an official of this barangay can approve or reject this account';
    end if;
  end if;

  -- officials can't move other people to another barangay
  if uid <> old.id and new.psgc_code is distinct from old.psgc_code then
    raise exception 'Only a super admin can move someone to another barangay';
  end if;

  if uid = old.id
     and lower(coalesce(old.role::text, 'resident')) = 'resident'
     and new.psgc_code is distinct from old.psgc_code
     and old.psgc_code is not null then
    new.account_status := 'pending';    -- resident moved: the new barangay verifies them
  end if;

  return new;
end;
$$;

-- ---------------------------------------------------------------------
-- 3. Stamping: officials always post to their own barangay; a super admin chooses
-- ---------------------------------------------------------------------
create or replace function public.trg_stamp_announcement()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  my_psgc text;
begin
  if uid is not null then
    if tg_op = 'INSERT' then new.author_id := uid; end if;
    if not public.app_is_super_admin() then
      select psgc_code into my_psgc from public.profiles where id = uid;
      if coalesce(my_psgc, '') <> '' then new.psgc_code := my_psgc; end if;
    end if;
  end if;
  if new.is_archived is null then new.is_archived := false; end if;
  return new;
end;
$$;

create or replace function public.trg_stamp_contact()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  my_psgc text;
begin
  if auth.uid() is not null and not public.app_is_super_admin() then
    select psgc_code into my_psgc from public.profiles where id = auth.uid();
    if coalesce(my_psgc, '') <> '' then new.psgc_code := my_psgc; end if;
  end if;
  return new;
end;
$$;

-- emergency contacts / announcements with no barangay = nationwide (needs psgc_code to allow NULL)
do $$
begin
  alter table public.emergency_contacts alter column psgc_code drop not null;
exception when others then null;
end $$;
do $$
begin
  alter table public.announcements alter column psgc_code drop not null;
exception when others then null;
end $$;

-- ---------------------------------------------------------------------
-- 4. Row level security (same rules as before + super admin + nationwide rows)
-- ---------------------------------------------------------------------
do $$
declare r record;
begin
  for r in
    select tablename, policyname from pg_policies
     where schemaname = 'public'
       and tablename in ('profiles', 'announcements', 'reports', 'requests', 'emergency_contacts')
  loop
    execute format('drop policy %I on public.%I', r.policyname, r.tablename);
  end loop;
end $$;

-- PROFILES
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or public.app_can_manage(psgc_code));
create policy profiles_insert_self on public.profiles for insert to authenticated
  with check (id = auth.uid());
create policy profiles_update on public.profiles for update to authenticated
  using      (id = auth.uid() or public.app_can_manage(psgc_code) or public.app_is_super_admin())
  with check (id = auth.uid() or public.app_can_manage(psgc_code) or public.app_is_super_admin());

-- ANNOUNCEMENTS: own barangay + nationwide (psgc_code null)
create policy announcements_select on public.announcements for select to authenticated
  using (psgc_code is null or psgc_code = public.app_my_psgc() or public.app_is_super_admin());
create policy announcements_write on public.announcements for all to authenticated
  using      (public.app_can_manage(psgc_code) or (psgc_code is null and public.app_is_super_admin()))
  with check (public.app_can_manage(psgc_code) or (psgc_code is null and public.app_is_super_admin()));

-- REPORTS / REQUESTS
create policy reports_select on public.reports for select to authenticated
  using (user_id = auth.uid() or public.app_can_manage(psgc_code));
create policy reports_insert on public.reports for insert to authenticated
  with check (user_id = auth.uid() and public.app_is_approved());
create policy reports_update_own_pending on public.reports for update to authenticated
  using      (user_id = auth.uid() and status = 'pending')
  with check (user_id = auth.uid() and status in ('pending', 'cancelled'));
create policy reports_update_official on public.reports for update to authenticated
  using (public.app_can_manage(psgc_code)) with check (public.app_can_manage(psgc_code));

create policy requests_select on public.requests for select to authenticated
  using (user_id = auth.uid() or public.app_can_manage(psgc_code));
create policy requests_insert on public.requests for insert to authenticated
  with check (user_id = auth.uid() and public.app_is_approved());
create policy requests_update_own_pending on public.requests for update to authenticated
  using      (user_id = auth.uid() and status = 'pending')
  with check (user_id = auth.uid() and status in ('pending', 'cancelled'));
create policy requests_update_official on public.requests for update to authenticated
  using (public.app_can_manage(psgc_code)) with check (public.app_can_manage(psgc_code));

-- EMERGENCY CONTACTS: own barangay + nationwide
create policy contacts_select on public.emergency_contacts for select to authenticated
  using (psgc_code is null or psgc_code = public.app_my_psgc() or public.app_is_super_admin());
create policy contacts_write on public.emergency_contacts for all to authenticated
  using      (public.app_can_manage(psgc_code) or (psgc_code is null and public.app_is_super_admin()))
  with check (public.app_can_manage(psgc_code) or (psgc_code is null and public.app_is_super_admin()));

-- BARANGAYS: everyone signed in reads, super admin edits
do $$
begin
  if to_regclass('public.barangays') is not null then
    alter table public.barangays enable row level security;
    drop policy if exists barangays_read on public.barangays;
    drop policy if exists barangays_admin on public.barangays;
    create policy barangays_read on public.barangays for select to authenticated using (true);
    create policy barangays_admin on public.barangays for all to authenticated
      using (public.app_is_super_admin()) with check (public.app_is_super_admin());
    grant select, insert, update, delete on public.barangays to authenticated;
  end if;
end $$;

-- ---------------------------------------------------------------------
-- 5. Announcement images: super admin may upload into any folder
-- ---------------------------------------------------------------------
do $$
begin
  if to_regclass('storage.objects') is not null then
    drop policy if exists announcement_images_insert on storage.objects;
    drop policy if exists announcement_images_update on storage.objects;
    drop policy if exists announcement_images_delete on storage.objects;
    create policy announcement_images_insert on storage.objects for insert to authenticated
      with check (bucket_id = 'announcement-images'
                  and (public.app_is_super_admin()
                       or (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())));
    create policy announcement_images_update on storage.objects for update to authenticated
      using (bucket_id = 'announcement-images'
             and (public.app_is_super_admin()
                  or (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())));
    create policy announcement_images_delete on storage.objects for delete to authenticated
      using (bucket_id = 'announcement-images'
             and (public.app_is_super_admin()
                  or (public.app_is_official() and (storage.foldername(name))[1] = public.app_my_psgc())));
  end if;
end $$;

-- ---------------------------------------------------------------------
-- 6. "approved" is a valid status too (e.g. typed by hand in the Supabase table editor)
--    statuses are always stored lowercase by the triggers
-- ---------------------------------------------------------------------
alter table public.reports  drop constraint if exists reports_status_check;
alter table public.reports  add  constraint reports_status_check
  check (status in ('pending', 'approved', 'in_progress', 'resolved', 'rejected', 'cancelled')) not valid;
alter table public.requests drop constraint if exists requests_status_check;
alter table public.requests add  constraint requests_status_check
  check (status in ('pending', 'approved', 'in_progress', 'ready_for_pickup', 'resolved', 'rejected', 'cancelled')) not valid;

commit;

-- check
select email, role, account_status, psgc_code,
       case when lower(role) in ('db_admin', 'super_admin', 'superadmin') then 'SUPER ADMIN'
            when lower(role) <> 'resident' then 'official' else 'resident' end as access
  from public.profiles order by access, email;
