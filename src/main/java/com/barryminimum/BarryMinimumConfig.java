package com.barryminimum;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(BarryMinimumConfig.GROUP)
public interface BarryMinimumConfig extends Config
{
    String GROUP = "barryminimum";

    @Range(min = 1, max = 300)
    @ConfigItem(keyName = "activeWindowSeconds", name = "Non-AFK countdown (seconds)",
            description = "Seconds before the character returns to AFK status.", position = 0)
    default int activeWindowSeconds() { return 30; }

    @Range(min = 0)
    @ConfigItem(keyName = "totalNonAfkDays", name = "Total non-AFK days",
            description = "Live lifetime total. Change this number to correct the total.", position = 1)
    default int totalNonAfkDays() { return 0; }

    @Range(min = 0, max = 23)
    @ConfigItem(keyName = "totalNonAfkHours", name = "Total non-AFK hours",
            description = "Live lifetime hours (0-23). Change this number to correct the total.", position = 2)
    default int totalNonAfkHours() { return 0; }

    @Range(min = 0, max = 59)
    @ConfigItem(keyName = "totalNonAfkMinutes", name = "Total non-AFK minutes",
            description = "Live lifetime minutes (0-59). Change this number to correct the total.", position = 3)
    default int totalNonAfkMinutes() { return 0; }

    @Range(min = 0, max = 59)
    @ConfigItem(keyName = "totalNonAfkSeconds", name = "Total non-AFK seconds",
            description = "Live lifetime seconds (0-59). Change this number to correct the total.", position = 4)
    default int totalNonAfkSeconds() { return 0; }

    @Range(min = 10, max = 600)
    @ConfigItem(keyName = "inactiveThresholdSeconds", name = "Inactive after idle (seconds)",
            description = "After this many seconds of confirmed idleness, time is excluded from AFK Purity.", position = 5)
    default int inactiveThresholdSeconds() { return 60; }

    @Range(min = 0)
    @ConfigItem(keyName = "totalInactiveDays", name = "Total inactive days",
            description = "Live lifetime inactive days. Editable.", position = 6)
    default int totalInactiveDays() { return 0; }

    @Range(min = 0, max = 23)
    @ConfigItem(keyName = "totalInactiveHours", name = "Total inactive hours",
            description = "Live lifetime inactive hours. Editable.", position = 7)
    default int totalInactiveHours() { return 0; }

    @Range(min = 0, max = 59)
    @ConfigItem(keyName = "totalInactiveMinutes", name = "Total inactive minutes",
            description = "Live lifetime inactive minutes. Editable.", position = 8)
    default int totalInactiveMinutes() { return 0; }

    @Range(min = 0, max = 59)
    @ConfigItem(keyName = "totalInactiveSeconds", name = "Total inactive seconds",
            description = "Live lifetime inactive seconds. Editable.", position = 9)
    default int totalInactiveSeconds() { return 0; }

    @ConfigItem(keyName = "trackGpPerHour", name = "Track GP/hr",
            description = "Track qualifying inventory gains over the previous 60 minutes.", position = 10)
    default boolean trackGpPerHour() { return false; }

    @ConfigItem(keyName = "showOverlay", name = "Show Barry Minimum overlay",
            description = "Show AFK Purity, status and GP/hour on screen.", position = 11)
    default boolean showOverlay() { return true; }
}
