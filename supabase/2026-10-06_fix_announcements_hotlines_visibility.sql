-- =====================================================================
-- Barangay SuperApp - announcements / hotlines not reaching residents (2026-10-06)
--
-- Run once in Supabase > SQL Editor. Safe to re-run.
--
-- Symptom: an official and a resident with the same barangay, but the mobile app shows no
-- announcements and no emergency hotlines (the database returned 0 rows to the resident).
--
-- What this does:
--   1. Normalizes every stored psgc_code (trim + 9-digit -> 10-digit) so "1381500020",
--      " 1381500020" and "137607020" are all the same barangay.
--   2. Re-files announcements under the barangay of the official who wrote them.
--   3. Re-creates the SELECT policies so the comparison is done on the normalized code
--      (a stray space / old 9-digit code can no longer hide rows from residents).
--   4. Makes sure signed-in users actually have SELECT permission on both tables.
--   5. Prints a check at the end: every announcement / hotline and how many residents can see it.
-- =====================================================================
begin;
set local client_min_messages = warning;

-- 1. normalize stored codes ------------------------------------------------
update public.profiles           set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);
update public.announcements      set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);
update public.emergency_contacts set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);

-- 2. announcements belong to the barangay of the (non super admin) official who posted them
update public.announcements a
   set psgc_code = p.psgc_code
  from public.profiles p
 where a.author_id = p.id
   and coalesce(p.psgc_code, '') <> ''
   and lower(coalesce(p.role::text, '')) not in ('db_admin', 'super_admin', 'superadmin')
   and a.psgc_code is distinct from p.psgc_code;

-- 3. helper: my barangay, normalized ---------------------------------------
create or replace function public.app_my_psgc()
returns text
language sql stable security definer set search_path = public
as $$
  select public.to_modern_psgc(psgc_code) from public.profiles where id = auth.uid();
$$;

drop policy if exists announcements_select on public.announcements;
create policy announcements_select on public.announcements for select to authenticated
  using (psgc_code is null
         or public.to_modern_psgc(psgc_code) = public.app_my_psgc()
         or public.app_is_super_admin());

drop policy if exists contacts_select on public.emergency_contacts;
create policy contacts_select on public.emergency_contacts for select to authenticated
  using (psgc_code is null
         or public.to_modern_psgc(psgc_code) = public.app_my_psgc()
         or public.app_is_super_admin());

-- 4. permissions ------------------------------------------------------------
grant select on public.announcements      to authenticated;
grant select on public.emergency_contacts to authenticated;
grant execute on function public.to_modern_psgc(text) to authenticated;
grant execute on function public.app_my_psgc()        to authenticated;

commit;

-- 5. check: residents_who_see_it = 0 means nobody in the app can see that row
select 'announcement' as kind, a.title as name, a.psgc_code, a.is_archived, a.event_date,
       (select count(*) from public.profiles p
         where lower(coalesce(p.role::text, 'resident')) = 'resident'
           and (a.psgc_code is null or p.psgc_code = a.psgc_code)) as residents_who_see_it
  from public.announcements a
union all
select 'hotline', c.name, c.psgc_code, null, null,
       (select count(*) from public.profiles p
         where lower(coalesce(p.role::text, 'resident')) = 'resident'
           and (c.psgc_code is null or p.psgc_code = c.psgc_code))
  from public.emergency_contacts c
order by 1, 3;

-- who is in which barangay
select email, role, account_status, psgc_code from public.profiles order by psgc_code, role;
