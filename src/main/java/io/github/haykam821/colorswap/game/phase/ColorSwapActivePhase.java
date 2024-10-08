package io.github.haykam821.colorswap.game.phase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import io.github.haykam821.colorswap.Main;
import io.github.haykam821.colorswap.game.ColorSwapConfig;
import io.github.haykam821.colorswap.game.ColorSwapTimerBar;
import io.github.haykam821.colorswap.game.item.PrismItem;
import io.github.haykam821.colorswap.game.map.ColorSwapMap;
import io.github.haykam821.colorswap.game.map.ColorSwapMapConfig;
import io.github.haykam821.colorswap.game.prism.Prism;
import io.github.haykam821.colorswap.game.prism.spawner.PrismSpawner;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

public class ColorSwapActivePhase {
	private final ServerWorld world;
	private final GameSpace gameSpace;
	private final ColorSwapMap map;
	private final ColorSwapConfig config;
	private final List<PlayerRef> players;
	private HolderAttachment guideText;
	private int maxTicksUntilSwap;
	private int ticksUntilSwap = 0;
	private List<Block> lastSwapBlocks = new ArrayList<>();
	private Block swapBlock;
	private boolean lastErased = true;
	private boolean singleplayer;
	private final ColorSwapTimerBar timerBar;
	private final PrismSpawner prismSpawner;
	private int rounds = 0;
	private int ticksElapsed = 0;
	private int ticksUntilClose = -1;

	public ColorSwapActivePhase(ServerWorld world, GameSpace gameSpace, ColorSwapMap map, ColorSwapConfig config, List<PlayerRef> players, HolderAttachment guideText, GlobalWidgets widgets) {
		this.world = world;
		this.gameSpace = gameSpace;
		this.map = map;
		this.config = config;
		this.players = players;

		this.guideText = guideText;

		this.timerBar = new ColorSwapTimerBar(widgets);
		this.maxTicksUntilSwap = this.getSwapTime();

		this.prismSpawner = this.config.getPrismConfig().map(prismConfig -> {
			return new PrismSpawner(this, prismConfig, this.world.getRandom());
		}).orElse(null);
	}

	public static void setRules(GameActivity activity) {
		activity.deny(GameRuleType.CRAFTING);
		activity.deny(GameRuleType.FALL_DAMAGE);
		activity.deny(GameRuleType.HUNGER);
		activity.deny(GameRuleType.PORTALS);
		activity.allow(GameRuleType.PVP);
		activity.deny(GameRuleType.THROW_ITEMS);
		activity.deny(GameRuleType.MODIFY_ARMOR);
		activity.deny(GameRuleType.MODIFY_INVENTORY);
	}

	public static void open(GameSpace gameSpace, ServerWorld world, ColorSwapMap map, ColorSwapConfig config, HolderAttachment guideText) {
		gameSpace.setActivity(activity -> {
			GlobalWidgets widgets = GlobalWidgets.addTo(activity);

			List<PlayerRef> players = gameSpace.getPlayers().stream().map(PlayerRef::of).collect(Collectors.toList());
			Collections.shuffle(players);

			ColorSwapActivePhase active = new ColorSwapActivePhase(world, gameSpace, map, config, players, guideText, widgets);

			ColorSwapActivePhase.setRules(activity);

			// Listeners
			activity.listen(GameActivityEvents.DISABLE, active::close);
			activity.listen(GameActivityEvents.ENABLE, active::enable);
			activity.listen(GameActivityEvents.TICK, active::tick);
			activity.listen(GamePlayerEvents.OFFER, active::offerPlayer);
			activity.listen(GamePlayerEvents.REMOVE, active::removePlayer);
			activity.listen(PlayerDamageEvent.EVENT, active::onPlayerDamage);
			activity.listen(PlayerDeathEvent.EVENT, active::onPlayerDeath);
			activity.listen(ItemUseEvent.EVENT, active::onUseItem);
		});
	}

	public void enable() {
		int index = 0;
		this.singleplayer = this.players.size() == 1;

		for (PlayerRef playerRef : this.players) {
			ServerPlayerEntity player = playerRef.getEntity(this.world);

			if (player != null) {
				this.updateRoundsExperienceLevel(player);
				player.changeGameMode(GameMode.ADVENTURE);

				double theta = ((double) index / this.players.size()) * 2 * Math.PI;
				float yaw = (float) theta * MathHelper.DEGREES_PER_RADIAN + 90;

				Vec3d spawnPos = this.map.getSpawnPos(theta);
				ColorSwapActivePhase.spawn(this.world, spawnPos, yaw, player);
			}

			index++;
		}
	}

	public void close() {
		this.timerBar.remove();
	}

	public void updateRoundsExperienceLevel(ServerPlayerEntity player) {
		player.setExperienceLevel(this.rounds);
	}

	private void setRounds(int rounds) {
		this.rounds = rounds;
		for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
			this.updateRoundsExperienceLevel(player);
		}
	}

	public void eraseTile(BlockPos.Mutable origin, int xSize, int zSize, BlockStateProvider erasedStateProvider) {
		boolean keep = this.world.getBlockState(origin).isOf(this.swapBlock);

		BlockPos.Mutable pos = origin.mutableCopy();
		for (int x = origin.getX(); x < origin.getX() + xSize; x++) {
			for (int z = origin.getZ(); z < origin.getZ() + zSize; z++) {
				pos.set(x, origin.getY(), z);

				if (!keep) {
					BlockState oldState = this.world.getBlockState(pos);
					BlockState newState = erasedStateProvider.get(this.world.getRandom(), pos);

					this.world.getWorldChunk(pos).setBlockState(pos, newState, false);
					this.world.updateListeners(pos, oldState, newState, 0);
				}
			}
		}
	}

	public void erase() {
		ColorSwapMapConfig mapConfig = this.config.getMapConfig();

 		for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
			player.playSound(this.config.getSwapSound(), SoundCategory.BLOCKS, 1, 1.5f);
		}

		BlockPos.Mutable pos = new BlockPos.Mutable();

		// Iterate over blocks when necessary to avoid conflicts with the splash prism
		int xScale = this.prismSpawner == null ? mapConfig.xScale : 1;
		int zScale = this.prismSpawner == null ? mapConfig.zScale : 1;

		for (int x = 0; x < mapConfig.x * mapConfig.xScale; x += xScale) {
			for (int z = 0; z < mapConfig.z * mapConfig.zScale; z += zScale) {
				pos.set(x, 64, z);
				this.eraseTile(pos, xScale, zScale, mapConfig.erasedStateProvider);
			}
		}
	}

	private Block getSwapBlock() {
		return this.lastSwapBlocks.get(this.world.getRandom().nextInt(this.lastSwapBlocks.size()));
	}

	public Block getCurrentSwapBlock() {
		return this.swapBlock;
	}

	public boolean hasLastErased() {
		return this.lastErased;
	}

	public void placeTile(BlockPos.Mutable origin, int xSize, int zSize, BlockState state) {
		BlockPos.Mutable pos = origin.mutableCopy();
		for (int x = origin.getX(); x < origin.getX() + xSize; x++) {
			for (int z = origin.getZ(); z < origin.getZ() + zSize; z++) {
				pos.set(x, origin.getY(), z);

				BlockState oldState = this.world.getBlockState(pos);
				this.world.getWorldChunk(pos).setBlockState(pos, state, false);
				this.world.updateListeners(pos, oldState, state, 0);
			}
		}
	}

	public void swap() {
		ColorSwapMapConfig mapConfig = this.config.getMapConfig();
		this.lastSwapBlocks.clear();

		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int x = 0; x < mapConfig.x * mapConfig.xScale; x += mapConfig.xScale) {
			for (int z = 0; z < mapConfig.z * mapConfig.zScale; z += mapConfig.zScale) {
				pos.set(x, 64, z);

				Block block = this.config.getMapConfig().getPlatformBlock(this.world.getRandom());
				if (!this.lastSwapBlocks.contains(block)) {
					this.lastSwapBlocks.add(block);
				}

				this.placeTile(pos, mapConfig.xScale, mapConfig.zScale, block.getDefaultState());
			}
		}
	}

	private void giveSwapBlocks() {
		ItemStack stack = new ItemStack(this.swapBlock);

		for (PlayerRef playerRef : this.players) {
			playerRef.ifOnline(this.world, player -> {
				PlayerInventory inventory = player.getInventory();

				for (int slot = 0; slot < 9; slot++) {
					if (!inventory.getStack(slot).isOf(Main.PRISM)) {
						inventory.setStack(slot, stack.copy());
					}
				}

				// Update inventory
				player.currentScreenHandler.sendContentUpdates();
				player.playerScreenHandler.onContentChanged(inventory);
			});
		}
	}

	private void checkElimination() {
		Iterator<PlayerRef> iterator = this.players.iterator();
		while (iterator.hasNext()) {
			PlayerRef playerRef = iterator.next();
			playerRef.ifOnline(this.world, player -> {
				if (this.map.isBelowPlatform(player) || (this.prismSpawner == null && this.map.isAbovePlatform(player, this.isKnockbackEnabled()))) {
					this.eliminate(player, false);
					iterator.remove();
				}
			});
		}
	}

	public float getTimerBarPercent() {
		return this.ticksUntilSwap / (float) this.maxTicksUntilSwap;
	}

	private int getSwapTime() {
		int swapTime = this.config.getSwapTime();
		if (swapTime >= 0) return swapTime;

		double swapSeconds = Math.pow(5, -0.04 * this.rounds + 1) + 0.5;
		return (int) (swapSeconds * 20);
	}

	private int getEraseTime() {
		int eraseTime = this.config.getEraseTime();
		if (eraseTime >= 0) return eraseTime;

		return this.rounds > 10 ? 20 : 20 * 2;
	}

	private Text getKnockbackEnabledText() {
		return Text.translatable("text.colorswap.knockback_enabled").formatted(Formatting.RED);
	}

	public void tick() {
		this.ticksElapsed += 1;

		if (this.guideText != null) {
			if (this.ticksElapsed == this.config.getGuideTicks()) {
				this.guideText.destroy();
				this.guideText = null;
			}
		}

		// Decrease ticks until game end to zero
		if (this.isGameEnding()) {
			if (this.ticksUntilClose == 0) {
				this.gameSpace.close(GameCloseReason.FINISHED);
			}

			this.ticksUntilClose -= 1;
			return;
		}

		this.ticksUntilSwap -= 1;
		this.timerBar.tick(this);
		if (this.ticksUntilSwap <= 0) {
			if (this.lastErased) {
				this.swap();

				this.swapBlock = this.getSwapBlock();
				this.lastErased = false;
				this.giveSwapBlocks();

				this.setRounds(this.rounds + 1);
				this.maxTicksUntilSwap = this.getSwapTime();
				if (this.rounds - 1 == this.config.getNoKnockbackRounds()) {
					this.sendMessage(this.getKnockbackEnabledText());
				}

				if (this.prismSpawner != null) {
					this.prismSpawner.onRoundEnd();
				}
			} else {
				this.erase();
				this.lastErased = true;

				this.maxTicksUntilSwap = this.getEraseTime();
			}
			this.ticksUntilSwap = this.maxTicksUntilSwap;
		}

		if (this.prismSpawner != null) {
			this.prismSpawner.tick();
		}

		this.checkElimination();

		if (this.players.size() < 2) {
			if (this.players.size() == 1 && this.singleplayer) return;

			this.sendMessage(this.getEndingMessage());

			this.endGame();
		}
	}

	private Text getEndingMessage() {
		if (this.players.size() == 1) {
			PlayerRef winnerRef = this.players.iterator().next();
			PlayerEntity winner = winnerRef.getEntity(this.world);
			if (winner != null) {
				return Text.translatable("text.colorswap.win", winner.getDisplayName()).formatted(Formatting.GOLD);
			}
		}
		return Text.translatable("text.colorswap.no_winners").formatted(Formatting.GOLD);
	}

	public void sendMessage(Text message) {
		this.gameSpace.getPlayers().sendMessage(message);
	}

	private void setSpectator(ServerPlayerEntity player) {
		player.changeGameMode(GameMode.SPECTATOR);
	}

	public PlayerOfferResult offerPlayer(PlayerOffer offer) {
		return offer.accept(this.world, this.map.getSpectatorSpawnPos()).and(() -> {
			this.updateRoundsExperienceLevel(offer.player());
			this.setSpectator(offer.player());
		});
	}

	public void removePlayer(ServerPlayerEntity player) {
		this.eliminate(player, true);
	}

	private boolean isKnockbackEnabled() {
		if (this.config.getNoKnockbackRounds() < 0) return false;
		return this.rounds - 1 >= this.config.getNoKnockbackRounds();
	}

	private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
		return this.isKnockbackEnabled() ? ActionResult.SUCCESS : ActionResult.FAIL;
	}

	public void eliminate(ServerPlayerEntity eliminatedPlayer, boolean remove) {
		if (this.isGameEnding()) return;

		PlayerRef eliminatedRef = PlayerRef.of(eliminatedPlayer);
		if (!this.players.contains(eliminatedRef)) return;

		Text message = Text.translatable("text.colorswap.eliminated", eliminatedPlayer.getDisplayName()).formatted(Formatting.RED);
		this.sendMessage(message);

		if (remove) {
			this.players.remove(eliminatedRef);
		}
		this.setSpectator(eliminatedPlayer);
	}

	private void endGame() {
		this.ticksUntilClose = this.config.getTicksUntilClose().get(this.world.getRandom());
	}

	private boolean isGameEnding() {
		return this.ticksUntilClose >= 0;
	}

	public ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
		this.eliminate(player, true);
		return ActionResult.SUCCESS;
	}

	public TypedActionResult<ItemStack> onUseItem(ServerPlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);

		PlayerRef ref = PlayerRef.of(player);
		if (this.players.contains(ref) && stack.isOf(Main.PRISM)) {
			Prism prism = PrismItem.getPrism(stack);

			if (prism != null && prism.activate(this, player)) {
				ItemStack newStack = new ItemStack(this.swapBlock);
				player.setStackInHand(hand, newStack);

				return TypedActionResult.success(newStack);
			}
		}

		if (PolymerItemUtils.getPolymerItemStack(stack, player).isOf(Items.ENDER_PEARL)) {
			stack.increment(1);
			player.getItemCooldownManager().set(Items.ENDER_PEARL, 0);
		}

		return TypedActionResult.pass(stack);
	}

	public static void spawn(ServerWorld world, Vec3d spawnPos, float yaw, ServerPlayerEntity player) {
		player.teleport(world, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(), yaw, 0);

		player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, StatusEffectInstance.INFINITE, 0, true, false));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, 127, true, false));
	}

	public ColorSwapMap getMap() {
		return this.map;
	}

	public ServerWorld getWorld() {
		return this.world;
	}

	public List<PlayerRef> getPlayers() {
		return this.players;
	}
}
