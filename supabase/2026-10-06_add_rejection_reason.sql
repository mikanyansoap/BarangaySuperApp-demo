begin;

-- Add rejection_reason to profiles
alter table public.profiles
  add column if not exists rejection_reason text;

-- Add rejection_reason to requests
alter table public.requests
  add column if not exists rejection_reason text;

-- Add rejection_reason to reports
alter table public.reports
  add column if not exists rejection_reason text;

commit;
