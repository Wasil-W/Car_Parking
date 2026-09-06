# Restoring the three scheduled tasks

The task definitions survived at `C:\Users\wasil\OneDrive\Documenten\Claude\Scheduled\`.
Claude's task registry did not. Re-adding is manual.

**Note on schedules:** the original cron expressions were not recorded anywhere — only the
prompt bodies survived. The frequencies below are inferred from each task's name and
description. Change them if you had something different.

---

## Paste this first (the introduction)

> I'm re-creating three scheduled tasks that were lost when my Claude desktop app was
> reinstalled. The definitions survived as files, so nothing needs writing from scratch — I'll
> paste each one in and you set it up as a scheduled task exactly as given.
>
> For each task I'll give you a name, how often it should run, and the prompt body. Create the
> scheduled task with that name and schedule, and use the prompt body **verbatim** as the task's
> instructions. Don't rewrite it, shorten it, or improve it — these were already tuned and I want
> them back as they were.
>
> Applies to all three:
> - They're for me, Wasil. Where a prompt says "the user" or "Wasil", that's me.
> - Each delivers its result as a chat message, not a file.
> - If a prompt calls for web search, it should use it.
>
> After each one, tell me the name and schedule you actually set, then wait for the next.

---

## Task 1 — `health-nutrition-training-watch`

**Schedule:** daily (suggested 08:00)

```
The user (Wasil) wants a daily digest of new developments in health, nutrition, and training/exercise science — the kind of thing that changes how someone should eat, train, or think about their health. Examples of what counts: new studies or expert statements on things like "seed oils are bad for you," "training to failure isn't necessary for hypertrophy," debates on protein intake, sleep, cardio vs. lifting, supplements, longevity research, etc. Focus on claims and findings from credible sources: peer-reviewed studies, reputable science journalists/outlets (e.g. Examine.com, NYT/WaPo health sections, major university press releases, well-regarded researchers/doctors on record), not random social media hot takes.

Steps:
1. Use WebSearch (load via ToolSearch with query "select:WebSearch" if not already loaded) to search for news from roughly the last 24-48 hours on topics like: nutrition science news, exercise/training science news, new health study seed oils / protein / training to failure / cardio / longevity / supplements, and any other prominent evidence-based health claims currently circulating. Run a few targeted searches rather than one broad one.
2. Filter out anything that isn't genuinely new (published/discussed in the last day or two) or that comes from low-credibility sources (influencer clickbait, unsourced claims).
3. If there is nothing substantive and new, say so briefly — do not pad with filler or old news.
4. If there are real findings, summarize each in 2-4 sentences: what the claim/finding is, what evidence backs it (study, expert, source), and why it matters or what's contested about it. Prioritize the 3-6 most notable items rather than an exhaustive list.
5. Include a source link for each item.
6. Present the result as a concise chat message (no need to create a file) with a short heading and the items below it, ordered by relevance/importance. Keep formatting minimal — plain prose per item, not heavy nested bullets.

Keep the overall response tight and skimmable; this is a daily habit, not a report.
```

---

## Task 2 — `monday-build-ideas`

**Schedule:** weekly, Mondays (suggested 08:00)

```
Draft a fresh list of 5-7 "things to build" ideas for this week. These can be apps, websites, servers, or improvements to an existing setup.

Focus mostly on these areas, drawing on what's known about Wasil's interests: software/SaaS and dev tools, AI/ML products, personal productivity and home server/self-hosted setups, and practical useful apps/websites — in the spirit of his automatic parking app project and the Android UI app he's been working on.

Include 1-2 ideas that are deliberately unrelated to his usual interests — something in a totally different domain, just to surface fresh directions worth exploring.

For each idea give: a short name, 1-2 sentence description, and why it's worth considering (problem it solves, what's interesting/new about it, or a rough build angle). Keep the whole thing tight and scannable — no long preamble. Present as a clean list, not a long report.
```

---

## Task 3 — `monday-todo-ideas`

**Schedule:** weekly, Mondays (suggested 08:15, so it lands after the build ideas)

```
Draft a fresh Monday to-do list of concrete, actionable tasks tied to Wasil's ongoing interests: software/SaaS and dev tools, AI/ML products, personal productivity and home server/self-hosted setups, and his active projects like the automatic parking app and the Android UI app.

Frame every item as a real to-do (e.g. "set up X", "fix Y", "prototype Z", "research W") rather than an open-ended idea. Include at least one server/infra-related to-do, since that's a recurring practical need in his line of work.

Include 1-2 to-dos that explore something completely new and unrelated to his usual focus areas — a small low-stakes task to try something different.

Present as a short, scannable checklist (5-7 items), no long preamble or report-style formatting.
```
