package ru.itemspeedrun.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import ru.itemspeedrun.TimeFormat;
import ru.itemspeedrun.TimerPayload;

/** Секундомер вверху по центру экрана. */
public final class TimerHud {
	private TimerHud() {
	}

	private static final long HIDE_AFTER_FINISH_MS = 60_000L;
	private static final long HIDE_AFTER_CANCEL_MS = 5_000L;

	private static int state = TimerPayload.CLEAR;
	private static Item target = Items.AIR;
	private static Component targetName = Component.empty();
	private static long startNanos;
	private static long frozenMs;
	private static long endedAtNanos;

	public static void apply(TimerPayload payload) {
		long now = System.nanoTime();
		state = payload.state();
		target = payload.item();
		targetName = target.getDefaultInstance().getHoverName();

		switch (state) {
			case TimerPayload.RUNNING -> startNanos = now;
			case TimerPayload.FINISHED, TimerPayload.CANCELLED -> {
				frozenMs = payload.elapsedMs();
				endedAtNanos = now;
			}
			default -> {
			}
		}
	}

	public static void reset() {
		state = TimerPayload.CLEAR;
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (state == TimerPayload.CLEAR) {
			return;
		}

		long now = System.nanoTime();
		long ms;
		int color;
		switch (state) {
			case TimerPayload.RUNNING -> {
				ms = (now - startNanos) / 1_000_000L;
				color = 0xFFFFFFFF;
			}
			case TimerPayload.FINISHED -> {
				if ((now - endedAtNanos) / 1_000_000L > HIDE_AFTER_FINISH_MS) {
					state = TimerPayload.CLEAR;
					return;
				}
				ms = frozenMs;
				color = 0xFF55FF55;
			}
			default -> {
				if ((now - endedAtNanos) / 1_000_000L > HIDE_AFTER_CANCEL_MS) {
					state = TimerPayload.CLEAR;
					return;
				}
				ms = frozenMs;
				color = 0xFFFF5555;
			}
		}

		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int screenWidth = mc.getWindow().getGuiScaledWidth();

		String time = TimeFormat.format(ms);
		Component line2 = Component.literal("Цель: ").append(targetName);

		int contentWidth = Math.max(font.width("88:88.888"), font.width(line2));
		int boxWidth = contentWidth + 12;
		int boxHeight = 9 * 2 + 10;
		int x1 = (screenWidth - boxWidth) / 2;
		int y1 = 4;

		graphics.fill(x1, y1, x1 + boxWidth, y1 + boxHeight, 0x90000000);
		graphics.text(font, time, (screenWidth - font.width(time)) / 2, y1 + 4, color, true);
		graphics.text(font, line2, (screenWidth - font.width(line2)) / 2, y1 + 5 + 9, 0xFFFFFFFF, true);
	}
}
