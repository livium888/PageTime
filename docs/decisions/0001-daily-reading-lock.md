# 0001: Daily reading lock replaces the session gate

**Status:** accepted, 2026-10-06. Owner decision; details below were chosen to fit the existing rules in `AccessGate.kt`.

## Context
The gate worked: the owner stopped opening Instagram and Facebook. But reading also stopped. Reading was only ever motivated by the apps it bought, and once those apps stopped being wanted (Premack, 1959: a reinforcer only works while it is still preferred), nothing pulled toward reading. The goal has changed from *less social media* to *guaranteed reading*.

## Decision
1. **Daily reading lock.** While enabled, the phone is locked except for allowed apps until **20 minutes** (configurable) of reading has been done since **04:00** local time. Once met, everything unlocks until the next 04:00.
2. **What counts as reading:** in-app reading (`earnFromReading`) and trusted external reading such as Kindle (`earnFromExternalReading`, already discounted and daily-capped). Flashcards, explain-back, concept links and the momentum bonus do **not** count, because the target is minutes of reading.
3. **App allowlist mode.** Alongside the existing blocklist: everything launchable is blocked except **at most 5** chosen apps plus fixed essentials (default dialer, default SMS app, home screen, Settings, clock/alarms, current keyboard, PageTime itself, well-known authenticator apps). Essentials are resolved by Android role or intent, not by app name.
4. **Emergency unlock:** 2 per rolling **7 days** (was 2 per rolling day), still 5 minutes and still one app only.
5. **Sessions are removed.** No buying of app time, no session cost or length.

## Rules kept from the gate (unchanged principles)
- Turning the lock off takes 24 hours; turning it on is instant.
- Tightening is always free; loosening (lower target, add an allowed app after setup, switch allowlist → blocklist, remove a blocked app) is allowed only once today's target is met, or while the lock is off.
- A one-time 30-minute setup window after entering app allowlist mode, so the list can be built before any reading. Mirrors `SiteMode.ALLOWLIST_SETUP_GRACE_MILLIS`.
- Hard lock still overrides everything, including emergency unlocks.

## Why the earlier objection no longer applies
`AccessGate.kt` rejected a "standing gate" (read, then apps open all day) as "a strange reward for an afternoon of reading". That objection was about rationing app time, the old goal. Under the new goal, unlocking the rest of the day is the intended reward: the daily target is the product, not the price.

## Options considered
- **Keep sessions, add a daily minimum on top.** Two economies to explain and maintain. Rejected.
- **Daily lock reusing the session counter.** Credit and sessions carry over between days, while the lock is daily by nature. Rejected for a separate per-day counter.
- **Lock everything including calls and messages.** Unsafe. Rejected.

## Revisit when
- Reading still doesn't happen after 2 weeks at 20 minutes: the lock isn't the bottleneck, so look at cues and book choice.
- The target is routinely met by 9am and the phone then becomes the problem again: consider a second check-in later in the day.
- An essential app turns out to be missing from the fixed list (e.g. a banking or transport app used daily): make essentials configurable rather than raising the limit of 5.
