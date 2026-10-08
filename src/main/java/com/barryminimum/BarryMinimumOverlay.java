package com.barryminimum;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class BarryMinimumOverlay extends OverlayPanel
{
    private static final int MINIMUM_WIDTH = 210;

    private static final Color GOOD_COLOR = Color.GREEN;
    private static final Color BAD_COLOR = Color.RED;
    private static final Color NORMAL_COLOR = Color.WHITE;

    private final BarryMinimumPlugin plugin;
    private final BarryMinimumConfig config;

    @Inject
    private BarryMinimumOverlay(
            BarryMinimumPlugin plugin,
            BarryMinimumConfig config)
    {
        this.plugin = plugin;
        this.config = config;

        panelComponent.setPreferredSize(
                new Dimension(MINIMUM_WIDTH, 0)
        );
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay())
        {
            return null;
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Barry Minimum")
                        .build()
        );

        /*
         * AFK PURITY
         *
         * 0 - 74.99%  = red
         * 75 - 89.99% = normal
         * 90 - 100%   = green
         */
        double afkPurity = plugin.getAfkPurity();

        Color afkPurityColor;

        if (afkPurity < 75.0)
        {
            afkPurityColor = BAD_COLOR;
        }
        else if (afkPurity < 90.0)
        {
            afkPurityColor = NORMAL_COLOR;
        }
        else
        {
            afkPurityColor = GOOD_COLOR;
        }

        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("AFK Purity")
                        .right(String.format("%.2f%%", afkPurity))
                        .rightColor(afkPurityColor)
                        .build()
        );

        /*
         * STATUS
         */
        boolean active = plugin.isNonAfk();
        boolean inactive = plugin.isInactive();

        String statusText = active
                ? "ACTIVE (" + plugin.getSecondsUntilAfk() + "s)"
                : inactive ? "INACTIVE" : "AFK";

        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("Status")
                        .right(statusText)
                        .rightColor(active ? BAD_COLOR : inactive ? Color.YELLOW : GOOD_COLOR)
                        .build()
        );

        /*
         * ROLLING GP/HR
         *
         * This is the total GE value of qualifying items obtained
         * during the previous 60 minutes.
         */
        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("GP / Hour")
                        .right(config.trackGpPerHour()
                                ? plugin.getFormattedGpPerHour() : "Not Active")
                        .rightColor(config.trackGpPerHour() ? NORMAL_COLOR : BAD_COLOR)
                        .build()
        );

        return super.render(graphics);
    }
}