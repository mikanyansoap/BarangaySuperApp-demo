-- =====================================================================
-- Barangay SuperApp - remove the duplicate profiles.barangay_id column (2026-10-06)
--
-- barangay_id held the same barangay code as psgc_code, in the old 9-digit format.
-- psgc_code (10-digit) is the only barangay field the app, portal and database use now.
-- Run once in Supabase > SQL Editor. Safe to re-run.
-- =====================================================================
begin;

do $$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'profiles' and column_name = 'barangay_id') then

    -- keep the code for anyone who only had barangay_id filled in
    -- (the guard trigger is paused so this admin fix doesn't reset anyone to "pending")
    if exists (select 1 from pg_trigger where tgrelid = 'public.profiles'::regclass and tgname = 'b_profiles_guard') then
      alter table public.profiles disable trigger b_profiles_guard;
    end if;

    update public.profiles
       set psgc_code = public.to_modern_psgc(barangay_id::text)
     where coalesce(psgc_code, '') = ''
       and coalesce(barangay_id::text, '') <> '';

    if exists (select 1 from pg_trigger where tgrelid = 'public.profiles'::regclass and tgname = 'b_profiles_guard') then
      alter table public.profiles enable trigger b_profiles_guard;
    end if;

    alter table public.profiles drop column barangay_id;
    raise notice 'profiles.barangay_id dropped';
  end if;
end $$;

commit;

-- check: everyone's barangay
select email, role, account_status, psgc_code from public.profiles order by email;
