-- =====================================================================
-- Barangay SuperApp - Supabase migration (2026-10-05)
--
-- Run the whole file ONCE in Supabase > SQL Editor. It is safe to re-run.
--
-- What it does
--   1. Adds the columns the web portal and the mobile app both need
--      (reports.latitude/longitude/location_label, announcements.is_archived, ...).
--   2. ONE barangay code format everywhere: the 10-digit PSGC code
--      (the one psgc.cloud returns). Old 9-digit codes are converted
--      automatically on every insert/update, and existing rows are converted now.
--   3. The server decides who you are, not the app:
--        - new accounts always start as role='resident', account_status='pending'
--          (residents can no longer approve themselves)
--        - only officials of the SAME barangay can approve/reject residents
--        - nobody can change their own role
--        - reports/requests get user_id and psgc_code from the signed-in user's profile
--        - announcements get psgc_code/author_id from the official who posts them
--          (this is what makes announcements reach residents of the same barangay)
--   4. Replaces the row level security (RLS) policies on
--      profiles, announcements, reports, requests, emergency_contacts with one clean set.
--
-- Officials = profiles whose role is anything other than 'resident' (e.g. 'official', 'admin')
-- and whose account_status is not pending/rejected.
-- =====================================================================

begin;
set local client_min_messages = warning;

-- ---------------------------------------------------------------------
-- 1. COLUMNS
-- ---------------------------------------------------------------------
alter table public.profiles      add column if not exists role            text default 'resident';
alter table public.profiles      add column if not exists account_status  text default 'pending';
alter table public.profiles      add column if not exists psgc_code       text;

alter table public.announcements add column if not exists psgc_code   text;
alter table public.announcements add column if not exists author_id   uuid;
alter table public.announcements add column if not exists type        text;
alter table public.announcements add column if not exists category    text;
alter table public.announcements add column if not exists body        text;
alter table public.announcements add column if not exists event_date  date;
alter table public.announcements add column if not exists is_archived boolean not null default false;

alter table public.reports add column if not exists user_id         uuid;
alter table public.reports add column if not exists psgc_code       text;
alter table public.reports add column if not exists category        text;
alter table public.reports add column if not exists description     text;
alter table public.reports add column if not exists photo_url       text;
alter table public.reports add column if not exists priority        text default 'medium';
alter table public.reports add column if not exists status          text default 'pending';
alter table public.reports add column if not exists internal_notes  text;
alter table public.reports add column if not exists latitude        double precision;
alter table public.reports add column if not exists longitude       double precision;
alter table public.reports add column if not exists location_label  text;
alter table public.reports add column if not exists created_at      timestamptz default now();
alter table public.reports add column if not exists updated_at      timestamptz default now();

alter table public.requests add column if not exists user_id          uuid;
alter table public.requests add column if not exists psgc_code        text;
alter table public.requests add column if not exists status           text default 'pending';
alter table public.requests add column if not exists location_address text;
alter table public.requests add column if not exists latitude         double precision;
alter table public.requests add column if not exists longitude        double precision;
alter table public.requests add column if not exists pickup_date      date;
alter table public.requests add column if not exists admin_remarks    text;
alter table public.requests add column if not exists internal_notes   text;
alter table public.requests add column if not exists created_at       timestamptz default now();
alter table public.requests add column if not exists updated_at       timestamptz default now();

alter table public.emergency_contacts add column if not exists psgc_code    text;
alter table public.emergency_contacts add column if not exists scope        text;
alter table public.emergency_contacts add column if not exists phone_number text;

-- Old check constraints may only allow UPPERCASE values; new ones are added in step 6.
alter table public.reports  drop constraint if exists reports_priority_check;
alter table public.reports  drop constraint if exists reports_status_check;
alter table public.requests  drop constraint if exists requests_status_check;

-- Old Spring entity used reports.resident_id; copy it into user_id if that column exists.
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'reports' and column_name = 'resident_id') then
    execute $q$update public.reports
               set user_id = resident_id::text::uuid
             where user_id is null
               and resident_id::text ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'$q$;
  end if;
end $$;

-- Older app versions only wrote the map pin into the description ("Map pin: 14.123456, 121.123456").
update public.reports
   set latitude  = (regexp_match(description, 'Map pin:\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)'))[1]::double precision,
       longitude = (regexp_match(description, 'Map pin:\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)'))[2]::double precision
 where latitude is null
   and description ~ 'Map pin:\s*-?\d+(\.\d+)?,\s*-?\d+(\.\d+)?';

-- ---------------------------------------------------------------------
-- 2. ONE PSGC FORMAT (10 digits)
--    Same mapping as PSGCClient.toModernCode() in the Android app.
--    e.g. Napindan, Taguig: 137607010 -> 1381500010
-- ---------------------------------------------------------------------
create or replace function public.to_modern_psgc(code text)
returns text
language plpgsql
immutable
as $$
declare
  c    text := btrim(coalesce(code, ''));
  p6   text;
  brgy text;
  ncr  text;
begin
  if c = '' then return null; end if;
  if c ~ '^\d{10}$' then return c; end if;
  if c !~ '^\d{9}$' then return c; end if;          -- unknown format: leave it alone

  brgy := substr(c, 7, 3);
  if left(c, 2) = '13' then                           -- NCR was re-coded
    p6 := left(c, 6);
    if p6 = '137606' then return '1381701' || brgy; end if;                     -- Pateros
    if left(c, 4) = '1339' then return '13806' || substr(c, 5, 2) || brgy; end if; -- Manila districts
    ncr := case p6
      when '137501' then '13801'  -- Caloocan
      when '137601' then '13802'  -- Las Pinas
      when '137602' then '13803'  -- Makati
      when '137502' then '13804'  -- Malabon
      when '137401' then '13805'  -- Mandaluyong
      when '137402' then '13807'  -- Marikina
      when '137603' then '13808'  -- Muntinlupa
      when '137503' then '13809'  -- Navotas
      when '137604' then '13810'  -- Paranaque
      when '137605' then '13811'  -- Pasay
      when '137403' then '13812'  -- Pasig
      when '137404' then '13813'  -- Quezon City
      when '137405' then '13814'  -- San Juan
      when '137607' then '13815'  -- Taguig
      when '137504' then '13816'  -- Valenzuela
    end;
    return coalesce(ncr || '00' || brgy, c);
  end if;

  return left(c, 2) || '0' || substr(c, 3);           -- provinces: RR PP MM BBB -> RR 0PP MM BBB
end;
$$;

create or replace function public.trg_normalize_psgc()
returns trigger
language plpgsql
as $$
begin
  if new.psgc_code is not null then
    new.psgc_code := public.to_modern_psgc(new.psgc_code);
  end if;
  return new;
end;
$$;

do $$
declare t text;
begin
  foreach t in array array['profiles', 'announcements', 'reports', 'requests', 'emergency_contacts'] loop
    execute format('drop trigger if exists a_normalize_psgc on public.%I', t);
    execute format('create trigger a_normalize_psgc before insert or update on public.%I
                    for each row execute function public.trg_normalize_psgc()', t);
    -- convert what is already stored
    execute format($q$update public.%I set psgc_code = public.to_modern_psgc(psgc_code)
                      where psgc_code ~ '^\d{9}$'$q$, t);
  end loop;

  -- profiles that only have the old barangay_id column filled in
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'profiles' and column_name = 'barangay_id') then
    execute $q$update public.profiles
               set psgc_code = public.to_modern_psgc(barangay_id::text)
             where coalesce(psgc_code, '') = '' and coalesce(barangay_id::text, '') <> ''$q$;
  end if;
end $$;

-- Announcements posted under a wrong / placeholder code (e.g. 'BRGY-001'):
-- move them to the barangay of the official who wrote them.
update public.announcements a
   set psgc_code = p.psgc_code
  from public.profiles p
 where a.author_id = p.id
   and coalesce(p.psgc_code, '') <> ''
   and a.psgc_code is distinct from p.psgc_code;

-- ---------------------------------------------------------------------
-- 3. HELPERS (security definer = they can read profiles without RLS recursion)
-- ---------------------------------------------------------------------
create or replace function public.app_my_psgc()
returns text
language sql stable security definer set search_path = public
as $$
  select psgc_code from public.profiles where id = auth.uid();
$$;

create or replace function public.app_is_approved()
returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
     where id = auth.uid()
       and lower(coalesce(account_status::text, 'approved')) not in ('pending', 'rejected', 'unapproved')
  );
$$;

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

-- ---------------------------------------------------------------------
-- 4. PROFILES GUARD
--    - INSERT: every new account is a pending resident, whatever the app sends.
--    - UPDATE: role can only be changed from the SQL editor / service key.
--              account_status only by an official of the same barangay.
--              A resident who moves to another barangay goes back to pending,
--              so the new barangay verifies them.
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
    return new; -- SQL editor or service_role key: allowed to do anything
  end if;

  if new.role is distinct from old.role then
    raise exception 'Only an administrator can change a role';
  end if;

  if new.account_status is distinct from old.account_status then
    if not (uid <> old.id
            and public.app_is_official()
            and old.psgc_code = public.app_my_psgc()) then
      raise exception 'Only an official of this barangay can approve or reject this account';
    end if;
  end if;

  if uid = old.id
     and lower(coalesce(old.role::text, 'resident')) = 'resident'
     and new.psgc_code is distinct from old.psgc_code
     and old.psgc_code is not null then
    new.account_status := 'pending';
  end if;

  return new;
end;
$$;

drop trigger if exists b_profiles_guard on public.profiles;
create trigger b_profiles_guard
  before insert or update on public.profiles
  for each row execute function public.trg_profiles_guard();

-- ---------------------------------------------------------------------
-- 5. SERVER-SIDE STAMPING
-- ---------------------------------------------------------------------

-- reports / requests: owner + barangay come from the signed-in resident's profile
create or replace function public.trg_stamp_submission()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  my_psgc text;
begin
  if new.status is not null then new.status := lower(new.status); end if;

  if tg_op = 'INSERT' then
    if uid is not null then
      new.user_id := uid;
      select psgc_code into my_psgc from public.profiles where id = uid;
      if coalesce(my_psgc, '') <> '' then new.psgc_code := my_psgc; end if;
    end if;
    if new.status is null then new.status := 'pending'; end if;
    new.created_at := coalesce(new.created_at, now());
  end if;

  new.updated_at := now();
  return new;
end;
$$;

create or replace function public.trg_lower_priority()
returns trigger
language plpgsql
as $$
begin
  if new.priority is not null then new.priority := lower(new.priority); end if;
  return new;
end;
$$;

drop trigger if exists b_stamp_submission on public.reports;
create trigger b_stamp_submission before insert or update on public.reports
  for each row execute function public.trg_stamp_submission();
drop trigger if exists c_lower_priority on public.reports;
create trigger c_lower_priority before insert or update on public.reports
  for each row execute function public.trg_lower_priority();

drop trigger if exists b_stamp_submission on public.requests;
create trigger b_stamp_submission before insert or update on public.requests
  for each row execute function public.trg_stamp_submission();

-- announcements: barangay + author come from the official who posts
create or replace function public.trg_stamp_announcement()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  my_psgc text;
begin
  if uid is not null then
    select psgc_code into my_psgc from public.profiles where id = uid;
    if coalesce(my_psgc, '') <> '' then new.psgc_code := my_psgc; end if;
    if tg_op = 'INSERT' then new.author_id := uid; end if;
  end if;
  if new.is_archived is null then new.is_archived := false; end if;
  return new;
end;
$$;

drop trigger if exists b_stamp_announcement on public.announcements;
create trigger b_stamp_announcement before insert or update on public.announcements
  for each row execute function public.trg_stamp_announcement();

-- same idea for emergency contacts
create or replace function public.trg_stamp_contact()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  my_psgc text;
begin
  if auth.uid() is not null then
    select psgc_code into my_psgc from public.profiles where id = auth.uid();
    if coalesce(my_psgc, '') <> '' then new.psgc_code := my_psgc; end if;
  end if;
  return new;
end;
$$;

drop trigger if exists b_stamp_contact on public.emergency_contacts;
create trigger b_stamp_contact before insert or update on public.emergency_contacts
  for each row execute function public.trg_stamp_contact();

-- ---------------------------------------------------------------------
-- 6. ONE SET OF STATUS / PRIORITY VALUES (lowercase)
--    NOT VALID = old rows are not re-checked, only new writes.
-- ---------------------------------------------------------------------
update public.reports  set priority = lower(priority) where priority <> lower(priority);
update public.reports  set status   = lower(status)   where status   <> lower(status);
update public.requests set status   = lower(status)   where status   <> lower(status);

alter table public.reports add  constraint reports_priority_check
  check (priority is null or priority in ('low', 'medium', 'high')) not valid;

alter table public.reports add  constraint reports_status_check
  check (status in ('pending', 'in_progress', 'resolved', 'rejected', 'cancelled')) not valid;

alter table public.requests add  constraint requests_status_check
  check (status in ('pending', 'in_progress', 'ready_for_pickup', 'resolved', 'rejected', 'cancelled')) not valid;

-- ---------------------------------------------------------------------
-- 7. ROW LEVEL SECURITY - drop the old policies, create one clean set
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

alter table public.profiles           enable row level security;
alter table public.announcements      enable row level security;
alter table public.reports            enable row level security;
alter table public.requests           enable row level security;
alter table public.emergency_contacts enable row level security;

-- PROFILES: you see yourself; officials see the people in their barangay
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or (public.app_is_official() and psgc_code = public.app_my_psgc()));

create policy profiles_insert_self on public.profiles for insert to authenticated
  with check (id = auth.uid());

create policy profiles_update on public.profiles for update to authenticated
  using      (id = auth.uid() or (public.app_is_official() and psgc_code = public.app_my_psgc()))
  with check (id = auth.uid() or (public.app_is_official() and psgc_code = public.app_my_psgc()));

-- ANNOUNCEMENTS: everyone signed in sees their own barangay's (+ global ones with no code);
-- only officials of that barangay write
create policy announcements_select on public.announcements for select to authenticated
  using (psgc_code is null or psgc_code = public.app_my_psgc());

create policy announcements_write on public.announcements for all to authenticated
  using      (public.app_is_official() and psgc_code = public.app_my_psgc())
  with check (public.app_is_official() and psgc_code = public.app_my_psgc());

-- REPORTS + REQUESTS
--   residents: insert (approved only), see their own, edit/cancel their own while still pending
--   officials: see and update everything in their barangay
create policy reports_select on public.reports for select to authenticated
  using (user_id = auth.uid() or (public.app_is_official() and psgc_code = public.app_my_psgc()));
create policy reports_insert on public.reports for insert to authenticated
  with check (user_id = auth.uid() and public.app_is_approved());
create policy reports_update_own_pending on public.reports for update to authenticated
  using      (user_id = auth.uid() and status = 'pending')
  with check (user_id = auth.uid() and status in ('pending', 'cancelled'));
create policy reports_update_official on public.reports for update to authenticated
  using      (public.app_is_official() and psgc_code = public.app_my_psgc())
  with check (public.app_is_official() and psgc_code = public.app_my_psgc());

create policy requests_select on public.requests for select to authenticated
  using (user_id = auth.uid() or (public.app_is_official() and psgc_code = public.app_my_psgc()));
create policy requests_insert on public.requests for insert to authenticated
  with check (user_id = auth.uid() and public.app_is_approved());
create policy requests_update_own_pending on public.requests for update to authenticated
  using      (user_id = auth.uid() and status = 'pending')
  with check (user_id = auth.uid() and status in ('pending', 'cancelled'));
create policy requests_update_official on public.requests for update to authenticated
  using      (public.app_is_official() and psgc_code = public.app_my_psgc())
  with check (public.app_is_official() and psgc_code = public.app_my_psgc());

-- EMERGENCY CONTACTS
create policy contacts_select on public.emergency_contacts for select to authenticated
  using (psgc_code is null or psgc_code = public.app_my_psgc());
create policy contacts_write on public.emergency_contacts for all to authenticated
  using      (public.app_is_official() and psgc_code = public.app_my_psgc())
  with check (public.app_is_official() and psgc_code = public.app_my_psgc());

commit;

-- =====================================================================
-- AFTER RUNNING: make sure every official has a role + the real barangay code.
-- Example (replace the email and the 10-digit PSGC code):
--
--   update public.profiles
--      set role = 'official', account_status = 'approved', psgc_code = '1381500010'
--    where email = 'kapitan@example.com';
--
-- Quick check - who is an official and for which barangay:
--   select email, role, account_status, psgc_code from public.profiles
--    where lower(coalesce(role::text, 'resident')) <> 'resident';
-- =====================================================================
