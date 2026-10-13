package com.afkpurity;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
        name = "AFK Purity",
        description = "Tracks AFK purity, active and inactive time, GP/hour, and account progress.",
        tags = {"afk", "tracker", "challenge", "metrics", "gp"}
)
public class AfkPurityPlugin extends Plugin
{
    /*
     * OSRS total account playtime in minutes.
     */
    private static final int PLAYTIME_VARC = 526;

    /*
     * Rolling GP/hr window.
     */
    private static final long DROPPED_ITEM_TTL_MS = 180_000L;

    private static final long GP_WINDOW_MS =
            60L * 60L * 1000L;

    /*
     * Profile-specific storage.
     */
    private static final String STORED_NON_AFK_MS =
            "storedNonAfkMs";
    private static final String STORED_INACTIVE_MS = "storedInactiveMs";
    private static final String STORED_GAME_FLOOR_MS = "storedGameFloorMs";
    private static final String STARTING_TIME_MIGRATED = "startingTimeMigrated";

    private static final long MAX_TICK_GAP_MS = 2_000L;

    @Inject
    private Client client;

    @Inject
    private AfkPurityConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AfkPurityOverlay overlay;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    private AfkPurityProgress progress;
    private AfkPurityProgressPanel progressPanel;
    private NavigationButton progressNavigation;
    private long lastDashboardRefresh;
    private long lastDailySample;

    // Last values written by the plugin. Used to distinguish user edits from live refreshes.
    private static final String[][] TIME_KEYS = {
            {"totalNonAfkDays", "totalNonAfkHours", "totalNonAfkMinutes", "totalNonAfkSeconds"},
            {"totalInactiveDays", "totalInactiveHours", "totalInactiveMinutes", "totalInactiveSeconds"}
    };
    private final long[][] displayedTime = {{-1, -1, -1, -1}, {-1, -1, -1, -1}};
    private boolean updatingTimeFields;

    /*
     * Lifetime AFK data.
     */
    private long storedNonAfkMs;
    private long storedInactiveMs;

    // Idle detection is based on the local player's movement and animation.
    // An animation may pause briefly during a legitimate skilling action,
    // so we wait for a short quiet period before confirming idleness.
    private static final long ACTIVITY_GRACE_SECONDS = 8L;
    private Instant lastActivitySignal;
    private Instant idleSince;
    private WorldPoint previousLocation;
    private boolean inactive;

    /*
     * Inventory snapshot used to identify positive inventory gains.
     */
    private final Map<Integer, Long> previousInventory =
            new HashMap<>();

    private boolean inventoryInitialized;

    // A drop only becomes a suppression credit after the item actually
    // disappears from inventory. Credits are quantity-aware and expire.
    private final Map<Integer, Long> pendingDropQuantities = new HashMap<>();
    private final Map<Integer, Deque<DroppedItem>> recentlyDropped = new HashMap<>();
    private Object previousDestination;
    private Instant lastRecordedWorldClick;

    private static class DroppedItem
    {
        private final Instant time;
        private long quantity;

        private DroppedItem(Instant time, long quantity)
        {
            this.time = time;
            this.quantity = quantity;
        }
    }

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
        progress = new AfkPurityProgress(configManager, client, this);
        progressPanel = new AfkPurityProgressPanel(progress, () -> {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                progress.beginEpisode();
                progressPanel.refresh();
            }
        });
        progressNavigation = NavigationButton.builder()
                .tooltip("AFK Purity Progress")
                .icon(createDashboardIcon())
                .priority(7)
                .panel(progressPanel)
                .build();
        clientToolbar.addNavigation(progressNavigation);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadProfileData();
            progress.load();
            syncGameTimeFromOsrs();
            initializeInventorySnapshot();
            previousDestination = client.getLocalDestinationLocation();

            lastAccountingTime = Instant.now();
            resetIdleTracking(lastAccountingTime);

            updateTimeReadouts();
        }

        log.debug("AFK Purity tracker started");
    }

    @Override
    protected void shutDown()
    {
        accountUntil(Instant.now());
        freezeGameTime();
        updateTimeReadouts();
        persistProfileData();

        if (progressNavigation != null) clientToolbar.removeNavigation(progressNavigation);
        if (progress != null) { progress.saveEpisodeTimes(); progress.unload(); }
        overlayManager.remove(overlay);
        lastAccountingTime = null;
        resetIdleTracking(null);
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

        previousInventory.clear();
        inventoryInitialized = false;

        rollingGpGains.clear();
        pendingDropQuantities.clear();
        recentlyDropped.clear();
        previousDestination = null;

        nonAfk = false;

        log.debug("AFK Purity tracker stopped");
    }

    @Subscribe
    public void onGameStateChanged(
            GameStateChanged event)
    {
        Instant now = Instant.now();

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            loadProfileData();
            progress.load();
            syncGameTimeFromOsrs();

            /*
             * Existing inventory is the baseline.
             * Nothing already present counts as new loot.
             */
            initializeInventorySnapshot();
            pendingDropQuantities.clear();
            recentlyDropped.clear();
            previousDestination = client.getLocalDestinationLocation();

            lastAccountingTime = now;
            resetIdleTracking(now);
            lastQualifyingInteraction = null;
            nonAfk = false;

            updateTimeReadouts();

            return;
        }

        accountUntil(now);
        freezeGameTime();
        updateTimeReadouts();
        persistProfileData();
        if (progress != null) { progress.saveEpisodeTimes(); progress.unload(); }
        if (progressPanel != null) progressPanel.refresh();

        lastAccountingTime = null;
        resetIdleTracking(null);
        lastQualifyingInteraction = null;
        gameTimeSyncInstant = null;

        previousInventory.clear();
        inventoryInitialized = false;
        pendingDropQuantities.clear();
        recentlyDropped.clear();
        previousDestination = null;

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
        observeCharacterActivity(now);
        observeDestinationChange(now);

        /*
         * Continuously expire GP gains that are more than
         * one hour old.
         */
        removeExpiredGpGains(now);

        /*
         * Keep the settings readout current.
         */
        updateTimeReadouts();

        persistProfileData();
        if (progress != null)
        {
            long tickMs = System.currentTimeMillis();
            if (tickMs - lastDailySample > 60_000L)
            {
                progress.sampleDaily();
                lastDailySample = tickMs;
            }
            if (tickMs - lastDashboardRefresh > 5_000L)
            {
                progressPanel.refresh();
                lastDashboardRefresh = tickMs;
            }
        }
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

        if (event.getContainerId() == InventoryID.INV)
        {
            processInventoryChange(
                    event.getItemContainer()
            );
        }
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

        Instant now = Instant.now();
        expireDroppedItems(now);
        // Match confirmed inventory losses to recent Drop clicks, even if
        // GP/hour tracking is disabled or a transfer interface is open.
        for (Map.Entry<Integer, Long> old : previousInventory.entrySet())
        {
            long removed = old.getValue() - currentInventory.getOrDefault(old.getKey(), 0L);
            if (removed <= 0) continue;
            long requested = pendingDropQuantities.getOrDefault(old.getKey(), 0L);
            long confirmed = Math.min(removed, requested);
            if (confirmed > 0)
            {
                recentlyDropped.computeIfAbsent(old.getKey(), k -> new ArrayDeque<>())
                        .addLast(new DroppedItem(now, confirmed));
                if (confirmed == requested) pendingDropQuantities.remove(old.getKey());
                else pendingDropQuantities.put(old.getKey(), requested - confirmed);
            }
        }

        if (canTrackGain)
        {

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

                // Picking up recently dropped items must not create GP.
                // Suppress only the matching quantity, not every item of
                // this ID acquired during the next three minutes.
                gainedQuantity -= consumeDroppedQuantity(itemId, gainedQuantity);
                if (gainedQuantity <= 0L) continue;

                long unitValue;

                if (itemId == 995)
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

                if (progress != null) progress.addGp(gainValue);
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

    private void expireDroppedItems(Instant now)
    {
        recentlyDropped.entrySet().removeIf(entry -> {
            Deque<DroppedItem> drops = entry.getValue();
            while (!drops.isEmpty() &&
                    !drops.peekFirst().time.plusMillis(DROPPED_ITEM_TTL_MS).isAfter(now))
            {
                drops.removeFirst();
            }
            return drops.isEmpty();
        });
    }

    private long consumeDroppedQuantity(int itemId, long gained)
    {
        Deque<DroppedItem> drops = recentlyDropped.get(itemId);
        if (drops == null) return 0L;
        long consumed = 0L;
        while (consumed < gained && !drops.isEmpty())
        {
            DroppedItem first = drops.peekFirst();
            long amount = Math.min(gained - consumed, first.quantity);
            consumed += amount;
            first.quantity -= amount;
            if (first.quantity == 0L) drops.removeFirst();
        }
        if (drops.isEmpty()) recentlyDropped.remove(itemId);
        return consumed;
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
        if (client.getGameState() != GameState.LOGGED_IN) return;

        String option = event.getMenuOption() == null ? "" :
                event.getMenuOption().replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
        if (option.equals("drop"))
        {
            // For inventory widget operations, getId() is the widget action
            // identifier, NOT reliably the item ID. param0 is the slot.
            // Read the actual item from that slot before the game removes it.
            ItemContainer inventory = client.getItemContainer(InventoryID.INV);
            int slot = event.getParam0();
            if (inventory != null && slot >= 0)
            {
                Item[] items = inventory.getItems();
                if (slot < items.length && items[slot] != null)
                {
                    int droppedItemId = items[slot].getId();
                    if (droppedItemId > 0)
                    {
                        pendingDropQuantities.merge(droppedItemId, 1L, Long::sum);
                        log.debug("Drop requested: slot={} itemId={}", slot, droppedItemId);
                    }
                }
            }
            return;
        }
        if (!isQualifyingInteraction(event)) return;
        recordQualifyingInteraction(Instant.now());
    }

    private void recordQualifyingInteraction(Instant now)
    {
        lastRecordedWorldClick = now;
        accountUntil(now);
        lastActivitySignal = now;
        idleSince = null;
        inactive = false;

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

        updateTimeReadouts();
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
        long nonAfkBefore = storedNonAfkMs;
        long inactiveBefore = storedInactiveMs;

        Instant nonAfkDeadline = nonAfk && lastQualifyingInteraction != null
                ? lastQualifyingInteraction.plusSeconds(config.activeWindowSeconds()) : null;
        if (nonAfkDeadline != null)
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

        // Count only the interval after the idle threshold. Never count
        // the same milliseconds as non-AFK, even across a timer boundary.
        if (idleSince != null)
        {
            Instant inactiveStart = idleSince.plusSeconds(config.inactiveThresholdSeconds());
            Instant chargeFrom = lastAccountingTime.isAfter(inactiveStart)
                    ? lastAccountingTime : inactiveStart;
            // An ACTIVE interval always wins over an INACTIVE interval.
            if (nonAfkDeadline != null && nonAfkDeadline.isAfter(chargeFrom))
            {
                chargeFrom = nonAfkDeadline;
            }
            if (now.isAfter(chargeFrom))
            {
                long inactiveMs = Duration.between(chargeFrom, now).toMillis();
                // The MAX_TICK_GAP limit also applies to inactive time.
                if (!nonAfk && (lastQualifyingInteraction == null
                        || !now.isBefore(lastQualifyingInteraction.plusSeconds(config.activeWindowSeconds()))))
                {
                    storedInactiveMs += Math.min(inactiveMs, elapsedMs);
                }
            }
        }

        if (progress != null)
        {
            progress.recordInterval(elapsedMs,
                    storedNonAfkMs - nonAfkBefore,
                    storedInactiveMs - inactiveBefore);
        }
        lastAccountingTime = now;
    }

    private void resetIdleTracking(Instant now)
    {
        idleSince = null;
        lastActivitySignal = now;
        previousLocation = null;
        inactive = false;
    }

    private void observeCharacterActivity(Instant now)
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            // Unknown player state must not be classified as idle.
            resetIdleTracking(now);
            return;
        }

        WorldPoint location = player.getWorldLocation();
        boolean moved = previousLocation != null && !previousLocation.equals(location);
        previousLocation = location;

        // A repeated animation, ongoing movement or an interaction target
        // is positive evidence of activity. This is a heuristic, not a
        // definitive determination of whether skilling continues.
        boolean activity = moved || player.getAnimation() != -1
                || player.getInteracting() != null;
        if (activity)
        {
            lastActivitySignal = now;
            idleSince = null;
            inactive = false;
            return;
        }

        if (lastActivitySignal == null)
        {
            lastActivitySignal = now;
        }
        if (idleSince == null && !now.isBefore(
                lastActivitySignal.plusSeconds(ACTIVITY_GRACE_SECONDS)))
        {
            idleSince = now;
        }
        inactive = !nonAfk && idleSince != null
                && !now.isBefore(idleSince.plusSeconds(config.inactiveThresholdSeconds()));
    }

    /*
     * ACCOUNT PLAYTIME
     */
    private void syncGameTimeFromOsrs()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Instant now = Instant.now();
        // Preserve elapsed logged-in time before changing the synchronization anchor.
        // This also provides a fallback when varc 526 is temporarily unavailable.
        long estimatedMs = getLifetimeGameMs();
        int gameMinutes = client.getVarcIntValue(PLAYTIME_VARC);

        // Some game transitions expose zero or stale values. Never rewind the
        // account clock: doing so can make eligible time zero and purity 100%.
        if (gameMinutes < 0)
        {
            return;
        }

        long reportedMs = gameMinutes * 60_000L;
        syncedGameMs = Math.max(estimatedMs, reportedMs);
        gameTimeSyncInstant = now;

        if (reportedMs < estimatedMs)
        {
            log.debug("Ignoring stale OSRS playtime: {} min; retained {} ms",
                    gameMinutes, estimatedMs);
        }
        else
        {
            log.debug("OSRS playtime synchronized: {} minutes", gameMinutes);
        }
    }

    private void freezeGameTime()
    {
        if (gameTimeSyncInstant != null)
        {
            syncedGameMs += Math.max(0L, Duration.between(
                    gameTimeSyncInstant, Instant.now()).toMillis());
        }
        gameTimeSyncInstant = null;
    }

    /** Keep both live totals in the native RuneLite settings page. */
    private void updateTimeReadouts()
    {
        updateTimeReadout(0, getLifetimeNonAfkMs());
        updateTimeReadout(1, getLifetimeInactiveMs());
    }

    private void updateTimeReadout(int type, long milliseconds)
    {
        long seconds = Math.max(0L, milliseconds / 1000L);
        long[] parts = {seconds / 86400L, (seconds / 3600L) % 24L,
                (seconds / 60L) % 60L, seconds % 60L};
        updatingTimeFields = true;
        try
        {
            for (int i = 0; i < 4; i++)
            {
                long part = Math.min(parts[i], Integer.MAX_VALUE);
                if (displayedTime[type][i] != part)
                {
                    displayedTime[type][i] = part;
                    configManager.setConfiguration(AfkPurityConfig.GROUP,
                            TIME_KEYS[type][i], part);
                }
            }
        }
        finally
        {
            updatingTimeFields = false;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!AfkPurityConfig.GROUP.equals(event.getGroup()) || updatingTimeFields)
        {
            return;
        }
        int type = -1;
        int index = -1;
        for (int t = 0; t < TIME_KEYS.length; t++)
        {
            for (int i = 0; i < 4; i++)
            {
                if (TIME_KEYS[t][i].equals(event.getKey()))
                {
                    type = t;
                    index = i;
                    break;
                }
            }
        }
        if (type < 0 || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        long newPart;
        try
        {
            newPart = Long.parseLong(event.getNewValue());
        }
        catch (NumberFormatException ex)
        {
            return;
        }
        if (newPart < 0 || (index > 0 && newPart >= (index == 1 ? 24 : 60))
                || displayedTime[type][index] == newPart)
        {
            return;
        }

        Instant now = Instant.now();
        accountUntil(now);
        long seconds = (type == 0 ? getLifetimeNonAfkMs() : getLifetimeInactiveMs()) / 1000L;
        long[] parts = {seconds / 86400L, (seconds / 3600L) % 24L,
                (seconds / 60L) % 60L, seconds % 60L};
        parts[index] = newPart;
        try
        {
            long newSeconds = Math.addExact(
                    Math.addExact(Math.multiplyExact(parts[0], 86400L),
                            Math.multiplyExact(parts[1], 3600L)),
                    Math.addExact(Math.multiplyExact(parts[2], 60L), parts[3]));
            if (type == 0)
            {
                storedNonAfkMs = Math.multiplyExact(newSeconds, 1000L);
            }
            else
            {
                storedInactiveMs = Math.multiplyExact(newSeconds, 1000L);
            }
            persistProfileData();
            updateTimeReadouts();
        }
        catch (ArithmeticException ex)
        {
            log.warn("Time correction is too large", ex);
        }
    }

    /*
     * PROFILE STORAGE
     */
    private void loadProfileData()
    {
        for (long[] row : displayedTime)
        {
            java.util.Arrays.fill(row, -1L);
        }
        storedNonAfkMs = readProfileLong(STORED_NON_AFK_MS);
        storedInactiveMs = readProfileLong(STORED_INACTIVE_MS);
        syncedGameMs = readProfileLong(STORED_GAME_FLOOR_MS);
        gameTimeSyncInstant = Instant.now();

        // One-time migration of the old editable starting-time settings.
        // Existing tracked time is preserved and the starting offset is applied once.
        String migrated = configManager.getRSProfileConfiguration(
                AfkPurityConfig.GROUP, STARTING_TIME_MIGRATED);
        if (!"true".equals(migrated))
        {
            long oldDays = readLegacyStartingValue("startingNonAfkDays");
            long oldHours = readLegacyStartingValue("startingNonAfkHours");
            long oldMinutes = readLegacyStartingValue("startingNonAfkMinutes");
            storedNonAfkMs += oldDays * 86_400_000L
                    + oldHours * 3_600_000L + oldMinutes * 60_000L;
            configManager.setRSProfileConfiguration(
                    AfkPurityConfig.GROUP, STORED_NON_AFK_MS, storedNonAfkMs);
            configManager.setRSProfileConfiguration(
                    AfkPurityConfig.GROUP, STARTING_TIME_MIGRATED, "true");
        }

    }

    private long readLegacyStartingValue(String key)
    {
        String value = configManager.getConfiguration(AfkPurityConfig.GROUP, key);
        if (value == null)
        {
            return 0L;
        }
        try
        {
            return Math.max(0L, Long.parseLong(value));
        }
        catch (NumberFormatException ex)
        {
            return 0L;
        }
    }

    private long readProfileLong(
            String key)
    {
        String value =
                configManager.getRSProfileConfiguration(
                        AfkPurityConfig.GROUP,
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
                    "Invalid AFK Purity value for {}: {}",
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
                AfkPurityConfig.GROUP,
                STORED_NON_AFK_MS,
                storedNonAfkMs
        );
        configManager.setRSProfileConfiguration(
                AfkPurityConfig.GROUP, STORED_INACTIVE_MS, storedInactiveMs);
        configManager.setRSProfileConfiguration(
                AfkPurityConfig.GROUP, STORED_GAME_FLOOR_MS, getLifetimeGameMs());
    }

    /*
     * QUALIFYING AFK INTERACTIONS
     */
    private void observeDestinationChange(Instant now)
    {
        // Minimap clicks sometimes bypass MenuOptionClicked entirely.
        // A newly assigned path destination is a fallback movement signal.
        Object destination = client.getLocalDestinationLocation();
        if (destination != null && !destination.equals(previousDestination)
                && (lastRecordedWorldClick == null ||
                Duration.between(lastRecordedWorldClick, now).toMillis() > 1_000L))
        {
            recordQualifyingInteraction(now);
        }
        previousDestination = destination;
    }

    boolean isQualifyingInteraction(
            MenuOptionClicked event)
    {
        // Inventory-only operations (such as Drop and Wear/Wield) do not
        // direct the character to interact with the game world. Do not
        // classify them as non-AFK activity. World-targeted item uses are
        // still recognized by their WIDGET_TARGET_ON_* menu actions.
        MenuAction action =
                event.getMenuAction();
        String option = event.getMenuOption() == null ? "" :
                event.getMenuOption().replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);

        // These UI controls deliberately count, but generic inventory
        // widget operations (wear, wield, eat, drop, etc.) do not.
        String target = event.getMenuTarget() == null ? "" :
                event.getMenuTarget().replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
        if ((target.contains("quick-prayer") &&
                (option.equals("activate") || option.equals("deactivate") || option.equals("toggle")))
                || option.equals("toggle run") || option.equals("run")
                || option.equals("quick-prayers") || option.equals("quick prayers")
                || option.equals("activate quick-prayers")
                || option.equals("deactivate quick-prayers")) return true;
        if (option.equals("walk here")) return true;

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
            case WIDGET_TARGET_ON_WIDGET: // e.g. tinderbox + logs, herb + vial

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

    long getLifetimeNonAfkMs()
    {
        return Math.max(0L, storedNonAfkMs);
    }

    long getLifetimeInactiveMs()
    {
        return Math.max(0L, storedInactiveMs);
    }

    double getAfkPurity()
    {
        long total = getLifetimeGameMs();
        long eligible = Math.max(0L, total - getLifetimeInactiveMs());
        if (eligible == 0L)
        {
            return 100.0;
        }
        long active = Math.min(getLifetimeNonAfkMs(), eligible);
        return 100.0 * (1.0 - (double) active / eligible);
    }

    boolean isInactive()
    {
        return inactive && !nonAfk;
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

    String getFormattedGpPerHour()
    {
        return formatGp(
                getRollingGpPerHour()
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

    private static BufferedImage createDashboardIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        try
        {
            g.setColor(new Color(100, 205, 125));
            g.fillRect(1, 9, 3, 6);
            g.fillRect(6, 5, 3, 10);
            g.fillRect(11, 1, 3, 14);
        }
        finally { g.dispose(); }
        return icon;
    }

    @Provides
    AfkPurityConfig provideConfig(
            ConfigManager configManager)
    {
        return configManager.getConfig(
                AfkPurityConfig.class
        );
    }
}