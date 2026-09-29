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
    @ConfigItem(
            keyName = "activeWindowSeconds",
            name = "Non-AFK countdown (seconds)",
            description = "After the second qualifying interaction, Barry remains non-AFK until this many seconds pass without another qualifying interaction."
    )
    default int activeWindowSeconds()
    {
        return 30;
    }

    @Range(min = 0)
    @ConfigItem(
            keyName = "startingNonAfkDays",
            name = "Starting non-AFK days",
            description = "Days of lifetime non-AFK time accumulated before this installation.",
            position = 1
    )
    default int startingNonAfkDays()
    {
        return 0;
    }

    @Range(min = 0, max = 23)
    @ConfigItem(
            keyName = "startingNonAfkHours",
            name = "Starting non-AFK hours",
            description = "Hours of lifetime non-AFK time accumulated before this installation.",
            position = 2
    )
    default int startingNonAfkHours()
    {
        return 0;
    }

    @Range(min = 0, max = 59)
    @ConfigItem(
            keyName = "startingNonAfkMinutes",
            name = "Starting non-AFK minutes",
            description = "Minutes of lifetime non-AFK time accumulated before this installation.",
            position = 3
    )
    default int startingNonAfkMinutes()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show AFK Purity overlay",
            description = "Show Barry Minimum's live AFK Purity and AFK status."
    )
    default boolean showOverlay()
    {
        return true;
    }
}