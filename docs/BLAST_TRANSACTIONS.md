# Lantern Blast Transactions

Task #20 adds guarded damage-and-drop accounting and reusable world-owner effects.
Production explosion event routing and provider validation remain pending.

## Exposure and protection

An authorized caller supplies one complete observation for the same native explosion,
including an exact explosion identity, world, the affected authoritative component IDs
and one applicable raw damage amount for the whole lantern. The component list is an
exposure proof, independent of a protection provider's outcome. Use the physical
representation of each candidate tower to prove which of its components were exposed.
Only owned entities and actual owned block replacements can supply exposure. Clearance
reservations over unchanged terrain or empty cells still protect placement boundaries,
but they are ineligible as affected components.
A missing damage callback, a filtered block list, or nearby explosion coordinates alone
cannot prove complete exposure, damage, or permission. Incorrect owners, unknown
components, incomplete or uncorrelated evidence are ineligible. The service neither
loads chunks nor discovers blasts globally.

The default respects protection. Each affected component needs an explicit current
decision. If any applicable decision denies the effect, the whole lantern stays intact
with its original HP and no item drop. Configured tower-only override may ignore an
explicit denial if physical ownership and mutation authority are independently proved.
Unknown decisions always withhold admission. The owner rechecks current evidence before
staging, applying, verifying and committing. A revocable permit carries the last
authorization to the SQLite commit boundary. Restoration has its own current physical
authority, and compensation can restore only exact changes owned by the operation.

The pinned Paper block and entity event surfaces are not used as an aggregated exposure
producer here. A production router must prove stable same-explosion identity across
relevant observations, establish trusted exposure even if a provider filters the block
list, and prevent separate vanilla mutation of owned components that are handled by the
lantern transaction. The typed caller boundary takes no Bukkit explosion events and
cannot uncancel unrelated block, entity or player damage.

## Transaction and recovery

One deterministic ID binds the explosion, world and tower. The request also binds the
current row version, item generation and deployment revision. Physical effects stage an
unavailable compact item before durable admission. After exact admission, the adapter
restores the original blocks and quiesces the recorded visual components, then verifies
the world effects. A successful commit deducts the finite nonnegative configured
multiplier times the applicable blast damage, clamped to zero. The new ground
representation preserves identity and switch preferences and advances generation and
deployment revision. A permitted blast still drops the lantern when its resulting HP is
positive or the multiplier is zero. The implementation makes no new balance choice.

Expanded exit clears the controller and starts the full configured cooldown at the
verified durable exit. Compact exit preserves its existing expansion timestamp.
Actual reductions of stored HP extend the existing repair lock to the later of its
current expiry and the configured cooling deadline. Zero applied damage or absent
cooling preserves the lock. Managed definitions supply the validated repair duration.
Receipt lookup never replays a drop or extends the timer. A competing transition can
acquire the row only once. The world-owner adapter
activates the exact staged item only after commit, removes owned visual components and
releases their reserved footprint. External edits are kept intact.

The structured journal evidence preserves same-explosion provenance and the admitted
damage policy. Trusted matched-save structure recovery can restore exact BEFORE or
finalize exact AFTER. For an interrupted expanded exit whose historical time cannot be
proved, the full admitted cooldown starts at the verified recovery commit. Unknown
physical outcomes remain pending. The default Paper recovery port still has no
blast-aware matched-save observation.

Publication ends staging invulnerability. A future native damage router can use the
exact origin-drop check to withhold damage only from the event that delivered that
entity. A distinct blast or replacement ground entity does not inherit the originating
protection. Active items otherwise follow the existing ground damage policy.

## Verification

Local tests exercise mixed protection, incomplete evidence, timer and HP calculation,
replay, simultaneous defeat, matched-save recovery, and the actual Paper adapter
against strict API doubles. Live Paper exposure, provider event precedence, chained
blast damage, Java/Bedrock appearance, and incidental Wither spawn blast behavior still
require separate acceptance. This path grants no direct boss combat support.
