package com.afkpurity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

/** Per-RS-profile episode baselines and daily purity history. No network access. */
final class AfkPurityProgress
{
    private static final String EPISODE_KEY = "episodeBaselineV1";
    private static final String HISTORY_KEY = "purityHistoryV1";
    private static final String EPISODE_TIMES_KEY = "episodeTimesV2";
    private long episodePlayMs, episodeActiveMs, episodeInactiveMs;
    private boolean episodeTimesAvailable;
    private long lastTimesSave;
    private static final int MAX_DAYS = 180;
    private final ConfigManager config;
    private final Client client;
    private final AfkPurityPlugin plugin;
    private Snapshot baseline;
    private final List<DayPoint> history = new ArrayList<>();
    private long episodeGp;
    private boolean loaded;

    AfkPurityProgress(ConfigManager config, Client client, AfkPurityPlugin plugin)
    {
        this.config = config;
        this.client = client;
        this.plugin = plugin;
    }

    void load()
    {
        loaded = true;
        episodeGp = 0;
        baseline = null;
        history.clear();
        String encoded = read(EPISODE_KEY);
        if (encoded != null)
        {
            try { baseline = Snapshot.decode(encoded); }
            catch (RuntimeException ignored) { baseline = null; }
        }
        String data = read(HISTORY_KEY);
        if (data != null)
        {
            for (String row : data.split(";"))
            {
                try
                {
                    String[] fields = row.split(",", -1);
                    if (fields.length == 2)
                    {
                        history.add(new DayPoint(LocalDate.parse(fields[0]), Double.parseDouble(fields[1])));
                    }
                }
                catch (RuntimeException ignored) { /* Skip malformed history rows. */ }
            }
        }
        if (baseline != null)
        {
            episodeGp = Math.max(0L, readLong("episodeGpV1"));
            String times = read(EPISODE_TIMES_KEY);
            if (times != null)
            {
                try
                {
                    String[] parts = times.split(":");
                    if (parts.length == 3)
                    {
                        episodePlayMs = Math.max(0L, Long.parseLong(parts[0]));
                        episodeActiveMs = Math.max(0L, Long.parseLong(parts[1]));
                        episodeInactiveMs = Math.max(0L, Long.parseLong(parts[2]));
                        episodeTimesAvailable = true;
                    }
                }
                catch (RuntimeException ignored) { /* Old or malformed record. */ }
            }
        }
    }

    void unload()
    {
        loaded = false;
        baseline = null;
        history.clear();
        episodeGp = 0;
        episodePlayMs = episodeActiveMs = episodeInactiveMs = 0L;
        episodeTimesAvailable = false;
    }

    boolean isLoaded() { return loaded; }
    boolean hasEpisode() { return baseline != null; }

    void beginEpisode()
    {
        if (!loaded) return;
        baseline = capture();
        episodeGp = 0L;
        episodePlayMs = episodeActiveMs = episodeInactiveMs = 0L;
        episodeTimesAvailable = true;
        write(EPISODE_TIMES_KEY, "0:0:0");
        write(EPISODE_KEY, baseline.encode());
        write("episodeGpV1", "0");
    }

    /** Called for each logged-in accounting interval. Exactly one category owns each millisecond. */
    void recordInterval(long elapsedMs, long activeMs, long inactiveMs)
    {
        if (!loaded || baseline == null || !episodeTimesAvailable || elapsedMs <= 0) return;
        long active = Math.min(elapsedMs, Math.max(0L, activeMs));
        long inactive = Math.min(elapsedMs - active, Math.max(0L, inactiveMs));
        episodePlayMs = safeAdd(episodePlayMs, elapsedMs);
        episodeActiveMs = safeAdd(episodeActiveMs, active);
        episodeInactiveMs = safeAdd(episodeInactiveMs, inactive);
        if (System.currentTimeMillis() - lastTimesSave >= 10_000L) saveEpisodeTimes();
    }

    void saveEpisodeTimes()
    {
        if (!loaded || !episodeTimesAvailable) return;
        write(EPISODE_TIMES_KEY, episodePlayMs + ":" + episodeActiveMs + ":" + episodeInactiveMs);
        lastTimesSave = System.currentTimeMillis();
    }

    void addGp(long value)
    {
        if (!loaded || baseline == null || value <= 0) return;
        episodeGp = safeAdd(episodeGp, value);
        write("episodeGpV1", Long.toString(episodeGp));
    }

    void sampleDaily()
    {
        if (!loaded) return;
        LocalDate today = LocalDate.now();
        double purity = plugin.getAfkPurity();
        if (!history.isEmpty() && history.get(history.size() - 1).date.equals(today))
        {
            history.set(history.size() - 1, new DayPoint(today, purity));
        }
        else
        {
            history.add(new DayPoint(today, purity));
            while (history.size() > MAX_DAYS) history.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (DayPoint point : history)
        {
            if (sb.length() > 0) sb.append(';');
            sb.append(point.date).append(',').append(String.format(Locale.ROOT, "%.4f", point.purity));
        }
        write(HISTORY_KEY, sb.toString());
    }

    List<DayPoint> getHistory() { return Collections.unmodifiableList(new ArrayList<>(history)); }

    String report()
    {
        if (!loaded) return "Log into your OSRS account to view progress.";
        if (baseline == null) return "Select Start New Episode to begin recording an episode baseline. Lifetime tracking is unaffected.";
        Snapshot now = capture();
        long game = episodeTimesAvailable ? episodePlayMs : Math.max(0L, now.gameMs - baseline.gameMs);
        long active = episodeTimesAvailable ? episodeActiveMs : Math.max(0L, now.nonAfkMs - baseline.nonAfkMs);
        long inactive = episodeTimesAvailable ? episodeInactiveMs : Math.max(0L, now.inactiveMs - baseline.inactiveMs);
        long afk = Math.max(0L, game - active - inactive);
        double change = now.purity - baseline.purity;
        StringBuilder sb = new StringBuilder();
        sb.append("AFK PURITY — EPISODE PROGRESS\n");
        if (!episodeTimesAvailable)
        {
            return "This episode was started before accurate episode-time tracking was installed. "
                    + "Please click Start New Episode once to begin reliable AFK/ACTIVE/INACTIVE totals. "
                    + "Your lifetime statistics and GP/hour tracking will not be reset.";
        }
        sb.append("Started: ").append(Instant.ofEpochMilli(baseline.at)).append("\n");
        sb.append("Elapsed account playtime: ").append(duration(game)).append("\n");
        sb.append("AFK time: ").append(duration(afk)).append("\n");
        sb.append("Non-AFK time: ").append(duration(active)).append("\n");
        sb.append("Inactive time: ").append(duration(inactive)).append("\n");
        sb.append(String.format(Locale.ROOT, "AFK Purity: %.2f%% (change %+.2f percentage points)\n", now.purity, change));
        sb.append("Tracked episode GP gained: ").append(String.format(Locale.US, "%,d", episodeGp)).append(" GP\n");
        int levelUps = 0;
        StringBuilder skills = new StringBuilder();
        Skill[] all = Skill.values();
        for (int i = 0; i < all.length && i < baseline.xp.length; i++)
        {
            if (all[i] == Skill.OVERALL) continue;
            int from = net.runelite.api.Experience.getLevelForXp(Math.max(0, baseline.xp[i]));
            int to = net.runelite.api.Experience.getLevelForXp(Math.max(0, now.xp[i]));
            if (to > from)
            {
                levelUps += to - from;
                if (skills.length() > 0) skills.append(", ");
                skills.append(all[i].getName()).append(" ").append(from).append("→").append(to);
            }
        }
        sb.append("Levels gained: ").append(levelUps).append("\n");
        sb.append("Skill milestones: ").append(skills.length() == 0 ? "None yet" : skills).append("\n");
        sb.append("\nNarration draft:\n");
        sb.append(String.format(Locale.ROOT,
                "Since the last episode, this account has spent %s playing, including %s of AFK gameplay. "
                        + "His AFK Purity is now %.2f%%, a change of %+.2f percentage points. "
                        + "He gained %d total skill levels and earned approximately %,d GP from tracked inventory gains.",
                duration(game), duration(afk), now.purity, change, levelUps, episodeGp));
        sb.append("\n\nNotes: GP only includes qualifying inventory gains while GP tracking was enabled. "
                + "Episode times are measured from logged-in intervals, separately from editable lifetime counters. "
                + "Past episodes created before this fix need a new baseline.");
        return sb.toString();
    }

    private Snapshot capture()
    {
        Skill[] skills = Skill.values();
        int[] xp = new int[skills.length];
        for (int i = 0; i < skills.length; i++)
        {
            xp[i] = Math.max(0, client.getSkillExperience(skills[i]));
        }
        return new Snapshot(System.currentTimeMillis(), plugin.getLifetimeGameMs(),
                plugin.getLifetimeNonAfkMs(), plugin.getLifetimeInactiveMs(), plugin.getAfkPurity(), xp);
    }

    static String duration(long ms)
    {
        long minutes = Math.max(0L, ms) / 60_000L;
        return (minutes / 60L) + "h " + (minutes % 60L) + "m";
    }

    private String read(String key) { return config.getRSProfileConfiguration(AfkPurityConfig.GROUP, key); }
    private void write(String key, String value) { config.setRSProfileConfiguration(AfkPurityConfig.GROUP, key, value); }
    private long readLong(String key)
    {
        try { return Long.parseLong(read(key)); }
        catch (RuntimeException ignored) { return 0L; }
    }
    private static long safeAdd(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }

    static final class DayPoint
    {
        final LocalDate date;
        final double purity;
        DayPoint(LocalDate date, double purity) { this.date = date; this.purity = purity; }
    }

    private static final class Snapshot
    {
        final long at, gameMs, nonAfkMs, inactiveMs;
        final double purity;
        final int[] xp;
        Snapshot(long at, long gameMs, long nonAfkMs, long inactiveMs, double purity, int[] xp)
        {
            this.at = at; this.gameMs = gameMs; this.nonAfkMs = nonAfkMs;
            this.inactiveMs = inactiveMs; this.purity = purity; this.xp = xp;
        }
        String encode()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(at).append('|').append(gameMs).append('|').append(nonAfkMs).append('|')
                    .append(inactiveMs).append('|').append(purity).append('|');
            for (int i = 0; i < xp.length; i++)
            {
                if (i > 0) sb.append(',');
                sb.append(xp[i]);
            }
            return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        static Snapshot decode(String encoded)
        {
            String raw = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 6) throw new IllegalArgumentException("Invalid baseline");
            String[] entries = fields[5].split(",");
            int[] xp = new int[entries.length];
            for (int i = 0; i < entries.length; i++) xp[i] = Integer.parseInt(entries[i]);
            return new Snapshot(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                    Long.parseLong(fields[2]), Long.parseLong(fields[3]),
                    Double.parseDouble(fields[4]), xp);
        }
    }
}
