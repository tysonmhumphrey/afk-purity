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
        keyName = "importedGameSeconds",
        name = "Imported game time (seconds)",
        description = "Lifetime logged-in time from another computer/install. Add the old total here when migrating."
    )
    default long importedGameSeconds()
    {
        return 0L;
    }

    @Range(min = 0)
    @ConfigItem(
        keyName = "importedNonAfkSeconds",
        name = "Imported non-AFK time (seconds)",
        description = "Lifetime non-AFK time from another computer/install. Add the old total here when migrating."
    )
    default long importedNonAfkSeconds()
    {
        return 0L;
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show AFK Purity overlay",
        description = "Show lifetime AFK Purity and timer totals on screen."
    )
    default boolean showOverlay()
    {
        return true;
    }
}
