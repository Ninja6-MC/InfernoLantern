# Ordinary Zombie Combat

Task #19 implements an initial combat service and Paper event adapter. The plugin entry
point still uses the separate prototype. Native production routing and acceptance remain
open. The recorded [zombie feasibility run](ZOMBIE_PROTOTYPE.md) applies to the older
prototype and its specific tested conditions.

## Eligibility and target ownership

The runtime registers loaded, expanded deployments with an explicit mode, finite positive
range and damage, and positive attack interval. It supplies a complete bounded index of
loaded candidate zombies and current world, capability, controller, fluid, component and
protection decisions. The Paper adapter requires a validated position for a living proxy
inside the deployed volume. Attack damage must be representable by the native float.
No gameplay range, damage, interval or default mode is selected here.

Defence can continue while a controller is offline. A known eligibility loss suspends
Defence while preserving deployment, the controller and both saved switches. The access
binding distinguishes offline status from a known denial and reports unknown eligibility
as unknown. The service treats an unknown decision as unavailable. Catalog publication,
retirement, the saved Defence switch, fluid, integrity, positive HP, and world restrictions
must still permit the action.

Initial candidates are ordinary adult zombies. Retain a reachable current target. When
acquiring, sort by squared distance and then zombie UUID within the complete loaded set.
Attack admission requires range, loaded line of sight and explicit protection permission.
Each tower owns at most one lease and each zombie at most one tower lease. Leases bind the
tower's item generation, deployment revision and exact proxy and zombie identities.
Processing checks a bounded number of tracked deployments, candidates and asynchronous
operations per tick. A candidate query over budget is unavailable for that pass. The
adapter conservatively checks the loaded chunk rectangle along the ray, with a fixed
work budget of 256 chunk checks. It loads no chunks.

| Mode | Native targeting |
| --- | --- |
| Normal | The zombie retains its ordinary target behavior. Acquisition of the combat proxy is denied. |
| Retaliation | Permit native `TARGET_ATTACKED_ENTITY` following positive, uncancelled beam damage under the exact lease and current target permission. |
| Priority | Permit an explicitly authorized target request for the selected zombie when it has no unrelated target. The Paper event can still deny the request. |

Release an owned retaliation or priority target after 200 ticks without a valid native
melee event. Repeated outgoing beam hits do not postpone that timeout. The released
zombie is excluded while still reachable, until the next timeout replaces that exclusion
or a fresh deployment registration begins. Release clears only the exact owned target.
The owner loop rechecks current target permission and releases the owned target if it is
lost. If another target is selected, that event revokes the lease while leaving the
external target alone.

## Damage and repair

The Paper adapter stages a nonpersistent, silent, invisible adult villager inside the
confirmed deployed geometry. It protects the proxy from damage, potion, fire, knockback,
conversion, teleport and interaction effects. Unload and shutdown release exact owned
targets and proxies, and entity-load cleanup removes stale combat-tagged proxies. No
terrain changes are needed to spawn or release a combat proxy.

The beam uses an explicit native `MOB_ATTACK` damage source with the proxy as both the
direct and causing entity. The plugin supplies no player attacker, kill attribution,
ignition or damage-over-time. Existing vanilla credit for an independent real player hit
is unaffected. After the exact uncancelled positive outgoing damage event, the adapter
attempts a twelve-point particle beam. A visual failure does not change the attack result.

Incoming melee requires a direct native zombie attack, an exact current lease, owned
target, current targeting and melee permissions, and a positive pre-shield final damage
value.
The shield captures the exact lease at HIGHEST and zeros native proxy damage. MONITOR
requires an uncancelled event and a still-zero native result. The exact event can deliver
one melee observation. A bounded deployment queue serializes accepted observations.
An unrelated target replacement closes the native lease to new hits. A hit already
accepted against the same deployment can settle after fresh protection checks. Deployment
changes, unloads and permission denial revoke pending damage. A damaged tower uses
the existing authoritative health, repair cooling and defeat compaction path.
Provider-native modifications after the proxy shield and external direct entity/health
edits require independent compatibility tests.

Repair uses a revocable idle permit. Acquiring a lease revokes the previous idle permit,
and a successful beam records existing attack cooling through the repository. An
uncertain cooling write suspends combat and keeps deployed repair closed for that identity
in the current service, including after re-registration. Runtime wiring still needs
publication and restart handling for these uncertain cases and must coordinate structural
transitions with outstanding combat work. There is no automatic replay of an already
emitted world attack.

## Acceptance boundary

Local tests exercise real temporary SQLite state and Paper API doubles. Native Paper event
ordering, protection-provider cancellation and modifications, proxy reach/collision, target
priority and external replacement, sustained damage and cooling, world/chunk lifecycle,
and Java/Bedrock visuals still need live evidence on a separately authorized environment.
Skeletons, witches, bosses, PvP and explosion handling are outside this implementation.
