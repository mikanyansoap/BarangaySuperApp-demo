begin;

-- Drop existing contacts_select policy
drop policy if exists contacts_select on public.emergency_contacts;

-- Create the new select policy that supports CITY: and REGION: scopes
create policy contacts_select on public.emergency_contacts for select to authenticated
  using (
    psgc_code is null
    or public.to_modern_psgc(psgc_code) = public.app_my_psgc()
    or (psgc_code like 'CITY:%' and exists (
         select 1 from public.barangays b 
         where b.psgc_code = public.app_my_psgc() 
           and b.city_municipality = substring(emergency_contacts.psgc_code from 6)
       ))
    or (psgc_code like 'REGION:%' and exists (
         select 1 from public.barangays b 
         where b.psgc_code = public.app_my_psgc() 
           and b.region = substring(emergency_contacts.psgc_code from 8)
       ))
    or public.app_is_super_admin()
  );

commit;
