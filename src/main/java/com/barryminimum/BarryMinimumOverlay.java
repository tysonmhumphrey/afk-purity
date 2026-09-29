package com.barryminimum;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class BarryMinimumOverlay extends OverlayPanel
{
    private final BarryMinimumPlugin plugin;
    private final BarryMinimumConfig config;

    @Inject
    private BarryMinimumOverlay(BarryMinimumPlugin plugin, BarryMinimumConfig config)
    {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay())
        {
            return null;
        }

        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Barry Minimum")
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("AFK Purity")
            .right(String.format("%.2f%%", plugin.getAfkPurity()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Game Time")
            .right(formatDuration(plugin.getLifetimeGameMs()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Non-AFK Time")
            .right(formatDuration(plugin.getLifetimeNonAfkMs()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Status")
            .right(plugin.isNonAfk() ? "ACTIVE (" + plugin.getSecondsUntilAfk() + "s)" : "AFK")
            .build());

        return super.render(graphics);
    }

    private static String formatDuration(long millis)
    {
        Duration d = Duration.ofMillis(Math.max(0L, millis));
        long hours = d.toHours();
        long minutes = d.minusHours(hours).toMinutes();
        long seconds = d.minusHours(hours).minusMinutes(minutes).getSeconds();
        return String.format("%dh %02dm %02ds", hours, minutes, seconds);
    }
}
