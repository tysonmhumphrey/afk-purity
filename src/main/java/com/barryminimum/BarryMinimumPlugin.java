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
import net.runelite.api.events.VarClientIntChanged;
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
    /*
     * OSRS client variable containing total account playtime in minutes.
     */
    private static final int PLAYTIME_VARC = 526;

    /*
     * Stored separately for each RuneScape profile.
     */
    private static final String STORED_NON_AFK_MS = "storedNonAfkMs";

    private static final long MAX_TICK_GAP_MS = 2_000L;

    @Inject
    private Client client;

    @Inject
    private BarryMinimumConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BarryMinimumOverlay overlay;

    /*
     * Total non-AFK time accumulated on this installation for the
     * currently logged-in RuneScape profile.
     */
    private long storedNonAfkMs;

    /*
     * Authoritative OSRS playtime.
     *
     * OSRS supplies this in whole minutes. We store the synchronized
     * value in milliseconds and locally advance it while logged in.
     */
    private long syncedGameMs;

    /*
     * Time at which syncedGameMs was last synchronized with OSRS.
     */
    private Instant gameTimeSyncInstant;

    /*
     * Used for non-AFK accounting.
     */
    private Instant lastAccountingTime;
    private Instant lastQualifyingInteraction;
    private boolean nonAfk;

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadProfileData();
            syncGameTimeFromOsrs();

            lastAccountingTime = Instant.now();
        }

        log.debug("Barry Minimum tracker started");
    }

    @Override
    protected void shutDown()
    {
        accountUntil(Instant.now());
        persistProfileData();

        overlayManager.remove(overlay);

        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

        nonAfk = false;

        log.debug("Barry Minimum tracker stopped");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        Instant now = Instant.now();

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            /*
             * We have entered a game world. Load data belonging specifically
             * to this RuneScape profile and obtain the OSRS playtime value.
             */
            loadProfileData();
            syncGameTimeFromOsrs();

            lastAccountingTime = now;
            lastQualifyingInteraction = null;
            nonAfk = false;

            return;
        }

        /*
         * Stop all clocks whenever Barry is not actually logged into a world.
         */
        accountUntil(now);
        persistProfileData();

        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

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
        persistProfileData();
    }

    /*
     * OSRS may update the account playtime client variable while we are
     * logged in. Whenever VARC 526 changes, resynchronize to the
     * authoritative value supplied by the game.
     */
    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (event.getIndex() == PLAYTIME_VARC)
        {
            syncGameTimeFromOsrs();
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (client.getGameState() != GameState.LOGGED_IN
                || !isQualifyingInteraction(event))
        {
            return;
        }

        Instant now = Instant.now();

        accountUntil(now);

        long windowSeconds = config.activeWindowSeconds();

        if (nonAfk)
        {
            /*
             * Any qualifying interaction while ACTIVE resets the countdown.
             */
            lastQualifyingInteraction = now;
        }
        else if (lastQualifyingInteraction != null
                && !now.isAfter(
                lastQualifyingInteraction.plusSeconds(windowSeconds)))
        {
            /*
             * The SECOND qualifying interaction inside the window begins
             * non-AFK time.
             */
            nonAfk = true;
            lastQualifyingInteraction = now;
        }
        else
        {
            /*
             * An isolated interaction does not count as non-AFK.
             */
            lastQualifyingInteraction = now;
        }

        persistProfileData();

        log.debug(
                "Qualifying interaction: {} {} | nonAfk={}",
                event.getMenuAction(),
                event.getMenuOption(),
                nonAfk
        );
    }

    /*
     * Add non-AFK wall time since the previous accounting point.
     *
     * While ACTIVE, only time up to the exact inactivity deadline
     * is charged as non-AFK.
     */
    private void accountUntil(Instant now)
    {
        if (lastAccountingTime == null
                || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        long elapsedMs =
                Duration.between(lastAccountingTime, now).toMillis();

        if (elapsedMs < 0)
        {
            lastAccountingTime = now;
            return;
        }

        /*
         * Prevent a suspended/frozen computer from accidentally adding
         * a large amount of non-AFK time.
         */
        elapsedMs = Math.min(elapsedMs, MAX_TICK_GAP_MS);

        if (nonAfk && lastQualifyingInteraction != null)
        {
            Instant deadline =
                    lastQualifyingInteraction.plusSeconds(
                            config.activeWindowSeconds()
                    );

            Instant chargedUntil =
                    now.isBefore(deadline) ? now : deadline;

            if (chargedUntil.isAfter(lastAccountingTime))
            {
                long nonAfkMs =
                        Duration.between(
                                lastAccountingTime,
                                chargedUntil
                        ).toMillis();

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

    /*
     * Read the authoritative account playtime supplied by OSRS.
     *
     * VARC 526 represents total account playtime in minutes.
     */
    private void syncGameTimeFromOsrs()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        int gameMinutes = client.getVarcIntValue(PLAYTIME_VARC);

        /*
         * Ignore an unavailable/invalid value rather than replacing a
         * previously valid synchronization with zero.
         */
        if (gameMinutes < 0)
        {
            return;
        }

        syncedGameMs = gameMinutes * 60_000L;
        gameTimeSyncInstant = Instant.now();

        log.debug(
                "OSRS playtime synchronized: {} minutes",
                gameMinutes
        );
    }

    /*
     * Load accumulated non-AFK time for the currently logged-in
     * RuneScape profile.
     */
    private void loadProfileData()
    {
        String value = configManager.getRSProfileConfiguration(
                BarryMinimumConfig.GROUP,
                STORED_NON_AFK_MS
        );

        if (value == null || value.isEmpty())
        {
            storedNonAfkMs = 0L;
            return;
        }

        try
        {
            storedNonAfkMs = Long.parseLong(value);
        }
        catch (NumberFormatException ex)
        {
            log.warn(
                    "Invalid persisted Barry Minimum non-AFK value: {}",
                    value
            );

            storedNonAfkMs = 0L;
        }
    }

    /*
     * Save accumulated non-AFK time specifically to the current
     * RuneScape profile.
     */
    private void persistProfileData()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        configManager.setRSProfileConfiguration(
                BarryMinimumConfig.GROUP,
                STORED_NON_AFK_MS,
                storedNonAfkMs
        );
    }

    /*
     * Conservative definition of a qualifying interaction.
     *
     * UI navigation, Examine, Cancel, and RuneLite-only menu actions
     * remain excluded.
     */
    boolean isQualifyingInteraction(MenuOptionClicked event)
    {
        if (event.isItemOp())
        {
            /*
             * Inventory actions such as Eat, Drop, Wear/Wield, etc.
             */
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

    /*
     * Current lifetime account playtime.
     *
     * Start with the most recent OSRS-authoritative value, then advance
     * it locally for the amount of time spent logged in since that sync.
     */
    long getLifetimeGameMs()
    {
        if (gameTimeSyncInstant == null)
        {
            return syncedGameMs;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return syncedGameMs;
        }

        long sinceSync =
                Duration.between(
                        gameTimeSyncInstant,
                        Instant.now()
                ).toMillis();

        return syncedGameMs + Math.max(0L, sinceSync);
    }

    /*
     * Starting value manually supplied by the user
     * +
     * non-AFK time accumulated on this installation/profile.
     */
    long getLifetimeNonAfkMs()
    {
        long startingNonAfkMs =
                config.startingNonAfkDays() * 86_400_000L
                        + config.startingNonAfkHours() * 3_600_000L
                        + config.startingNonAfkMinutes() * 60_000L;

        return storedNonAfkMs + startingNonAfkMs;
    }

    double getAfkPurity()
    {
        long total = getLifetimeGameMs();

        if (total <= 0)
        {
            return 100.0;
        }

        long active =
                Math.min(getLifetimeNonAfkMs(), total);

        return 100.0
                * (1.0 - ((double) active / (double) total));
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

        long millis =
                Duration.between(
                        Instant.now(),
                        lastQualifyingInteraction.plusSeconds(
                                config.activeWindowSeconds()
                        )
                ).toMillis();

        return Math.max(
                0L,
                (millis + 999L) / 1000L
        );
    }

    @Provides
    BarryMinimumConfig provideConfig(
            ConfigManager configManager)
    {
        return configManager.getConfig(
                BarryMinimumConfig.class
        );
    }
}