-- Schema v5: the member and household resolvers also return the account id.
--
-- The console lookup page renders phone, verified and account status for a
-- lookup by member id or household id, but V2's `admin_resolve_member` and
-- `admin_resolve_household` returned only `(household_id, member_id,
-- display_name)`, and `members` is invisible to `hopa_app` without a tenant
-- GUC. Both functions are therefore recreated with a fourth column
-- `account_id uuid` (the member's `account_id`), keeping the same SECURITY
-- DEFINER posture, pinned search_path, revoke/grant to `hopa_app`, and the
-- same temporary-ownership dance and residual-capability guards as V2.
-- `admin_resolve_phone` (which already returns `account_id`) and
-- `admin_list_devices` are unchanged.
--
-- The drop below requires the same provisioning as V2: run this migration as a
-- superuser, or as a non-superuser deploy role that holds `hopa_admin` WITH
-- ADMIN OPTION (SET enabled) for the duration of the migration, so it can drop
-- and re-own the two `hopa_admin`-owned functions.

drop function admin_resolve_member(uuid);
drop function admin_resolve_household(uuid);

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

revoke all on function admin_resolve_member(uuid) from public;
revoke all on function admin_resolve_household(uuid) from public;
grant execute on function admin_resolve_member(uuid) to hopa_app;
grant execute on function admin_resolve_household(uuid) to hopa_app;

-- The same temporary-ownership dance as V2: borrow membership in `hopa_admin`
-- and CREATE on the schema just long enough to change the owners, then take
-- both back before the migration ends.
grant hopa_admin to current_user;
grant create on schema public to hopa_admin;
alter function admin_resolve_member(uuid) owner to hopa_admin;
alter function admin_resolve_household(uuid) owner to hopa_admin;
revoke create on schema public from hopa_admin;
revoke hopa_admin from current_user;

-- Runtime invariant, repeated from V2: no non-superuser may still be able to
-- SET ROLE into (or re-grant) the BYPASSRLS owner once the migration ends.
do $$
declare
    has_admin_option boolean;
begin
    if not (select rolsuper from pg_roles where rolname = current_user) then
        if pg_has_role(current_user, 'hopa_admin', 'set') then
            raise exception
                'the deploy role can still SET ROLE to the BYPASSRLS owner. As a superuser: revoke hopa_admin from %;', current_user;
        end if;
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
