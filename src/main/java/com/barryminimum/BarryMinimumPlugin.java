package com.barryminimum;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
        name = "Barry Minimum",
        description = "Tracks Barry Minimum's lifetime AFK Purity and economic progress.",
        tags = {"afk", "tracker", "challenge", "metrics", "profit", "bond"}
)
public class BarryMinimumPlugin extends Plugin
{
    /*
     * OSRS total account playtime in minutes.
     */
    private static final int PLAYTIME_VARC = 526;

    /*
     * Tradeable Old School Bond.
     */
    private static final int OLD_SCHOOL_BOND_ID = 13190;

    /*
     * Rolling GP/hr window.
     */
    private static final long GP_WINDOW_MS =
            60L * 60L * 1000L;

    /*
     * Profile-specific storage.
     */
    private static final String STORED_NON_AFK_MS =
            "storedNonAfkMs";

    private static final String STORED_LIFETIME_PROFIT_GP =
            "storedLifetimeProfitGp";

    private static final String STORED_BANK_CASH_GP =
            "storedBankCashGp";

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

    @Inject
    private ItemManager itemManager;

    /*
     * Lifetime AFK data.
     */
    private long storedNonAfkMs;

    /*
     * Lifetime realized profit.
     *
     * This remains separate from rolling GP/hr.
     */
    private long storedLifetimeProfitGp;

    /*
     * Actual coins currently stored in the bank.
     */
    private long storedBankCashGp;

    /*
     * Inventory snapshot used to identify positive inventory gains.
     */
    private final Map<Integer, Long> previousInventory =
            new HashMap<>();

    private boolean inventoryInitialized;

    /*
     * Each accepted loot gain is stored separately with the exact
     * time it was obtained.
     *
     * Once an entry becomes more than 60 minutes old, it is removed.
     */
    private final Deque<GpGain> rollingGpGains =
            new ArrayDeque<>();

    /*
     * OSRS account playtime.
     */
    private long syncedGameMs;
    private Instant gameTimeSyncInstant;

    /*
     * AFK accounting.
     */
    private Instant lastAccountingTime;
    private Instant lastQualifyingInteraction;
    private boolean nonAfk;

    /*
     * Simple record representing one qualifying value gain.
     */
    private static class GpGain
    {
        private final Instant timestamp;
        private final long value;

        private GpGain(
                Instant timestamp,
                long value)
        {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadProfileData();
            syncGameTimeFromOsrs();
            initializeInventorySnapshot();
            updateBankCashIfAvailable();

            lastAccountingTime = Instant.now();

            updateCurrentNonAfkReadout();
        }

        log.debug("Barry Minimum tracker started");
    }

    @Override
    protected void shutDown()
    {
        accountUntil(Instant.now());
        updateCurrentNonAfkReadout();
        persistProfileData();

        overlayManager.remove(overlay);

        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

        previousInventory.clear();
        inventoryInitialized = false;

        rollingGpGains.clear();

        nonAfk = false;

        log.debug("Barry Minimum tracker stopped");
    }

    @Subscribe
    public void onGameStateChanged(
            GameStateChanged event)
    {
        Instant now = Instant.now();

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            loadProfileData();
            syncGameTimeFromOsrs();

            /*
             * Existing inventory is the baseline.
             * Nothing already present counts as new loot.
             */
            initializeInventorySnapshot();

            updateBankCashIfAvailable();

            lastAccountingTime = now;
            lastQualifyingInteraction = null;
            nonAfk = false;

            updateCurrentNonAfkReadout();

            return;
        }

        accountUntil(now);
        updateCurrentNonAfkReadout();
        persistProfileData();

        lastAccountingTime = null;
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

        previousInventory.clear();
        inventoryInitialized = false;

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

        /*
         * Continuously expire GP gains that are more than
         * one hour old.
         */
        removeExpiredGpGains(now);

        /*
         * Keep the settings readout current.
         */
        updateCurrentNonAfkReadout();

        persistProfileData();
    }

    @Subscribe
    public void onVarClientIntChanged(
            VarClientIntChanged event)
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

    /*
     * BANK + INVENTORY TRACKING
     */
    @Subscribe
    public void onItemContainerChanged(
            ItemContainerChanged event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (event.getContainerId() == InventoryID.BANK)
        {
            updateBankCash(event.getItemContainer());
            return;
        }

        if (event.getContainerId() == InventoryID.INV)
        {
            processInventoryChange(
                    event.getItemContainer()
            );
        }
    }

    /*
     * BOND PROGRESS
     *
     * Only actual coins in the bank count.
     */
    private void updateBankCashIfAvailable()
    {
        ItemContainer bank =
                client.getItemContainer(InventoryID.BANK);

        if (bank != null)
        {
            updateBankCash(bank);
        }
    }

    private void updateBankCash(ItemContainer bank)
    {
        if (bank == null)
        {
            return;
        }

        long coins = 0L;

        for (Item item : bank.getItems())
        {
            if (item.getId() == ItemID.COINS)
            {
                coins += item.getQuantity();
            }
        }

        storedBankCashGp =
                Math.max(0L, coins);

        persistProfileData();

        log.debug(
                "Barry bank cash updated: {} GP",
                storedBankCashGp
        );
    }

    /*
     * INVENTORY BASELINE
     */
    private void initializeInventorySnapshot()
    {
        previousInventory.clear();

        ItemContainer inventory =
                client.getItemContainer(InventoryID.INV);

        if (inventory == null)
        {
            inventoryInitialized = false;
            return;
        }

        previousInventory.putAll(
                buildInventoryMap(inventory)
        );

        inventoryInitialized = true;
    }

    /*
     * ROLLING GP/HR TRACKING
     *
     * Positive inventory changes are potential loot.
     *
     * They only count when:
     *
     * 1. Track GP/hr is enabled.
     * 2. Bank is not open.
     * 3. Grand Exchange is not open.
     * 4. Shop is not open.
     * 5. Player trade is not open.
     *
     * This allows ordinary resources, drops, pick-ups, etc. to count
     * while excluding the major ways existing items enter inventory.
     */
    private void processInventoryChange(
            ItemContainer inventory)
    {
        if (inventory == null)
        {
            return;
        }

        Map<Integer, Long> currentInventory =
                buildInventoryMap(inventory);

        if (!inventoryInitialized)
        {
            previousInventory.clear();
            previousInventory.putAll(currentInventory);

            inventoryInitialized = true;
            return;
        }

        boolean canTrackGain =
                config.trackGpPerHour()
                        && !isBankOpen()
                        && !isGrandExchangeOpen()
                        && !isShopOpen()
                        && !isTradeOpen();

        if (canTrackGain)
        {
            Instant now = Instant.now();

            for (Map.Entry<Integer, Long> entry :
                    currentInventory.entrySet())
            {
                int itemId =
                        entry.getKey();

                long newQuantity =
                        entry.getValue();

                long oldQuantity =
                        previousInventory.getOrDefault(
                                itemId,
                                0L
                        );

                long gainedQuantity =
                        newQuantity - oldQuantity;

                if (gainedQuantity <= 0L)
                {
                    continue;
                }

                long unitValue;

                if (itemId == ItemID.COINS)
                {
                    unitValue = 1L;
                }
                else
                {
                    unitValue =
                            itemManager.getItemPrice(itemId);
                }

                if (unitValue <= 0L)
                {
                    continue;
                }

                long gainValue =
                        unitValue * gainedQuantity;

                rollingGpGains.addLast(
                        new GpGain(
                                now,
                                gainValue
                        )
                );

                log.debug(
                        "Rolling GP/hr gain: item={} qty={} unit={} value={}",
                        itemId,
                        gainedQuantity,
                        unitValue,
                        gainValue
                );
            }

            removeExpiredGpGains(now);
        }

        /*
         * Always update the snapshot, even when tracking is off.
         *
         * This is important: turning tracking back on must not cause
         * everything acquired while tracking was disabled to suddenly
         * appear as new loot.
         */
        previousInventory.clear();
        previousInventory.putAll(currentInventory);
    }

    private Map<Integer, Long> buildInventoryMap(
            ItemContainer inventory)
    {
        Map<Integer, Long> result =
                new HashMap<>();

        for (Item item : inventory.getItems())
        {
            int itemId =
                    item.getId();

            int quantity =
                    item.getQuantity();

            if (itemId <= 0 || quantity <= 0)
            {
                continue;
            }

            result.merge(
                    itemId,
                    (long) quantity,
                    Long::sum
            );
        }

        return result;
    }

    /*
     * Remove each gain exactly once it is more than
     * 60 minutes old.
     */
    private void removeExpiredGpGains(
            Instant now)
    {
        Instant cutoff =
                now.minusMillis(GP_WINDOW_MS);

        while (!rollingGpGains.isEmpty())
        {
            GpGain oldest =
                    rollingGpGains.peekFirst();

            if (oldest.timestamp.isAfter(cutoff))
            {
                break;
            }

            rollingGpGains.removeFirst();
        }
    }

    /*
     * Current value generated during the previous 60 minutes.
     */
    private long getRollingGpValue()
    {
        removeExpiredGpGains(
                Instant.now()
        );

        long total = 0L;

        for (GpGain gain : rollingGpGains)
        {
            total += gain.value;
        }

        return total;
    }

    /*
     * Major transfer/purchase interfaces.
     *
     * RuneLite exposes these widget groups directly. This lets us
     * suppress inventory gains caused by withdrawals, GE collection,
     * shop purchases, and player trades.
     */
    private boolean isBankOpen()
    {
        return client.getWidget(
                WidgetID.BANK_GROUP_ID,
                0
        ) != null;
    }

    private boolean isGrandExchangeOpen()
    {
        return client.getWidget(
                WidgetID.GRAND_EXCHANGE_GROUP_ID,
                0
        ) != null;
    }

    private boolean isShopOpen()
    {
        return client.getWidget(
                WidgetID.SHOP_GROUP_ID,
                0
        ) != null;
    }

    private boolean isTradeOpen()
    {
        return client.getWidget(
                WidgetID.PLAYER_TRADE_SCREEN_GROUP_ID,
                0
        ) != null;
    }

    /*
     * AFK INTERACTION TRACKING
     */
    @Subscribe
    public void onMenuOptionClicked(
            MenuOptionClicked event)
    {
        if (client.getGameState() != GameState.LOGGED_IN
                || !isQualifyingInteraction(event))
        {
            return;
        }

        Instant now =
                Instant.now();

        accountUntil(now);

        long windowSeconds =
                config.activeWindowSeconds();

        if (nonAfk)
        {
            lastQualifyingInteraction = now;
        }
        else if (
                lastQualifyingInteraction != null
                        && !now.isAfter(
                        lastQualifyingInteraction.plusSeconds(
                                windowSeconds
                        )
                )
        )
        {
            nonAfk = true;
            lastQualifyingInteraction = now;
        }
        else
        {
            lastQualifyingInteraction = now;
        }

        updateCurrentNonAfkReadout();
        persistProfileData();
    }

    private void accountUntil(
            Instant now)
    {
        if (lastAccountingTime == null
                || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        long elapsedMs =
                Duration.between(
                        lastAccountingTime,
                        now
                ).toMillis();

        if (elapsedMs < 0L)
        {
            lastAccountingTime = now;
            return;
        }

        elapsedMs =
                Math.min(
                        elapsedMs,
                        MAX_TICK_GAP_MS
                );

        if (nonAfk
                && lastQualifyingInteraction != null)
        {
            Instant deadline =
                    lastQualifyingInteraction.plusSeconds(
                            config.activeWindowSeconds()
                    );

            Instant chargedUntil =
                    now.isBefore(deadline)
                            ? now
                            : deadline;

            if (chargedUntil.isAfter(
                    lastAccountingTime))
            {
                long nonAfkMs =
                        Duration.between(
                                lastAccountingTime,
                                chargedUntil
                        ).toMillis();

                storedNonAfkMs +=
                        Math.min(
                                nonAfkMs,
                                elapsedMs
                        );
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
     * ACCOUNT PLAYTIME
     */
    private void syncGameTimeFromOsrs()
    {
        if (client.getGameState()
                != GameState.LOGGED_IN)
        {
            return;
        }

        int gameMinutes =
                client.getVarcIntValue(
                        PLAYTIME_VARC
                );

        if (gameMinutes < 0)
        {
            return;
        }

        syncedGameMs =
                gameMinutes * 60_000L;

        gameTimeSyncInstant =
                Instant.now();

        log.debug(
                "OSRS playtime synchronized: {} minutes",
                gameMinutes
        );
    }

    /*
     * CURRENT NON-AFK READOUT
     *
     * This writes the total to the RuneLite settings panel so it can
     * easily be copied/migrated to another computer.
     */
    private void updateCurrentNonAfkReadout()
    {
        long totalMs =
                getLifetimeNonAfkMs();

        String formatted =
                formatDuration(totalMs);

        String existing =
                configManager.getConfiguration(
                        BarryMinimumConfig.GROUP,
                        "currentNonAfkTime"
                );

        if (!formatted.equals(existing))
        {
            configManager.setConfiguration(
                    BarryMinimumConfig.GROUP,
                    "currentNonAfkTime",
                    formatted
            );
        }
    }

    private String formatDuration(
            long milliseconds)
    {
        long totalSeconds =
                Math.max(
                        0L,
                        milliseconds / 1000L
                );

        long days =
                totalSeconds / 86_400L;

        long hours =
                (totalSeconds % 86_400L)
                        / 3_600L;

        long minutes =
                (totalSeconds % 3_600L)
                        / 60L;

        long seconds =
                totalSeconds % 60L;

        return String.format(
                "%dd %02dh %02dm %02ds",
                days,
                hours,
                minutes,
                seconds
        );
    }

    /*
     * PROFILE STORAGE
     */
    private void loadProfileData()
    {
        storedNonAfkMs =
                readProfileLong(
                        STORED_NON_AFK_MS
                );

        storedLifetimeProfitGp =
                readProfileLong(
                        STORED_LIFETIME_PROFIT_GP
                );

        storedBankCashGp =
                readProfileLong(
                        STORED_BANK_CASH_GP
                );
    }

    private long readProfileLong(
            String key)
    {
        String value =
                configManager.getRSProfileConfiguration(
                        BarryMinimumConfig.GROUP,
                        key
                );

        if (value == null
                || value.isEmpty())
        {
            return 0L;
        }

        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ex)
        {
            log.warn(
                    "Invalid Barry Minimum value for {}: {}",
                    key,
                    value
            );

            return 0L;
        }
    }

    private void persistProfileData()
    {
        if (client.getGameState()
                != GameState.LOGGED_IN)
        {
            return;
        }

        configManager.setRSProfileConfiguration(
                BarryMinimumConfig.GROUP,
                STORED_NON_AFK_MS,
                storedNonAfkMs
        );

        configManager.setRSProfileConfiguration(
                BarryMinimumConfig.GROUP,
                STORED_LIFETIME_PROFIT_GP,
                storedLifetimeProfitGp
        );

        configManager.setRSProfileConfiguration(
                BarryMinimumConfig.GROUP,
                STORED_BANK_CASH_GP,
                storedBankCashGp
        );
    }

    /*
     * QUALIFYING AFK INTERACTIONS
     */
    boolean isQualifyingInteraction(
            MenuOptionClicked event)
    {
        if (event.isItemOp())
        {
            return true;
        }

        MenuAction action =
                event.getMenuAction();

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
     * OVERLAY METRICS
     */
    long getLifetimeGameMs()
    {
        if (gameTimeSyncInstant == null)
        {
            return syncedGameMs;
        }

        if (client.getGameState()
                != GameState.LOGGED_IN)
        {
            return syncedGameMs;
        }

        long sinceSync =
                Duration.between(
                        gameTimeSyncInstant,
                        Instant.now()
                ).toMillis();

        return syncedGameMs
                + Math.max(
                0L,
                sinceSync
        );
    }

    /*
     * Manual starting time
     * +
     * time accumulated by this installation.
     */
    long getLifetimeNonAfkMs()
    {
        long startingNonAfkMs =
                config.startingNonAfkDays()
                        * 86_400_000L
                        + config.startingNonAfkHours()
                        * 3_600_000L
                        + config.startingNonAfkMinutes()
                        * 60_000L;

        return storedNonAfkMs
                + startingNonAfkMs;
    }

    double getAfkPurity()
    {
        long total =
                getLifetimeGameMs();

        if (total <= 0L)
        {
            return 100.0;
        }

        long active =
                Math.min(
                        getLifetimeNonAfkMs(),
                        total
                );

        return 100.0
                * (
                1.0
                        - (
                        (double) active
                                / (double) total
                )
        );
    }

    /*
     * Rolling GP/hr is simply the GE value obtained during the
     * previous 60 minutes.
     *
     * Because the measurement window itself is one hour, no further
     * division is required.
     */
    long getRollingGpPerHour()
    {
        return getRollingGpValue();
    }

    long getLifetimeProfitGp()
    {
        return Math.max(
                0L,
                (long) config.startingLifetimeProfitGp()
                        + storedLifetimeProfitGp
        );
    }

    long getCurrentBankCashGp()
    {
        return Math.max(
                0L,
                storedBankCashGp
        );
    }

    long getCurrentBondPriceGp()
    {
        long price =
                itemManager.getItemPrice(
                        OLD_SCHOOL_BOND_ID
                );

        return Math.max(
                0L,
                price
        );
    }

    /*
     * Bond Progress =
     *
     * coins currently in bank
     * ----------------------- x 100
     * current Bond price
     */
    double getBondProgressPercent()
    {
        long bondPrice =
                getCurrentBondPriceGp();

        if (bondPrice <= 0L)
        {
            return 0.0;
        }

        return 100.0
                * (
                (double) getCurrentBankCashGp()
                        / (double) bondPrice
        );
    }

    String getFormattedBondProgress()
    {
        if (getCurrentBondPriceGp()
                <= 0L)
        {
            return "N/A";
        }

        return String.format(
                "%.1f%%",
                getBondProgressPercent()
        );
    }

    String getFormattedGpPerHour()
    {
        return formatGp(
                getRollingGpPerHour()
        );
    }

    String getFormattedLifetimeProfit()
    {
        return formatGp(
                getLifetimeProfitGp()
        );
    }

    private String formatGp(
            long value)
    {
        if (value >= 1_000_000_000L)
        {
            return String.format(
                    "%.2fb",
                    value / 1_000_000_000.0
            );
        }

        if (value >= 1_000_000L)
        {
            return String.format(
                    "%.2fm",
                    value / 1_000_000.0
            );
        }

        if (value >= 1_000L)
        {
            return String.format(
                    "%.1fk",
                    value / 1_000.0
            );
        }

        return Long.toString(value);
    }

    boolean isNonAfk()
    {
        return nonAfk;
    }

    long getSecondsUntilAfk()
    {
        if (!nonAfk
                || lastQualifyingInteraction == null)
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