# EMCIP Admin Setup — Realistic Ruleset for Telegram Group Monitoring

You are configuring EMCIP, a Telegram group monitoring and moderation platform, through its admin UI at **http://emcip.local**. The goal is to set up a realistic, production-like configuration for monitoring 4-8 Telegram groups across 2 tenants.

Log in with username `admin` and password `admin123` (or whatever the login page accepts).

## Overview of What to Configure (in this order)

1. **Tenants** — Create 2 tenants
2. **Intent Rules** — Pattern-based message classification
3. **Intent Signal Config** — Structural signal thresholds (per tenant)
4. **Policy Rules** — What actions to take based on classified intents
5. **Moderation Rules** — Content-level pattern matching (spam, slurs, etc.)
6. **Watched Groups** — Configure the groups with appropriate moderation levels

Work through the sidebar navigation. Each section below tells you which page to visit and exactly what to create.

---

## 1. Tenants (`/tenants`)

Create two tenants to represent different use cases:

| Name | Description | LLM Model Override |
|------|-------------|--------------------|
| `Community Hub` | General-purpose community groups — tech discussions, hobby groups, local meetups | *(leave blank — use system default)* |
| `Business Ops` | Business-oriented groups — customer support channels, team coordination, vendor communication | *(leave blank)* |

After creating both, note their IDs — you'll need to select the right tenant in the sidebar when creating tenant-scoped rules.

---

## 2. Intent Rules (`/intent-rules`)

These classify incoming messages by matching patterns. Create these rules **globally** (no tenant selected in sidebar) unless noted.

### Core Intent Rules (Global)

| Name | Match Mode | Pattern | Intent | Confidence | Priority |
|------|-----------|---------|--------|------------|----------|
| `Greeting patterns` | KEYWORD | `hello\|hi\|hey\|good morning\|good evening\|howdy\|hola\|moin\|servus` | `GREETING` | 0.85 | 100 |
| `Goodbye patterns` | KEYWORD | `bye\|goodbye\|see you\|cya\|tschüss\|bis dann\|later` | `GOODBYE` | 0.85 | 100 |
| `Thanks patterns` | KEYWORD | `thanks\|thank you\|thx\|danke\|appreciated\|ty` | `THANKS` | 0.80 | 100 |
| `Question markers` | REGEX | `(?i)^(who\|what\|where\|when\|why\|how\|can\|could\|would\|is\|are\|do\|does\|has\|wer\|was\|wo\|wann\|warum\|wie)\b.*\??\s*$` | `QUESTION` | 0.75 | 90 |
| `Help request` | KEYWORD | `help\|assist\|support\|problem\|issue\|trouble\|stuck\|hilfe\|nicht funktioniert` | `HELP_REQUEST` | 0.80 | 80 |
| `Spam link patterns` | REGEX | `(?i)(bit\.ly\|t\.co\|tinyurl\|rb\.gy\|shorturl)\/.+` | `SPAM` | 0.90 | 50 |
| `Crypto spam` | REGEX | `(?i)(earn\|make\|profit)\s+(up to\s+)?\$?\d+.*\b(daily\|weekly\|per day\|passive)\b` | `SPAM` | 0.95 | 40 |
| `Join group spam` | REGEX | `(?i)(join\|click)\s+(my\|our\|this)\s+(group\|channel\|chat)\b.*https?://` | `SPAM` | 0.90 | 45 |
| `Bot command` | REGEX | `^/[a-zA-Z]+(\s.*)?$` | `COMMAND` | 0.95 | 30 |
| `Complaint pattern` | KEYWORD | `complaint\|unacceptable\|terrible\|worst\|refund\|scam\|rip-off\|beschwerde\|skandal` | `COMPLAINT` | 0.80 | 70 |
| `Offensive language` | REGEX | `(?i)\b(idiot\|stupid\|dumb\|moron\|loser\|shut up\|stfu)\b` | `OFFENSIVE` | 0.85 | 60 |
| `Threat detection` | REGEX | `(?i)\b(i('ll\|m going to\| will)\s+(kill\|hurt\|destroy\|hack\|dox\|find you\|report))\b` | `THREAT` | 0.90 | 20 |
| `Extremist rhetoric` | REGEX | `(?i)\b(white\s+genocide\|great\s+replacement\|race\s+war\|volksverräter\|umvolkung\|remigration\|white\s+power\|heil\s+hitler\|sieg\s+heil\|88\s*hh)\b` | `EXTREMIST` | 0.95 | 15 |
| `Conspiracy framing` | REGEX | `(?i)\b(wake\s+up\s+sheeple\|they\s+don'?t\s+want\s+you\s+to\s+know\|do\s+your\s+own\s+research\|mainstream\s+media\s+lies\|lügenpresse\|plandemie\|great\s+reset\|new\s+world\s+order\|deep\s+state)\b` | `CONSPIRACY` | 0.75 | 65 |
| `Doxxing attempt` | REGEX | `(?i)(his\|her\|their)\s+(real\s+)?(name\|address\|employer\|school\|phone)\s+(is\|was)\b\|(?i)\blives\s+at\s+\d` | `DOXXING` | 0.90 | 15 |
| `Dehumanizing language` | REGEX | `(?i)\b(untermensch\|parasit(en)?\|ungeziefer\|abschaum\|pack\|gesindel\|cockroach(es)?\|vermin\|subhuman)\b` | `DEHUMANIZING` | 0.90 | 25 |
| `Heated debate` | REGEX | `(?i)\b(you('re\| are)\s+(wrong\|clueless\|delusional\|ignorant)\|absolute\s+nonsense\|totaler\s+quatsch\|völliger\s+blödsinn)\b` | `HEATED_DEBATE` | 0.70 | 85 |

### Business Ops Tenant-Specific Rules

Switch the sidebar tenant to **Business Ops**, then create:

| Name | Match Mode | Pattern | Intent | Confidence | Priority |
|------|-----------|---------|--------|------------|----------|
| `Order inquiry` | KEYWORD | `order\|tracking\|shipment\|delivery\|package\|invoice\|bestellung\|lieferung` | `ORDER_INQUIRY` | 0.80 | 75 |
| `Pricing question` | KEYWORD | `price\|cost\|quote\|pricing\|discount\|how much\|preis\|kosten\|angebot` | `PRICING` | 0.80 | 75 |
| `Urgent escalation` | REGEX | `(?i)\b(urgent\|asap\|emergency\|critical\|immediately\|dringend\|sofort)\b` | `URGENT` | 0.85 | 30 |

---

## 3. Intent Signal Config (`/intent-signal-config`)

Configure structural signal detection thresholds. Do this **per tenant** (switch sidebar).

### Community Hub

| Field | Value | Rationale |
|-------|-------|-----------|
| Foreign Script Ratio | 0.50 | Multilingual communities — be slightly more tolerant |
| Cyrillic Ratio | 0.70 | Don't flag legitimate Cyrillic messages too aggressively |
| Lookalike Suspicion | 3 | Default — catches homoglyph attacks |
| Zero Width Abuse | 2 | Default — catches invisible character manipulation |
| Caps Ratio | 0.60 | Flag heavy caps a bit earlier (excited users are common) |
| Toxicity Words | `nazi, faschist, rassist, hurensohn, wichser, arschloch, scheisse, nigger, retard, kys` | German + English slurs relevant to community groups |
| Description | `Community groups — balanced between engagement and safety` |

### Business Ops

| Field | Value | Rationale |
|-------|-------|-----------|
| Foreign Script Ratio | 0.60 | Default |
| Cyrillic Ratio | 0.60 | Default — business context, flag sooner |
| Lookalike Suspicion | 2 | Lower threshold — impersonation is higher risk in business |
| Zero Width Abuse | 1 | Stricter — any invisible chars in business comms is suspicious |
| Caps Ratio | 0.70 | Default |
| Toxicity Words | `scam, fraud, betrug, abzocke, fick, arschloch, hurensohn, wichser, idiot, depp` | Business-relevant abuse terms |
| Description | `Business groups — stricter structural signal detection` |

---

## 4. Policy Rules (`/policy-rules`)

These define what happens when an intent is detected. Create globally unless noted.

### Global Policy Rules

| Name | Target Intent | Min Conf | Max Conf | Action | Priority | Description | Conditions |
|------|--------------|----------|----------|--------|----------|-------------|------------|
| `Block confirmed spam` | `SPAM` | 0.90 | *(empty)* | `BLOCK` | 10 | Block messages classified as spam with high confidence | *(none)* |
| `Flag possible spam` | `SPAM` | 0.70 | 0.89 | `FLAG` | 20 | Flag lower-confidence spam for human review | *(none)* |
| `Block threats` | `THREAT` | 0.80 | *(empty)* | `BLOCK` | 5 | Immediately block threatening messages | *(none)* |
| `Escalate threats` | `THREAT` | 0.60 | 0.79 | `ESCALATE` | 15 | Escalate uncertain threats to moderators | *(none)* |
| `Flag offensive content` | `OFFENSIVE` | 0.75 | *(empty)* | `FLAG` | 30 | Flag offensive language for review | *(none)* |
| `Escalate extremist content` | `EXTREMIST` | 0.85 | *(empty)* | `ESCALATE` | 3 | Highest priority — escalate extremist rhetoric immediately | *(none)* |
| `Flag extremist low-conf` | `EXTREMIST` | 0.60 | 0.84 | `FLAG` | 12 | Lower confidence extremist matches need human judgment | *(none)* |
| `Escalate doxxing` | `DOXXING` | 0.80 | *(empty)* | `ESCALATE` | 4 | Doxxing attempts are urgent — escalate for review | *(none)* |
| `Flag dehumanizing language` | `DEHUMANIZING` | 0.80 | *(empty)* | `ESCALATE` | 8 | Dehumanizing language is a precursor to worse — escalate | *(none)* |
| `Flag conspiracy content` | `CONSPIRACY` | 0.70 | *(empty)* | `FLAG` | 70 | Flag for awareness, don't escalate — these are common in debate groups | *(none)* |
| `Allow heated debate` | `HEATED_DEBATE` | 0.65 | *(empty)* | `ALLOW` | 90 | Strong disagreement is legitimate discourse, let it through but log it | *(none)* |
| `Allow greetings` | `GREETING` | 0.70 | *(empty)* | `ALLOW` | 100 | Explicitly pass greetings through | *(none)* |
| `Allow thanks` | `THANKS` | 0.70 | *(empty)* | `ALLOW` | 100 | | *(none)* |
| `Allow goodbyes` | `GOODBYE` | 0.70 | *(empty)* | `ALLOW` | 100 | | *(none)* |
| `Respond to help` | `HELP_REQUEST` | 0.75 | *(empty)* | `RESPOND` | 50 | Generate an LLM response for help requests | *(none)* |
| `Respond to questions` | `QUESTION` | 0.70 | *(empty)* | `RESPOND` | 60 | Generate LLM response to questions | Condition: `MIN_THREAD_LENGTH` min: `1` (only respond in active threads) |
| `Default allow` | `*` | 0.00 | *(empty)* | `ALLOW` | 999 | Catch-all: allow everything not matched above | *(none)* |

### Community Hub Tenant Rules

Switch sidebar to **Community Hub**:

| Name | Target Intent | Min Conf | Action | Priority | Description | Conditions |
|------|--------------|----------|--------|----------|-------------|------------|
| `New account spam guard` | `SPAM` | 0.60 | `BLOCK` | 8 | Block spam-like messages from very new accounts | `ACCOUNT_AGE_DAYS` max: `7` |
| `Night time flag` | `*` | 0.00 | `FLAG` | 200 | Flag any message sent during off-hours for morning review | `TIME_WINDOW` start: `01:00`, end: `05:00` |
| `Frequent flagger watch` | `OFFENSIVE` | 0.50 | `ESCALATE` | 25 | Repeat offenders get escalated | `FLAGGED_COUNT` min: `3`, windowDays: `14` |
| `New account extremism` | `EXTREMIST` | 0.50 | `ESCALATE` | 6 | Brand-new accounts posting extremist content — likely bad actors | `ACCOUNT_AGE_DAYS` max: `3` |
| `Debate group long threads` | `HEATED_DEBATE` | 0.60 | `FLAG` | 80 | Flag heated exchanges in long threads — may be spiraling | `MIN_THREAD_LENGTH` min: `10` |

### Business Ops Tenant Rules

Switch sidebar to **Business Ops**:

| Name | Target Intent | Min Conf | Action | Priority | Description | Conditions |
|------|--------------|----------|--------|----------|-------------|------------|
| `Auto-respond orders` | `ORDER_INQUIRY` | 0.75 | `RESPOND` | 40 | Auto-respond to order inquiries with tracking lookup | *(none)* |
| `Escalate urgent` | `URGENT` | 0.80 | `ESCALATE` | 10 | Immediately escalate urgent messages to team lead | *(none)* |
| `Flag pricing questions` | `PRICING` | 0.70 | `FLAG` | 50 | Flag pricing questions for sales team review | *(none)* |
| `Complaint escalation` | `COMPLAINT` | 0.70 | `ESCALATE` | 20 | Escalate complaints to management queue | *(none)* |

---

## 5. Moderation Rules (`/moderation-rules`)

Content-level pattern matching — catches things regardless of intent classification.

### Global Moderation Rules

| Name | Rule Type | Pattern | Severity | Action | Enabled |
|------|-----------|---------|----------|--------|---------|
| `Phone number harvest` | REGEX | `(?i)(send\|dm\|text)\s+(me\|your)\s+(number\|phone\|whatsapp)` | HIGH | FLAG | Yes |
| `External link flood` | REGEX | `(https?://\S+\s*){4,}` | MEDIUM | FLAG | Yes |
| `All caps shouting` | REGEX | `^[A-Z\s!?.]{50,}$` | LOW | WARN | Yes |
| `Crypto wallet address` | REGEX | `\b(0x[a-fA-F0-9]{40}\|[13][a-km-zA-HJ-NP-Z1-9]{25,34}\|bc1[a-zA-HJ-NP-Z0-9]{39,59})\b` | HIGH | FLAG | Yes |
| `Email harvesting` | REGEX | `(?i)(send\|share\|give)\s+(me\s+)?(your\s+)?email` | MEDIUM | FLAG | Yes |
| `Excessive message length` | LENGTH | `4000` | LOW | WARN | Yes |
| `Slur filter` | REGEX | `(?i)\b(nigger\|faggot\|retard\|tranny\|spastic\|hurensohn\|kanacke\|mongo)\b` | CRITICAL | DELETE | Yes |
| `Phishing keywords` | KEYWORD | `verify your account\|confirm your identity\|suspended your account\|click here to restore\|konto gesperrt\|identität bestätigen` | HIGH | DELETE | Yes |

### Business Ops Tenant Rules

Switch sidebar to **Business Ops**:

| Name | Rule Type | Pattern | Severity | Action | Enabled |
|------|-----------|---------|----------|--------|---------|
| `Competitor mention` | KEYWORD | `CompetitorA\|CompetitorB\|Wettbewerber` | LOW | FLAG | Yes |
| `Confidential data leak` | REGEX | `(?i)\b(confidential\|internal only\|nicht weitergeben\|vertraulich)\b` | HIGH | ESCALATE | Yes |
| `Invoice/bank detail sharing` | REGEX | `\b(IBAN\|DE\d{20}\|BIC\|SWIFT)\b\|(?i)bank\s*(account\|details\|konto)` | HIGH | FLAG | Yes |

---

## 6. Watched Groups (`/groups`)

The groups page shows groups that were discovered via the Telegram watcher. You'll need to configure their moderation profiles. If no groups are discovered yet, go to `/telegram` first and ensure the watcher is connected and has groups to watch.

The system already has groups that were discovered by the Telegram watcher. Below are the **group archetypes** — match each discovered group to the archetype that fits best based on its name and description, then apply the settings. If a group doesn't fit any archetype, use the "General Hobby" settings as a safe default.

**Important:** We are in **observation mode** — the system only flags and categorizes, it never takes autonomous action in Telegram. All actions (BLOCK, RESPOND, ESCALATE, FLAG) just write different categories to the decision log for human review.

### Community Hub Groups (select Community Hub tenant in sidebar)

Assign 3-5 groups to this tenant. Look for groups that are hobbyist, community-oriented, or discussion-focused.

#### Archetype A: General Hobby / Tech Group
*Examples: maker spaces, photography, gardening, programming, board games, cooking, 3D printing*

- **Moderation Level**: `MEDIUM`
- **Auto Respond**: `Yes`
- **Knowledge Fork Enabled**: `Yes` — conversations build a useful knowledge base
- **Welcome Message**: `Welcome! This is a friendly hobby group. Feel free to ask questions and share your projects.`

#### Archetype B: Controversial / Political Discussion Group
*Examples: political debate, current events, philosophy, religion, Stammtisch, local politics, media criticism*

These groups feature strong opinions, heated debate, sarcasm, and occasionally extreme positions. The challenge is distinguishing legitimate (if passionate) discourse from actual abuse. Settings should flag more aggressively for human review without suppressing debate.

- **Moderation Level**: `HIGH`
- **Auto Respond**: `No` — bot responses in heated debates would be tone-deaf and escalate tensions
- **Knowledge Fork Enabled**: `Yes` — these discussions are valuable context for understanding group dynamics
- **Welcome Message**: *(leave blank — these groups have their own culture, a bot welcome would feel intrusive)*

#### Archetype C: Mixed / Social Group
*Examples: local neighborhood chat, university cohort, expat community, sports fan group, event planning*

Mix of casual chat, coordination, occasional drama. Moderate volume with bursts around events or local incidents.

- **Moderation Level**: `MEDIUM`
- **Auto Respond**: `Yes`
- **Knowledge Fork Enabled**: `Yes`
- **Welcome Message**: `Welcome to the group! Feel free to introduce yourself.`

### Business Ops Groups (select Business Ops tenant in sidebar)

Assign 1-3 groups to this tenant. Look for groups that are business-oriented, support-related, or professional.

#### Archetype D: Customer Support / Service Channel
- **Moderation Level**: `HIGH`
- **Auto Respond**: `Yes` — draft responses for common inquiries
- **Knowledge Fork Enabled**: `Yes` — builds FAQ knowledge base from real questions
- **Welcome Message**: `Welcome! Please describe your issue and our team will respond shortly.`

#### Archetype E: Internal Team / Vendor Coordination
- **Moderation Level**: `MEDIUM`
- **Auto Respond**: `No` — internal comms don't need bot interference
- **Knowledge Fork Enabled**: `No` — internal discussions stay internal
- **Welcome Message**: *(leave blank)*

---

## Verification

After creating everything, verify by going to `/simulate` and testing a few messages:

1. Type `hello everyone!` — should classify as GREETING, action ALLOW
2. Type `Earn $500 daily with crypto! Join https://bit.ly/scam123` — should classify as SPAM, action BLOCK
3. Type `I need help with my order` — should classify as HELP_REQUEST, action RESPOND
4. Type `I'll kill you` — should classify as THREAT, action BLOCK
5. Type `Die Umvolkung ist in vollem Gange` — should classify as EXTREMIST, action ESCALATE
6. Type `Wake up sheeple, do your own research` — should classify as CONSPIRACY, action FLAG
7. Type `You're completely delusional if you believe that` — should classify as HEATED_DEBATE, action ALLOW
8. Switch to Business Ops tenant, type `What is the price for 100 units?` — should classify as PRICING, action FLAG

---

## Important Note: Observation Mode

This entire setup is **observation-only**. None of the actions (BLOCK, RESPOND, ESCALATE, FLAG) have any real effect in Telegram. They all just categorize entries in the decision log with different severity labels:

- **ALLOW** = logged, not flagged
- **FLAG** = appears in decision log for review
- **ESCALATE** = appears in escalation queue with higher visibility
- **BLOCK** = appears as high-severity flag (nothing is actually blocked)
- **RESPOND** = LLM generates a draft response you can review, but it's never sent automatically

The only way anything is ever sent to Telegram is when a human operator manually clicks "Reply" in the admin UI. Use the different action labels to create a useful triage experience — ESCALATE items bubble to the top, FLAG items go to the standard queue, ALLOW items just build your analytics baseline.

---

## Summary of What You Created

- **2 tenants**: Community Hub (hobby + controversial discussion groups), Business Ops (support + team channels)
- **~21 intent rules**: 18 global (including extremism, conspiracy, doxxing, dehumanizing, heated debate) + 3 Business Ops specific
- **2 signal configs**: Community Hub (tolerant, multilingual) vs Business Ops (stricter)
- **~24 policy rules**: 17 global + 5 Community Hub (with new-account-extremism and long-thread-debate tracking) + 4 Business Ops
- **~11 moderation rules**: 8 global + 3 Business Ops
- **4-8 group profiles** across 5 archetypes: hobby groups, controversial discussion groups, social groups, support channels, internal teams

This gives a realistic, layered defense:
- **Layer 1** (Moderation Rules): catches content patterns regardless of classification
- **Layer 2** (Intent Classification): categorizes message intent — from harmless greetings through heated debate to extremist rhetoric
- **Layer 3** (Policy Rules): decides triage level based on intent + context (account age, thread length, time of day, repeat offender status)
