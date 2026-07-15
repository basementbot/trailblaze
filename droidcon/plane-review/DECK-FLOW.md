# Deck Flow / Narrative Thread — working plan

**Captured 2026-07-15 (plane session) so it survives the move to the laptop.**
This is a PLAN, not applied to the slides yet. The deck still has the old Act order;
only the incremental edits listed at the bottom are actually in `samdroidcon.md`.

Two questions are still OPEN (see bottom) — the full reorder is blocked on them.

---

## The reframe (the important part)

The deck's current backbone is a **mechanism**: "natural language on top, determinism
underneath." But Sam's natural telling (10-min free-flow, transcript below) kept returning
— five separate times — to a different idea: **you can't see what you're testing.**

So the real spine is **VISIBILITY / COMPREHENSION**, and the map is the destination:

> Ten years shipping mobile and we still can't *see* what our tests cover or whether they
> match what the product wants. Natural language + AI lets us write journeys we can read,
> capture rich context every run, and finally see the whole app as a **map**.

Arc: **one screenshot** (2016, one test at a time) → **readable journeys (trails)** →
**your whole app as a map** (2026). That arc *is* the title, "Map Your App."
Determinism/recordability stop being the headline and become the **enabler**.

---

## The differentiators (why Trailblaze, not the ten other NL-test tools)

Sam's words (2026-07-15):
1. Natural language — **plus recorded, deterministic** replay
2. Your **custom tools, provided as first-class citizens** to the LLM
3. **Consistent across iOS, Android, and any target you choose**

(Cost/CI-economics is now a SUPPORT line under #1, not a headline.)

---

## Proposed flow (the thread)

1. **Open — who + the 10-year problem.** Sam / Block / Square Android Foundation, ~1.5 yrs
   on Trailblaze full-time. Ten years on, mobile tests are still hard; Espresso is still the
   fastest way to run them — but we still can't *see* what we're testing or tie it to what the
   product wants. *"A screenshot is worth 1,000 words"* (2016 callback) — even Sam couldn't tell
   what the tests did from the filenames.
2. **The unit — user journeys.** What the product wants has a name: what a user must always be
   able to do; the stuff you sign off before release. The gap: how does a journey map to the
   test that actually runs? How do the tests interconnect? (the pyramid)
3. **AI shows up — but not the way we need.** NL tests exist; lots of tools. AI *could* do
   everything — but not how we need it: can't rerun it, can't trust it, throws away the link.
   (The old "AI is supposed to do everything" + "The missing link" collapse into ONE quick turn.)
4. **What makes Trailblaze different** — the three differentiators above. Quick: open source,
   used by Cash + Square.
5. **A trail, concretely — the evolution.** **iOS Contacts is the example.** Just
   natural-language steps → + iOS recording → + Android recording. Same journey, growing
   fidelity. (This is the "what a trail file looks like" beat Sam said must come BEFORE
   waypoints. The existing "The same journey, three ways" slide — reworked **iOS-first**.)
6. **1,000 tokens — the artifacts.** A screenshot was worth 1,000 words; now it's worth
   1,000 *tokens*. Every run captures what we used to dig through by hand — screenshots +
   view hierarchy + logcat + network — so you diagnose failures fast, and so can the coding
   agent. (The reports / ASSET A material, reframed as the payoff of the 1,000-tokens hook.)
7. **Your app as a map.** Trails for every critical journey — but Espresso-style you still see
   one test at a time. How do you see them ALL together? Waypoints *(experimental)*:
   analytic-worthy points, a screen + an action, shortcuts learned from real runs.
   (Title payoff + vision close.) NOTE: needs a bridge for "how we transition to waypoints."
8. **Close** — it's real, `brew install block/tap/trailblaze`, go.

### What this does to the current deck
- "Seven targets" folds into differentiator #3 (cross-platform) — OR stays as a beat (OPEN Q1).
- CI-cost beat becomes a support line under "deterministic," not a headline.
- The "What is Trailblaze / How we got here / And we use it" intro trio (added earlier today)
  collapses into section 4.
- The 1,000 words→1,000 tokens callback: open with "words" (2016), pay off with "tokens" (§6).

---

## OPEN QUESTIONS (blocking the full reorder)

1. **The mechanism middle.** The current deck has ~30 slides between the trail format and the
   map — 2016 robot pattern, TypeScript tool authoring, tools-return-data, the CLI/MCP pivot,
   owning the driver. Almost NONE of that was in Sam's 10-min telling. Is it depth Sam still
   covers in a 40-min slot, or does the natural telling mean it shrinks hard? (25-slide talk
   vs 55-slide talk.)
2. **Section 3 — show it or say it?** For "AI could do everything but not how we need it," keep
   the live cart-demo moment (watch an agent drive a phone), or is that now just a spoken line
   on the way to the differentiators?

Sam's own flagged gaps from the free-flow:
- The transition INTO waypoints was skipped — needs a bridge.
- "What a trail file looks like" must come before waypoints — placed at §5 above.

---

## Sam's free-flow narration (2026-07-15, speech-to-text, lightly cleaned) — SOURCE OF TRUTH FOR VOICE

> Hello and welcome to my talk, Trailblaze: Map Your App with AI. I'm Sam Edwards. I work at
> Block, about 3 years. Square Android Foundation team; on Trailblaze ~1.5 years full-time.
>
> Where we are today: still the same place we were 10 years ago on mobile tests — they're hard.
> Espresso tests are still the fastest way to run tests, but we still have the problem of
> insight into what we're testing and tracking that back to what the product actually wants.
> These product flows used for acceptance tests are called **user journeys** — scenarios and
> actions a user must complete for the app to work as expected; the things needed for sign-off
> before releasing.
>
> We're still shipping mobile apps, still need confidence in what we're shipping and what we're
> checking. 10 years ago when I talked about screenshots with Espresso, I found I couldn't
> understand what we were actually testing. Many tests; if you looked at the filenames you
> didn't know what they did. The robot pattern separates why vs how, and if you pointed an LLM
> at robot-pattern Espresso tests it could probably figure out what you're testing. But the
> bigger problem: knowing how all our tests interconnect and what they validate — it's a
> pyramid: product requirements → end-user acceptance tests → smaller unit tests → edge cases.
>
> The key thing is the user journey, in natural language: this is what I want the user to be
> able to do. And: how does that map to the test actually executed? So we took on natural
> language tests like many others — describe what you want, an LLM navigates and does it. Lots
> of tools now. But Trailblaze has a few differentiators. Trailblaze is open source, used by
> Cash and Square…
>
> [where Sam was heading:] transition into all the artifacts we save — view hierarchy,
> networking logs, everything — not just screenshots. Now that we have a user journey defined
> in natural language, we have a trail that can be run with AI. But with the high-fidelity view
> hierarchy info and more, we can create RECORDABLE trails, and as they run we collect the data
> again so you can diagnose failures easily — full context available to you AND the LLM coding
> agent.
>
> I didn't want to go deep into how we collected all this data. Great that we now have a set of
> trails representing our critical jobs-to-be-done and user journeys. But now the problem: how
> do we make AI better understand what we're doing, and how do we see a holistic view of the
> tests? With Espresso screenshots you see one test at a time — same here. How do you see them
> all working together? How do you see your app as a map? Thinking about this, we came up with
> **waypoints**: a point in the app during a user journey that emits an analytic (something you'd
> track to make sure someone gets something done). To get there, a screen is shown representing
> it and an action is performed. We have waypoints, and **shortcuts** between them — known ways
> to get between them from historical runs.
>
> [NOTE from Sam: waypoints are experimental; we skipped how we transition to them. We didn't
> talk about what a trail file looks like — that would've been good before this.]

### Sam's differentiator phrasing (verbatim, 2026-07-15)
> Differentiated because we have natural language, but we have the recorded deterministic tools,
> and the tools are your contributed custom tools provided as first-class citizens to the LLM,
> and it's consistent across iOS and Android and other platforms that you choose the target.

### Sam on the example + the "AI could do everything" beat + 1,000 tokens
> Show the evolution of a trail file: just natural language steps, then the iOS recording, then
> the Android recording. **iOS will actually be the example.**
>
> [What drops:] we can call out that AI could do everything but it's not going to do it the way
> we need it to.
>
> [1,000 tokens] goes into us collecting all that context during a run — logs, screenshots,
> networking — things we normally manually dug through, but now all part of this trail execution.

---

## Where the actual slides stand (already applied today, on basementbot)

The deck itself still has the OLD order. Incremental edits already committed + pushed:
- Act 0 intro arc added (What is Trailblaze / How we got here / And we use it) — will mostly
  collapse into §4 of the new flow.
- "It's not that easy — yet" + waypoints plant added.
- Slides 5/8/9 legibility; deck-wide bullet bump; "Ten years" rebuilt (side-by-side cards).
- **"The same journey, three ways"** (Contacts, Android+iOS) added — this becomes §5, needs
  reworking **iOS-first**.
- Act 0 problem beats condensed: merged "AI is supposed to do everything" + "An LLM on every
  run" into one slide; trimmed "It's not that easy." (HEAD 8d0c085b)

Fork: `basementbot/2026-droidcon-us`. Push there (SSH alias `github-basementbot`), NOT origin.
Safety tags: `plane-session-work`, `remote-waypoints-preremrge`.
