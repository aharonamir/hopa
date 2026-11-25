-- Optional admin device inventory: every device across every household, with
-- the owning account's phone for the console's masked column.
--
-- Same posture as V5: FORCE RLS binds the table owner too, so this read is a
-- SECURITY DEFINER function owned by the NOLOGIN BYPASSRLS `hopa_admin` role.
-- The app role never bypasses a policy; it only gains EXECUTE. The limit keeps
-- a runaway inventory from rendering an unbounded page.

create function admin_list_all_devices()
returns table (
    device_id uuid,
    household_id uuid,
    platform text,
    name text,
    last_seen_at timestamptz,
    revoked_at timestamptz,
    phone text
)
language sql
security definer
set search_path = public, pg_temp
as $$
    select d.id, d.household_id, d.platform, d.name, d.last_seen_at, d.revoked_at, a.phone
    from devices d
    left join members m on m.id = d.member_id
    left join accounts a on a.id = m.account_id and a.deleted_at is null
    order by d.last_seen_at desc nulls last, d.created_at desc
    limit 500
$$;

revoke all on function admin_list_all_devices() from public;

do $$
begin
    execute format('grant execute on function admin_list_all_devices() to %I', current_user);
    if exists (select from pg_roles where rolname = 'hopa_app') then
        grant execute on function admin_list_all_devices() to hopa_app;
    end if;
end
$$;

-- Temporary membership, held only while the ownership changes are made. The
-- new owner must have CREATE on the object's schema, so that privilege is
-- borrowed for the duration and taken back before the migration ends. V5's
-- runtime check then still holds: no non-superuser may SET into hopa_admin.
grant hopa_admin to current_user;
grant create on schema public to hopa_admin;
alter function admin_list_all_devices() owner to hopa_admin;
revoke create on schema public from hopa_admin;
revoke hopa_admin from current_user;
