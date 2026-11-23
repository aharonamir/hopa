-- Optional admin lookup provisioning. This file lives outside db/migration and
-- is applied through its own Flyway history table only when the deployment
-- has a superuser (HOPA_ADMIN_LOOKUPS=true). The core V2 schema must apply
-- first; this migration then adds the BYPASSRLS owner and its four lookups.

-- Admin console lookups. FORCE RLS binds the table owner too, so a
-- SECURITY DEFINER function owned by the migration role would read no tenant
-- rows. These four functions are therefore owned by `hopa_admin`, a NOLOGIN
-- role with BYPASSRLS that only these functions ever run as; the app role
-- itself never bypasses a policy. Each function returns only the columns the
-- lookup page renders, and callers audit-log every phone-number read.
--
-- Production provisioning (creating or altering a BYPASSRLS role requires a
-- superuser; CREATEROLE alone is not enough):
--   create role hopa_admin nologin bypassrls;
-- Run this migration as that superuser, or as a non-superuser deploy role that
-- holds `hopa_admin` WITH ADMIN OPTION, SET FALSE for the duration of the
-- migration and has it revoked afterwards. The temporary grant below adds SET
-- only to this migration's own grant row; a pre-existing grant from another
-- grantor survives the revoke. If a non-superuser deploy role still has SET on
-- `hopa_admin` when the migration ends, the check at the bottom fails here,
-- loudly, rather than leaving a SQL-injection route to the BYPASSRLS owner.
-- `ALTER ... OWNER` also requires CREATE on schema `public` (or ownership of
-- it), so that privilege is borrowed below as well.
do $$
begin
    if not exists (select from pg_roles where rolname = 'hopa_admin') then
        begin
            create role hopa_admin nologin bypassrls;
        exception
            when insufficient_privilege then
                raise exception
                    'admin lookups need hopa_admin, and only a superuser can create a BYPASSRLS role. Run as a superuser: create role hopa_admin nologin bypassrls;';
        end;
    end if;
end
$$;

-- A pre-created role with the wrong attributes would silently deny every admin
-- lookup (or allow a login). Refuse to migrate instead.
do $$
declare
    can_login boolean;
    bypasses_rls boolean;
begin
    select rolcanlogin, rolbypassrls into can_login, bypasses_rls
    from pg_roles
    where rolname = 'hopa_admin';
    if can_login or not bypasses_rls then
        raise exception
            'hopa_admin must be NOLOGIN BYPASSRLS. As a superuser: alter role hopa_admin nologin bypassrls;';
    end if;
end
$$;

-- BYPASSRLS bypasses policies, not privileges: the function owner still needs
-- schema usage and SELECT on every table the lookups touch.
grant usage on schema public to hopa_admin;
grant select on accounts, members, devices, households to hopa_admin;

create function admin_resolve_member(member uuid)
returns table (household_id uuid, member_id uuid, display_name text, account_id uuid)
language sql
security definer
set search_path = public, pg_temp
as $$
    select m.household_id, m.id, m.display_name, m.account_id
    from members m
    where m.id = $1
$$;

create function admin_resolve_phone(phone text)
returns table (household_id uuid, member_id uuid, display_name text, account_id uuid)
language sql
security definer
set search_path = public, pg_temp
as $$
    select m.household_id, m.id, m.display_name, a.id
    from accounts a
    join members m on m.account_id = a.id
    where a.phone = $1
      and a.deleted_at is null
    order by m.created_at
    limit 1
$$;

create function admin_resolve_household(household uuid)
returns table (household_id uuid, member_id uuid, display_name text, account_id uuid)
language sql
security definer
set search_path = public, pg_temp
as $$
    select m.household_id, m.id, m.display_name, m.account_id
    from members m
    where m.household_id = $1
      and m.role = 'owner'
    order by m.created_at, m.id
    limit 1
$$;

create function admin_list_devices(household uuid)
returns table (
    device_id uuid,
    platform text,
    name text,
    last_seen_at timestamptz,
    revoked_at timestamptz
)
language sql
security definer
set search_path = public, pg_temp
as $$
    select d.id, d.platform, d.name, d.last_seen_at, d.revoked_at
    from devices d
    where d.household_id = $1
    order by d.created_at
$$;

revoke all on function admin_resolve_member(uuid) from public;
revoke all on function admin_resolve_phone(text) from public;
revoke all on function admin_resolve_household(uuid) from public;
revoke all on function admin_list_devices(uuid) from public;

-- The migration role is the deploy role; the app may connect as the same role
-- or as the dedicated hopa_app role used by the test harness and render.yaml.
do $$
begin
    execute format('grant execute on function admin_resolve_member(uuid) to %I', current_user);
    execute format('grant execute on function admin_resolve_phone(text) to %I', current_user);
    execute format('grant execute on function admin_resolve_household(uuid) to %I', current_user);
    execute format('grant execute on function admin_list_devices(uuid) to %I', current_user);
    if exists (select from pg_roles where rolname = 'hopa_app') then
        grant execute on function admin_resolve_member(uuid) to hopa_app;
        grant execute on function admin_resolve_phone(text) to hopa_app;
        grant execute on function admin_resolve_household(uuid) to hopa_app;
        grant execute on function admin_list_devices(uuid) to hopa_app;
    end if;
end
$$;

-- Temporary membership, held only while the ownership changes are made. The
-- new owner must have CREATE on the object's schema, so that privilege is also
-- borrowed for the duration and taken back before the migration ends.
grant hopa_admin to current_user;
grant create on schema public to hopa_admin;
alter function admin_resolve_member(uuid) owner to hopa_admin;
alter function admin_resolve_phone(text) owner to hopa_admin;
alter function admin_resolve_household(uuid) owner to hopa_admin;
alter function admin_list_devices(uuid) owner to hopa_admin;
revoke create on schema public from hopa_admin;

-- The migration role's own grant is gone after this line, but a grant made by
-- another grantor (for example the operator's bootstrap grant) is not removed
-- by it. The invariant that matters is narrower and is checked below: no
-- non-superuser may be able to SET ROLE into the BYPASSRLS owner.
revoke hopa_admin from current_user;

-- Runtime invariant: "no SET capability for a non-superuser", not "no
-- membership". Superusers are exempt because they can already do anything (and
-- Postgres reports SET for them universally). A failure here rolls the whole
-- migration back and names the operator line that removes the residual grant.
do $$
declare
    has_admin_option boolean;
begin
    if not (select rolsuper from pg_roles where rolname = current_user) then
        if pg_has_role(current_user, 'hopa_admin', 'set') then
            raise exception
                'the deploy role can still SET ROLE to the BYPASSRLS owner. As a superuser: revoke hopa_admin from %;', current_user;
        end if;
        -- ADMIN OPTION is not SET, but it still lets this role re-grant the
        -- BYPASSRLS owner to itself later. Refuse to finish the migration with
        -- that residual capability.
        select exists (
            select 1
            from pg_auth_members m
            join pg_roles r on r.oid = m.roleid
            join pg_roles member on member.oid = m.member
            where r.rolname = 'hopa_admin'
              and member.rolname = current_user
              and m.admin_option
        ) into has_admin_option;
        if has_admin_option then
            raise exception
                'the deploy role still holds ADMIN OPTION on the BYPASSRLS owner. As a superuser: revoke admin option for hopa_admin from %;', current_user;
        end if;
    end if;
end
$$;
