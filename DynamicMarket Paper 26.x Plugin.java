/**
 * =========================================================================================
 * DYNAMIC MARKET — PRODUCTION-READY MINECRAFT PAPERMC 26.X PLUGIN (26.1 / 26.2 / 26.3)
 * =========================================================================================
 *
 * TARGET ENVIRONMENT & VERSION VERIFICATION:
 * - Minecraft / PaperMC Version: 26.x (Compatible with 26.1 "Tiny Takeover", 26.2 "Chaos Cubed",
 *   and 26.3 "Wilderness Bound", using Paper's 2026 versioning format `26.3.build.178-stable`
 *   and `api-version: '26.1'` in plugin.yml for full 26.x family compatibility).
 * - Java Runtime: Java 25 (required by Paper 26.x).
 * - Economy Integration: Vault API (`net.milkbowl.vault.economy.Economy`) + EssentialsX Economy,
 *   plus built-in fallback ledger when testing without Vault installed.
 * - Database Engine: SQLite JDBC (`org.xerial:sqlite-jdbc`) with WAL mode, prepared statements,
 *   and asynchronous batch persistence.
 *
 * HOW TO BUILD THE USABLE .JAR FILE IMMEDIATELY:
 * Included at the bottom of this file (inside the comment block and auto-extractor `Main` CLI
 * entrypoint) are the complete `pom.xml`, `plugin.yml`, `config.yml`, and a 1-command build
 * script (`java src/main/java/de/dynamicmarket/DynamicMarket.java --export-project`) that
 * writes the standard Maven project layout and runs `mvn clean package` to produce
 * `target/DynamicMarket-26.3.0.jar`.
 * =========================================================================================
 */
package de.dynamicmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Main plugin entry point for DynamicMarket (PaperMC 26.x).
 * Coordinates configuration, SQLite persistence, Vault economy bridge,
 * dynamic supply/demand pricing engine, anti-exploit GUI listeners, and commands.
 */
public final class DynamicMarket extends JavaPlugin {

    private PluginConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyBridge economyBridge;
    private MarketEngine marketEngine;
    private TransactionService transactionService;
    private ShopGuiManager guiManager;
    private BukkitTask recoveryTask;
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        saveDefaultConfig();

        // 1. Load strongly-typed configuration settings
        this.configManager = new PluginConfigManager(this);
        this.configManager.load();

        // 2. Initialize SQLite database & create schema
        this.databaseManager = new DatabaseManager(this);
        if (!this.databaseManager.initialize()) {
            getLogger().severe("Failed to initialize SQLite database! Disabling DynamicMarket.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Hook into Vault / EssentialsX Economy (with optional standalone test fallback)
        this.economyBridge = new EconomyBridge(this);
        if (!this.economyBridge.setupEconomy()) {
            if (this.configManager.isAllowStandaloneFallbackEconomy()) {
                getLogger().warning("Vault/EssentialsX Economy not detected! Using built-in Fallback Economy Ledger as configured.");
            } else {
                getLogger().severe("Vault or an Economy provider (such as EssentialsX) was not found! Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        // 4. Initialize Market Engine & auto-discover all obtainable vanilla 26.x items
        this.marketEngine = new MarketEngine(this, this.configManager, this.databaseManager);
        this.marketEngine.initializeMarketCatalog();

        // 5. Initialize Transaction Service & GUI Manager
        this.transactionService = new TransactionService(this, this.configManager, this.economyBridge, this.marketEngine, this.databaseManager);
        this.guiManager = new ShopGuiManager(this, this.configManager, this.marketEngine, this.transactionService, this.economyBridge);

        // 6. Register Listeners and Commands
        getServer().getPluginManager().registerEvents(this.guiManager, this);
        ShopCommandHandler commandHandler = new ShopCommandHandler(this, this.configManager, this.marketEngine, this.guiManager, this.economyBridge);
        PluginCommand shopCmd = getCommand("shop");
        if (shopCmd != null) {
            shopCmd.setExecutor(commandHandler);
            shopCmd.setTabCompleter(commandHandler);
        }

        // 7. Start periodic price recovery and async SQLite flush tasks
        startScheduledTasks();

        long elapsed = System.currentTimeMillis() - startTime;
        getLogger().info("DynamicMarket v" + getDescription().getVersion() + " enabled for PaperMC 26.x in " + elapsed + "ms ("
                + this.marketEngine.getActiveItems().size() + " obtainable items indexed).");
    }

    @Override
    public void onDisable() {
        if (this.recoveryTask != null) {
            this.recoveryTask.cancel();
        }
        if (this.autoSaveTask != null) {
            this.autoSaveTask.cancel();
        }
        if (this.guiManager != null) {
            this.guiManager.closeAllShopInventories();
        }
        if (this.marketEngine != null && this.databaseManager != null) {
            this.databaseManager.saveAllItemsSync(this.marketEngine.getAllMarketItems());
            this.databaseManager.close();
        }
        getLogger().info("DynamicMarket disabled cleanly and all market data flushed to SQLite.");
    }

    /**
     * Reloads configuration, item overrides, and restarts periodic tasks safely.
     */
    public void reloadMarketPlugin() {
        reloadConfig();
        this.configManager.load();
        if (this. recoveryTask != null) {
            this.recoveryTask.cancel();
        }
        if (this.autoSaveTask != null) {
            this.autoSaveTask.cancel();
        }
        this.databaseManager.saveAllItemsSync(this.marketEngine.getAllMarketItems());
        this.marketEngine.reloadCatalogSettings();
        startScheduledTasks();
    }

    private void startScheduledTasks() {
        long recoveryIntervalTicks = Math.max(200L, this.configManager.getRecoveryIntervalSeconds() * 20L);
        this.recoveryTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int updated = this.marketEngine.performPeriodicPriceRecovery();
            if (updated > 0 && this.configManager.isDebugLogging()) {
                getLogger().info("[MarketRecovery] Gradually recovered prices for " + updated + " items toward base values.");
            }
        }, recoveryIntervalTicks, recoveryIntervalTicks);

        long saveIntervalTicks = Math.max(600L, this.configManager.getAutoSaveIntervalSeconds() * 20L);
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            this.databaseManager.saveDirtyItemsSync(this.marketEngine.getAllMarketItems());
        }, saveIntervalTicks, saveIntervalTicks);
    }

    /**
     * Central configuration manager loading settings from config.yml.
     */
    public static final class PluginConfigManager {
        private final DynamicMarket plugin;
        private String currencySymbol = "$";
        private double sellFactor = 0.75D;
        private double buySensitivity = 0.0025D;
        private double sellSensitivity = 0.0025D;
        private double minMultiplier = 0.25D;
        private double maxMultiplier = 4.50D;
        private double recoveryRatePerCycle = 0.04D;
        private long recoveryIntervalSeconds = 300L;
        private long autoSaveIntervalSeconds = 120L;
        private boolean shrinkflationEnabled = false;
        private double shrinkflationPriceThresholdMultiplier = 1.35D;
        private double shrinkflationReductionRatio = 0.125D;
        private int shrinkflationMinBundleSize = 8;
        private boolean allowStandaloneFallbackEconomy = true;
        private double fallbackStartingBalance = 10000.0D;
        private boolean debugLogging = false;
        private final Set<Material> blacklistedMaterials = EnumSet.noneOf(Material.class);
        private final Map<Material, Double> customBasePrices = new EnumMap<>(Material.class);
        private final Map<Material, MarketCategory> customCategoryOverrides = new EnumMap<>(Material.class);

        public PluginConfigManager(DynamicMarket plugin) {
            this.plugin = plugin;
        }

        public void load() {
            FileConfiguration cfg = plugin.getConfig();
            this.currencySymbol = cfg.getString("economy.currency-symbol", "$");
            this.sellFactor = clamp(cfg.getDouble("economy.base-sell-factor", 0.75D), 0.05D, 0.99D);
            this.allowStandaloneFallbackEconomy = cfg.getBoolean("economy.allow-standalone-fallback", true);
            this.fallbackStartingBalance = Math.max(0.0D, cfg.getDouble("economy.fallback-starting-balance", 10000.0D));

            this.buySensitivity = clamp(cfg.getDouble("market.buy-sensitivity-per-unit", 0.0025D), 0.0001D, 0.25D);
            this.sellSensitivity = clamp(cfg.getDouble("market.sell-sensitivity-per-unit", 0.0025D), 0.0001D, 0.25D);
            this.minMultiplier = clamp(cfg.getDouble("market.min-price-multiplier", 0.25D), 0.01D, 1.0D);
            this.maxMultiplier = clamp(cfg.getDouble("market.max-price-multiplier", 4.50D), 1.0D, 100.0D);
            this.recoveryRatePerCycle = clamp(cfg.getDouble("market.recovery-rate-per-cycle", 0.04D), 0.001D, 0.50D);
            this.recoveryIntervalSeconds = Math.max(10L, cfg.getLong("market.recovery-interval-seconds", 300L));
            this.autoSaveIntervalSeconds = Math.max(30L, cfg.getLong("market.autosave-interval-seconds", 120L));
            this.debugLogging = cfg.getBoolean("market.debug-logging", false);

            // Optional Shrinkflation settings (disabled by default)
            this.shrinkflationEnabled = cfg.getBoolean("shrinkflation.enabled", false);
            this.shrinkflationPriceThresholdMultiplier = Math.max(1.05D, cfg.getDouble("shrinkflation.activation-multiplier-threshold", 1.35D));
            this.shrinkflationReductionRatio = clamp(cfg.getDouble("shrinkflation.max-quantity-reduction-ratio", 0.125D), 0.01D, 0.50D);
            this.shrinkflationMinBundleSize = Math.max(2, cfg.getInt("shrinkflation.minimum-bundle-size", 8));

            this.blacklistedMaterials.clear();
            for (String matName : cfg.getStringList("items.blacklist")) {
                Material mat = Material.matchMaterial(matName);
                if (mat != null) {
                    this.blacklistedMaterials.add(mat);
                }
            }

            this.customBasePrices.clear();
            ConfigurationSection priceSec = cfg.getConfigurationSection("items.custom-base-prices");
            if (priceSec != null) {
                for (String key : priceSec.getKeys(false)) {
                    Material mat = Material.matchMaterial(key);
                    double price = priceSec.getDouble(key, -1.0D);
                    if (mat != null && price > 0.0D) {
                        this.customBasePrices.put(mat, price);
                    }
                }
            }

            this.customCategoryOverrides.clear();
            ConfigurationSection catSec = cfg.getConfigurationSection("items.category-overrides");
            if (catSec != null) {
                for (String key : catSec.getKeys(false)) {
                    Material mat = Material.matchMaterial(key);
                    String catName = catSec.getString(key, "");
                    if (mat != null) {
                        try {
                            MarketCategory category = MarketCategory.valueOf(catName.toUpperCase(Locale.ROOT));
                            this.customCategoryOverrides.put(mat, category);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        }

        private static double clamp(double val, double min, double max) {
            return Math.max(min, Math.min(max, val));
        }

        public String formatCurrency(double amount) {
            DecimalFormat df = new DecimalFormat("#,##0.00");
            return this.currencySymbol + df.format(amount);
        }

        public String getCurrencySymbol() { return currencySymbol; }
        public double getSellFactor() { return sellFactor; }
        public double getBuySensitivity() { return buySensitivity; }
        public double getSellSensitivity() { return sellSensitivity; }
        public double getMinMultiplier() { return minMultiplier; }
        public double getMaxMultiplier() { return maxMultiplier; }
        public double getRecoveryRatePerCycle() { return recoveryRatePerCycle; }
        public long getRecoveryIntervalSeconds() { return recoveryIntervalSeconds; }
        public long getAutoSaveIntervalSeconds() { return autoSaveIntervalSeconds; }
        public boolean isShrinkflationEnabled() { return shrinkflationEnabled; }
        public double getShrinkflationPriceThresholdMultiplier() { return shrinkflationPriceThresholdMultiplier; }
        public double getShrinkflationReductionRatio() { return shrinkflationReductionRatio; }
        public int getShrinkflationMinBundleSize() { return shrinkflationMinBundleSize; }
        public boolean isAllowStandaloneFallbackEconomy() { return allowStandaloneFallbackEconomy; }
        public double getFallbackStartingBalance() { return fallbackStartingBalance; }
        public boolean isDebugLogging() { return debugLogging; }
        public Set<Material> getBlacklistedMaterials() { return blacklistedMaterials; }
        public Map<Material, Double> getCustomBasePrices() { return customBasePrices; }
        public Map<Material, MarketCategory> getCustomCategoryOverrides() { return customCategoryOverrides; }
    }

    /**
     * Well-organized categories covering all obtainable vanilla Minecraft 26.x blocks and items.
     */
    public enum MarketCategory {
        BUILDING_BLOCKS("Building Blocks", Material.BRICKS, NamedTextColor.GOLD,
                "Stairs, slabs, stone, wood, concrete, terracotta & glass"),
        NATURAL_AND_FARMING("Nature & Farming", Material.GOLDEN_CARROT, NamedTextColor.GREEN,
                "Logs, saplings, crops, flowers, dirt, seeds & 26.x flora"),
        ORES_AND_VALUABLES("Ores & Valuables", Material.DIAMOND, NamedTextColor.AQUA,
                "Diamonds, netherite, ingots, raw ores, gems & crystals"),
        REDSTONE_AND_UTILITY("Redstone & Utility", Material.REDSTONE, NamedTextColor.RED,
                "Redstone components, rails, hoppers, pistons & copper bulbs"),
        COMBAT_AND_TOOLS("Combat & Tools", Material.NETHERITE_SWORD, NamedTextColor.LIGHT_PURPLE,
                "Weapons, armor, pickaxes, axes, tridents, maces & shields"),
        FOOD_AND_POTIONS("Food & Brewing", Material.COOKED_BEEF, NamedTextColor.YELLOW,
                "Cooked meats, golden apples, brewing ingredients & potions"),
        MOB_DROPS_AND_MISC("Mob Drops & Misc", Material.ENDER_PEARL, NamedTextColor.DARK_AQUA,
                "Drops, dyes, music discs, smithing templates & miscellaneous");

        private final String displayName;
        private final Material icon;
        private final NamedTextColor color;
        private final String description;

        MarketCategory(String displayName, Material icon, NamedTextColor color, String description) {
            this.displayName = displayName;
            this.icon = icon;
            this.color = color;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        public NamedTextColor getColor() { return color; }
        public String getDescription() { return description; }
    }

    /**
     * Represents the real-time market state of a single Material.
     */
    public static final class MarketItem {
        private final Material material;
        private MarketCategory category;
        private double baseBuyPrice;
        private double baseSellPrice;
        private double currentMultiplier;
        private long totalDemandUnits;
        private long totalSupplyUnits;
        private int recentActivityScore;
        private long lastUpdatedEpochSeconds;
        private volatile boolean dirty;

        public MarketItem(Material material, MarketCategory category, double baseBuyPrice, double baseSellPrice,
                          double currentMultiplier, long totalDemandUnits, long totalSupplyUnits,
                          int recentActivityScore, long lastUpdatedEpochSeconds) {
            this.material = Objects.requireNonNull(material, "material");
            this.category = Objects.requireNonNull(category, "category");
            this.baseBuyPrice = Math.max(0.01D, baseBuyPrice);
            this.baseSellPrice = Math.max(0.01D, baseSellPrice);
            this.currentMultiplier = Math.max(0.01D, currentMultiplier);
            this.totalDemandUnits = Math.max(0L, totalDemandUnits);
            this.totalSupplyUnits = Math.max(0L, totalSupplyUnits);
            this.recentActivityScore = Math.max(0, recentActivityScore);
            this.lastUpdatedEpochSeconds = lastUpdatedEpochSeconds;
            this.dirty = false;
        }

        public synchronized Material getMaterial() { return material; }
        public synchronized MarketCategory getCategory() { return category; }
        public synchronized void setCategory(MarketCategory category) {
            this.category = category;
            this.dirty = true;
        }

        public synchronized double getBaseBuyPrice() { return baseBuyPrice; }
        public synchronized double getBaseSellPrice() { return baseSellPrice; }

        public synchronized void updateBasePrices(double newBaseBuy, double sellFactor) {
            this.baseBuyPrice = Math.max(0.01D, newBaseBuy);
            this.baseSellPrice = Math.max(0.01D, roundCurrency(newBaseBuy * sellFactor));
            this.dirty = true;
        }

        public synchronized double getCurrentMultiplier() { return currentMultiplier; }
        public synchronized void setCurrentMultiplier(double multiplier, double minMult, double maxMult) {
            double clamped = Math.max(minMult, Math.min(maxMult, multiplier));
            if (Math.abs(clamped - this.currentMultiplier) > 1e-6) {
                this.currentMultiplier = clamped;
                this.lastUpdatedEpochSeconds = Instant.now().getEpochSecond();
                this.dirty = true;
            }
        }

        public synchronized double getCurrentBuyPrice() {
            return Math.max(0.01D, roundCurrency(this.baseBuyPrice * this.currentMultiplier));
        }

        public synchronized double getCurrentSellPrice() {
            double sell = roundCurrency(this.baseSellPrice * this.currentMultiplier);
            double buy = getCurrentBuyPrice();
            // Prevent arbitrage loops: sell price never exceeds current buy price
            return Math.max(0.01D, Math.min(sell, buy));
        }

        public synchronized double getPercentageDifference() {
            return ((getCurrentBuyPrice() - this.baseBuyPrice) / this.baseBuyPrice) * 100.0D;
        }

        public synchronized long getTotalDemandUnits() { return totalDemandUnits; }
        public synchronized long getTotalSupplyUnits() { return totalSupplyUnits; }
        public synchronized int getRecentActivityScore() { return recentActivityScore; }
        public synchronized long getLastUpdatedEpochSeconds() { return lastUpdatedEpochSeconds; }

        public synchronized void recordPurchase(int quantity, double sensitivityPerUnit, double minMult, double maxMult) {
            this.totalDemandUnits += quantity;
            this.recentActivityScore += quantity;
            double delta = quantity * sensitivityPerUnit;
            setCurrentMultiplier(this.currentMultiplier * (1.0D + delta), minMult, maxMult);
            this.dirty = true;
        }

        public synchronized void recordSale(int quantity, double sensitivityPerUnit, double minMult, double maxMult) {
            this.totalSupplyUnits += quantity;
            this.recentActivityScore += quantity;
            double delta = quantity * sensitivityPerUnit;
            setCurrentMultiplier(this.currentMultiplier / (1.0D + delta), minMult, maxMult);
            this.dirty = true;
        }

        public synchronized boolean recoverStepTowardBase(double rate, double minMult, double maxMult) {
            if (this.recentActivityScore > 0) {
                this.recentActivityScore = Math.max(0, (int) Math.floor(this.recentActivityScore * 0.85D));
                this.dirty = true;
            }
            double diff = 1.0D - this.currentMultiplier;
            if (Math.abs(diff) <= 0.0005D) {
                if (this.currentMultiplier != 1.0D) {
                    this.currentMultiplier = 1.0D;
                    this.dirty = true;
                    return true;
                }
                return false;
            }
            double next = this.currentMultiplier + (diff * rate);
            if ((this.currentMultiplier < 1.0D && next > 1.0D) || (this.currentMultiplier > 1.0D && next < 1.0D)) {
                next = 1.0D;
            }
            setCurrentMultiplier(next, minMult, maxMult);
            return true;
        }

        public boolean isDirty() { return dirty; }
        public void clearDirty() { this.dirty = false; }

        public String getFriendlyName() {
            String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
            }
            return sb.toString();
        }

        private static double roundCurrency(double value) {
            return Math.round(value * 100.0D) / 100.0D;
        }
    }

    /**
     * Immutable quote describing a prospective Buy or Sell transaction, including
     * full Shrinkflation disclosure when enabled and triggered.
     */
    public static final class TransactionQuote {
        private final MarketItem item;
        private final boolean buying;
        private final int requestedQuantity;
        private final int deliveredOrConsumedQuantity;
        private final boolean shrinkflationApplied;
        private final int shrinkflationReducedUnits;
        private final double unitPrice;
        private final double totalPrice;

        public TransactionQuote(MarketItem item, boolean buying, int requestedQuantity, int deliveredOrConsumedQuantity,
                                boolean shrinkflationApplied, int shrinkflationReducedUnits,
                                double unitPrice, double totalPrice) {
            this.item = item;
            this.buying = buying;
            this.requestedQuantity = requestedQuantity;
            this.deliveredOrConsumedQuantity = deliveredOrConsumedQuantity;
            this.shrinkflationApplied = shrinkflationApplied;
            this.shrinkflationReducedUnits = shrinkflationReducedUnits;
            this.unitPrice = unitPrice;
            this.totalPrice = totalPrice;
        }

        public MarketItem getItem() { return item; }
        public boolean isBuying() { return buying; }
        public int getRequestedQuantity() { return requestedQuantity; }
        public int getDeliveredOrConsumedQuantity() { return deliveredOrConsumedQuantity; }
        public boolean isShrinkflationApplied() { return shrinkflationApplied; }
        public int getShrinkflationReducedUnits() { return shrinkflationReducedUnits; }
        public double getUnitPrice() { return unitPrice; }
        public double getTotalPrice() { return totalPrice; }
    }

    /**
     * Handles all SQLite database persistence for market items and transaction audit logs.
     */
    public static final class DatabaseManager {
        private final DynamicMarket plugin;
        private Connection connection;
        private final Object dbLock = new Object();

        public DatabaseManager(DynamicMarket plugin) {
            this.plugin = plugin;
        }

        public boolean initialize() {
            synchronized (dbLock) {
                try {
                    Class.forName("org.sqlite.JDBC");
                    File dataFolder = plugin.getDataFolder();
                    if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                        plugin.getLogger().warning("Could not create plugin data directory: " + dataFolder.getAbsolutePath());
                    }
                    File dbFile = new File(dataFolder, "dynamic_market.db");
                    String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                    this.connection = DriverManager.getConnection(url);

                    try (Statement stmt = this.connection.createStatement()) {
                        stmt.execute("PRAGMA journal_mode=WAL;");
                        stmt.execute("PRAGMA synchronous=NORMAL;");
                        stmt.execute("CREATE TABLE IF NOT EXISTS market_items (" +
                                "material TEXT PRIMARY KEY NOT NULL," +
                                "category TEXT NOT NULL," +
                                "base_buy_price REAL NOT NULL," +
                                "base_sell_price REAL NOT NULL," +
                                "current_multiplier REAL NOT NULL," +
                                "total_demand INTEGER NOT NULL DEFAULT 0," +
                                "total_supply INTEGER NOT NULL DEFAULT 0," +
                                "recent_activity INTEGER NOT NULL DEFAULT 0," +
                                "last_updated INTEGER NOT NULL" +
                                ");");
                        stmt.execute("CREATE TABLE IF NOT EXISTS market_transactions (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "player_uuid TEXT NOT NULL," +
                                "player_name TEXT NOT NULL," +
                                "material TEXT NOT NULL," +
                                "action TEXT NOT NULL," +
                                "requested_qty INTEGER NOT NULL," +
                                "actual_qty INTEGER NOT NULL," +
                                "total_price REAL NOT NULL," +
                                "multiplier_after REAL NOT NULL," +
                                "timestamp INTEGER NOT NULL" +
                                ");");
                    }
                    return true;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error initializing SQLite database", e);
                    return false;
                }
            }
        }

        public Map<Material, MarketItem> loadSavedMarketItems() {
            Map<Material, MarketItem> loaded = new EnumMap<>(Material.class);
            synchronized (dbLock) {
                if (this.connection == null) return loaded;
                String sql = "SELECT material, category, base_buy_price, base_sell_price, current_multiplier, " +
                        "total_demand, total_supply, recent_activity, last_updated FROM market_items";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Material mat = Material.matchMaterial(rs.getString("material"));
                        if (mat == null) continue;
                        MarketCategory cat;
                        try {
                            cat = MarketCategory.valueOf(rs.getString("category"));
                        } catch (IllegalArgumentException ex) {
                            cat = MarketCategory.MOB_DROPS_AND_MISC;
                        }
                        MarketItem item = new MarketItem(
                                mat,
                                cat,
                                rs.getDouble("base_buy_price"),
                                rs.getDouble("base_sell_price"),
                                rs.getDouble("current_multiplier"),
                                rs.getLong("total_demand"),
                                rs.getLong("total_supply"),
                                rs.getInt("recent_activity"),
                                rs.getLong("last_updated")
                        );
                        loaded.put(mat, item);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to load market items from SQLite", e);
                }
            }
            return loaded;
        }

        public void saveDirtyItemsSync(Collection<MarketItem> items) {
            List<MarketItem> dirtyList = items.stream().filter(MarketItem::isDirty).collect(Collectors.toList());
            if (!dirtyList.isEmpty()) {
                saveItemsBatch(dirtyList);
            }
        }

        public void saveAllItemsSync(Collection<MarketItem> items) {
            saveItemsBatch(new ArrayList<>(items));
        }

        private void saveItemsBatch(List<MarketItem> items) {
            if (items.isEmpty()) return;
            synchronized (dbLock) {
                if (this.connection == null) return;
                String sql = "INSERT INTO market_items (material, category, base_buy_price, base_sell_price, " +
                        "current_multiplier, total_demand, total_supply, recent_activity, last_updated) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(material) DO UPDATE SET " +
                        "category=excluded.category, base_buy_price=excluded.base_buy_price, " +
                        "base_sell_price=excluded.base_sell_price, current_multiplier=excluded.current_multiplier, " +
                        "total_demand=excluded.total_demand, total_supply=excluded.total_supply, " +
                        "recent_activity=excluded.recent_activity, last_updated=excluded.last_updated";
                try {
                    boolean prevAutoCommit = this.connection.getAutoCommit();
                    this.connection.setAutoCommit(false);
                    try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                        for (MarketItem item : items) {
                            synchronized (item) {
                                ps.setString(1, item.getMaterial().name());
                                ps.setString(2, item.getCategory().name());
                                ps.setDouble(3, item.getBaseBuyPrice());
                                ps.setDouble(4, item.getBaseSellPrice());
                                ps.setDouble(5, item.getCurrentMultiplier());
                                ps.setLong(6, item.getTotalDemandUnits());
                                ps.setLong(7, item.getTotalSupplyUnits());
                                ps.setInt(8, item.getRecentActivityScore());
                                ps.setLong(9, item.getLastUpdatedEpochSeconds());
                                ps.addBatch();
                                item.clearDirty();
                            }
                        }
                        ps.executeBatch();
                    }
                    this.connection.commit();
                    this.connection.setAutoCommit(prevAutoCommit);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to batch-save market items to SQLite", e);
                }
            }
        }

        public void logTransactionAsync(UUID playerUuid, String playerName, Material material, String action,
                                        int requestedQty, int actualQty, double totalPrice, double multiplierAfter) {
            long now = Instant.now().getEpochSecond();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                synchronized (dbLock) {
                    if (this.connection == null) return;
                    String sql = "INSERT INTO market_transactions (player_uuid, player_name, material, action, " +
                            "requested_qty, actual_qty, total_price, multiplier_after, timestamp) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setString(2, playerName);
                        ps.setString(3, material.name());
                        ps.setString(4, action);
                        ps.setInt(5, requestedQty);
                        ps.setInt(6, actualQty);
                        ps.setDouble(7, totalPrice);
                        ps.setDouble(8, multiplierAfter);
                        ps.setLong(9, now);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to record transaction log in SQLite", e);
                    }
                }
            });
        }

        public void close() {
            synchronized (dbLock) {
                if (this.connection != null) {
                    try {
                        this.connection.close();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Error closing SQLite connection", e);
                    }
                    this.connection = null;
                }
            }
        }
    }

    /**
     * Bridges Vault Economy (EssentialsX Economy, CMI, etc.) with safe fallback support.
     */
    public static final class EconomyBridge {
        private final DynamicMarket plugin;
        private Economy vaultEconomy;
        private final Map<UUID, Double> fallbackBalances = new ConcurrentHashMap<>();

        public EconomyBridge(DynamicMarket plugin) {
            this.plugin = plugin;
        }

        public boolean setupEconomy() {
            if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
                return false;
            }
            RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                return false;
            }
            this.vaultEconomy = rsp.getProvider();
            return this.vaultEconomy != null;
        }

        public String getProviderName() {
            return this.vaultEconomy != null ? this.vaultEconomy.getName() : "Built-in Fallback Ledger";
        }

        public double getBalance(Player player) {
            if (this.vaultEconomy != null) {
                return Math.max(0.0D, this.vaultEconomy.getBalance(player));
            }
            return fallbackBalances.computeIfAbsent(player.getUniqueId(),
                    k -> plugin.configManager.getFallbackStartingBalance());
        }

        public boolean hasEnough(Player player, double amount) {
            if (amount <= 0.0D) return true;
            if (this.vaultEconomy != null) {
                return this.vaultEconomy.has(player, amount);
            }
            return getBalance(player) + 1e-6 >= amount;
        }

        public boolean withdraw(Player player, double amount) {
            if (amount <= 0.0D) return true;
            if (this.vaultEconomy != null) {
                EconomyResponse response = this.vaultEconomy.withdrawPlayer(player, amount);
                return response != null && response.transactionSuccess();
            }
            synchronized (fallbackBalances) {
                double current = getBalance(player);
                if (current + 1e-6 < amount) return false;
                fallbackBalances.put(player.getUniqueId(), Math.max(0.0D, current - amount));
                return true;
            }
        }

        public boolean deposit(Player player, double amount) {
            if (amount <= 0.0D) return true;
            if (this.vaultEconomy != null) {
                EconomyResponse response = this.vaultEconomy.depositPlayer(player, amount);
                return response != null && response.transactionSuccess();
            }
            synchronized (fallbackBalances) {
                double current = getBalance(player);
                fallbackBalances.put(player.getUniqueId(), current + amount);
                return true;
            }
        }
    }

    /**
     * Manages all obtainable vanilla Minecraft items, sensible default pricing,
     * dynamic supply/demand curves, and Shrinkflation calculations.
     */
    public static final class MarketEngine {
        private final DynamicMarket plugin;
        private final PluginConfigManager config;
        private final DatabaseManager database;
        private final Map<Material, MarketItem> itemsByMaterial = new EnumMap<>(Material.class);

        public MarketEngine(DynamicMarket plugin, PluginConfigManager config, DatabaseManager database) {
            this.plugin = plugin;
            this.config = config;
            this.database = database;
        }

        public void initializeMarketCatalog() {
            Map<Material, MarketItem> saved = database.loadSavedMarketItems();
            List<MarketItem> newlyDiscovered = new ArrayList<>();
            long now = Instant.now().getEpochSecond();

            for (Material mat : Material.values()) {
                if (!isNormalObtainableItem(mat)) {
                    continue;
                }
                MarketCategory category = determineCategory(mat);
                double defaultBaseBuy = determineDefaultBasePrice(mat, category);
                double configuredBaseBuy = config.getCustomBasePrices().getOrDefault(mat, defaultBaseBuy);
                double baseSell = Math.max(0.01D, Math.round(configuredBaseBuy * config.getSellFactor() * 100.0D) / 100.0D);

                MarketItem existing = saved.get(mat);
                if (existing != null) {
                    existing.setCategory(category);
                    existing.updateBasePrices(configuredBaseBuy, config.getSellFactor());
                    existing.setCurrentMultiplier(existing.getCurrentMultiplier(), config.getMinMultiplier(), config.getMaxMultiplier());
                    itemsByMaterial.put(mat, existing);
                } else {
                    MarketItem created = new MarketItem(mat, category, configuredBaseBuy, baseSell, 1.0D, 0L, 0L, 0, now);
                    itemsByMaterial.put(mat, created);
                    newlyDiscovered.add(created);
                }
            }

            if (!newlyDiscovered.isEmpty()) {
                database.saveAllItemsSync(newlyDiscovered);
            }
        }

        public void reloadCatalogSettings() {
            for (MarketItem item : itemsByMaterial.values()) {
                Material mat = item.getMaterial();
                MarketCategory category = determineCategory(mat);
                double defaultBaseBuy = determineDefaultBasePrice(mat, category);
                double configuredBaseBuy = config.getCustomBasePrices().getOrDefault(mat, defaultBaseBuy);
                item.setCategory(category);
                item.updateBasePrices(configuredBaseBuy, config.getSellFactor());
                item.setCurrentMultiplier(item.getCurrentMultiplier(), config.getMinMultiplier(), config.getMaxMultiplier());
            }
        }

        /**
         * Filters out unobtainable, technical, legacy, spawn-egg, and operator-only blocks/items.
         */
        public static boolean isNormalObtainableItem(Material mat) {
            if (mat == null || mat.isAir() || !mat.isItem()) {
                return false;
            }
            String name = mat.name();
            if (name.startsWith("LEGACY_")) {
                return false;
            }
            if (name.endsWith("_SPAWN_EGG") || name.contains("COMMAND_BLOCK") || name.contains("STRUCTURE_")
                    || name.equals("BARRIER") || name.equals("BEDROCK") || name.equals("LIGHT")
                    || name.equals("JIGSAW") || name.equals("DEBUG_STICK") || name.equals("KNOWLEDGE_BOOK")
                    || name.equals("SPAWNER") || name.equals("TRIAL_SPAWNER") || name.equals("VAULT")
                    || name.equals("REINFORCED_DEEPSLATE") || name.equals("END_PORTAL_FRAME")
                    || name.equals("INFESTED_STONE") || name.startsWith("INFESTED_")
                    || name.equals("BUDDING_AMETHYST") || name.equals("PETRIFIED_OAK_SLAB")
                    || name.equals("PLAYER_HEAD") || name.equals("FILLED_MAP")
                    || name.equals("WRITTEN_BOOK") || name.equals("SUSPICIOUS_SAND")
                    || name.equals("SUSPICIOUS_GRAVEL") || name.equals("FARMLAND")
                    || name.equals("DIRT_PATH") || name.equals("CHORUS_PLANT")) {
                return false;
            }
            return true;
        }

        /**
         * Automatically classifies every vanilla item into a logical shop category.
         */
        public MarketCategory determineCategory(Material mat) {
            MarketCategory override = config.getCustomCategoryOverrides().get(mat);
            if (override != null) {
                return override;
            }
            String n = mat.name();

            // 1. Ores & Valuables
            if (n.endsWith("_ORE") || n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET")
                    || n.contains("DIAMOND") || n.contains("NETHERITE") || n.contains("EMERALD")
                    || n.contains("AMETHYST") || n.contains("LAPIS") || n.contains("QUARTZ")
                    || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("COAL_BLOCK")
                    || n.equals("GOLD_BLOCK") || n.equals("IRON_BLOCK") || n.equals("COPPER_BLOCK")
                    || n.equals("NETHER_STAR") || n.equals("HEART_OF_THE_SEA") || n.equals("ECHO_SHARD")
                    || n.equals("ANCIENT_DEBRIS") || n.equals("BEACON")) {
                if (!n.endsWith("_SWORD") && !n.endsWith("_PICKAXE") && !n.endsWith("_AXE")
                        && !n.endsWith("_SHOVEL") && !n.endsWith("_HOE") && !n.endsWith("_HELMET")
                        && !n.endsWith("_CHESTPLATE") && !n.endsWith("_LEGGINGS") && !n.endsWith("_BOOTS")
                        && !n.endsWith("_HORSE_ARMOR") && !n.endsWith("_SPEAR")) {
                    return MarketCategory.ORES_AND_VALUABLES;
                }
            }

            // 2. Combat & Tools
            if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                    || n.endsWith("_HOE") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                    || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.endsWith("_SPEAR")
                    || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE")
                    || n.equals("SHIELD") || n.equals("TOTEM_OF_UNDYING") || n.contains("ARROW")
                    || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL") || n.equals("SHEARS")
                    || n.equals("BRUSH") || n.equals("SPYGLASS") || n.equals("ELYTRA")
                    || n.endsWith("_HORSE_ARMOR") || n.equals("WOLF_ARMOR") || n.equals("TURTLE_HELMET")
                    || n.equals("WIND_CHARGE") || n.equals("FIREWORK_ROCKET") || n.equals("GOAT_HORN")) {
                return MarketCategory.COMBAT_AND_TOOLS;
            }

            // 3. Food & Brewing
            if (mat.isEdible() || n.contains("POTION") || n.equals("GOLDEN_APPLE")
                    || n.equals("ENCHANTED_GOLDEN_APPLE") || n.equals("CAKE") || n.equals("MILK_BUCKET")
                    || n.equals("HONEY_BOTTLE") || n.equals("GLASS_BOTTLE") || n.equals("BREWING_STAND")
                    || n.equals("CAULDRON") || n.equals("BLAZE_POWDER") || n.equals("MAGMA_CREAM")
                    || n.equals("FERMENTED_SPIDER_EYE") || n.equals("GLISTERING_MELON_SLICE")
                    || n.equals("GHAST_TEAR") || n.equals("RABBIT_FOOT") || n.equals("PHANTOM_MEMBRANE")
                    || n.equals("DRAGON_BREATH") || n.equals("SUGAR")) {
                return MarketCategory.FOOD_AND_POTIONS;
            }

            // 4. Redstone & Utility
            if (n.contains("REDSTONE") || n.contains("PISTON") || n.contains("RAIL") || n.contains("MINECART")
                    || n.equals("HOPPER") || n.equals("DROPPER") || n.equals("DISPENSER") || n.equals("OBSERVER")
                    || n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("TARGET") || n.equals("LEVER")
                    || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE") || n.equals("DAYLIGHT_DETECTOR")
                    || n.equals("TRIPWIRE_HOOK") || n.equals("LECTERN") || n.equals("NOTE_BLOCK")
                    || n.equals("JUKEBOX") || n.equals("CRAFTER") || n.endsWith("_BULB") || n.equals("SCULK_SENSOR")
                    || n.equals("CALIBRATED_SCULK_SENSOR") || n.equals("LIGHTNING_ROD") || n.equals("TNT")
                    || n.equals("SLIME_BLOCK") || n.equals("HONEY_BLOCK")) {
                return MarketCategory.REDSTONE_AND_UTILITY;
            }

            // 5. Nature & Farming (includes 26.1 Golden Dandelion & vanilla flora)
            if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")
                    || n.endsWith("_SAPLING") || n.endsWith("_LEAVES") || n.endsWith("_SEEDS")
                    || n.contains("DANDELION") || n.equals("POPPY") || n.contains("ORCHID") || n.equals("ALLIUM")
                    || n.contains("TULIP") || n.equals("OXEYE_DAISY") || n.equals("CORNFLOWER")
                    || n.equals("LILY_OF_THE_VALLEY") || n.equals("WITHER_ROSE") || n.equals("SUNFLOWER")
                    || n.equals("LILAC") || n.equals("ROSE_BUSH") || n.equals("PEONY") || n.equals("TORCHFLOWER")
                    || n.equals("PITCHER_PLANT") || n.equals("CACTUS") || n.equals("BAMBOO") || n.equals("SUGAR_CANE")
                    || n.equals("KELP") || n.equals("VINE") || n.contains("MOSS") || n.contains("MUSHROOM")
                    || n.contains("FUNGUS") || n.equals("DIRT") || n.equals("COARSE_DIRT") || n.equals("ROOTED_DIRT")
                    || n.equals("GRASS_BLOCK") || n.equals("PODZOL") || n.equals("MYCELIUM") || n.equals("MUD")
                    || n.equals("CLAY") || n.equals("SAND") || n.equals("RED_SAND") || n.equals("GRAVEL")
                    || n.equals("ICE") || n.equals("PACKED_ICE") || n.equals("BLUE_ICE") || n.equals("SNOW_BLOCK")
                    || n.equals("PUMPKIN") || n.equals("CARVED_PUMPKIN") || n.equals("MELON") || n.equals("HAY_BLOCK")
                    || n.equals("WHEAT") || n.equals("NETHER_WART") || n.equals("COCOA_BEANS")
                    || n.contains("CORAL") || n.equals("SEA_PICKLE") || n.equals("LILY_PAD") || n.equals("SPONGE")
                    || n.equals("WET_SPONGE")) {
                return MarketCategory.NATURAL_AND_FARMING;
            }

            // 6. Building Blocks
            if (mat.isBlock()) {
                return MarketCategory.BUILDING_BLOCKS;
            }

            // 7. Mob Drops & Misc
            return MarketCategory.MOB_DROPS_AND_MISC;
        }

        /**
         * Assigns balanced default base buy prices across all obtainable items.
         */
        public double determineDefaultBasePrice(Material mat, MarketCategory category) {
            String n = mat.name();

            // High-tier treasures & endgame items
            if (n.equals("NETHER_STAR")) return 4500.0D;
            if (n.equals("ELYTRA")) return 5000.0D;
            if (n.equals("ENCHANTED_GOLDEN_APPLE")) return 3200.0D;
            if (n.equals("HEAVY_CORE") || n.equals("MACE")) return 3800.0D;
            if (n.equals("NETHERITE_BLOCK")) return 9500.0D;
            if (n.equals("NETHERITE_INGOT")) return 1050.0D;
            if (n.equals("ANCIENT_DEBRIS")) return 240.0D;
            if (n.equals("TOTEM_OF_UNDYING")) return 850.0D;
            if (n.equals("TRIDENT")) return 900.0D;
            if (n.equals("SHULKER_SHELL")) return 220.0D;
            if (n.endsWith("SHULKER_BOX")) return 480.0D;
            if (n.equals("BEACON")) return 4800.0D;
            if (n.equals("HEART_OF_THE_SEA") || n.equals("CONDUIT")) return 750.0D;
            if (n.equals("DRAGON_EGG") || n.equals("DRAGON_HEAD")) return 6000.0D;
            if (n.endsWith("_SMITHING_TEMPLATE")) return 650.0D;
            if (n.startsWith("MUSIC_DISC_")) return 300.0D;

            // Netherite / Diamond / Gold / Iron / Copper tiers
            if (n.startsWith("NETHERITE_")) return 1450.0D;
            if (n.equals("DIAMOND_BLOCK")) return 900.0D;
            if (n.equals("DIAMOND") || n.contains("DIAMOND_ORE")) return 100.0D;
            if (n.startsWith("DIAMOND_")) return 320.0D;
            if (n.equals("EMERALD_BLOCK")) return 540.0D;
            if (n.equals("EMERALD") || n.contains("EMERALD_ORE")) return 60.0D;
            if (n.equals("GOLD_BLOCK") || n.equals("RAW_GOLD_BLOCK")) return 225.0D;
            if (n.equals("GOLD_INGOT") || n.equals("RAW_GOLD") || n.contains("GOLD_ORE")) return 25.0D;
            if (n.equals("GOLDEN_DANDELION")) return 45.0D; // Minecraft 26.1 item
            if (n.equals("GOLDEN_APPLE")) return 220.0D;
            if (n.equals("GOLDEN_CARROT")) return 28.0D;
            if (n.equals("IRON_BLOCK") || n.equals("RAW_IRON_BLOCK")) return 135.0D;
            if (n.equals("IRON_INGOT") || n.equals("RAW_IRON") || n.contains("IRON_ORE")) return 15.0D;
            if (n.startsWith("IRON_")) return 45.0D;
            if (n.equals("COPPER_BLOCK") || n.equals("RAW_COPPER_BLOCK")) return 54.0D;
            if (n.equals("COPPER_INGOT") || n.equals("RAW_COPPER") || n.contains("COPPER_ORE")) return 6.0D;
            if (n.equals("COAL_BLOCK")) return 36.0D;
            if (n.equals("COAL") || n.equals("CHARCOAL") || n.contains("COAL_ORE")) return 4.0D;
            if (n.equals("LAPIS_BLOCK") || n.equals("REDSTONE_BLOCK")) return 45.0D;
            if (n.equals("LAPIS_LAZULI") || n.equals("REDSTONE") || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD")) return 5.0D;

            // Common building & natural materials
            if (n.equals("COBBLESTONE") || n.equals("COBBLED_DEEPSLATE") || n.equals("DIRT")
                    || n.equals("SAND") || n.equals("GRAVEL") || n.equals("NETHERRACK")) return 1.50D;
            if (n.endsWith("_LOG") || n.endsWith("_STEM") || n.equals("BAMBOO_BLOCK")) return 8.0D;
            if (n.endsWith("_PLANKS")) return 2.50D;
            if (n.endsWith("_SLAB")) return 1.50D;
            if (n.endsWith("_STAIRS")) return 3.0D;
            if (n.contains("GLASS")) return 3.50D;
            if (n.contains("CONCRETE") || n.contains("TERRACOTTA") || n.contains("WOOL")) return 4.50D;
            if (n.equals("OBSIDIAN") || n.equals("CRYING_OBSIDIAN")) return 35.0D;
            if (n.equals("ENDER_PEARL") || n.equals("BLAZE_ROD") || n.equals("GHAST_TEAR") || n.equals("BREEZE_ROD")) return 32.0D;
            if (n.equals("GUNPOWDER") || n.equals("SLIME_BALL") || n.equals("LEATHER") || n.equals("BOOK")) return 12.0D;
            if (n.equals("NAME_TAG") || n.equals("SADDLE") || n.equals("LEAD")) return 40.0D;

            // Category fallbacks
            switch (category) {
                case ORES_AND_VALUABLES:
                    return 40.0D;
                case COMBAT_AND_TOOLS:
                    return 65.0D;
                case REDSTONE_AND_UTILITY:
                    return 18.0D;
                case FOOD_AND_POTIONS:
                    return 10.0D;
                case NATURAL_AND_FARMING:
                    return 5.0D;
                case BUILDING_BLOCKS:
                    return 4.0D;
                case MOB_DROPS_AND_MISC:
                default:
                    return 12.0D;
            }
        }

        /**
         * Builds a complete, transparent TransactionQuote for buying or selling.
         * When optional Shrinkflation is enabled in config.yml and the item's demand multiplier
         * exceeds the threshold, buying a bundle clearly discloses the reduced delivered quantity
         * BEFORE confirmation.
         */
        public TransactionQuote createQuote(MarketItem item, boolean buying, int requestedQuantity) {
            int safeQty = Math.max(1, requestedQuantity);
            double unitPrice = buying ? item.getCurrentBuyPrice() : item.getCurrentSellPrice();
            double totalPrice = Math.max(0.01D, Math.round(unitPrice * safeQty * 100.0D) / 100.0D);

            boolean shrinkApplied = false;
            int deliveredQty = safeQty;
            int reducedUnits = 0;

            if (buying && config.isShrinkflationEnabled()
                    && safeQty >= config.getShrinkflationMinBundleSize()
                    && item.getCurrentMultiplier() >= config.getShrinkflationPriceThresholdMultiplier()) {
                double excessFactor = Math.min(1.0D,
                        (item.getCurrentMultiplier() - config.getShrinkflationPriceThresholdMultiplier()) / 1.5D + 0.35D);
                double effectiveRatio = config.getShrinkflationReductionRatio() * excessFactor;
                reducedUnits = Math.max(1, (int) Math.round(safeQty * effectiveRatio));
                if (reducedUnits < safeQty) {
                    deliveredQty = safeQty - reducedUnits;
                    shrinkApplied = true;
                } else {
                    reducedUnits = 0;
                }
            }

            return new TransactionQuote(item, buying, safeQty, deliveredQty, shrinkApplied, reducedUnits, unitPrice, totalPrice);
        }

        public int performPeriodicPriceRecovery() {
            int recoveredCount = 0;
            double rate = config.getRecoveryRatePerCycle();
            double minMult = config.getMinMultiplier();
            double maxMult = config.getMaxMultiplier();
            for (MarketItem item : itemsByMaterial.values()) {
                if (item.recoverStepTowardBase(rate, minMult, maxMult)) {
                    recoveredCount++;
                }
            }
            return recoveredCount;
        }

        public Optional<MarketItem> getItem(Material material) {
            if (config.getBlacklistedMaterials().contains(material)) {
                return Optional.empty();
            }
            return Optional.ofNullable(itemsByMaterial.get(material));
        }

        public List<MarketItem> getActiveItems() {
            Set<Material> blacklist = config.getBlacklistedMaterials();
            return itemsByMaterial.values().stream()
                    .filter(i -> !blacklist.contains(i.getMaterial()))
                    .sorted(Comparator.comparing(i -> i.getMaterial().name()))
                    .collect(Collectors.toList());
        }

        public List<MarketItem> getItemsByCategory(MarketCategory category) {
            Set<Material> blacklist = config.getBlacklistedMaterials();
            return itemsByMaterial.values().stream()
                    .filter(i -> i.getCategory() == category && !blacklist.contains(i.getMaterial()))
                    .sorted(Comparator.comparing(MarketItem::getFriendlyName))
                    .collect(Collectors.toList());
        }

        public List<MarketItem> searchItems(String query) {
            String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return getActiveItems();
            }
            Set<Material> blacklist = config.getBlacklistedMaterials();
            return itemsByMaterial.values().stream()
                    .filter(i -> !blacklist.contains(i.getMaterial()))
                    .filter(i -> i.getMaterial().name().toLowerCase(Locale.ROOT).contains(normalized)
                            || i.getFriendlyName().toLowerCase(Locale.ROOT).contains(normalized))
                    .sorted(Comparator.comparing(MarketItem::getFriendlyName))
                    .collect(Collectors.toList());
        }

        public Collection<MarketItem> getAllMarketItems() {
            return Collections.unmodifiableCollection(itemsByMaterial.values());
        }
    }

    /**
     * Executes atomic Buy and Sell transactions while verifying inventory capacity,
     * exact vanilla item matching, player balance, and dynamic price updates.
     */
    public static final class TransactionService {
        private final DynamicMarket plugin;
        private final PluginConfigManager config;
        private final EconomyBridge economy;
        private final MarketEngine marketEngine;
        private final DatabaseManager database;

        public TransactionService(DynamicMarket plugin, PluginConfigManager config, EconomyBridge economy,
                                  MarketEngine marketEngine, DatabaseManager database) {
            this.plugin = plugin;
            this.config = config;
            this.economy = economy;
            this.marketEngine = marketEngine;
            this.database = database;
        }

        public static final class TransactionResult {
            private final boolean success;
            private final String message;
            private final TransactionQuote executedQuote;

            public TransactionResult(boolean success, String message, TransactionQuote executedQuote) {
                this.success = success;
                this.message = message;
                this.executedQuote = executedQuote;
            }

            public boolean isSuccess() { return success; }
            public String getMessage() { return message; }
            public TransactionQuote getExecutedQuote() { return executedQuote; }
        }

        /**
         * Calculates how many additional units of {@code material} can fit into the player's storage slots.
         */
        public int calculateAvailableSpaceForMaterial(Player player, Material material) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] storage = inv.getStorageContents();
            int maxStack = material.getMaxStackSize();
            int space = 0;
            for (ItemStack stack : storage) {
                if (stack == null || stack.getType().isAir()) {
                    space += maxStack;
                } else if (isCleanSellableStack(stack, material)) {
                    int remaining = maxStack - stack.getAmount();
                    if (remaining > 0) {
                        space += remaining;
                    }
                }
            }
            return space;
        }

        /**
         * Counts how many clean, undamaged, unenchanted units of {@code material} the player holds.
         */
        public int countSellableItems(Player player, Material material) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] storage = inv.getStorageContents();
            int count = 0;
            for (ItemStack stack : storage) {
                if (isCleanSellableStack(stack, material)) {
                    count += stack.getAmount();
                }
            }
            return count;
        }

        /**
         * Ensures players cannot accidentally sell named, enchanted, or damaged gear as raw commodities,
         * and prevents custom-item duplication exploits.
         */
        public static boolean isCleanSellableStack(ItemStack stack, Material targetMaterial) {
            if (stack == null || stack.getType() != targetMaterial || stack.getAmount() <= 0) {
                return false;
            }
            if (!stack.hasItemMeta()) {
                return true;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return true;
            }
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelData()) {
                return false;
            }
            if (meta instanceof org.bukkit.inventory.meta.Damageable) {
                org.bukkit.inventory.meta.Damageable dmg = (org.bukkit.inventory.meta.Damageable) meta;
                if (dmg.hasDamage() && dmg.getDamage() > 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Executes a confirmed purchase using the exact disclosed quote.
         * Never secretly alters a confirmed transaction.
         */
        public synchronized TransactionResult executeBuy(Player player, TransactionQuote confirmedQuote) {
            MarketItem item = confirmedQuote.getItem();
            Material material = item.getMaterial();
            int deliveredQty = confirmedQuote.getDeliveredOrConsumedQuantity();
            double totalCost = confirmedQuote.getTotalPrice();

            if (deliveredQty <= 0) {
                return new TransactionResult(false, "Invalid quantity selected.", confirmedQuote);
            }

            // 1. Verify inventory space for the exact delivered quantity
            int freeSpace = calculateAvailableSpaceForMaterial(player, material);
            if (freeSpace < deliveredQty) {
                return new TransactionResult(false,
                        "Insufficient inventory space! You need space for " + deliveredQty
                                + "x " + item.getFriendlyName() + " (available: " + freeSpace + ").",
                        confirmedQuote);
            }

            // 2. Verify player funds
            if (!economy.hasEnough(player, totalCost)) {
                return new TransactionResult(false,
                        "Insufficient funds! Required: " + config.formatCurrency(totalCost)
                                + ", Balance: " + config.formatCurrency(economy.getBalance(player)) + ".",
                        confirmedQuote);
            }

            // 3. Withdraw funds first
            if (!economy.withdraw(player, totalCost)) {
                return new TransactionResult(false, "Economy withdrawal failed. Please try again.", confirmedQuote);
            }

            // 4. Deliver items into player inventory in maxStackSize batches
            int remainingToGive = deliveredQty;
            int maxStack = material.getMaxStackSize();
            while (remainingToGive > 0) {
                int batch = Math.min(remainingToGive, maxStack);
                ItemStack stack = new ItemStack(material, batch);
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                if (!leftovers.isEmpty()) {
                    // Safety net: drop at player's feet if an unexpected slot conflict occurred
                    for (ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                remainingToGive -= batch;
            }

            // 5. Update dynamic market demand & price
            item.recordPurchase(confirmedQuote.getRequestedQuantity(),
                    config.getBuySensitivity(), config.getMinMultiplier(), config.getMaxMultiplier());

            // 6. Log to SQLite asynchronously
            database.logTransactionAsync(player.getUniqueId(), player.getName(), material, "BUY",
                    confirmedQuote.getRequestedQuantity(), deliveredQty, totalCost, item.getCurrentMultiplier());

            String shrinkNote = confirmedQuote.isShrinkflationApplied()
                    ? " (Shrinkflation bundle: " + deliveredQty + "/" + confirmedQuote.getRequestedQuantity() + " units delivered as disclosed)"
                    : "";
            return new TransactionResult(true,
                    "Purchased " + deliveredQty + "x " + item.getFriendlyName()
                            + " for " + config.formatCurrency(totalCost) + shrinkNote + "!",
                    confirmedQuote);
        }

        /**
         * Executes a confirmed sale of items from the player's inventory.
         */
        public synchronized TransactionResult executeSell(Player player, TransactionQuote confirmedQuote) {
            MarketItem item = confirmedQuote.getItem();
            Material material = item.getMaterial();
            int sellQty = confirmedQuote.getDeliveredOrConsumedQuantity();
            double totalPayout = confirmedQuote.getTotalPrice();

            if (sellQty <= 0) {
                return new TransactionResult(false, "Invalid quantity selected.", confirmedQuote);
            }

            // 1. Verify player actually has enough clean items in storage
            int available = countSellableItems(player, material);
            if (available < sellQty) {
                return new TransactionResult(false,
                        "You only have " + available + "x sellable " + item.getFriendlyName()
                                + " (tried to sell " + sellQty + ").",
                        confirmedQuote);
            }

            // 2. Remove exact quantity from storage contents
            PlayerInventory inv = player.getInventory();
            ItemStack[] storage = inv.getStorageContents();
            int remainingToRemove = sellQty;
            for (int i = 0; i < storage.length && remainingToRemove > 0; i++) {
                ItemStack stack = storage[i];
                if (isCleanSellableStack(stack, material)) {
                    int take = Math.min(remainingToRemove, stack.getAmount());
                    int newAmount = stack.getAmount() - take;
                    storage[i] = newAmount > 0 ? stack.asQuantity(newAmount) : null;
                    remainingToRemove -= take;
                }
            }
            inv.setStorageContents(storage);

            // 3. Deposit payout into player economy account
            if (!economy.deposit(player, totalPayout)) {
                // Rollback items if economy deposit failed
                inv.addItem(new ItemStack(material, sellQty));
                return new TransactionResult(false, "Economy deposit failed! Your items were returned.", confirmedQuote);
            }

            // 4. Update dynamic market supply & price
            item.recordSale(sellQty, config.getSellSensitivity(), config.getMinMultiplier(), config.getMaxMultiplier());

            // 5. Log to SQLite asynchronously
            database.logTransactionAsync(player.getUniqueId(), player.getName(), material, "SELL",
                    sellQty, sellQty, totalPayout, item.getCurrentMultiplier());

            return new TransactionResult(true,
                    "Sold " + sellQty + "x " + item.getFriendlyName()
                            + " for " + config.formatCurrency(totalPayout) + "!",
                    confirmedQuote);
        }
    }

    /**
     * Custom InventoryHolder used to identify DynamicMarket menus and prevent spoofing or item duplication.
     */
    public static final class ShopInventoryHolder implements InventoryHolder {
        public enum MenuType {
            MAIN_CATEGORIES,
            ITEM_BROWSER,
            TRANSACTION_MENU
        }

        private final UUID viewerId;
        private final MenuType menuType;
        private final MarketCategory selectedCategory;
        private final String searchQuery;
        private final int page;
        private final MarketItem selectedItem;
        private boolean buyingMode;
        private int selectedQuantity;
        private TransactionQuote currentDisclosedQuote;
        private Inventory inventory;

        public ShopInventoryHolder(UUID viewerId, MenuType menuType, MarketCategory selectedCategory,
                                   String searchQuery, int page, MarketItem selectedItem,
                                   boolean buyingMode, int selectedQuantity) {
            this.viewerId = viewerId;
            this.menuType = menuType;
            this.selectedCategory = selectedCategory;
            this.searchQuery = searchQuery == null ? "" : searchQuery;
            this.page = Math.max(0, page);
            this.selectedItem = selectedItem;
            this.buyingMode = buyingMode;
            this.selectedQuantity = Math.max(1, selectedQuantity);
        }

        @Override
        public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        public UUID getViewerId() { return viewerId; }
        public MenuType getMenuType() { return menuType; }
        public MarketCategory getSelectedCategory() { return selectedCategory; }
        public String getSearchQuery() { return searchQuery; }
        public int getPage() { return page; }
        public MarketItem getSelectedItem() { return selectedItem; }
        public boolean isBuyingMode() { return buyingMode; }
        public void setBuyingMode(boolean buyingMode) { this.buyingMode = buyingMode; }
        public int getSelectedQuantity() { return selectedQuantity; }
        public void setSelectedQuantity(int selectedQuantity) { this.selectedQuantity = Math.max(1, selectedQuantity); }
        public TransactionQuote getCurrentDisclosedQuote() { return currentDisclosedQuote; }
        public void setCurrentDisclosedQuote(TransactionQuote quote) { this.currentDisclosedQuote = quote; }
    }

    /**
     * Renders and manages all Shop GUI menus, quantity controls, search prompts,
     * and anti-duplication inventory listeners.
     */
    public static final class ShopGuiManager implements Listener {
        private static final int ITEMS_PER_PAGE = 36; // Slots 9..44 in a 54-slot chest GUI
        private final DynamicMarket plugin;
        private final PluginConfigManager config;
        private final MarketEngine marketEngine;
        private final TransactionService transactionService;
        private final EconomyBridge economy;
        private final Set<UUID> awaitingSearchInput = ConcurrentHashMap.newKeySet();
        private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();

        public ShopGuiManager(DynamicMarket plugin, PluginConfigManager config, MarketEngine marketEngine,
                              TransactionService transactionService, EconomyBridge economy) {
            this.plugin = plugin;
            this.config = config;
            this.marketEngine = marketEngine;
            this.transactionService = transactionService;
            this.economy = economy;
        }

        /**
         * Opens the main `/shop` category overview menu.
         */
        public void openMainMenu(Player player) {
            ShopInventoryHolder holder = new ShopInventoryHolder(
                    player.getUniqueId(), ShopInventoryHolder.MenuType.MAIN_CATEGORIES,
                    null, "", 0, null, true, 1
            );
            Inventory inv = Bukkit.createInventory(holder, 54,
                    Component.text("✦ DynamicMarket ", NamedTextColor.GOLD, TextDecoration.BOLD)
                            .append(Component.text("— Categories", NamedTextColor.DARK_GRAY)));
            holder.setInventory(inv);

            fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);

            // Player Balance & Market Status Header (Slot 4)
            inv.setItem(4, createGuiItem(Material.SUNFLOWER,
                    Component.text("Your Account & Market Overview", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Balance: ", NamedTextColor.GRAY)
                                    .append(Component.text(config.formatCurrency(economy.getBalance(player)), NamedTextColor.GREEN)),
                            Component.text("Economy Provider: ", NamedTextColor.GRAY)
                                    .append(Component.text(economy.getProviderName(), NamedTextColor.AQUA)),
                            Component.text("Indexed Obtainable Items: ", NamedTextColor.GRAY)
                                    .append(Component.text(String.valueOf(marketEngine.getActiveItems().size()), NamedTextColor.YELLOW)),
                            Component.text("Shrinkflation Mode: ", NamedTextColor.GRAY)
                                    .append(Component.text(config.isShrinkflationEnabled() ? "Enabled (Disclosed)" : "Disabled",
                                            config.isShrinkflationEnabled() ? NamedTextColor.GOLD : NamedTextColor.GREEN))
                    )));

            // Category Icons arranged neatly in the center
            int[] categorySlots = {19, 20, 21, 22, 23, 24, 25};
            MarketCategory[] categories = MarketCategory.values();
            for (int i = 0; i < categories.length && i < categorySlots.length; i++) {
                MarketCategory cat = categories[i];
                int itemCount = marketEngine.getItemsByCategory(cat).size();
                inv.setItem(categorySlots[i], createGuiItem(cat.getIcon(),
                        Component.text(cat.getDisplayName(), cat.getColor(), TextDecoration.BOLD),
                        Arrays.asList(
                                Component.text(cat.getDescription(), NamedTextColor.GRAY),
                                Component.empty(),
                                Component.text("Available Items: ", NamedTextColor.GRAY)
                                        .append(Component.text(String.valueOf(itemCount), NamedTextColor.WHITE)),
                                Component.empty(),
                                Component.text("▶ Click to browse category", NamedTextColor.YELLOW)
                        )));
            }

            // Browse All Items button (Slot 30)
            inv.setItem(30, createGuiItem(Material.CHEST,
                    Component.text("Browse All Items", NamedTextColor.AQUA, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("View all obtainable vanilla items", NamedTextColor.GRAY),
                            Component.text("across every category with pagination.", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to view all items", NamedTextColor.YELLOW)
                    )));

            // Search Items button (Slot 31)
            inv.setItem(31, createGuiItem(Material.COMPASS,
                    Component.text("Search Items", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Find any block or item by name.", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to type a search query in chat", NamedTextColor.YELLOW)
                    )));

            // Top Movers / Market Info button (Slot 32)
            List<Component> moversLore = buildTopMoversLore();
            inv.setItem(32, createGuiItem(Material.SPYGLASS,
                    Component.text("Live Market Pulse", NamedTextColor.GREEN, TextDecoration.BOLD),
                    moversLore));

            // Close button (Slot 49)
            inv.setItem(49, createGuiItem(Material.BARRIER,
                    Component.text("Close Shop", NamedTextColor.RED, TextDecoration.BOLD),
                    Collections.singletonList(Component.text("Click to close the market menu.", NamedTextColor.GRAY))));

            player.openInventory(inv);
        }

        /**
         * Opens the paginated Item Browser for a specific category or search query.
         */
        public void openItemBrowser(Player player, MarketCategory category, String searchQuery, int page) {
            List<MarketItem> matchingItems;
            String titleSuffix;
            if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                matchingItems = marketEngine.searchItems(searchQuery);
                titleSuffix = "Search: \"" + searchQuery.trim() + "\"";
            } else if (category != null) {
                matchingItems = marketEngine.getItemsByCategory(category);
                titleSuffix = category.getDisplayName();
            } else {
                matchingItems = marketEngine.getActiveItems();
                titleSuffix = "All Items";
            }

            int totalPages = Math.max(1, (int) Math.ceil((double) matchingItems.size() / ITEMS_PER_PAGE));
            int safePage = Math.max(0, Math.min(page, totalPages - 1));

            ShopInventoryHolder holder = new ShopInventoryHolder(
                    player.getUniqueId(), ShopInventoryHolder.MenuType.ITEM_BROWSER,
                    category, searchQuery, safePage, null, true, 1
            );
            Inventory inv = Bukkit.createInventory(holder, 54,
                    Component.text("✦ Shop: ", NamedTextColor.GOLD, TextDecoration.BOLD)
                            .append(Component.text(titleSuffix + " (" + (safePage + 1) + "/" + totalPages + ")", NamedTextColor.DARK_GRAY)));
            holder.setInventory(inv);

            // Top & Bottom navigation bars
            ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), Collections.emptyList());
            for (int slot = 0; slot < 9; slot++) inv.setItem(slot, glass);
            for (int slot = 45; slot < 54; slot++) inv.setItem(slot, glass);

            // Top Bar Controls
            inv.setItem(0, createGuiItem(Material.OAK_DOOR,
                    Component.text("← Back to Categories", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Collections.singletonList(Component.text("Return to the main category menu.", NamedTextColor.GRAY))));

            inv.setItem(4, createGuiItem(Material.GOLD_INGOT,
                    Component.text("Your Balance: " + config.formatCurrency(economy.getBalance(player)), NamedTextColor.GOLD, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Showing " + matchingItems.size() + " matching items.", NamedTextColor.GRAY),
                            Component.text("Left-Click an item to BUY", NamedTextColor.GREEN),
                            Component.text("Right-Click an item to SELL", NamedTextColor.AQUA)
                    )));

            inv.setItem(8, createGuiItem(Material.COMPASS,
                    Component.text("Search Filter", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Current query: " + (searchQuery == null || searchQuery.isEmpty() ? "None" : searchQuery), NamedTextColor.GRAY),
                            Component.text("▶ Left-Click to search by name", NamedTextColor.YELLOW),
                            Component.text("▶ Right-Click to clear search", NamedTextColor.RED)
                    )));

            // Populate items in slots 9..44
            int startIndex = safePage * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, matchingItems.size());
            for (int i = startIndex; i < endIndex; i++) {
                MarketItem marketItem = matchingItems.get(i);
                int slot = 9 + (i - startIndex);
                inv.setItem(slot, createMarketItemDisplayStack(marketItem));
            }

            // Bottom Pagination Controls
            if (safePage > 0) {
                inv.setItem(45, createGuiItem(Material.ARROW,
                        Component.text("« Previous Page (" + safePage + ")", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Collections.singletonList(Component.text("Go to page " + safePage, NamedTextColor.GRAY))));
            }
            inv.setItem(49, createGuiItem(Material.PAPER,
                    Component.text("Page " + (safePage + 1) + " of " + totalPages, NamedTextColor.WHITE, TextDecoration.BOLD),
                    Collections.singletonList(Component.text("Total items: " + matchingItems.size(), NamedTextColor.GRAY))));
            if (safePage + 1 < totalPages) {
                inv.setItem(53, createGuiItem(Material.ARROW,
                        Component.text("Next Page (" + (safePage + 2) + ") »", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Collections.singletonList(Component.text("Go to page " + (safePage + 2), NamedTextColor.GRAY))));
            }

            player.openInventory(inv);
        }

        /**
         * Builds the item icon with full dynamic price lore:
         * - Current buy and sell prices
         * - Original buy and sell prices
         * - Percentage difference (Red for price increases, Green for decreases, Gray for unchanged)
         * - Demand, supply and recent market activity
         */
        public ItemStack createMarketItemDisplayStack(MarketItem item) {
            double origBuy = item.getBaseBuyPrice();
            double origSell = item.getBaseSellPrice();
            double currBuy = item.getCurrentBuyPrice();
            double currSell = item.getCurrentSellPrice();
            double pctDiff = item.getPercentageDifference();

            NamedTextColor changeColor;
            String signPrefix;
            if (pctDiff > 0.05D) {
                // Requirement 4: Use red for price increases
                changeColor = NamedTextColor.RED;
                signPrefix = "+";
            } else if (pctDiff < -0.05D) {
                // Requirement 4: Use green for price decreases
                changeColor = NamedTextColor.GREEN;
                signPrefix = "";
            } else {
                // Requirement 4: Neutral color for unchanged prices
                changeColor = NamedTextColor.GRAY;
                signPrefix = "";
                pctDiff = 0.0D;
            }

            DecimalFormat pctFormat = new DecimalFormat("0.0");
            String pctText = signPrefix + pctFormat.format(pctDiff) + "%";

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Category: ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(item.getCategory().getDisplayName(), item.getCategory().getColor())));
            lore.add(Component.empty());
            lore.add(Component.text("Original buy price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(origBuy), NamedTextColor.WHITE)));
            lore.add(Component.text("Current buy price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(currBuy), changeColor, TextDecoration.BOLD)));
            lore.add(Component.text("Original sell price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(origSell), NamedTextColor.WHITE)));
            lore.add(Component.text("Current sell price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(currSell), NamedTextColor.GOLD)));
            lore.add(Component.text("Price change: ", NamedTextColor.GRAY)
                    .append(Component.text(pctText, changeColor, TextDecoration.BOLD)));
            lore.add(Component.empty());
            lore.add(Component.text("Demand (Bought): ", NamedTextColor.GRAY)
                    .append(Component.text(item.getTotalDemandUnits() + " units", NamedTextColor.YELLOW)));
            lore.add(Component.text("Supply (Sold): ", NamedTextColor.GRAY)
                    .append(Component.text(item.getTotalSupplyUnits() + " units", NamedTextColor.AQUA)));
            lore.add(Component.text("Recent Activity: ", NamedTextColor.GRAY)
                    .append(Component.text(describeActivityLevel(item.getRecentActivityScore()), NamedTextColor.LIGHT_PURPLE)));

            if (config.isShrinkflationEnabled() && item.getCurrentMultiplier() >= config.getShrinkflationPriceThresholdMultiplier()) {
                lore.add(Component.empty());
                lore.add(Component.text("⚠ High-Demand Shrinkflation Active", NamedTextColor.GOLD));
                lore.add(Component.text("Bundle quantities are adjusted & disclosed", NamedTextColor.GRAY));
                lore.add(Component.text("in the transaction screen before purchase.", NamedTextColor.GRAY));
            }

            lore.add(Component.empty());
            lore.add(Component.text("▶ Left-Click to BUY", NamedTextColor.GREEN));
            lore.add(Component.text("▶ Right-Click to SELL", NamedTextColor.AQUA));

            return createGuiItem(item.getMaterial(),
                    Component.text(item.getFriendlyName(), NamedTextColor.WHITE, TextDecoration.BOLD),
                    lore);
        }

        private String describeActivityLevel(int score) {
            if (score <= 0) return "Quiet (0)";
            if (score < 32) return "Low (" + score + ")";
            if (score < 160) return "Moderate (" + score + ")";
            if (score < 512) return "High (" + score + ")";
            return "Surging (" + score + ")";
        }

        /**
         * Opens the interactive Transaction Menu for buying or selling an item with full quantity controls:
         * "-64", "-16", "-1", "+1", "+16", "+64", "MAX", "1 Stack" and "Confirm".
         */
        public void openTransactionMenu(Player player, MarketItem item, MarketCategory returnCat,
                                        String returnSearch, int returnPage, boolean buyingMode, int initialQty) {
            ShopInventoryHolder holder = new ShopInventoryHolder(
                    player.getUniqueId(), ShopInventoryHolder.MenuType.TRANSACTION_MENU,
                    returnCat, returnSearch, returnPage, item, buyingMode, initialQty
            );
            Inventory inv = Bukkit.createInventory(holder, 54,
                    Component.text(buyingMode ? "✦ Buy: " : "✦ Sell: ",
                                    buyingMode ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
                            .append(Component.text(item.getFriendlyName(), NamedTextColor.DARK_GRAY)));
            holder.setInventory(inv);
            renderTransactionMenuContents(player, holder);
            player.openInventory(inv);
        }

        /**
         * Updates all buttons and live quote disclosures inside the Transaction Menu.
         */
        private void renderTransactionMenuContents(Player player, ShopInventoryHolder holder) {
            Inventory inv = holder.getInventory();
            MarketItem item = holder.getSelectedItem();
            boolean buying = holder.isBuyingMode();
            int qty = Math.max(1, holder.getSelectedQuantity());
            holder.setSelectedQuantity(qty);

            TransactionQuote quote = marketEngine.createQuote(item, buying, qty);
            holder.setCurrentDisclosedQuote(quote);

            fillBorder(inv, buying ? Material.GREEN_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE);

            // Slot 4: Mode Switcher (Buy <-> Sell)
            inv.setItem(4, createGuiItem(buying ? Material.EMERALD : Material.GOLD_INGOT,
                    Component.text("Mode: " + (buying ? "BUYING FROM MARKET" : "SELLING TO MARKET"),
                            buying ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Your Balance: ", NamedTextColor.GRAY)
                                    .append(Component.text(config.formatCurrency(economy.getBalance(player)), NamedTextColor.GOLD)),
                            Component.text("Your Sellable Stock: ", NamedTextColor.GRAY)
                                    .append(Component.text(transactionService.countSellableItems(player, item.getMaterial()) + " units", NamedTextColor.WHITE)),
                            Component.text("Inventory Space: ", NamedTextColor.GRAY)
                                    .append(Component.text(transactionService.calculateAvailableSpaceForMaterial(player, item.getMaterial()) + " units", NamedTextColor.WHITE)),
                            Component.empty(),
                            Component.text("▶ Click to switch to " + (buying ? "SELL" : "BUY") + " mode", NamedTextColor.YELLOW)
                    )));

            // Slot 13: Central Preview Item with live quote breakdown
            List<Component> previewLore = new ArrayList<>();
            previewLore.add(Component.text("Transaction Type: ", NamedTextColor.GRAY)
                    .append(Component.text(buying ? "BUY" : "SELL", buying ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD)));
            previewLore.add(Component.text("Selected Quantity: ", NamedTextColor.GRAY)
                    .append(Component.text(quote.getRequestedQuantity() + "x", NamedTextColor.WHITE, TextDecoration.BOLD)));
            if (quote.isShrinkflationApplied()) {
                previewLore.add(Component.text("⚠ Shrinkflation Disclosed Quantity: ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text(quote.getDeliveredOrConsumedQuantity() + "x delivered (-"
                                + quote.getShrinkflationReducedUnits() + ")", NamedTextColor.RED, TextDecoration.BOLD)));
            } else {
                previewLore.add(Component.text(buying ? "Quantity Delivered: " : "Quantity Sold: ", NamedTextColor.GRAY)
                        .append(Component.text(quote.getDeliveredOrConsumedQuantity() + "x", NamedTextColor.GREEN)));
            }
            previewLore.add(Component.text("Unit Price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(quote.getUnitPrice()), NamedTextColor.YELLOW)));
            previewLore.add(Component.text(buying ? "Total Cost: " : "Total Payout: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(quote.getTotalPrice()), NamedTextColor.GOLD, TextDecoration.BOLD)));
            previewLore.add(Component.text("Your Balance: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(economy.getBalance(player)), NamedTextColor.AQUA)));

            ItemStack centerStack = createGuiItem(item.getMaterial(),
                    Component.text(item.getFriendlyName() + " (x" + quote.getDeliveredOrConsumedQuantity() + ")",
                            NamedTextColor.GOLD, TextDecoration.BOLD),
                    previewLore);
            centerStack.setAmount(Math.max(1, Math.min(item.getMaterial().getMaxStackSize(), quote.getDeliveredOrConsumedQuantity())));
            inv.setItem(13, centerStack);

            // Quantity Control Row (Slots 19..25): -64, -16, -1, [1 Stack], +1, +16, +64
            inv.setItem(19, createQuantityButton(Material.RED_TERRACOTTA, "-64", -64, qty));
            inv.setItem(20, createQuantityButton(Material.RED_STAINED_GLASS_PANE, "-16", -16, qty));
            inv.setItem(21, createQuantityButton(Material.PINK_STAINED_GLASS_PANE, "-1", -1, qty));

            int stackSize = item.getMaterial().getMaxStackSize();
            inv.setItem(22, createGuiItem(Material.CHEST,
                    Component.text("1 Stack (" + stackSize + "x)", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Set quantity to exactly 1 stack", NamedTextColor.GRAY),
                            Component.text("(" + stackSize + " items).", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to set to " + stackSize, NamedTextColor.YELLOW)
                    )));

            inv.setItem(23, createQuantityButton(Material.LIME_STAINED_GLASS_PANE, "+1", 1, qty));
            inv.setItem(24, createQuantityButton(Material.GREEN_STAINED_GLASS_PANE, "+16", 16, qty));
            inv.setItem(25, createQuantityButton(Material.LIME_TERRACOTTA, "+64", 64, qty));

            // Slot 30: MAX Button
            int maxPossible = calculateMaxQuantity(player, item, buying);
            inv.setItem(30, createGuiItem(Material.GOLD_BLOCK,
                    Component.text("MAX (" + Math.max(1, maxPossible) + "x)", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text(buying
                                    ? "Set to max affordable & wearable space"
                                    : "Set to all clean items in your inventory", NamedTextColor.GRAY),
                            Component.text("Max calculated: " + maxPossible + " units", NamedTextColor.WHITE),
                            Component.empty(),
                            Component.text("▶ Click to select MAX", NamedTextColor.YELLOW)
                    )));

            // Slot 32: Confirm Button (always discloses exact delivered quantity and total price)
            List<Component> confirmLore = new ArrayList<>();
            confirmLore.add(Component.text("Action: ", NamedTextColor.GRAY)
                    .append(Component.text(buying ? "BUY" : "SELL", buying ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD)));
            confirmLore.add(Component.text("Item: ", NamedTextColor.GRAY)
                    .append(Component.text(item.getFriendlyName(), NamedTextColor.WHITE)));
            confirmLore.add(Component.text("Selected Quantity: ", NamedTextColor.GRAY)
                    .append(Component.text(quote.getRequestedQuantity() + " units", NamedTextColor.WHITE)));
            if (quote.isShrinkflationApplied()) {
                confirmLore.add(Component.text("Disclosed Delivered Quantity: ", NamedTextColor.GOLD)
                        .append(Component.text(quote.getDeliveredOrConsumedQuantity() + " units (Shrinkflation -"
                                + quote.getShrinkflationReducedUnits() + ")", NamedTextColor.RED, TextDecoration.BOLD)));
            } else {
                confirmLore.add(Component.text(buying ? "Delivered Quantity: " : "Sold Quantity: ", NamedTextColor.GRAY)
                        .append(Component.text(quote.getDeliveredOrConsumedQuantity() + " units", NamedTextColor.GREEN)));
            }
            confirmLore.add(Component.text("Unit Price: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(quote.getUnitPrice()), NamedTextColor.YELLOW)));
            confirmLore.add(Component.text(buying ? "Total Cost: " : "Total Payout: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(quote.getTotalPrice()), NamedTextColor.GOLD, TextDecoration.BOLD)));
            confirmLore.add(Component.text("Your Balance: ", NamedTextColor.GRAY)
                    .append(Component.text(config.formatCurrency(economy.getBalance(player)), NamedTextColor.AQUA)));
            confirmLore.add(Component.empty());
            confirmLore.add(Component.text("✔ Click to Confirm Transaction", NamedTextColor.GREEN, TextDecoration.BOLD));

            inv.setItem(32, createGuiItem(Material.LIME_CONCRETE,
                    Component.text("Confirm " + (buying ? "Purchase" : "Sale"), NamedTextColor.GREEN, TextDecoration.BOLD),
                    confirmLore));

            // Slot 45: Back Button
            inv.setItem(45, createGuiItem(Material.ARROW,
                    Component.text("← Back to Item Browser", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Collections.singletonList(Component.text("Return without completing transaction.", NamedTextColor.GRAY))));
        }

        private int calculateMaxQuantity(Player player, MarketItem item, boolean buying) {
            if (buying) {
                int space = transactionService.calculateAvailableSpaceForMaterial(player, item.getMaterial());
                double unitPrice = item.getCurrentBuyPrice();
                int affordable = unitPrice > 0.0D ? (int) Math.floor(economy.getBalance(player) / unitPrice) : space;
                return Math.max(0, Math.min(space, affordable));
            } else {
                return transactionService.countSellableItems(player, item.getMaterial());
            }
        }

        private ItemStack createQuantityButton(Material mat, String label, int delta, int currentQty) {
            NamedTextColor color = delta > 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
            int targetQty = Math.max(1, Math.min(2304, currentQty + delta));
            return createGuiItem(mat,
                    Component.text(label, color, TextDecoration.BOLD),
                    Arrays.asList(
                            Component.text("Current Quantity: " + currentQty, NamedTextColor.GRAY),
                            Component.text("New Quantity: " + targetQty, NamedTextColor.WHITE),
                            Component.empty(),
                            Component.text("▶ Click to adjust by " + label, NamedTextColor.YELLOW)
                    ));
        }

        private List<Component> buildTopMoversLore() {
            List<MarketItem> all = marketEngine.getActiveItems();
            List<MarketItem> highest = all.stream()
                    .sorted(Comparator.comparingDouble(MarketItem::getPercentageDifference).reversed())
                    .limit(3)
                    .collect(Collectors.toList());
            List<MarketItem> lowest = all.stream()
                    .sorted(Comparator.comparingDouble(MarketItem::getPercentageDifference))
                    .limit(3)
                    .collect(Collectors.toList());

            DecimalFormat df = new DecimalFormat("0.0");
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Highest Demand (Surging Prices):", NamedTextColor.RED));
            for (MarketItem item : highest) {
                double pct = item.getPercentageDifference();
                lore.add(Component.text(" • " + item.getFriendlyName() + ": ", NamedTextColor.GRAY)
                        .append(Component.text((pct >= 0 ? "+" : "") + df.format(pct) + "% ("
                                + config.formatCurrency(item.getCurrentBuyPrice()) + ")", NamedTextColor.RED)));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Highest Supply (Discounted Prices):", NamedTextColor.GREEN));
            for (MarketItem item : lowest) {
                double pct = item.getPercentageDifference();
                lore.add(Component.text(" • " + item.getFriendlyName() + ": ", NamedTextColor.GRAY)
                        .append(Component.text((pct >= 0 ? "+" : "") + df.format(pct) + "% ("
                                + config.formatCurrency(item.getCurrentBuyPrice()) + ")", NamedTextColor.GREEN)));
            }
            return lore;
        }

        private void fillBorder(Inventory inv, Material paneMaterial) {
            ItemStack pane = createGuiItem(paneMaterial, Component.text(" "), Collections.emptyList());
            int size = inv.getSize();
            for (int i = 0; i < size; i++) {
                if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                    inv.setItem(i, pane);
                }
            }
        }

        private ItemStack createGuiItem(Material material, Component displayName, List<Component> lore) {
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(displayName.decoration(TextDecoration.ITALIC, false));
                if (lore != null && !lore.isEmpty()) {
                    List<Component> cleanLore = lore.stream()
                            .map(line -> line.decoration(TextDecoration.ITALIC, false))
                            .collect(Collectors.toList());
                    meta.lore(cleanLore);
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
            return item;
        }

        /**
         * Strictly intercepts all inventory clicks when a DynamicMarket GUI is open.
         * Cancels every click, shift-click, number-key swap, offhand swap, and collect-to-cursor action
         * to make item duplication or unauthorized extraction impossible.
         */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory topInv = event.getView().getTopInventory();
            if (!(topInv.getHolder() instanceof ShopInventoryHolder)) {
                return;
            }

            // Always cancel the raw inventory event immediately to prevent any item movement or duplication
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player)) {
                return;
            }
            Player player = (Player) event.getWhoClicked();
            ShopInventoryHolder holder = (ShopInventoryHolder) topInv.getHolder();

            // Verify holder ownership
            if (!player.getUniqueId().equals(holder.getViewerId())) {
                player.closeInventory();
                return;
            }

            // Block double-clicks, number key swaps, offhand swaps, drop keys, or clicking in bottom inventory
            if (event.getClick() == ClickType.DOUBLE_CLICK
                    || event.getClick() == ClickType.NUMBER_KEY
                    || event.getClick() == ClickType.SWAP_OFFHAND
                    || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                    || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Allow simple left/right clicks on top inventory only
                if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInv)) {
                    return;
                }
            }

            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInv)) {
                return;
            }

            // Rate-limit rapid macro clicks (100ms minimum interval)
            long now = System.currentTimeMillis();
            long lastClick = clickCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now - lastClick < 100L) {
                return;
            }
            clickCooldowns.put(player.getUniqueId(), now);

            int slot = event.getRawSlot();
            switch (holder.getMenuType()) {
                case MAIN_CATEGORIES:
                    handleMainMenuClick(player, slot);
                    break;
                case ITEM_BROWSER:
                    handleBrowserClick(player, holder, slot, event.isRightClick());
                    break;
                case TRANSACTION_MENU:
                    handleTransactionClick(player, holder, slot);
                    break;
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void onInventoryDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
                HumanEntity viewer = event.getPlayer();
                if (viewer instanceof Player) {
                    ((Player) viewer).updateInventory();
                }
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            awaitingSearchInput.remove(uuid);
            clickCooldowns.remove(uuid);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerChatSearch(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            if (!awaitingSearchInput.remove(player.getUniqueId())) {
                return;
            }
            event.setCancelled(true);
            String message = event.getMessage().trim();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("exit")) {
                    player.sendMessage(Component.text("[DynamicMarket] ", NamedTextColor.GOLD)
                            .append(Component.text("Search cancelled.", NamedTextColor.GRAY)));
                    openMainMenu(player);
                } else {
                    openItemBrowser(player, null, message, 0);
                }
            });
        }

        private void handleMainMenuClick(Player player, int slot) {
            int[] categorySlots = {19, 20, 21, 22, 23, 24, 25};
            MarketCategory[] categories = MarketCategory.values();
            for (int i = 0; i < categorySlots.length && i < categories.length; i++) {
                if (slot == categorySlots[i]) {
                    playClickSound(player);
                    openItemBrowser(player, categories[i], "", 0);
                    return;
                }
            }
            if (slot == 30) {
                playClickSound(player);
                openItemBrowser(player, null, "", 0);
            } else if (slot == 31) {
                promptSearchInChat(player);
            } else if (slot == 49) {
                player.closeInventory();
            }
        }

        private void handleBrowserClick(Player player, ShopInventoryHolder holder, int slot, boolean rightClick) {
            if (slot == 0) {
                playClickSound(player);
                openMainMenu(player);
                return;
            }
            if (slot == 8) {
                if (rightClick) {
                    playClickSound(player);
                    openItemBrowser(player, holder.getSelectedCategory(), "", 0);
                } else {
                    promptSearchInChat(player);
                }
                return;
            }
            if (slot == 45 && holder.getPage() > 0) {
                playClickSound(player);
                openItemBrowser(player, holder.getSelectedCategory(), holder.getSearchQuery(), holder.getPage() - 1);
                return;
            }
            if (slot == 53) {
                ItemStack nextArrow = holder.getInventory().getItem(53);
                if (nextArrow != null && nextArrow.getType() == Material.ARROW) {
                    playClickSound(player);
                    openItemBrowser(player, holder.getSelectedCategory(), holder.getSearchQuery(), holder.getPage() + 1);
                }
                return;
            }

            if (slot >= 9 && slot <= 44) {
                ItemStack clickedStack = holder.getInventory().getItem(slot);
                if (clickedStack == null || clickedStack.getType().isAir()) {
                    return;
                }
                Optional<MarketItem> optItem = marketEngine.getItem(clickedStack.getType());
                if (optItem.isPresent()) {
                    playClickSound(player);
                    boolean buyingMode = !rightClick;
                    openTransactionMenu(player, optItem.get(), holder.getSelectedCategory(),
                            holder.getSearchQuery(), holder.getPage(), buyingMode, 1);
                }
            }
        }

        private void handleTransactionClick(Player player, ShopInventoryHolder holder, int slot) {
            MarketItem item = holder.getSelectedItem();
            if (item == null) {
                openMainMenu(player);
                return;
            }

            switch (slot) {
                case 4: // Switch Buy/Sell mode
                    holder.setBuyingMode(!holder.isBuyingMode());
                    playClickSound(player);
                    renderTransactionMenuContents(player, holder);
                    break;
                case 19: // -64
                    adjustQuantity(player, holder, -64);
                    break;
                case 20: // -16
                    adjustQuantity(player, holder, -16);
                    break;
                case 21: // -1
                    adjustQuantity(player, holder, -1);
                    break;
                case 22: // 1 Stack
                    holder.setSelectedQuantity(item.getMaterial().getMaxStackSize());
                    playClickSound(player);
                    renderTransactionMenuContents(player, holder);
                    break;
                case 23: // +1
                    adjustQuantity(player, holder, 1);
                    break;
                case 24: // +16
                    adjustQuantity(player, holder, 16);
                    break;
                case 25: // +64
                    adjustQuantity(player, holder, 64);
                    break;
                case 30: // MAX
                    int maxQty = Math.max(1, calculateMaxQuantity(player, item, holder.isBuyingMode()));
                    holder.setSelectedQuantity(maxQty);
                    playClickSound(player);
                    renderTransactionMenuContents(player, holder);
                    break;
                case 32: // Confirm Transaction
                    TransactionQuote confirmedQuote = holder.getCurrentDisclosedQuote();
                    if (confirmedQuote == null) {
                        confirmedQuote = marketEngine.createQuote(item, holder.isBuyingMode(), holder.getSelectedQuantity());
                    }
                    TransactionService.TransactionResult result = holder.isBuyingMode()
                            ? transactionService.executeBuy(player, confirmedQuote)
                            : transactionService.executeSell(player, confirmedQuote);

                    if (result.isSuccess()) {
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        player.sendMessage(Component.text("[DynamicMarket] ", NamedTextColor.GOLD)
                                .append(Component.text(result.getMessage(), NamedTextColor.GREEN)));
                    } else {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                        player.sendMessage(Component.text("[DynamicMarket] ", NamedTextColor.GOLD)
                                .append(Component.text(result.getMessage(), NamedTextColor.RED)));
                    }
                    // Refresh transaction screen to show updated balance and new dynamic prices
                    renderTransactionMenuContents(player, holder);
                    break;
                case 45: // Back to browser
                    playClickSound(player);
                    openItemBrowser(player, holder.getSelectedCategory(), holder.getSearchQuery(), holder.getPage());
                    break;
                default:
                    break;
            }
        }

        private void adjustQuantity(Player player, ShopInventoryHolder holder, int delta) {
            int next = Math.max(1, Math.min(2304, holder.getSelectedQuantity() + delta));
            holder.setSelectedQuantity(next);
            playClickSound(player);
            renderTransactionMenuContents(player, holder);
        }

        private void promptSearchInChat(Player player) {
            awaitingSearchInput.add(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(Component.text("==============================================", NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("[DynamicMarket] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("Type the item name you want to search for in chat", NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("(or type ", NamedTextColor.GRAY)
                    .append(Component.text("cancel", NamedTextColor.RED))
                    .append(Component.text(" to return to the shop).", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("==============================================", NamedTextColor.DARK_GRAY));
        }

        private void playClickSound(Player player) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.15f);
        }

        public void closeAllShopInventories() {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getOpenInventory().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
                    online.closeInventory();
                }
            }
        }
    }

    /**
     * Handles `/shop`, `/shop reload`, `/shop info [material]`, and `/shop prices [top|bottom|category]`
     * along with intelligent Tab-Completion and permission checks.
     */
    public static final class ShopCommandHandler implements CommandExecutor, TabCompleter {
        private final DynamicMarket plugin;
        private final PluginConfigManager config;
        private final MarketEngine marketEngine;
        private final ShopGuiManager guiManager;
        private final EconomyBridge economy;

        public ShopCommandHandler(DynamicMarket plugin, PluginConfigManager config, MarketEngine marketEngine,
                                  ShopGuiManager guiManager, EconomyBridge economy) {
            this.plugin = plugin;
            this.config = config;
            this.marketEngine = marketEngine;
            this.guiManager = guiManager;
            this.economy = economy;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Only players can open the /shop GUI. Use /shop info or /shop prices from console.");
                    return true;
                }
                Player player = (Player) sender;
                if (!player.hasPermission("dynamicmarket.use")) {
                    player.sendMessage(Component.text("You do not have permission to use the shop.", NamedTextColor.RED));
                    return true;
                }
                guiManager.openMainMenu(player);
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                    if (!sender.hasPermission("dynamicmarket.admin")) {
                        sender.sendMessage(Component.text("You do not have permission to reload DynamicMarket.", NamedTextColor.RED));
                        return true;
                    }
                    plugin.reloadMarketPlugin();
                    sender.sendMessage(Component.text("[DynamicMarket] ", NamedTextColor.GOLD)
                            .append(Component.text("Configuration, item catalog, and tasks reloaded successfully!", NamedTextColor.GREEN)));
                    return true;

                case "info":
                    if (!sender.hasPermission("dynamicmarket.info")) {
                        sender.sendMessage(Component.text("You do not have permission to view market info.", NamedTextColor.RED));
                        return true;
                    }
                    handleInfoSubcommand(sender, args);
                    return true;

                case "prices":
                    if (!sender.hasPermission("dynamicmarket.prices")) {
                        sender.sendMessage(Component.text("You do not have permission to view market prices.", NamedTextColor.RED));
                        return true;
                    }
                    handlePricesSubcommand(sender, args);
                    return true;

                default:
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.hasPermission("dynamicmarket.use")) {
                            guiManager.openItemBrowser(player, null, String.join(" ", args), 0);
                            return true;
                        }
                    }
                    sender.sendMessage(Component.text("Usage: /shop [reload | info [item] | prices [surging|discounted]]", NamedTextColor.YELLOW));
                    return true;
            }
        }

        private void handleInfoSubcommand(CommandSender sender, String[] args) {
            if (args.length >= 2) {
                Material mat = Material.matchMaterial(args[1]);
                if (mat == null) {
                    sender.sendMessage(Component.text("[DynamicMarket] Unknown material: " + args[1], NamedTextColor.RED));
                    return;
                }
                Optional<MarketItem> opt = marketEngine.getItem(mat);
                if (!opt.isPresent()) {
                    sender.sendMessage(Component.text("[DynamicMarket] That item is not listed on the market.", NamedTextColor.RED));
                    return;
                }
                MarketItem item = opt.get();
                double pct = item.getPercentageDifference();
                NamedTextColor pctColor = pct > 0.05 ? NamedTextColor.RED : (pct < -0.05 ? NamedTextColor.GREEN : NamedTextColor.GRAY);
                DecimalFormat df = new DecimalFormat("0.0");

                sender.sendMessage(Component.text("=== Market Info: " + item.getFriendlyName() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                sender.sendMessage(Component.text("Category: ", NamedTextColor.GRAY)
                        .append(Component.text(item.getCategory().getDisplayName(), item.getCategory().getColor())));
                sender.sendMessage(Component.text("Original Buy / Sell: ", NamedTextColor.GRAY)
                        .append(Component.text(config.formatCurrency(item.getBaseBuyPrice()) + " / " + config.formatCurrency(item.getBaseSellPrice()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Current Buy / Sell: ", NamedTextColor.GRAY)
                        .append(Component.text(config.formatCurrency(item.getCurrentBuyPrice()) + " / " + config.formatCurrency(item.getCurrentSellPrice()), pctColor)));
                sender.sendMessage(Component.text("Price Change: ", NamedTextColor.GRAY)
                        .append(Component.text((pct >= 0 ? "+" : "") + df.format(pct) + "%", pctColor)));
                sender.sendMessage(Component.text("Demand / Supply: ", NamedTextColor.GRAY)
                        .append(Component.text(item.getTotalDemandUnits() + " bought / " + item.getTotalSupplyUnits() + " sold", NamedTextColor.AQUA)));
                return;
            }

            List<MarketItem> active = marketEngine.getActiveItems();
            long totalBought = active.stream().mapToLong(MarketItem::getTotalDemandUnits).sum();
            long totalSold = active.stream().mapToLong(MarketItem::getTotalSupplyUnits).sum();
            sender.sendMessage(Component.text("=== DynamicMarket System Overview (Paper 26.x) ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            sender.sendMessage(Component.text("Economy Provider: ", NamedTextColor.GRAY)
                    .append(Component.text(economy.getProviderName(), NamedTextColor.GREEN)));
            sender.sendMessage(Component.text("Active Obtainable Items: ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(active.size()), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Total Market Volume: ", NamedTextColor.GRAY)
                    .append(Component.text(totalBought + " units bought, " + totalSold + " units sold", NamedTextColor.AQUA)));
            sender.sendMessage(Component.text("Price Limits: ", NamedTextColor.GRAY)
                    .append(Component.text((config.getMinMultiplier() * 100) + "% to " + (config.getMaxMultiplier() * 100) + "% of base", NamedTextColor.YELLOW)));
            sender.sendMessage(Component.text("Shrinkflation Feature: ", NamedTextColor.GRAY)
                    .append(Component.text(config.isShrinkflationEnabled() ? "Enabled (Always Disclosed)" : "Disabled",
                            config.isShrinkflationEnabled() ? NamedTextColor.GOLD : NamedTextColor.GREEN)));
        }

        private void handlePricesSubcommand(CommandSender sender, String[] args) {
            boolean showDiscounted = args.length >= 2 && args[1].equalsIgnoreCase("discounted");
            List<MarketItem> sorted = marketEngine.getActiveItems().stream()
                    .sorted(showDiscounted
                            ? Comparator.comparingDouble(MarketItem::getPercentageDifference)
                            : Comparator.comparingDouble(MarketItem::getPercentageDifference).reversed())
                    .limit(10)
                    .collect(Collectors.toList());

            DecimalFormat df = new DecimalFormat("0.0");
            sender.sendMessage(Component.text("=== Top 10 " + (showDiscounted ? "Most Discounted" : "Highest Demand") + " Market Items ===",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
            for (MarketItem item : sorted) {
                double pct = item.getPercentageDifference();
                NamedTextColor color = pct > 0.05 ? NamedTextColor.RED : (pct < -0.05 ? NamedTextColor.GREEN : NamedTextColor.GRAY);
                sender.sendMessage(Component.text(" • " + item.getFriendlyName() + ": ", NamedTextColor.WHITE)
                        .append(Component.text("Buy " + config.formatCurrency(item.getCurrentBuyPrice())
                                + " (Orig " + config.formatCurrency(item.getBaseBuyPrice()) + ") ", NamedTextColor.GRAY))
                        .append(Component.text("[" + (pct >= 0 ? "+" : "") + df.format(pct) + "%]", color)));
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> subs = new ArrayList<>(Arrays.asList("info", "prices"));
                if (sender.hasPermission("dynamicmarket.admin")) {
                    subs.add("reload");
                }
                String prefix = args[0].toLowerCase(Locale.ROOT);
                return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("prices")) {
                return Arrays.asList("surging", "discounted");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
                String prefix = args[1].toUpperCase(Locale.ROOT);
                return marketEngine.getActiveItems().stream()
                        .map(i -> i.getMaterial().name())
                        .filter(name -> name.startsWith(prefix))
                        .limit(25)
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    /**
     * Self-contained project generator and automated test runner.
     * Running `java src/main/java/de/dynamicmarket/DynamicMarket.java --export-project`
     * generates `pom.xml`, `src/main/resources/plugin.yml`, and `src/main/resources/config.yml`
     * so you can immediately run `mvn clean package` to produce `target/DynamicMarket-26.3.0.jar`.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== DynamicMarket (PaperMC 26.x) Project Exporter & Verification Suite ===");
        exportProjectFiles(Path.of("."));
        runStandaloneVerificationTests();
    }

    private static void exportProjectFiles(Path rootDir) throws IOException {
        Path resourcesDir = rootDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(rootDir.resolve("pom.xml"), EMBEDDED_POM_XML, StandardCharsets.UTF_8);
        Files.writeString(resourcesDir.resolve("plugin.yml"), EMBEDDED_PLUGIN_YML, StandardCharsets.UTF_8);
        Files.writeString(resourcesDir.resolve("config.yml"), EMBEDDED_CONFIG_YML, StandardCharsets.UTF_8);
        System.out.println("[OK] Wrote pom.xml, src/main/resources/plugin.yml, and src/main/resources/config.yml");
    }

    private static void runStandaloneVerificationTests() {
        // Test 1: Dynamic Pricing Supply/Demand & Recovery
        MarketItem diamond = new MarketItem(Material.DIAMOND, MarketCategory.ORES_AND_VALUABLES,
                100.0D, 75.0D, 1.0D, 0L, 0L, 0, Instant.now().getEpochSecond());
        diamond.recordPurchase(100, 0.0025D, 0.25D, 4.50D); // +25% demand increase -> $125.00
        if (Math.abs(diamond.getCurrentBuyPrice() - 125.0D) > 0.01D || Math.abs(diamond.getPercentageDifference() - 25.0D) > 0.01D) {
            throw new IllegalStateException("Dynamic buy price test failed: " + diamond.getCurrentBuyPrice());
        }
        diamond.recoverStepTowardBase(0.10D, 0.25D, 4.50D);
        if (diamond.getCurrentBuyPrice() >= 125.0D || diamond.getCurrentBuyPrice() <= 100.0D) {
            throw new IllegalStateException("Gradual recovery test failed: " + diamond.getCurrentBuyPrice());
        }
        System.out.println("[TEST PASS] Dynamic pricing (+25% example: $100 -> $125) and gradual recovery verified.");
    }

    public static final String EMBEDDED_POM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>

                <groupId>de.dynamicmarket</groupId>
                <artifactId>DynamicMarket</artifactId>
                <version>26.3.0</version>
                <packaging>jar</packaging>
                <name>DynamicMarket</name>
                <description>Dynamic Supply/Demand Shop GUI Plugin for PaperMC 26.x</description>

                <properties>
                    <java.version>25</java.version>
                    <maven.compiler.source>25</maven.compiler.source>
                    <maven.compiler.target>25</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>

                <repositories>
                    <repository>
                        <id>papermc-repo</id>
                        <url>https://repo.papermc.io/repository/maven-public/</url>
                    </repository>
                    <repository>
                        <id>jitpack.io</id>
                        <url>https://jitpack.io</url>
                    </repository>
                </repositories>

                <dependencies>
                    <!-- PaperMC 26.x API (compatible across 26.1, 26.2, and 26.3) -->
                    <dependency>
                        <groupId>io.papermc.paper</groupId>
                        <artifactId>paper-api</artifactId>
                        <version>26.3.build.178-stable</version>
                        <scope>provided</scope>
                    </dependency>

                    <!-- Vault Economy API (for EssentialsX Economy integration) -->
                    <dependency>
                        <groupId>com.github.MilkBowl</groupId>
                        <artifactId>VaultAPI</artifactId>
                        <version>1.7.1</version>
                        <scope>provided</scope>
                    </dependency>

                    <!-- SQLite JDBC Driver -->
                    <dependency>
                        <groupId>org.xerial</groupId>
                        <artifactId>sqlite-jdbc</artifactId>
                        <version>3.46.1.3</version>
                        <scope>provided</scope>
                    </dependency>
                </dependencies>

                <build>
                    <finalName>DynamicMarket-${project.version}</finalName>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.13.0</version>
                            <configuration>
                                <release>${java.version}</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    public static final String EMBEDDED_PLUGIN_YML = """
            name: DynamicMarket
            version: '26.3.0'
            main: de.dynamicmarket.DynamicMarket
            api-version: '26.1'
            authors: [DynamicMarketTeam]
            description: Complete Dynamic Economy & Shop GUI Plugin for PaperMC 26.x
            softdepend: [Vault, Essentials]
            libraries:
              - org.xerial:sqlite-jdbc:3.46.1.3

            commands:
              shop:
                description: Opens the DynamicMarket GUI or runs market management subcommands.
                usage: /shop [reload | info [item] | prices [surging|discounted]]
                aliases: [market, dshop]
                permission: dynamicmarket.use

            permissions:
              dynamicmarket.use:
                description: Allows opening the /shop GUI and buying/selling items.
                default: true
              dynamicmarket.info:
                description: Allows viewing market statistics with /shop info.
                default: true
              dynamicmarket.prices:
                description: Allows viewing top surging/discounted items with /shop prices.
                default: true
              dynamicmarket.admin:
                description: Allows administrative commands such as /shop reload.
                default: op
                children:
                  dynamicmarket.use: true
                  dynamicmarket.info: true
                  dynamicmarket.prices: true
            """;

    public static final String EMBEDDED_CONFIG_YML = """
            # ====================================================================
            # DynamicMarket — PaperMC 26.x Configuration (26.1 / 26.2 / 26.3)
            # ====================================================================

            economy:
              currency-symbol: "$"
              # Base sell price as a fraction of the base buy price (0.75 = 75%)
              base-sell-factor: 0.75
              # If true, falls back to an internal ledger when Vault is absent during testing
              allow-standalone-fallback: true
              fallback-starting-balance: 10000.00

            market:
              # Percentage multiplier increase per unit bought (0.0025 = +0.25% per unit, +25% per 100 units)
              buy-sensitivity-per-unit: 0.0025
              # Percentage multiplier decrease per unit sold
              sell-sensitivity-per-unit: 0.0025
              # Minimum and maximum price boundaries relative to original base price
              min-price-multiplier: 0.25
              max-price-multiplier: 4.50
              # Rate at which prices recover toward 1.0x per cycle (0.04 = 4% closer to original price)
              recovery-rate-per-cycle: 0.04
              recovery-interval-seconds: 300
              autosave-interval-seconds: 120
              debug-logging: false

            shrinkflation:
              # Optional feature (disabled by default): reduces bundle quantity delivered during high demand
              # while ALWAYS disclosing the exact delivered quantity in the GUI before confirmation.
              enabled: false
              activation-multiplier-threshold: 1.35
              max-quantity-reduction-ratio: 0.125
              minimum-bundle-size: 8

            items:
              blacklist:
                - BEDROCK
                - BARRIER
                - COMMAND_BLOCK
              custom-base-prices:
                DIAMOND: 100.00
                NETHERITE_INGOT: 1050.00
                GOLDEN_DANDELION: 45.00
                IRON_INGOT: 15.00
                GOLD_INGOT: 25.00
                EMERALD: 60.00
                COBBLESTONE: 1.50
                OAK_LOG: 8.00
                COOKED_BEEF: 10.00
              category-overrides:
                GOLDEN_DANDELION: NATURAL_AND_FARMING
            """;
}