# AFK Purity

**AFK Purity** is a [RuneLite](https://runelite.net/) plugin for Old School RuneScape that tracks how much of your account's gameplay is spent AFK, actively interacting, or inactive. It also provides an optional account progress dashboard.

## Features

- **AFK Purity percentage:** Estimates the share of eligible logged-in playtime spent outside active interaction windows.
- **Activity status overlay:** Displays **ACTIVE** (red), **AFK** (green), or **INACTIVE** (yellow), alongside AFK Purity and optional GP/hour.
- **Configurable timers:** Adjust the active interaction window and the inactivity threshold.
- **Editable lifetime counters:** Review and correct lifetime Non-AFK and Inactive Time in the plugin settings.
- **Rolling GP/hour estimate:** Values qualifying positive inventory gains from the preceding 60 minutes when enabled.
- **Progress Dashboard:** Optional RuneLite sidebar with episode baselines, episode time breakdowns, skill-level gains, daily AFK Purity history, and a copyable progress report.
- **Per-account statistics:** Stores lifetime counters and episode progress using RuneLite's account-profile configuration.

## How AFK Purity is calculated

AFK Purity is based on the following formula:

```text
AFK Purity (%) = 100 × (Total Playtime − Inactive Time − Non-AFK Time)
                     / (Total Playtime − Inactive Time)
```

The plugin guards against invalid or zero denominators. **ACTIVE** time is accumulated during qualifying interaction windows. **INACTIVE** time begins after the plugin detects character idleness and the configured inactivity threshold elapses. Time outside these categories is treated as **AFK** for this metric.

These are plugin-defined estimates, not official OSRS account statistics. Some activities have intermittent animations, and detection may not perfectly distinguish productive AFK gameplay from idleness.

## GP/hour limitations

GP/hour is an **estimate of qualifying inventory gains**, not net profit. The plugin attempts to exclude gains from common transfer interfaces (such as banks, shops, trades, and the Grand Exchange) and recently dropped items picked back up. It cannot reliably distinguish every item source or uniquely identify identical ground items. The rolling estimate is based on the last 60 minutes of recorded gains, not a prediction of future earnings.

## Progress Dashboard

Open **AFK Purity Progress** from the RuneLite sidebar. Select **Start New Episode** to create a new progress baseline; this does **not** reset lifetime statistics. Use **Copy Report** to copy a summary of recorded episode progress. The dashboard is displayed in the RuneLite sidebar, not on the game canvas.

Episode and historical statistics are recorded prospectively: the plugin cannot reconstruct activity from before it was installed or an episode was started. Data is stored locally through RuneLite configuration; this plugin does not intentionally send gameplay statistics to an external analytics service.

## Installation

Once approved, install **AFK Purity** through RuneLite's **Plugin Hub**. Until then, developers can build and run this repository locally using the Gradle `run` task.

## Development

The project uses Java and Gradle. To build:

```bash
./gradlew clean build
```

To launch a development RuneLite client:

```bash
./gradlew run
```

The RuneLite development client may require additional setup to sign in with a Jagex Account. See the [RuneLite Plugin Hub developer guide](https://github.com/runelite/plugin-hub).

## Disclaimer

AFK Purity is an independent, community-developed plugin and is not affiliated with Jagex or the RuneLite developers. It does not automate gameplay.

## License

BSD 2-Clause License. See [LICENSE](LICENSE).

