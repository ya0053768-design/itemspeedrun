package ru.itemspeedrun;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Вся серверная логика. Команды /itemrun не требуют прав оператора,
 * поэтому работают и в хардкоре, и без читов.
 */
public final class ItemRunManager {
	private ItemRunManager() {
	}

	private record Run(Item target, long startNanos) {
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("itemrun")
						.executes(ctx -> start(ctx.getSource(), false))
						.then(Commands.literal("start").executes(ctx -> start(ctx.getSource(), false)))
						.then(Commands.literal("reroll").executes(ctx -> start(ctx.getSource(), true)))
						.then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource())))
				));

		ServerTickEvents.END_SERVER_TICK.register(ItemRunManager::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RUNS.clear());
	}

	private static int start(CommandSourceStack source, boolean reroll) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		UUID id = player.getUUID();
		Run previous = RUNS.get(id);

		if (previous != null && !reroll) {
			player.sendSystemMessage(Component.literal(
					"Забег уже идёт. /itemrun reroll - другой предмет, /itemrun cancel - отмена.")
					.withStyle(ChatFormatting.YELLOW));
			return 0;
		}

		// Предмет не должен уже лежать в инвентаре (иначе время было бы 0) и не должен совпасть с прошлым.
		Item target = ItemPool.pick(source.enabledFeatures(),
				item -> hasItem(player, item) || (previous != null && previous.target() == item));
		if (target == null) {
			player.sendSystemMessage(Component.literal("Не нашлось подходящего предмета.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		player.sendSystemMessage(Component.literal("Добудь: ")
				.withStyle(ChatFormatting.GRAY)
				.append(itemName(target).copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

		RUNS.put(id, new Run(target, System.nanoTime()));
		send(player, new TimerPayload(TimerPayload.RUNNING, target, 0L));
		return 1;
	}

	private static int cancel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Run run = RUNS.remove(player.getUUID());
		if (run == null) {
			player.sendSystemMessage(Component.literal("Активного забега нет.").withStyle(ChatFormatting.YELLOW));
			return 0;
		}
		send(player, new TimerPayload(TimerPayload.CANCELLED, run.target(), elapsedMs(run)));
		player.sendSystemMessage(Component.literal("Забег отменён.").withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static void tick(MinecraftServer server) {
		if (RUNS.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Run>> it = RUNS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Run> entry = it.next();
			Run run = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

			if (player == null) {
				// игрок вышел
				it.remove();
				continue;
			}
			if (!player.isAlive()) {
				it.remove();
				send(player, new TimerPayload(TimerPayload.CANCELLED, run.target(), elapsedMs(run)));
				player.sendSystemMessage(Component.literal("Забег прерван: ты умер.").withStyle(ChatFormatting.RED));
				continue;
			}
			if (hasItem(player, run.target())) {
				it.remove();
				long ms = elapsedMs(run);
				send(player, new TimerPayload(TimerPayload.FINISHED, run.target(), ms));
				player.sendSystemMessage(Component.literal("Готово! ")
						.withStyle(ChatFormatting.GREEN)
						.append(itemName(run.target()).copy().withStyle(ChatFormatting.GOLD))
						.append(Component.literal(" добыт за ").withStyle(ChatFormatting.GREEN))
						.append(Component.literal(TimeFormat.format(ms)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
			}
		}
	}

	/** Есть ли предмет в инвентаре (хотбар, основной инвентарь, броня, вторая рука). */
	private static boolean hasItem(ServerPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) {
				return true;
			}
		}
		return false;
	}

	private static long elapsedMs(Run run) {
		return (System.nanoTime() - run.startNanos()) / 1_000_000L;
	}

	private static Component itemName(Item item) {
		return item.getDefaultInstance().getHoverName();
	}

	private static void send(ServerPlayer player, TimerPayload payload) {
		// если у игрока нет мода на клиенте - просто не шлём пакет, чат продолжит работать
		if (ServerPlayNetworking.canSend(player, TimerPayload.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
