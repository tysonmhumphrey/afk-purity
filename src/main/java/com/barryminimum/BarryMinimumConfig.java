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
            description = "After the second qualifying interaction, Barry remains non-AFK until this many seconds pass without another qualifying interaction.",
            position = 0
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
            keyName = "currentNonAfkTime",
            name = "Current non-AFK time",
            description = "Current lifetime non-AFK time, including the manual starting value and all time tracked by this installation.",
            position = 4
    )
    default String currentNonAfkTime()
    {
        return "0d 00h 00m 00s";
    }

    @ConfigItem(
            keyName = "trackGpPerHour",
            name = "Track GP/hr",
            description = "When enabled, qualifying items entering the inventory are added to the rolling 60-minute GP/hr calculation.",
            position = 5
    )
    default boolean trackGpPerHour()
    {
        return false;
    }

    @Range(min = 0)
    @ConfigItem(
            keyName = "startingLifetimeProfitGp",
            name = "Starting lifetime profit (GP)",
            description = "Lifetime realized GP earned before this installation.",
            position = 6
    )
    default int startingLifetimeProfitGp()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show Barry Minimum overlay",
            description = "Show Barry Minimum's AFK and economic metrics.",
            position = 7
    )
    default boolean showOverlay()
    {
        return true;
    }
}