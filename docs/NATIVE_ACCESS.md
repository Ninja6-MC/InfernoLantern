# Native Permissions and Session Override

The approved `/infernolantern override on|off|status` command and its `/ilantern` alias
are available to connected players with `infernolantern.admin.override`. Help and tab
completion expose the command only to those players. The admin parent and its existing
permission defaults still apply. The command reports mode and scope privately.

Each connection starts with override off. An enabled mode is local to one connection.
Turning it off invalidates outstanding actions that depended on the previous activation,
including after turning it back on. Quit, successful kick, and plugin shutdown remove the
connection. Revocation observed by an owner check clears the mode and invalidates its old
activation. Re-granting the permission after that check requires a fresh `override on`.
Permission checks use the current Paper player on the owner thread.

## Action eligibility

Native actions require `infernolantern.use`, default true, and the optional `permission`
in the current or retained variant definition. A variant without that setting has no
additional eligibility check. Player pickup and storage withdrawal continue under their
existing rules. Every native action also needs its applicable world, fluid, reach,
structure and external protection checks.

| Action | Ordinary eligibility | Scope of enabled override |
| --- | --- | --- |
| Place | Current portable identity, active variant, and placement conditions | None |
| Expand | Compact base, active variant, HP threshold, and elapsed expansion cooldown | May bypass the expansion cooldown |
| Compact | Expanded tower and current controller | May bypass the controller restriction |
| Break compact | Compact base and ordinary break protection | None |
| Recover expanded directly | Expanded tower and explicit enabled override | Permits the recovery action, subject to external protection |
| Change light or defence preference | Expanded tower, current controller, active variant, and available capability | May bypass the controller restriction |
| Repair | Compact base or current controller of an expanded tower, with active variant, elapsed repair cooling and current payment conditions | May bypass the expanded controller restriction |

Recovery and compaction can use retained definitions for retired variants. Placement,
expansion, switches and repair require active definitions. Ordinary break remains a
compact-only action for every player. Independent integrity, eligibility, clearance,
HP, protection, repair cooling and payment checks apply to override actions. Successful
expansion records the expander as controller through the structure transaction, and
compaction and expanded recovery clear that controller through the same transaction.

## Owner callbacks and audit

A future native router captures `OverrideSessions.Session` on the Paper owner thread and
uses it with `PaperNativeAccess`, the same `CatalogService` and the existing repository
owner. A structure grant binds the exact request, before state, publication, connection
and any required override activation. Pass that grant as `PaperStructureEffects.Access`
and use its session supplier. The adapter rechecks it at preparation, application,
verification and activation. Once the verified effects are journaled, the service repeats
owner authorization before submitting the durable completion. Managed structures also
check the catalog revision inside the SQLite completion transaction. The owner callback
is the permission decision point. A permission or session change after database
completion can still prevent later activation and retain quarantine.

The router must supply `PhysicalAccess` for the actual target and full action footprint.
The callback evaluates current protections, fluid/reach, and independent conditions,
including repair payment where applicable. Restoration is a separate callback purpose.
If restoration is denied, the structure adapter leaves changed terrain and prior visual
state in quarantine. It can still discard its exact unavailable staging objects. An
eligible admin receives a private hint for a restriction the toggle can resolve.

`PaperNativeAccess.switchPreference` uses the existing catalog and tower/deployment CAS.
It checks authorization again before the write and before effect handoff. An already
persisted preference remains authoritative if the effect handoff later fails. Independent
light and defence effects still need their owner reconciliation. The repair check is
available for the future resource transaction, which must supply its own guarded commit.

The structure journal records actor, tower, action, and whether the request used a bypass.
Operation phase records whether that request completed or needs review. A successful
overridden switch preference write logs its actor UUID, tower UUID, action and row
revision. Normal actions by a player who happens to have override enabled do not produce a
bypass record solely for that mode. Audit output stays on the server.

## Verification scope

Local Paper API doubles and temporary SQLite stores cover command visibility, connection
changes, override revocation, ordinary and controller actions, variant eligibility,
retirement, world denial, protected restoration, independent switches and stale
catalog/commit responses. The command is wired into the plugin entry point. Native
placement, expanded interaction, recovery and repair event routes still require the
production catalog, asset and protection bindings and live Java/Bedrock acceptance.
