package io.github.haykam821.colorswap.game;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.haykam821.colorswap.game.map.ColorSwapMapConfig;
import io.github.haykam821.colorswap.game.prism.PrismConfig;
import net.minecraft.SharedConstants;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.intprovider.IntProvider;
import xyz.nucleoid.plasmid.game.common.config.PlayerConfig;

public class ColorSwapConfig {
	public static final Codec<ColorSwapConfig> CODEC = RecordCodecBuilder.create(instance -> {
		return instance.group(
			ColorSwapMapConfig.CODEC.fieldOf("map").forGetter(ColorSwapConfig::getMapConfig),
			PlayerConfig.CODEC.fieldOf("players").forGetter(ColorSwapConfig::getPlayerConfig),
			PrismConfig.CODEC.optionalFieldOf("prisms").forGetter(ColorSwapConfig::getPrismConfig),
			Codec.INT.optionalFieldOf("guide_ticks", SharedConstants.TICKS_PER_SECOND * 10).forGetter(ColorSwapConfig::getGuideTicks),
			IntProvider.NON_NEGATIVE_CODEC.optionalFieldOf("ticks_until_close", ConstantIntProvider.create(SharedConstants.TICKS_PER_SECOND * 5)).forGetter(ColorSwapConfig::getTicksUntilClose),
			SoundEvent.CODEC.optionalFieldOf("swap_sound", SoundEvents.BLOCK_NOTE_BLOCK_SNARE.value()).forGetter(ColorSwapConfig::getSwapSound),
			Codec.INT.optionalFieldOf("swap_time", -1).forGetter(ColorSwapConfig::getSwapTime),
			Codec.INT.optionalFieldOf("erase_time", -1).forGetter(ColorSwapConfig::getEraseTime),
			Codec.INT.optionalFieldOf("no_knockback_rounds", -1).forGetter(ColorSwapConfig::getNoKnockbackRounds)
		).apply(instance, ColorSwapConfig::new);
	});

	private final ColorSwapMapConfig mapConfig;
	private final PlayerConfig playerConfig;
	private final Optional<PrismConfig> prisms;
	private final int guideTicks;
	private final IntProvider ticksUntilClose;
	private final SoundEvent swapSound;
	private final int swapTime;
	private final int eraseTime;
	private final int noKnockbackRounds;

	public ColorSwapConfig(ColorSwapMapConfig mapConfig, PlayerConfig playerConfig, Optional<PrismConfig> prisms, int guideTicks, IntProvider ticksUntilClose, SoundEvent swapSound, int swapTime, int eraseTime, int noKnockbackRounds) {
		this.mapConfig = mapConfig;
		this.playerConfig = playerConfig;
		this.prisms = prisms;
		this.guideTicks = guideTicks;
		this.ticksUntilClose = ticksUntilClose;
		this.swapSound = swapSound;
		this.swapTime = swapTime;
		this.eraseTime = eraseTime;
		this.noKnockbackRounds = noKnockbackRounds;
	}

	public ColorSwapMapConfig getMapConfig() {
		return this.mapConfig;
	}

	public PlayerConfig getPlayerConfig() {
		return this.playerConfig;
	}

	public Optional<PrismConfig> getPrismConfig() {
		return this.prisms;
	}

	public int getGuideTicks() {
		return this.guideTicks;
	}

	public IntProvider getTicksUntilClose() {
		return this.ticksUntilClose;
	}

	public SoundEvent getSwapSound() {
		return this.swapSound;
	}

	public int getSwapTime() {
		return this.swapTime;
	}

	public int getEraseTime() {
		return this.eraseTime;
	}

	public int getNoKnockbackRounds() {
		return this.noKnockbackRounds;
	}
}
