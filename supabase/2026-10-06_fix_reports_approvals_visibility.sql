-- =====================================================================
-- Fix for Official Visibility (Profiles/Reports/Requests)
-- 
-- The previous visibility fix only applied to announcements and hotlines.
-- Officials were unable to see pending account approvals (profiles),
-- reports, and requests because the RLS policies compared the normalized
-- 10-digit PSGC code against potentially unnormalized 9-digit codes in
-- the database.
--
-- This script normalizes the psgc_code in reports and requests, and
-- updates the RLS policies to use the to_modern_psgc() function for
-- comparison.
-- =====================================================================
begin;
set local client_min_messages = warning;

-- 1. normalize stored codes in reports and requests (missed in previous script)
update public.reports set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);

update public.requests set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);

-- profiles was already normalized, but just in case:
update public.profiles set psgc_code = public.to_modern_psgc(psgc_code)
 where psgc_code is not null and psgc_code is distinct from public.to_modern_psgc(psgc_code);

-- 2. update profiles RLS to ensure modern psgc match
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or (public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

drop policy if exists profiles_update on public.profiles;
create policy profiles_update on public.profiles for update to authenticated
  using      (id = auth.uid() or (public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin())
  with check (id = auth.uid() or (public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

-- 3. update reports RLS
drop policy if exists reports_select on public.reports;
create policy reports_select on public.reports for select to authenticated
  using (user_id = auth.uid() or (public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

drop policy if exists reports_update_official on public.reports;
create policy reports_update_official on public.reports for update to authenticated
  using      ((public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin())
  with check ((public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

-- 4. update requests RLS
drop policy if exists requests_select on public.requests;
create policy requests_select on public.requests for select to authenticated
  using (user_id = auth.uid() or (public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

drop policy if exists requests_update_official on public.requests;
create policy requests_update_official on public.requests for update to authenticated
  using      ((public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin())
  with check ((public.app_is_official() and public.to_modern_psgc(psgc_code) = public.app_my_psgc()) or public.app_is_super_admin());

commit;
