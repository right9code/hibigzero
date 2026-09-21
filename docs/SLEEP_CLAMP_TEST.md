# Sleep-Clamp Energy Test — Results

**Date:** 2026-09-21 · **Device:** `B6BWE0W2GF4A006000461` (MT6765, e-ink)
**Question:** does the `deep_sleep` CPU clamp actually save battery?

**Answer: the mechanism works and it saves roughly 10–17 mA while the device is awake
with the screen off — but the total-energy comparison is *not measurable* on this
hardware, because the two instruments (timing and current) disagree by ~6×.**

---

## 1. Test design

Only two things can make the clamp matter:

* **Screen off + device awake** (background work) — core clocks/frequencies apply here.
* **Screen off + device suspended** — firmware powers the cores down via PSCI
  regardless of governor, so the clamp is inert.

`adb` is USB-only and USB blocks suspend, so the device was held awake with an explicit
`/sys/power/wake_lock` to force the **awake** case deterministically, identical in both
legs. Two workloads, 8 legs, alternating order (`WAKE CLAMP CLAMP WAKE`):

* `par=1` — one 1,000,000-iteration shell loop
* `par=4` — four such loops concurrently (4 little cores)

Each leg: 180 s idle baseline, then the work phase.

## 2. Results

| leg | mode | par | work time | idle mA | work mA | Δi mA | sag mV |
|:---|:---|:---|:---|:---|:---|:---|:---|
| A1 | WAKE | 1 | 19.8 s | 19.1 | 115.3 | 96.2 | 8.4 |
| A2 | WAKE | 1 | 19.6 s | 13.4 | 128.0 | 114.6 | 11.1 |
| B1 | CLAMP | 1 | 48.0 s | 6.1 | 7.6 | 1.6 | 0.2 |
| B2 | CLAMP | 1 | 48.0 s | 5.2 | 8.4 | 3.2 | 0.4 |
| A3 | WAKE | 4 | 19.6 s | 23.5 | 313.6 | 290.1 | 45.8 |
| A4 | WAKE | 4 | 19.7 s | 22.5 | 298.6 | 276.0 | 42.9 |
| B3 | CLAMP | 4 | 54.6 s | 5.9 | 9.8 | 3.8 | 0.6 |
| B4 | CLAMP | 4 | 52.2 s | 7.0 | 14.4 | 7.4 | 0.4 |

Frequencies verified in every sample: WAKE `p0=2200000 on=0-7`; CLAMP
`p0=900000 on=0-3`.

## 3. Findings

### 3.1 The clamp does what it claims (verified)

Identical work takes **2.44× longer** under the clamp (`par=1`), reproduced across all
8 legs with tight spread. CPU state confirmed directly: 4 cores offline, little cores
capped at 900 MHz, vs 8 cores online up to 2.2 GHz.

### 3.2 The energy comparison is NOT measurable on this device

The gauge's own behaviour rules it out:

| Observation | Verdict |
|:---|:---|
| `charge_counter` frozen at `2828160` for >30 min | **cannot integrate charge** |
| `current_avg` frozen at `-2800` on repeat reads | **dead node** |
| `current_now` returns **positive** values while discharging (±5 mA) | **nonsense below ~10 mA** |
| Turning the screen on steps current 4.0 → 23.3 mA (state verified per window) | **works at ≥20 mA** |

And the measured current ratio is physically impossible. A 2.44× frequency reduction
bounds the power reduction at **≤6×** (full DVFS, V∝f); measured Δi ratio is **~40–52×**.
Timing and current therefore disagree by ~6×, so **at least one is wrong** and no
trustworthy absolute energy figure exists for this device.

The voltage sag independently agrees with `current_now` (87–155 mΩ implied internal
resistance, both plausible), so the two signals are self-consistent *and* jointly
implausible. A gauge validated at 19 mA cannot be trusted to resolve single-digit mA
CPU deltas.

### 3.3 What *is* measurable: the idle cost of the wake profile

With the screen off and the device awake, the two profiles differ by more than the
gauge's noise floor:

* **WAKE profile idle: 13.4–23.5 mA**
* **CLAMP profile idle: 4.0–7.0 mA** (screen-step test: 4.0 mA)

→ the clamp saves **≈10–17 mA** in the awake-screen-off state, a magnitude the gauge
demonstrably resolves. Cause: the wake profile pins `p0 min = 900 MHz` (and 745 MHz on
the second cluster) with all 8 cores online, so it burns ~10–17 mA even with nothing to
do.

## 4. Conclusion

1. The clamp is **real and correctly implemented**.
2. Its benefit is confined to **awake-screen-off** windows and measures **~10–17 mA**;
   during true suspend it is **inert**.
3. A trustworthy "X% of the night" figure **cannot be produced on this device** — the
   battery gauge is unusable below ~10 mA and the current/timing instruments contradict
   each other.
4. Its failure mode is severe and was reproduced: killed mid-clamp leaves a screen-on
   device at 400–900 MHz with 4 cores dead. Contained by the Tier-1/2/3 durability work
   (dynamic receiver + `onCreate` reconcile + non-wakeup watchdog, ≤90 s residual window).
5. **Recommendation: disable the clamp by default** (`SLEEP_GOVERNOR_ENABLED=0`). The
   measured upside is small and bounded; the downside is a crippled device. Keeping the
   feature enabled is defensible now that the durability machinery exists, but its
   benefit remains modest and does not justify the exposure on its own.

## 5. Harness defects found and fixed during the attempt

* **v1 false start:** the start gate tested `status == Discharging`, which is also true
  while *plugged in* with the charge ceiling holding charging off. The run idled 6.5 h
  and sampled only twice. Fixed by requiring every `power_supply/*/online == 0`,
  confirmed twice 45 s apart.
* **Wakelock leak:** the pre-flight held `hbzshot` for 27.5 min and never released it,
  which would have blocked suspend. Released manually; the A/B script releases its own
  lock in the exit trap.
* **`current_avg`/`charge_counter` looked usable at design time** — they are frozen on
  this ROM. A gauge-characterisation step should have come first; without it, the whole
  night was spent building an experiment whose instrument could not answer the question.

Raw logs (on device, root): `/data/local/tmp/diag/{ab3.log,calib2.log,probe.log.saved}`.
