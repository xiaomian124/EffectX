package com.xiaomian124.effectplugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.TabCompleter;
import org.bukkit.block.Container;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.entity.Firework;
import org.bukkit.util.Vector;

import java.util.*;

public class EffectPlugin extends JavaPlugin implements Listener, TabCompleter {

    // 存储冰封特效中需要恢复的方块原始状态
    private final Map<Location, BlockState> frozenBlocks = new HashMap<>();
    // 存储特效产生的猪实体，用于最后清理
    private final List<Entity> spawnedPigs = new ArrayList<>();
    // 存储为铁砧雨产生的铁砧实体
    private final List<Entity> spawnedAnvils = new ArrayList<>();
    // 当前正在运行的周期性任务
    private BukkitTask dayNightTask = null;
    private BukkitTask lightningTask = null;
    private BukkitTask pigTask = null;
    private BukkitTask freezeSpreadTask = null;
    private long originalWorldTime;           // 保存世界原始时间
    private boolean originalDaylightCycle;    // 保存世界是否开启昼夜循环

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("effectx");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);   // 注册 Tab 补全
        } else {
            getLogger().warning("命令 /effectx 注册失败，请在Github提供Issue");
        }
        getLogger().info("EffectX插件已加载，使用 /effectx 命令");
    }

    @Override
    public void onDisable() {
        // 插件卸载时清理所有残留特效实体和恢复方块
        cleanupAllEffects();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("anvil", "freeze", "daynight", "lightning", "pig");
            String partial = args[0].toLowerCase();
            return subCommands.stream()
                    .filter(cmd -> cmd.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onAnvilLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fallingBlock && fallingBlock.hasMetadata("effect_anvil")) {
            event.setCancelled(true);
            Location loc = fallingBlock.getLocation();
            Objects.requireNonNull(loc.getWorld()).playSound(loc, Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
            event.getEntity().remove();
        }
    }

    @EventHandler
    public void onLightningDamage(EntityDamageEvent event) {
        // 防止闪电伤害生物/玩家
        if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING) {
            event.setCancelled(true);
        }
    }

    // 命令处理
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家执行！");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("用法: /effectx <anvil|freeze|daynight|lightning|pig>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "anvil" -> startAnvilRain(player);
            case "freeze" -> startFreeze(player);
            case "daynight" -> startDayNightShift(player);
            case "lightning" -> startLightningStorm(player);
            case "pig" -> startPigAscension(player);
            default -> player.sendMessage("未知特效类型！可选: anvil, freeze, daynight, lightning, pig");
        }
        return true;
    }

    // ---------- 铁砧雨 ----------
    private void startAnvilRain(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int anvilCount = 80;
        int duration = 10 * 20;
        for (int i = 0; i < anvilCount; i++) {
            double xOffset = (Math.random() - 0.5) * 12;
            double zOffset = (Math.random() - 0.5) * 12;
            double y = center.getY() + 15 + Math.random() * 10;
            Location spawnLoc = new Location(world, center.getX() + xOffset, y, center.getZ() + zOffset);
            FallingBlock anvil = world.spawnFallingBlock(spawnLoc, Material.ANVIL.createBlockData());
            anvil.setHurtEntities(false);
            anvil.setDropItem(false);
            anvil.setMetadata("effect_anvil", new FixedMetadataValue(this, true));
            spawnedAnvils.add(anvil);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Entity anvil : spawnedAnvils) if (anvil.isValid()) anvil.remove();
                spawnedAnvils.clear();
                player.sendMessage("§e铁砧雨特效结束！");
            }
        }.runTaskLater(this, duration);
        player.sendMessage("§a铁砧雨特效已启动，持续10秒！");
    }

    // ---------- 全场冻结----------
    private void startFreeze(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int radius = 15;          // 冰封半径
        int spreadDuration = 100; // 扩散总时长
        int totalKeep = 10 * 20;  // 整体特效

        // 清除之前的扩散任务和已冰封方块
        if (freezeSpreadTask != null) freezeSpreadTask.cancel();
        if (!frozenBlocks.isEmpty()) restoreFrozenBlocks();

        // 收集需要替换的方块位置
        List<Location> toFreeze = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // 球形范围优化：距离平方 <= radius^2
                    if (x*x + y*y + z*z > radius*radius) continue;
                    Location loc = center.clone().add(x, y, z);
                    if (loc.getY() < world.getMinHeight() || loc.getY() > world.getMaxHeight()) continue;
                    Block block = world.getBlockAt(loc);
                    Material mat = block.getType();
                    // 跳过空气、液体、冰块类、基岩、容器方块
                    if (mat.isAir() || mat == Material.BLUE_ICE || mat == Material.PACKED_ICE || mat == Material.ICE
                            || mat == Material.BEDROCK || mat == Material.WATER || mat == Material.LAVA
                            || block.getState() instanceof Container) {
                        continue;
                    }
                    toFreeze.add(loc);
                }
            }
        }

        // 扩散
        toFreeze.sort(Comparator.comparingDouble(loc -> loc.distanceSquared(center)));

        // 分批替换
        int total = toFreeze.size();
        if (total == 0) {
            player.sendMessage("§c你不能在这尝试全场冻结特效！");
            return;
        }
        int batchSize = Math.max(1, total / spreadDuration);
        Iterator<Location> iterator = toFreeze.iterator();

        freezeSpreadTask = new BukkitRunnable() {
            int processed = 0;
            @Override
            public void run() {
                int count = 0;
                while (iterator.hasNext() && count < batchSize) {
                    Location loc = iterator.next();
                    Block block = loc.getBlock();
                    // 二次检查
                    Material mat = block.getType();
                    if (!(mat.isAir() || mat == Material.BLUE_ICE || mat == Material.PACKED_ICE || mat == Material.ICE
                            || mat == Material.BEDROCK || mat == Material.WATER || mat == Material.LAVA
                            || block.getState() instanceof Container)) {
                        // 保存原始状态
                        frozenBlocks.put(loc, block.getState());
                        block.setType(Material.BLUE_ICE);
                        world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.6f, 1.2f);
                    }
                    count++;
                }
                processed += count;
                if (!iterator.hasNext()) {
                    this.cancel();
                    freezeSpreadTask = null;
                }
            }
        }.runTaskTimer(this, 0L, 1L); // 每 1 tick 执行一批

        // 恢复所有方块
        new BukkitRunnable() {
            @Override
            public void run() {
                if (freezeSpreadTask != null) {
                    freezeSpreadTask.cancel();
                    freezeSpreadTask = null;
                }
                restoreFrozenBlocks();
                player.sendMessage("§e全场冻结特效结束，建筑已恢复！");
            }
        }.runTaskLater(this, totalKeep);

        player.sendMessage("§b全场冻结特效已启动！持续10秒。");
    }

    private void restoreFrozenBlocks() {
        for (Map.Entry<Location, BlockState> entry : frozenBlocks.entrySet()) {
            Location loc = entry.getKey();
            Block current = loc.getBlock();
            if (current.getType() == Material.BLUE_ICE) {
                entry.getValue().update(true, false);
            }
        }
        frozenBlocks.clear();
    }

    // ---------- 昼夜变更 ----------
    private void startDayNightShift(Player player) {
        World world = player.getWorld();
        originalWorldTime = world.getTime();
        originalDaylightCycle = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); // 暂停系统时间流动

        int duration = 10 * 20;     // 特效持续200tick
        long timeIncrement = 300;    // 时间流逝

        dayNightTask = new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (tick >= duration) {
                    // 恢复原始时间和游戏规则
                    world.setTime(originalWorldTime);
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, originalDaylightCycle);
                    player.sendMessage("§e昼夜变更特效结束！");
                    this.cancel();
                    dayNightTask = null;
                    return;
                }
                long currentTime = world.getTime();
                long newTime = (currentTime + timeIncrement) % 24000L;
                world.setTime(newTime);
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L); // 每1tick执行一次，持续加速200tick
        player.sendMessage("§6昼夜变更特效已启动，持续10秒！");
    }

    // ---------- 雷击 ----------
    private void startLightningStorm(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int duration = 10 * 20;
        lightningTask = new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (tick >= duration) {
                    this.cancel();
                    lightningTask = null;
                    player.sendMessage("§e打雷特效结束！");
                    return;
                }
                // 每tick产生2~5的闪电，围绕玩家半径15格内
                int strikes = 2 + (int)(Math.random() * 4);
                for (int i = 0; i < strikes; i++) {
                    double angle = Math.random() * 2 * Math.PI;
                    double radius = 5 + Math.random() * 12;
                    double xOffset = Math.cos(angle) * radius;
                    double zOffset = Math.sin(angle) * radius;
                    double yOffset = 3 + Math.random() * 8;
                    Location strikeLoc = center.clone().add(xOffset, yOffset, zOffset);
                    // 使用strikeLightningEffect只产生视觉效果，不造成伤害
                    world.strikeLightningEffect(strikeLoc);
                }
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L);
        player.sendMessage("§e打雷特效已启动，持续10秒！");
    }

    // ---------- 飞天猪 ----------
    private void startPigAscension(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int pigCount = 25;
        int duration = 10 * 20;
        double targetY = center.getY() + 25;

        for (Entity pig : spawnedPigs) if (pig.isValid()) pig.remove();
        spawnedPigs.clear();

        // 生成位置：玩家脚下
        for (int i = 0; i < pigCount; i++) {
            double angle = Math.random() * 2 * Math.PI;
            double radius = 3 + Math.random() * 5;
            double xOffset = Math.cos(angle) * radius;
            double zOffset = Math.sin(angle) * radius;
            double yPos = center.getY();
            Location spawnLoc = new Location(world, center.getX() + xOffset, yPos, center.getZ() + zOffset);
            double groundY = world.getHighestBlockYAt(spawnLoc);
            if (spawnLoc.getY() < groundY) spawnLoc.setY(groundY + 0.1);

            Pig pig = world.spawn(spawnLoc, Pig.class);
            pig.setAI(false);
            pig.setGravity(false);
            pig.setInvulnerable(true);
            pig.setCustomName("§d飞天猪");
            pig.setCustomNameVisible(true);
            pig.setMetadata("effect_pig", new FixedMetadataValue(this, true));
            spawnedPigs.add(pig);
        }

        pigTask = new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (tick >= duration) {
                    for (Entity pig : spawnedPigs) {
                        if (pig.isValid()) {
                            spawnColorfulFirework(pig.getLocation(), pig.getWorld());
                            pig.remove();
                        }
                    }
                    spawnedPigs.clear();
                    this.cancel();
                    pigTask = null;
                    player.sendMessage("§e飞天猪特效结束！");
                    return;
                }
                Iterator<Entity> iterator = spawnedPigs.iterator();
                while (iterator.hasNext()) {
                    Entity pig = iterator.next();
                    if (!pig.isValid()) {
                        iterator.remove();
                        continue;
                    }
                    Location loc = pig.getLocation();
                    if (loc.getY() >= targetY || loc.getY() > world.getMaxHeight()) {
                        spawnColorfulFirework(loc, world);
                        pig.remove();
                        iterator.remove();
                        continue;
                    }
                    Location newLoc = loc.clone().add(0, 0.35, 0);
                    pig.teleport(newLoc);
                    // 飞行粒子
                    world.spawnParticle(Particle.WITCH, newLoc, 5, 0.3, 0.3, 0.3, 0);
                    world.spawnParticle(Particle.FLAME, newLoc, 3, 0.2, 0.2, 0.2, 0.02);
                    world.spawnParticle(Particle.END_ROD, newLoc, 2, 0.2, 0.1, 0.2, 0.01);
                }
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L);
        player.sendMessage("§d飞天猪特效已启动，持续10秒！");
    }

    // 生成烟花
    private void spawnColorfulFirework(Location location, World world) {
        Firework firework = world.spawn(location, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        // 随机烟花颜色
        List<Color> colors = Arrays.asList(
                Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN,
                Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.AQUA, Color.LIME
        );
        // 随机取2~4种颜色作为主色
        int colorCount = 2 + (int)(Math.random() * 3);
        List<Color> chosenColors = new ArrayList<>();
        for (int i = 0; i < colorCount; i++) {
            chosenColors.add(colors.get((int)(Math.random() * colors.size())));
        }
        List<Color> fadeColors = Arrays.asList(Color.WHITE, Color.SILVER, Color.GRAY);
        Color fade = fadeColors.get((int)(Math.random() * fadeColors.size()));

        meta.setPower(1);
        meta.addEffects(FireworkEffect.builder()
                .withColor(chosenColors)
                .withFade(fade)
                .with(FireworkEffect.Type.BURST)
                .trail(true)
                .flicker(true)
                .build());
        firework.setFireworkMeta(meta);
        world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 1.2f);
        world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 1.0f);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!firework.isDead()) {
                    world.spawnParticle(Particle.FIREWORK, firework.getLocation(), 100, 0.5, 0.5, 0.5, 0.1);
                    world.playSound(firework.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 1.0f);
                    firework.remove();
                }
            }
        }.runTaskLater(this, 2L);
    }

    private void cleanupAllEffects() {
        if (dayNightTask != null) dayNightTask.cancel();
        if (lightningTask != null) lightningTask.cancel();
        if (pigTask != null) pigTask.cancel();
        if (freezeSpreadTask != null) freezeSpreadTask.cancel();
        for (Entity anvil : spawnedAnvils) if (anvil.isValid()) anvil.remove();
        for (Entity pig : spawnedPigs) {
            if (pig.isValid()) {
                spawnColorfulFirework(pig.getLocation(), pig.getWorld());
                pig.remove();
            }
        }
        spawnedAnvils.clear();
        spawnedPigs.clear();
        restoreFrozenBlocks();
    }
}
