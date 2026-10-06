-- Fix infinite recursion or RLS block for officials viewing residents
create or replace function public.app_is_official() returns boolean as $$
  select (current_setting('request.jwt.claims', true)::json->>'role') in ('official', 'Official')
      or exists(select 1 from public.profiles where id = auth.uid() and role in ('official', 'Official') and account_status = 'approved');
$$ language sql stable security definer set search_path = public;

create or replace function public.app_my_psgc() returns text as $$
  select psgc_code from public.profiles where id = auth.uid() limit 1;
$$ language sql stable security definer set search_path = public;

create or replace function public.app_can_manage(code text) returns boolean as $$
  select public.app_is_super_admin()
      or (public.app_is_official() and code is not null and code = public.app_my_psgc());
$$ language sql stable security definer set search_path = public;

-- Drop and recreate profiles policy to ensure it's not recursive
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or public.app_can_manage(psgc_code));
