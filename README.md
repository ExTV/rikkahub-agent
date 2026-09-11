<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent

**Your phone, automated.**

A fork of [RikkaHub](https://github.com/rikkahub/rikkahub) that turns the native Android LLM chat client into a real on-device agent: 80+ device tools, AI-authored workflows, scheduled jobs, an in-app browser the AI drives, keyless web search, a Linux workspace, SSH, screen automation, file manager, music player, voice transcription, downloadable on-device LLMs, and a remote Telegram bot. All opt-in.

<p>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/v/release/ExTV/rikkahub-agent?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/downloads/ExTV/rikkahub-agent/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/stargazers"><img src="https://img.shields.io/github/stars/ExTV/rikkahub-agent?style=flat-square&color=yellow" alt="Stars" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
</p>

<a href="https://extv.github.io/rikkahub-agent/">Website</a> ·
<a href="https://github.com/ExTV/rikkahub-agent/releases/latest">Download</a> ·
<a href="#features">Features</a> ·
<a href="#quick-start">Quick Start</a> ·
<a href="#building-from-source">Build</a>

</div>

---

## What can it do?

Tell it what to do in plain language. The phone runs it in the background while you live your life.

> *"Every weekday at 9am, summarize my unread WhatsApp into one Telegram message."*
> *"If my home server's disk fills up, ping me."*
> *"Watch my notifications. If anything from my boss comes in, forward it to Telegram."*
> *"Find the PDF on my phone that mentions 'invoice' and read me the first paragraph."*
> *"Take a screenshot every 30 minutes for the next 4 hours so I can see what I actually did all afternoon."*
> *"Use Termux to build me a webpage listing everything you can do, then open it in my browser."*
> *"When I plug in headphones at home WiFi after 7pm, start my evening playlist."*
> *"Open my router's admin page, sign in with the saved password, and tell me which devices are eating the most bandwidth right now."*
> *"Spin up two researches in parallel: one finds the cheapest one-way flight to Tokyo this month, the other lists hotels in Shibuya under $100."*

Each of those is a one-line setup.

---

## Features

### Device Control

Tap, swipe, scroll, type, take screenshots, open apps, adjust brightness/volume, post notifications, check battery/WiFi/signal/location/sensors, read contacts & SMS, send SMS, set wallpaper, read/write NFC tags, sign and encrypt data with the Android Keystore, access external storage and SD cards, and manage ZIP archives. **80+ tools**, all built into Android. Each one stays off until you flip it on.

### Chat

When a reply ends in an error or comes back with no visible text, a continue chip appears among the suggestions so you can nudge it forward with one tap. Tap any tool call in a message to open its details and re-run it with the same arguments, without spending a new turn. Tap a composer attachment before sending to preview it: images open in a swipeable viewer, other files open through the system chooser. Voice mode lets you talk to the assistant instead of typing, with speech recognition on the way in and optional spoken replies on the way out. Messages you send while a reply is still generating are queued and delivered in order rather than interrupting it.

### Shizuku

Install [Shizuku](https://github.com/RikkaApps/Shizuku/releases/latest) and the assistant can run shell commands at a higher privilege level than a normal app, without root and without Termux. Useful for the things Android normally refuses: granting permissions, poking at system settings, inspecting other packages. Settings shows live status so you can tell whether Shizuku is installed, running, and authorized.

Off by default on every assistant, behind Local Tools, Privileged tools. Enabling it takes effect on new conversations, so start a fresh chat after switching it on. Commands still pass the HARDLINE floor below, so the genuinely destructive ones stay blocked no matter how they are invoked.

### Workflows & Schedules

**Workflows** — Describe a trigger and action in plain language: *"when I get home, turn the ringer off."* 19 triggers (WiFi, Bluetooth, headphones, geofence, app launch, notifications, time, charging, screen state, and more) and 14 conditions (battery thresholds, sunrise/sunset, day-of-week, foreground app, screen state) decide when each fires. Receivers register only when needed — battery drain stays minimal.

**Schedules** — Run tasks on any cadence: *"every Monday at 8am"*, *"every two hours"*, *"next Friday at 3pm."* Survives reboots and battery saver. Let the AI think at runtime, or pre-bake fixed actions that don't burn tokens.

### Telegram Bot

Talk to your assistant from anywhere. Send a question, photo, PDF, or voice note. Approval prompts use simple Yes/No buttons. When the AI needs input, it pops a tappable multiple-choice question right in the chat. Long messages arrive as downloadable files. Message bursts are paced to avoid Telegram rate limits. Where Telegram is blocked, point the bot at a SOCKS5 or HTTP proxy in its settings; the proxy covers the bot's own traffic only.

### In-App Browser

A real browser built into the app. The AI clicks through cookie banners, fills search boxes, scrolls, and reads pages back to you. A live action trail above the page shows each step and whether it succeeded or failed, with a Stop button to interrupt the run mid-task. Streams fresh screenshots to your chat after every step. Floating chat pill lets you keep talking to the AI without leaving the page. Built-in article extraction and diff-after-action keep token costs low.

### Web Search & Fetch

Search works with no API key out of the box: the **Built-in** engine (DuckDuckGo) is the default, and anti-bot blocks report an honest retryable error instead of a silent "no results" thanks to a circuit breaker. The engine picker lists 19 in total if you'd rather bring your own key: Tavily, Exa, Brave, Perplexity, Jina, Firecrawl, SearXNG, Bing, Serper, Ollama, and more, plus a custom-script engine you can point anywhere.

Separately, the assistant can pull any page directly. **Web fetch and extract** is on by default (Settings → Search) and stays out of the per-assistant tool menu:

- `web_fetch` — retrieves a page, decodes it with the response charset, and paginates long documents instead of blowing the context window
- `web_extract` — jsoup-based readability pass that strips nav and boilerplate down to article text

Both are capped at 30 seconds, read bounded response bodies so a huge page can't OOM the app, and are blocked from private network targets at DNS resolution time.

### File Manager

Find files, read them, save new ones, copy, move, rename, delete. *"Find every PDF mentioning 'invoice' on my phone"* works in one sentence. System folders outside your app's sandbox are off-limits, even if you ask.

### Workspace

A real Linux environment on the phone. The AI runs shell commands, reads, writes, and patches files in it, creates folders, and browses the tree in a built-in file manager with a text editor, image thumbnails, video preview, and rendered previews of HTML and SVG files. Copy files in from anywhere on the device through the system file picker (up to 256 MiB), or export a whole folder back out to a location you pick.

Long-running work survives across turns: `workspace_run_background` starts a dev server, install, or file watcher and hands back a task id, `workspace_background_status` polls its recent output, and `workspace_background_kill` stops it. Task ids are scoped to their workspace, and deleting a workspace kills everything it started.

### SSH

Save your servers once. Run commands, upload files, pull backups, check disk space, tail logs — all from chat. Pipe input into commands, write remote files, or launch long-running servers that return a PID instead of hanging. Works on WiFi or cell.

### Music & Media

Play music through Android's normal media controls: lock-screen art, headphone keys, the works. Pause, resume, adjust volume — all from chat or Telegram. Your queue survives force-stops via snapshot fallback.

### Skills

Drop a Markdown skill file and the AI gains a new playbook. A bundled catalog ships with a QR generator, Wikipedia query box, piano, interactive map, and more. Two skills enabled out of the box: an always-on agent playbook and an OpenClaw converter. Add skills from a URL or by sharing a Markdown file into the app.

### Sub-Agents

For long tasks, the main assistant dispatches focused sub-agents into clean side-contexts, optionally on smaller, cheaper models: name a model when you dispatch and that sub-agent runs on it, so cheap work does not have to share the main model. Run multiple in parallel. Each result comes back as a single summary. `/stop` cascades cancellation through every active child in one tick.

Define a sub-agent once and reuse it: a profile pairs a name with its own system prompt and model, and the assistant dispatches to it by name instead of respelling the setup every time. Manage profiles in Settings.

### Context Compaction

Long agent runs fill the context window and eventually fail. Compaction summarizes older turns automatically when usage crosses a threshold you set, keeping recent tool calls and their results so the assistant does not lose the thread. Summaries are written alongside the conversation rather than over it, so the original messages stay readable. Can also be triggered by hand.

### Doctor

A built-in health checkup. Runs a full audit of permissions, background services, database integrity, network, Termux, and diagnostics, plus Shizuku, MCP servers, sub-agent profiles, skills, the image gallery, workspaces, the Telegram proxy, and context compaction. A tool-groups overview shows, per backend-dependent group (screen automation, app launcher, agent keyboard, notification listener, Termux, Shizuku, files, browser), whether it's in use and whether its backend is ready. Tap auto-fix to grant permissions, restart services, or rebuild search indexes. Also available remotely via `/doctor` on Telegram.

### MCP Servers

Connect [Model Context Protocol](https://modelcontextprotocol.io) servers and the AI gains whatever tools they expose. The AI can add, update, and manage MCP connections itself — every change is approval-gated.

### Notifications & External Triggers

The AI can read, summarize, and forward incoming notifications from apps you choose. The whitelist starts empty. Notifications the agent posts deep-link back to the conversation that produced them, so a tap opens the full reply even from a cold start. Other apps (Tasker, automation tools, ADB) can hand the agent tasks through the External Automation Intent API.

### Safety & Privacy

Three layers of protection:

1. **Per-assistant toggles** — Every tool starts off. Flip on only what you want.
2. **Per-call approval** — Tools that change something ask before running.
3. **HARDLINE floor** — Genuinely dangerous commands (wipe, reboot, fork bombs, system file destruction) are blocked unconditionally.

Passwords and API keys never hit log files. Cloud backups skip saved credentials. The Telegram bot ignores everyone except your allowlist. Web fetches are refused at DNS resolution if they resolve to a private network address, so the assistant cannot be talked into probing your LAN or a cloud metadata endpoint.

---

## Quick Start

### 1. Install

Download the latest `*-release.apk` from [Releases](https://github.com/ExTV/rikkahub-agent/releases/latest). Allow install from unknown sources, then open.

> **Note:** If you have an old debug build installed, uninstall it first — the release build is signed differently.

> **Upgrading from before `2.3.1-agent.0`?** The app ID changed to `excp.rikkahub` so the fork installs alongside upstream RikkaHub. To migrate your data: open the old app → Settings → Backup → install this release → restore the backup.

### 2. Add an LLM Provider

**Settings → Providers → pick one → paste your API key.**

- **OpenRouter** — first-class support with auto-detected model capabilities, pricing, and routing, plus a fallback model list tried in order when your primary is down, rate-limited, or refuses
- **Codex** — sign in with your ChatGPT account (OpenAI plan over OAuth)
- **Grok** — sign in with your xAI account (SuperGrok or X Premium+ over OAuth)
- **Local · LiteRT** — download a local model (Gemma, Qwen). No key, no network. Runs on-device with GPU acceleration where supported
- **AICore** — Pixel 8/9/10 users can enable Gemini Nano for on-device inference (currently requires the AICore Beta)

### 3. Turn On What You Want

**Settings → Assistants → tap your assistant → Local Tools** — flip the categories you want enabled.

If you don't turn anything on, the app behaves exactly like vanilla RikkaHub. Can't find a toggle? Tap the search icon in Settings' top bar to search every page by name.

### 4. (Optional) Telegram Bot

1. Message [@BotFather](https://t.me/BotFather) with `/newbot` to get a token
2. Message [@userinfobot](https://t.me/userinfobot) with `/start` to get your numeric user ID
3. Tell the assistant: *"Set up the Telegram bot. Token is `<token>`. My user id is `<id>`. Set me as the default chat. Enable it."*

---

## Requirements

| | |
|---|---|
| **Architecture** | arm64 or x86_64 |
| **Android** | 8.0+ (API 26), targets API 37 |
| **Storage** | ~80 MB |
| **LLM Provider** | OpenAI, Google, Anthropic, OpenRouter, Codex, Grok, Ollama, or any OpenAI-compatible endpoint. OR a Google account sign-in instead of a Gemini API key. OR Gemini Nano via AICore on Pixel 8/9/10+ |

---

## Languages

The interface ships in **English, 简体中文, 繁體中文, 日本語, 한국어, Русский, and العربية**. The app follows your system language and falls back to English. RTL languages (Arabic, Persian, Urdu) render correctly in chat — code blocks stay LTR. Arabic now covers the full interface rather than a subset.

Screen readers are supported throughout: icon-only buttons, attachment chips, and the media and speech controls carry spoken labels, so the app is navigable with TalkBack.

---

## Building from Source

Requires [bun](https://bun.sh) and [pnpm](https://pnpm.io) on your PATH — bun installs the web-ui dependencies, pnpm builds the bundle.

```bash
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent
./gradlew :app:installDebug
```

---

## Credits

Stands on the shoulders of giants:

| Project | Role |
|---|---|
| [RikkaHub](https://github.com/rikkahub/rikkahub) | The upstream chat client this forks |
| [cron-utils](https://github.com/jmrozanec/cron-utils) | Cron parser for the scheduler |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | On-device speech-to-text via Termux |
| [Termux](https://github.com/termux/termux-app) | Shell + package manager |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | Native SSH client |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Base for the companion [agent-keyboard](https://github.com/ExTV/agent-keyboard) |

This fork is unaffiliated with upstream RikkaHub maintainers. All credit for the underlying chat client, provider abstraction, and UI design goes to the upstream team.

---

## License

GNU AGPL-3.0, inherited from [upstream](https://github.com/rikkahub/rikkahub). See [LICENSE](LICENSE).
