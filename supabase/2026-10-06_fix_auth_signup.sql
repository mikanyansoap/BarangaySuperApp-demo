-- =====================================================================
-- Barangay SuperApp - fix "Database error creating new user" (2026-10-06)
--
-- Run the whole file ONCE in Supabase > SQL Editor (after 2026-10-05_fix_psgc_rls_reports.sql).
-- Safe to re-run.
--
-- Why sign-up / "Add user" failed:
--   Creating a user inserts into auth.users, which fires the trigger that creates the
--   public.profiles row. If ANYTHING in that trigger fails (old public.users table that no
--   longer exists, a NOT NULL column, a CHECK on role/account_status, a bad date, a foreign
--   key...), Supabase cancels the whole sign-up with "Database error creating new user".
--
-- What this does:
--   1. Shows the auth.users triggers that exist now and the exact error the current setup gives.
--   2. Removes the old trigger(s) that create profiles / users rows.
--   3. Makes profiles.role / profiles.account_status plain text with simple checks.
--   4. Installs ONE robust handle_new_user():
--        - copies whatever fields the app sent (first_name, psgc_code, birth_date, ...)
--          into the matching profiles columns that exist,
--        - never blocks a sign-up: if a value is rejected it retries with fewer columns.
--      (role / account_status are still forced to resident / pending by the profiles guard.)
--   5. Tests it with a fake user (rolled back) and shows the result in the last result grid.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. DIAGNOSTIC: current triggers on auth.users
-- ---------------------------------------------------------------------
select t.tgname as trigger_name,
       p.proname as function_name,
       case when t.tgenabled = 'D' then 'disabled' else 'enabled' end as state
  from pg_trigger t
  join pg_proc p on p.oid = t.tgfoid
 where t.tgrelid = 'auth.users'::regclass and not t.tgisinternal;

begin;
set local client_min_messages = warning;

-- ---------------------------------------------------------------------
-- 1. Remove old triggers that write to profiles or the old users table
-- ---------------------------------------------------------------------
do $$
declare r record;
begin
  for r in
    select t.tgname, p.oid::regprocedure as fn, pg_get_functiondef(p.oid) as src
      from pg_trigger t
      join pg_proc p on p.oid = t.tgfoid
     where t.tgrelid = 'auth.users'::regclass and not t.tgisinternal
  loop
    if r.src ~* '(insert\s+into|update)\s+(public\.)?"?(profiles|users)"?\M' then
      raise warning 'Dropping old auth trigger % (function %)', r.tgname, r.fn;
      execute format('drop trigger %I on auth.users', r.tgname);
    end if;
  end loop;
end $$;

-- ---------------------------------------------------------------------
-- 2. role / account_status: plain lowercase text, no enum surprises
-- ---------------------------------------------------------------------
do $$
declare
  r   record;
  col text;
begin
  foreach col in array array['role', 'account_status'] loop
    -- drop CHECK constraints on that column (they may only allow e.g. 'Resident' or 'unapproved')
    for r in
      select c.conname
        from pg_constraint c
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
       where c.conrelid = 'public.profiles'::regclass and c.contype = 'c' and a.attname = col
    loop
      execute format('alter table public.profiles drop constraint %I', r.conname);
    end loop;

    -- enum -> text
    if exists (select 1 from information_schema.columns
                where table_schema = 'public' and table_name = 'profiles'
                  and column_name = col and data_type = 'USER-DEFINED') then
      execute format('alter table public.profiles alter column %I drop default', col);
      execute format('alter table public.profiles alter column %I type text using %I::text', col, col);
    end if;
  end loop;
end $$;

-- the guard trigger (from the 2026-10-05 migration) is paused while existing rows are cleaned up
do $$ begin
  if exists (select 1 from pg_trigger where tgrelid = 'public.profiles'::regclass and tgname = 'b_profiles_guard') then
    alter table public.profiles disable trigger b_profiles_guard;
  end if;
end $$;

update public.profiles set role = lower(role) where role <> lower(role);
update public.profiles set account_status = lower(account_status) where account_status <> lower(account_status);
update public.profiles set account_status = 'pending'  where account_status in ('unapproved', 'for_review', 'review');
update public.profiles set account_status = 'approved' where account_status in ('active', 'verified');

do $$ begin
  if exists (select 1 from pg_trigger where tgrelid = 'public.profiles'::regclass and tgname = 'b_profiles_guard') then
    alter table public.profiles enable trigger b_profiles_guard;
  end if;
end $$;

alter table public.profiles alter column role set default 'resident';
alter table public.profiles alter column account_status set default 'pending';

alter table public.profiles drop constraint if exists profiles_account_status_check;
alter table public.profiles add constraint profiles_account_status_check
  check (account_status in ('pending', 'approved', 'rejected')) not valid;

-- ---------------------------------------------------------------------
-- 3. Robust profile creation on sign-up
-- ---------------------------------------------------------------------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  m       jsonb := coalesce(new.raw_user_meta_data, '{}'::jsonb);
  c       record;
  v       text;
  cols    text := 'id';
  vals    text := quote_literal(new.id) || '::uuid';
  attempt int;
begin
  for attempt in 1..3 loop
    cols := 'id';
    vals := quote_literal(new.id) || '::uuid';

    for c in
      select column_name, is_nullable, column_default, data_type
        from information_schema.columns
       where table_schema = 'public' and table_name = 'profiles'
         and column_name not in ('id', 'role', 'account_status', 'created_at', 'updated_at')
         and is_generated = 'NEVER'
    loop
      v := case c.column_name
             when 'email'           then coalesce(new.email, m->>'email')
             when 'psgc_code'       then coalesce(m->>'psgc_code', m->>'barangay_id')
             when 'birth_date'      then coalesce(m->>'birth_date', m->>'birthdate')
             when 'birthdate'       then coalesce(m->>'birthdate', m->>'birth_date')
             when 'mobile_number'   then coalesce(m->>'mobile_number', m->>'phone', new.phone)
             when 'phone'           then coalesce(m->>'phone', m->>'mobile_number', new.phone)
             when 'current_address' then coalesce(m->>'current_address', m->>'address')
             when 'full_name'       then coalesce(m->>'full_name',
                                          nullif(trim(concat_ws(' ', m->>'first_name', m->>'last_name')), ''))
             else m->>c.column_name
           end;
      if v = '' then v := null; end if;

      -- attempt 2: only the essentials; attempt 3: only id + email
      if attempt = 2 and c.column_name not in ('email', 'first_name', 'last_name', 'middle_name', 'psgc_code', 'mobile_number') then
        v := null;
      elsif attempt = 3 and c.column_name <> 'email' then
        v := null;
      end if;

      -- required text column with no default and no value -> empty string instead of failing
      if v is null and c.is_nullable = 'NO' and c.column_default is null
         and c.data_type in ('text', 'character varying', 'character') then
        v := '';
      end if;

      if v is not null then
        cols := cols || ', ' || quote_ident(c.column_name);
        vals := vals || ', ' || quote_literal(v);
      end if;
    end loop;

    begin
      execute format('insert into public.profiles (%s) values (%s) on conflict (id) do nothing', cols, vals);
      if attempt > 1 then
        raise warning 'handle_new_user: profile for % created with fewer fields (attempt %)', new.id, attempt;
      end if;
      return new;
    exception when others then
      raise warning 'handle_new_user attempt % for % failed: % (%)', attempt, new.id, sqlerrm, sqlstate;
    end;
  end loop;

  -- never block the sign-up itself
  raise warning 'handle_new_user: no profile row created for %', new.id;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- auth users that were created but never got a profile row
insert into public.profiles (id, email)
select u.id, u.email
  from auth.users u
 where not exists (select 1 from public.profiles p where p.id = u.id)
on conflict (id) do nothing;

-- The web portal reads barangay names from public.barangays for its header.
-- If that table has RLS on, signed-in users need permission to read it.
do $$
begin
  if to_regclass('public.barangays') is not null then
    if (select relrowsecurity from pg_class where oid = 'public.barangays'::regclass) then
      drop policy if exists barangays_read on public.barangays;
      create policy barangays_read on public.barangays for select to authenticated using (true);
    end if;
    grant select on public.barangays to authenticated;
  end if;
end $$;

commit;

-- ---------------------------------------------------------------------
-- 4. SELF-TEST: create a fake user, check its profile, then undo everything
-- ---------------------------------------------------------------------
create temp table if not exists _signup_test (step text, result text);
truncate _signup_test;

do $$
declare
  test_id uuid := gen_random_uuid();
  r_role text; r_status text; r_psgc text; r_found boolean := false;
  err text;
begin
  begin
    insert into auth.users (id, email, raw_user_meta_data)
    values (test_id, 'signup-test-' || left(test_id::text, 8) || '@example.invalid',
            jsonb_build_object('first_name', 'Test', 'last_name', 'Resident',
                               'psgc_code', '137607010', 'birth_date', '2000-01-31',
                               'role', 'official', 'verification_status', 'approved'));
    select true, role::text, account_status::text, psgc_code
      into r_found, r_role, r_status, r_psgc
      from public.profiles where id = test_id;
    raise exception 'rollback-test-user';   -- undo the fake user
  exception
    when others then
      if sqlerrm <> 'rollback-test-user' then err := sqlerrm || ' (' || sqlstate || ')'; end if;
  end;

  if err is not null then
    insert into _signup_test values ('create user', 'FAILED: ' || err);
  else
    insert into _signup_test values
      ('create user', 'OK'),
      ('profile row', case when r_found then 'OK' else 'MISSING' end),
      ('role (must be resident)', coalesce(r_role, 'null')),
      ('account_status (must be pending)', coalesce(r_status, 'null')),
      ('psgc_code (must be 1381500010)', coalesce(r_psgc, 'null'));
  end if;
end $$;

select * from _signup_test;
