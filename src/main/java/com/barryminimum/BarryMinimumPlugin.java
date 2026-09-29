package com.barryminimum;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "Barry Minimum",
    description = "Tracks Barry Minimum's lifetime AFK Purity.",
    tags = {"afk", "tracker", "challenge", "metrics"}
)
public class BarryMinimumPlugin extends Plugin
{
    private static final String STORED_GAME_MS = "storedGameMs";
    private static final String STORED_NON_AFK_MS = "storedNonAfkMs";
    private static final long MAX_TICK_GAP_MS = 2_000L;

    @Inject private Client client;
    @Inject private BarryMinimumConfig config;
    @Inject private ConfigManager configManager;
    @Inject private OverlayManager overlayManager;
    @Inject private BarryMinimumOverlay overlay;

    private long storedGameMs;
    private long storedNonAfkMs;

    private Instant lastAccountingTime;
    private Instant lastQualifyingInteraction;
    private boolean nonAfk;

    @Override
    protected void startUp()
    {
        storedGameMs = readLong(STORED_GAME_MS);
        storedNonAfkMs = readLong(STORED_NON_AFK_MS);
        overlayManager.add(overlay);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            lastAccountingTime = Instant.now();
        }

        log.debug("Barry Minimum tracker started");
    }

    @Override
    protected void shutDown()
    {
        accountUntil(Instant.now());
        persist();
        overlayManager.remove(overlay);
        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        nonAfk = false;
        log.debug("Barry Minimum tracker stopped");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        Instant now = Instant.now();

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            lastAccountingTime = now;
            return;
        }

        // Stop all clocks whenever Barry is not actually logged into a world.
        accountUntil(now);
        persist();
        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        nonAfk = false;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Instant now = Instant.now();
        accountUntil(now);
        persist();
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (client.getGameState() != GameState.LOGGED_IN || !isQualifyingInteraction(event))
        {
            return;
        }

        Instant now = Instant.now();
        accountUntil(now);

        long windowSeconds = config.activeWindowSeconds();

        if (nonAfk)
        {
            // A qualifying interaction while active simply pushes the inactivity deadline out again.
            lastQualifyingInteraction = now;
        }
        else if (lastQualifyingInteraction != null
            && !now.isAfter(lastQualifyingInteraction.plusSeconds(windowSeconds)))
        {
            // The SECOND qualifying interaction inside the window starts non-AFK time.
            nonAfk = true;
            lastQualifyingInteraction = now;
        }
        else
        {
            // An isolated interaction remains AFK; remember it in case another follows soon.
            lastQualifyingInteraction = now;
        }

        persist();
        log.debug("Qualifying interaction: {} {} | nonAfk={}",
            event.getMenuAction(), event.getMenuOption(), nonAfk);
    }

    /**
     * Add logged-in wall time since the previous accounting point. While non-AFK,
     * only time up to the exact 30-second inactivity deadline is charged as non-AFK.
     */
    private void accountUntil(Instant now)
    {
        if (lastAccountingTime == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        long elapsedMs = Duration.between(lastAccountingTime, now).toMillis();
        if (elapsedMs < 0)
        {
            lastAccountingTime = now;
            return;
        }

        // Prevent suspend/resume or a frozen client from inventing hours of logged-in time.
        elapsedMs = Math.min(elapsedMs, MAX_TICK_GAP_MS);
        storedGameMs += elapsedMs;

        if (nonAfk && lastQualifyingInteraction != null)
        {
            Instant deadline = lastQualifyingInteraction.plusSeconds(config.activeWindowSeconds());
            Instant chargedUntil = now.isBefore(deadline) ? now : deadline;

            if (chargedUntil.isAfter(lastAccountingTime))
            {
                long nonAfkMs = Duration.between(lastAccountingTime, chargedUntil).toMillis();
                storedNonAfkMs += Math.min(nonAfkMs, elapsedMs);
            }

            if (!now.isBefore(deadline))
            {
                nonAfk = false;
                lastQualifyingInteraction = null;
            }
        }

        lastAccountingTime = now;
    }

    /**
     * Conservative v0.1 definition of a qualifying interaction.
     * UI navigation, Examine, Cancel, and RuneLite-only menu actions are intentionally excluded.
     */
    boolean isQualifyingInteraction(MenuOptionClicked event)
    {
        if (event.isItemOp())
        {
            // Inventory actions such as Eat, Drop, Wear/Wield, etc.
            return true;
        }

        MenuAction action = event.getMenuAction();
        switch (action)
        {
            case WALK:
            case GAME_OBJECT_FIRST_OPTION:
            case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GAME_OBJECT:
            case NPC_FIRST_OPTION:
            case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC:
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case PLAYER_EIGHTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GROUND_ITEM:
            case WIDGET_CONTINUE:
            case WORLD_ENTITY_FIRST_OPTION:
            case WORLD_ENTITY_SECOND_OPTION:
            case WORLD_ENTITY_THIRD_OPTION:
            case WORLD_ENTITY_FOURTH_OPTION:
            case WORLD_ENTITY_FIFTH_OPTION:
                return true;
            default:
                return false;
        }
    }

    private long readLong(String key)
    {
        String value = configManager.getConfiguration(BarryMinimumConfig.GROUP, key);
        if (value == null || value.isEmpty())
        {
            return 0L;
        }

        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ex)
        {
            log.warn("Invalid persisted Barry Minimum value for {}: {}", key, value);
            return 0L;
        }
    }

    private void persist()
    {
        configManager.setConfiguration(BarryMinimumConfig.GROUP, STORED_GAME_MS, storedGameMs);
        configManager.setConfiguration(BarryMinimumConfig.GROUP, STORED_NON_AFK_MS, storedNonAfkMs);
    }

    long getLifetimeGameMs()
    {
        return storedGameMs + config.importedGameSeconds() * 1000L;
    }

    long getLifetimeNonAfkMs()
    {
        return storedNonAfkMs + config.importedNonAfkSeconds() * 1000L;
    }

    double getAfkPurity()
    {
        long total = getLifetimeGameMs();
        if (total <= 0)
        {
            return 100.0;
        }

        long active = Math.min(getLifetimeNonAfkMs(), total);
        return 100.0 * (1.0 - ((double) active / (double) total));
    }

    boolean isNonAfk()
    {
        return nonAfk;
    }

    long getSecondsUntilAfk()
    {
        if (!nonAfk || lastQualifyingInteraction == null)
        {
            return 0L;
        }

        long millis = Duration.between(Instant.now(),
            lastQualifyingInteraction.plusSeconds(config.activeWindowSeconds())).toMillis();
        return Math.max(0L, (millis + 999L) / 1000L);
    }

    @Provides
    BarryMinimumConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BarryMinimumConfig.class);
    }
}
