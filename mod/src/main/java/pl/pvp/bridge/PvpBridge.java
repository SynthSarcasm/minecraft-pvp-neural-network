package pl.pvp.bridge;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Set;

/**
 * Bridge between the server and Python. Python connects over TCP (localhost:25599), sends the number of arenas, the
 * server tick rate and the number of 1v3 arenas, and then every tick: the mod sends the observations of all bots, Python
 * sends back actions. The server waits for the actions, so learning runs in lockstep with the game.
 *
 * Stage 6: an arena = a 23x23 glass cage with terrain (hills, pits, lava pools; or flat) holding 2 Carpet fake players
 * (1v1) or 4 (1v3: player 0 vs 1–3). The 1v3 arenas are at the end of the arena list.
 */
public class PvpBridge implements ModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("pvpmost");
	static final int PORT = Integer.getInteger("pvpmost.port", 25599);   // -Dpvpmost.port=... for a second server
	static final int RAW = 249;         // numbers per player in an observation (fields: see zapiszObs; 0–124 as in stage 5)
	static final int ACT = 10;          // numbers per player in an action (8: slot choice 0=no change/1–9, 9: right button)
	static final int VOX = 5;           // block grid around the player VOX×3×VOX in the player's frame (forward/right), levels -1..+1
	static final int MAPA = 7;          // terrain map MAPA×MAPA around the player (surface height + lava)
	// item set (bit mask, from Python every round): 1 bow+arrows, 2 food, 4 shield, 8 fishing rod, 16 blocks,
	// 32 lava bucket, 64 healing potions (splash), 128 a potion from the potion list (kind in a separate byte)
	static final int Z_LUK = 1, Z_JEDZENIE = 2, Z_TARCZA = 4, Z_WEDKA = 8, Z_BLOKI = 16, Z_LAWA = 32, Z_LECZENIE = 64, Z_MIKSTURA = 128;
	static final int PARKUJ = 255;      // weapon code "this player sits this round out" (1v1 and 1v2 inside a 1v3 arena)
	// -Dpvpmost.vanillaKlik=true: buttons exactly as in the client (Minecraft.handleKeybinds): an attack click in a tick
	// in which the player is using something is lost; right-click again only after 4 ticks; a miss blocks attacks for 10 ticks
	static final boolean VANILLA_KLIK = Boolean.getBoolean("pvpmost.vanillaKlik");
	static int[] przerwaPrawego = new int[0], pudlo = new int[0];
	static final int ROZSTAW = 48;      // spacing between arenas (larger than the players' view distance)
	static final int W_RZEDZIE = 8;
	static final int Y0 = 100;          // flat floor level
	static final int POL = 11;          // interior from -11 to +11, i.e. 23 blocks
	static final int WYS = 12;          // interior height above the flat floor
	static final int DNO = 4;           // how many blocks below the flat floor (pits)
	static final int H_MIN = -3, H_MAX = 5;   // terrain height relative to the flat floor
	static final float MAX_OBROT_YAW = 45f, MAX_OBROT_PITCH = 30f;   // degrees per tick

	enum Stan { CZEKA, SPAWN, GRA }

	static MinecraftServer server;
	static ServerSocket gniazdoSerwera;
	static volatile Socket oczekujace;
	static Socket gniazdo;
	static DataInputStream wej;
	static OutputStream wyj;
	static Stan stan = Stan.CZEKA;
	static int nAren = 0, n2 = 0, n4 = 0;
	static int[] pocz = new int[0], ile = new int[0];   // first player and number of players in the arena
	static int[] klatka = new int[0], kWKlatce = new int[0];   // player -> arena, index within the arena
	static int[] cel = new int[0];                // the opponent the player targets (player index; from Python every tick)
	static boolean[] wypadl = new boolean[0];     // player out of the arena until the round ends (died in 1v3 or sits this round out)
	static int[][] teren = new int[0][];          // terrain height of every column of the arena
	static boolean[][] lawaT = new boolean[0][];  // lava pools in the terrain
	static ServerPlayer[] gracze = new ServerPlayer[0];
	static boolean[] zginal = new boolean[0];
	static boolean[] czlowiek = new boolean[0];   // slot taken by a real player (the /walcz command)
	static int[] bron = new int[0];               // weapon this round: 0 fists, 1 diamond sword, 2 diamond axe
	static int[] nastepnaBron = new int[0];       // weapon for the next round (Python sends it every tick, applied on reset)
	static int[] maska = new int[0], nastepnaMaska = new int[0];   // item set this round / next round
	static int[] mikstura = new int[0], nastepnaMikstura = new int[0];   // potion kind in slot 8
	static boolean[] wcisniety = new boolean[0];  // right button on the previous tick (rising edge = click)
	static int[] trzyma = new int[0];             // how many ticks the right button is held (blocks: repeat every 4 ticks as in the game)
	static int[] trafStrzala = new int[0], zablokowane = new int[0], postawione = new int[0];   // per-round counters
	static ServerPlayer zaparkowany = null;        // bot moved aside while a human fights
	static int wygraneCz = 0, przegraneCz = 0, czasCz = 0;
	static ByteBuffer buforObs, buforAkcji;
	static int tickiGry = 0, tickiSpawnu = 0;
	static float tempoGry = 20f;
	static final String[] NAZWY_BRONI = {"piesci", "miecz", "siekiera"};
	static int bronCz = 0, bronSieciCz = 0;            // weapons in the human's arena (chosen with /walcz)
	static int maskaCz() { return bronCz > 0 || bronSieciCz > 0 ? 255 : 0; }   // with weapons: full loadout for both
	// potions in slot 8 (kind 1–10): drinkable (strength II, speed II, fire resistance, regeneration II) and splash
	// (strength II, speed II on yourself; harming II, poison II, slowness, weakness on the opponent)
	static final int N_MIKSTUR = 10;
	static ItemStack zrobMiksture(int rodzaj) {
		Holder<Potion> p;
		boolean rzucana = rodzaj >= 5;
		int ile = 1;
		switch (rodzaj) {
			case 1, 5 -> p = Potions.STRONG_STRENGTH;
			case 2, 6 -> p = Potions.STRONG_SWIFTNESS;
			case 3 -> p = Potions.FIRE_RESISTANCE;
			case 4 -> p = Potions.STRONG_REGENERATION;
			case 7 -> { p = Potions.STRONG_HARMING; ile = 2; }
			case 8 -> { p = Potions.STRONG_POISON; ile = 2; }
			case 9 -> { p = Potions.SLOWNESS; ile = 2; }
			case 10 -> { p = Potions.WEAKNESS; ile = 2; }
			default -> { return ItemStack.EMPTY; }
		}
		ItemStack st = PotionContents.createItemStack(rzucana ? Items.SPLASH_POTION : Items.POTION, p);
		st.set(DataComponents.MAX_STACK_SIZE, 8);
		st.setCount(ile);
		return st;
	}
	// password on join (file pvpmost_haslo.txt in the server directory; no file = no password)
	static final String HASLO = wczytajHaslo();
	static final Set<UUID> zweryfikowani = ConcurrentHashMap.newKeySet();
	static final Map<UUID, Long> czekajaNaHaslo = new ConcurrentHashMap<>();

	static String wczytajHaslo() {
		try {
			Path plik = Path.of("pvpmost_haslo.txt");
			if (!Files.exists(plik)) return null;
			String h = Files.readString(plik).trim();
			return h.isEmpty() ? null : h;
		} catch (IOException e) {
			return null;
		}
	}
	static final Random los = new Random();

	@Override
	public void onInitialize() {
		// /walcz                      -> enter (fists) or leave, if you are already fighting
		// /walcz <weapon>             -> you and the network with this weapon (piesci = fists / miecz = sword / siekiera = axe)
		// /walcz <yours> <network's>  -> a different weapon for each
		CommandRegistrationCallback.EVENT.register((dispatcher, kontekst, srodowisko) -> {
			var walcz = Commands.literal("walcz").executes(ctx -> wykonajWalcz(ctx.getSource().getPlayerOrException(), -1, -1));
			for (int i = 0; i < NAZWY_BRONI.length; i++) {
				final int ty = i;
				var pierwsza = Commands.literal(NAZWY_BRONI[i]).executes(ctx -> wykonajWalcz(ctx.getSource().getPlayerOrException(), ty, ty));
				for (int j = 0; j < NAZWY_BRONI.length; j++) {
					final int siec = j;
					pierwsza.then(Commands.literal(NAZWY_BRONI[j]).executes(ctx -> wykonajWalcz(ctx.getSource().getPlayerOrException(), ty, siec)));
				}
				walcz.then(pierwsza);
			}
			dispatcher.register(walcz);
			dispatcher.register(Commands.literal("haslo").then(Commands.argument("kod", StringArgumentType.greedyString())
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						boolean ok = HASLO == null || HASLO.equals(StringArgumentType.getString(ctx, "kod").trim());
						if (ok) {
							zweryfikowani.add(p.getUUID());
							czekajaNaHaslo.remove(p.getUUID());
							p.sendSystemMessage(Component.literal("Password accepted. Welcome! Fight the network: /walcz [piesci|miecz|siekiera] [network's weapon] (fists|sword|axe)"));
						} else {
							p.sendSystemMessage(Component.literal("Wrong password."));
						}
						LOG.info("pvpmost: /haslo from {} -> {}", p.getGameProfile().name(), ok ? "ok" : "WRONG");
						return 1;
					})));
		});
		ServerLifecycleEvents.SERVER_STARTED.register(PvpBridge::start);
		ServerTickEvents.END_SERVER_TICK.register(PvpBridge::poTicku);
		ServerLivingEntityEvents.ALLOW_DEATH.register(PvpBridge::czyMozeZginac);
		ServerLivingEntityEvents.AFTER_DAMAGE.register((ofiara, zrodlo, bazowe, zadane, zablok) -> {
			for (int i = 0; i < gracze.length; i++) {
				if (gracze[i] == ofiara && zablok) zablokowane[i]++;
				if (zadane > 0 && gracze[i] != ofiara && zrodlo.getDirectEntity() instanceof AbstractArrow && zrodlo.getEntity() == gracze[i])
					trafStrzala[i]++;
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
			ServerPlayer p = handler.player;
			if (p instanceof EntityPlayerMPFake) return;
			// a real player = spectator above the first arena
			p.setGameMode(GameType.SPECTATOR);
			p.getInventory().clearContent();
			p.teleportTo(srv.overworld(), 0.5, Y0 + 10, -POL - 6.5, Set.of(), 0f, 35f, false);
			if (HASLO != null && !zweryfikowani.contains(p.getUUID())) {
				czekajaNaHaslo.put(p.getUUID(), System.currentTimeMillis());
				p.sendSystemMessage(Component.literal("Type /haslo <password> within 30 s, otherwise you will be disconnected."));
			}
		});
	}

	static void start(MinecraftServer srv) {
		server = srv;
		GameRules g = srv.getGameRules();
		g.set(GameRules.ADVANCE_TIME, false, srv);
		g.set(GameRules.ADVANCE_WEATHER, false, srv);
		g.set(GameRules.SPAWN_MOBS, false, srv);
		g.set(GameRules.SPAWN_MONSTERS, false, srv);
		g.set(GameRules.SPAWN_PHANTOMS, false, srv);
		g.set(GameRules.SPAWN_PATROLS, false, srv);
		g.set(GameRules.SPAWN_WANDERING_TRADERS, false, srv);
		g.set(GameRules.SEND_COMMAND_FEEDBACK, false, srv);
		g.set(GameRules.LOG_ADMIN_COMMANDS, false, srv);
		srv.setDifficulty(Difficulty.NORMAL, true);
		srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "time set noon");
		try {
			gniazdoSerwera = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress());
		} catch (IOException e) {
			LOG.error("Cannot open port {}", PORT, e);
			return;
		}
		Thread t = new Thread(() -> {
			while (true) {
				try {
					Socket s = gniazdoSerwera.accept();
					s.setTcpNoDelay(true);
					oczekujace = s;
				} catch (IOException e) {
					return;
				}
			}
		}, "pvpmost-accept");
		t.setDaemon(true);
		t.start();
		LOG.info("pvpmost: waiting for Python on port {}", PORT);
	}

	// ---------------------------------------------------------------- arena and terrain

	static int srodekX(int a) { return (a % W_RZEDZIE) * ROZSTAW; }
	static int srodekZ(int a) { return (a / W_RZEDZIE) * ROZSTAW; }
	static final int BOK = 2 * POL + 1;
	static int kom(int dx, int dz) { return (dx + POL) * BOK + (dz + POL); }

	/** Arena frame: bottom, glass walls and ceiling, above the ceiling a "waiting room" for players out of play. */
	static void zbudujKlatke(ServerLevel lv, int a) {
		int cx = srodekX(a), cz = srodekZ(a);
		BlockState szklo = Blocks.GLASS.defaultBlockState();
		BlockState dno = Blocks.STONE.defaultBlockState();
		int r = POL + 1;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				for (int dy = -DNO - 1; dy <= WYS + 1; dy++) {
					boolean sciana = Math.abs(dx) == r || Math.abs(dz) == r;
					if (dy == -DNO - 1) ustaw(lv, cx + dx, Y0 + dy, cz + dz, dno);
					else if (dy == WYS + 1 || sciana) ustaw(lv, cx + dx, Y0 + dy, cz + dz, szklo);
				}
		// waiting room: a 3x3 glass box above the ceiling
		int yp = Y0 + WYS + 3;
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++)
				for (int dy = 0; dy <= 4; dy++) {
					boolean brzeg = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 0 || dy == 4;
					ustaw(lv, cx + dx, yp + dy, cz + dz, brzeg ? szklo : Blocks.AIR.defaultBlockState());
				}
		if (teren[a] == null) generujTeren(a, false);
		ustawTeren(lv, a);
	}

	static void ustaw(ServerLevel lv, int x, int y, int z, BlockState b) {
		BlockPos bp = new BlockPos(x, y, z);
		if (lv.getBlockState(bp) != b) lv.setBlock(bp, b, 2);
	}

	/** Random terrain: a few gentle hills and valleys, sometimes a pit (some with lava at the bottom) and cover pillars. */
	static void generujTeren(int a, boolean losowy) {
		int[] h = new int[BOK * BOK];
		boolean[] lw = new boolean[BOK * BOK];
		if (losowy) {
			double[] pole = new double[BOK * BOK];
			int gorki = 2 + los.nextInt(4);
			for (int g = 0; g < gorki; g++) {
				double gx = (los.nextDouble() * 2 - 1) * POL, gz = (los.nextDouble() * 2 - 1) * POL;
				double r = 2.5 + los.nextDouble() * 5;
				double amp = (los.nextDouble() < 0.7 ? 1 : -1) * (1 + los.nextDouble() * 3.5);
				for (int dx = -POL; dx <= POL; dx++)
					for (int dz = -POL; dz <= POL; dz++) {
						double d2 = (dx - gx) * (dx - gx) + (dz - gz) * (dz - gz);
						pole[kom(dx, dz)] += amp * Math.exp(-d2 / (2 * r * r));
					}
			}
			for (int c = 0; c < h.length; c++) h[c] = Mth.clamp((int) Math.round(pole[c]), -2, H_MAX);
			int doly = los.nextDouble() < 0.6 ? 1 + los.nextInt(2) : 0;
			for (int d = 0; d < doly; d++) {
				int gx = los.nextInt(BOK - 4) - POL + 2, gz = los.nextInt(BOK - 4) - POL + 2;
				int rx = 1 + los.nextInt(2), rz = 1 + los.nextInt(2);
				boolean zLawa = los.nextDouble() < 0.5;
				for (int dx = gx - rx; dx <= gx + rx; dx++)
					for (int dz = gz - rz; dz <= gz + rz; dz++) {
						if (Math.abs(dx) > POL || Math.abs(dz) > POL) continue;
						h[kom(dx, dz)] = H_MIN;
						lw[kom(dx, dz)] = zLawa;
					}
			}
			int slupy = los.nextInt(4);
			for (int s = 0; s < slupy; s++) {
				int gx = los.nextInt(BOK - 2) - POL + 1, gz = los.nextInt(BOK - 2) - POL + 1;
				int c = kom(gx, gz);
				if (!lw[c]) h[c] = Math.min(H_MAX, Math.max(h[c], 0) + 3);
			}
		}
		teren[a] = h;
		lawaT[a] = lw;
	}

	/** Sets the arena to its terrain: removes placed blocks, puts out fire, removes spilled lava. */
	static void ustawTeren(ServerLevel lv, int a) {
		int cx = srodekX(a), cz = srodekZ(a);
		BlockState trawa = Blocks.GRASS_BLOCK.defaultBlockState(), ziemia = Blocks.DIRT.defaultBlockState();
		BlockState powietrze = Blocks.AIR.defaultBlockState(), lawa = Blocks.LAVA.defaultBlockState();
		int[] h = teren[a];
		boolean[] lw = lawaT[a];
		for (int dx = -POL; dx <= POL; dx++)
			for (int dz = -POL; dz <= POL; dz++) {
				int c = kom(dx, dz), hc = h[c];
				for (int dy = -DNO; dy <= WYS; dy++) {
					BlockState b = dy < hc ? ziemia : dy == hc ? trawa : (lw[c] && dy == hc + 1) ? lawa : powietrze;
					ustaw(lv, cx + dx, Y0 + dy, cz + dz, b);
				}
			}
	}

	static AABB pudloKlatki(int a) {
		int cx = srodekX(a), cz = srodekZ(a);
		return new AABB(cx - POL - 1, Y0 - DNO - 1, cz - POL - 1, cx + POL + 2, Y0 + WYS + 8, cz + POL + 2);
	}

	/** After a round: terrain as new; arrows, bobbers, potions in flight, effect clouds and items disappear. */
	static void posprzataj(ServerLevel lv, int a) {
		ustawTeren(lv, a);
		AABB box = pudloKlatki(a);
		for (Entity e : lv.getEntitiesOfClass(AbstractArrow.class, box)) e.discard();
		for (Entity e : lv.getEntitiesOfClass(FishingHook.class, box)) e.discard();
		for (Entity e : lv.getEntitiesOfClass(AbstractThrownPotion.class, box)) e.discard();
		for (Entity e : lv.getEntitiesOfClass(AreaEffectCloud.class, box)) e.discard();
		for (Entity e : lv.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
	}

	/** A free start column (no lava, no pit) inside the given rectangle; y = on the surface. */
	static double[] miejsceStartu(int a, int x0, int x1, int z0, int z1) {
		int[] h = teren[a];
		for (int proba = 0; proba < 40; proba++) {
			int dx = x0 + los.nextInt(x1 - x0 + 1), dz = z0 + los.nextInt(z1 - z0 + 1);
			int c = kom(dx, dz);
			if (lawaT[a][c] || h[c] < -1) continue;
			return new double[]{srodekX(a) + dx + 0.5, Y0 + h[c] + 1, srodekZ(a) + dz + 0.5};
		}
		int dx = (x0 + x1) / 2, dz = (z0 + z1) / 2;
		return new double[]{srodekX(a) + dx + 0.5, Y0 + Math.max(h[kom(dx, dz)], 0) + 1, srodekZ(a) + dz + 0.5};
	}

	/** Player out of play until the round ends: box above the ceiling, empty inventory, invulnerable. */
	static void zaparkuj(int i) {
		ServerPlayer p = gracze[i];
		int a = klatka[i];
		wypadl[i] = true;
		if (p.isUsingItem()) p.stopUsingItem();
		((ServerPlayerInterface) p).getActionPack().stopAll();
		p.getInventory().clearContent();
		p.removeAllEffects();
		p.clearFire();
		p.getAbilities().invulnerable = true;
		p.setHealth(p.getMaxHealth());
		p.teleportTo(server.overworld(), srodekX(a) + 0.5, Y0 + WYS + 4, srodekZ(a) + 0.5, Set.of(), 0f, 0f, false);
		p.setDeltaMovement(0, 0, 0);
	}

	static void resetujArene(int a, int kod) {
		ServerLevel lv = server.overworld();
		if (kod == 2) generujTeren(a, true);
		else if (kod == 3) generujTeren(a, false);
		int p0 = pocz[a], k4 = ile[a];
		boolean zCzlowiekiem = false;
		for (int k = 0; k < k4; k++) zCzlowiekiem |= czlowiek[p0 + k];
		posprzataj(lv, a);
		for (int k = 0; k < k4; k++) {
			int i = p0 + k;
			ServerPlayer p = gracze[i];
			if (!czlowiek[i] && nastepnaBron[i] == PARKUJ) {
				zaparkuj(i);
				zginal[i] = false;
				continue;
			}
			wypadl[i] = false;
			// a fake player is always in survival: after a server restart pvp-bot restores "its" bots with
			// /player … spawn without a game mode, and Carpet then gives creative mode — attack reach 5 instead of 3 (the
			// crosshair catches the opponent 2× as often), nothing gets used up; such a bot won ~68% vs an identical one
			if (!czlowiek[i] && p.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
				LOG.warn("pvpmost: {} was in {} mode — switching to survival", nazwa(i), p.gameMode.getGameModeForPlayer().getName());
				p.setGameMode(GameType.SURVIVAL);
			}
			p.getAbilities().invulnerable = false;
			double[] poz;
			float yaw;
			if (k4 == 2) {
				poz = k == 0 ? miejsceStartu(a, -8, -3, -6, 6) : miejsceStartu(a, 3, 8, -6, 6);
				yaw = (float) ((k == 0 ? -90f : 90f) + (los.nextFloat() * 2 - 1) * 40f);
			} else if (k == 0) {
				poz = miejsceStartu(a, -9, -4, -4, 4);
				yaw = -90f + (los.nextFloat() * 2 - 1) * 30f;
			} else {
				int z0 = -9 + (k - 1) * 6;
				poz = miejsceStartu(a, 3, 9, z0, z0 + 5);
				yaw = 90f + (los.nextFloat() * 2 - 1) * 30f;
			}
			p.teleportTo(lv, poz[0], poz[1], poz[2], Set.of(), yaw, 0f, false);
			p.setDeltaMovement(0, 0, 0);
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5f);
			p.setInvulnerableTime(0);
			p.hurtTime = 0;
			p.clearFire();
			p.setRemainingFireTicks(0);
			p.resetFallDistance();
			if (p.isUsingItem()) p.stopUsingItem();
			if (czlowiek[i]) {                         // human: weapon of their choice (/walcz)
				uzbroj(p, bronCz, maskaCz(), 1 + los.nextInt(N_MIKSTUR));
				bron[i] = bronCz;
				maska[i] = maskaCz();
			} else {
				((ServerPlayerInterface) p).getActionPack().stopAll();
				int kodB = zCzlowiekiem ? bronSieciCz : nastepnaBron[i];   // network in the human's arena: weapon from /walcz
				int m = zCzlowiekiem ? maskaCz() : nastepnaMaska[i];
				// potion byte: bits 0–3 kind, 4–5 starting slot (0 weapon, 1 lava, 2 healing, 3 potion), bit 6 = start with 10 HP
				int mk = zCzlowiekiem ? 1 + los.nextInt(N_MIKSTUR) : nastepnaMikstura[i];
				int rodzaj = mk & 15, start = (mk >> 4) & 3;
				uzbroj(p, kodB, m, rodzaj);
				if (start > 0 && !p.getInventory().getItem(5 + start).isEmpty()) p.getInventory().setSelectedSlot(5 + start);
				if ((mk & 64) != 0) p.setHealth(10f);
				bron[i] = kodB;
				maska[i] = m;
				mikstura[i] = rodzaj;
			}
			wcisniety[i] = false;
			trzyma[i] = 0;
			przerwaPrawego[i] = pudlo[i] = 0;
			// new round = working shield (the axe-disable cooldown does not carry over to the next round)
			p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(new ItemStack(Items.SHIELD)));
			trafStrzala[i] = zablokowane[i] = postawione[i] = 0;
			p.setAbsorptionAmount(0f);
			p.setJumping(false);
			p.removeAllEffects();
			p.resetAttackStrengthTicker();
			zginal[i] = false;
		}
	}

	static boolean czyMozeZginac(LivingEntity e, Object zrodlo, float obrazenia) {
		for (int i = 0; i < gracze.length; i++) {
			if (gracze[i] == e) {
				zginal[i] = true;
				e.setHealth(1f);
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- tick

	static void poTicku(MinecraftServer srv) {
		if (!czekajaNaHaslo.isEmpty() && srv.getTickCount() % 20 == 0) {   // no password after 30 s: disconnect
			long teraz = System.currentTimeMillis();
			for (var w : new ArrayList<>(czekajaNaHaslo.entrySet())) {
				if (teraz - w.getValue() < 30_000) continue;
				czekajaNaHaslo.remove(w.getKey());
				ServerPlayer p = srv.getPlayerList().getPlayer(w.getKey());
				if (p != null && !zweryfikowani.contains(w.getKey())) {
					LOG.info("pvpmost: disconnecting {} — no password", p.getGameProfile().name());
					p.connection.disconnect(Component.literal("No password."));
				}
			}
		}
		try {
			switch (stan) {
				case CZEKA -> {
					Socket s = oczekujace;
					if (s != null) {
						oczekujace = null;
						polacz(s);
					}
				}
				case SPAWN -> {
					if (wszyscyGotowi()) {
						for (int a = 0; a < nAren; a++) resetujArene(a, 1);
						srv.tickRateManager().setTickRate(tempoGry);
						stan = Stan.GRA;
						LOG.info("pvpmost: {} arenas ready, starting", nAren);
					}
				}
				case GRA -> krok();
			}
		} catch (IOException e) {
			LOG.warn("pvpmost: Python disconnected ({})", e.toString());
			rozlacz();
		}
	}

	static void polacz(Socket s) throws IOException {
		gniazdo = s;
		wej = new DataInputStream(s.getInputStream());
		wyj = s.getOutputStream();
		ByteBuffer pow = czytaj(12);
		int n = pow.getInt();
		float tempo = pow.getFloat();
		int m4 = pow.getInt();
		if (n < 1 || n > 256 || m4 < 0 || m4 > n) throw new IOException("bad number of arenas: " + n + " / 1v3: " + m4);
		ServerLevel lv = server.overworld();
		nAren = n;
		n4 = m4;
		n2 = n - m4;
		pocz = new int[n];
		ile = new int[n];
		int gr = 0;
		for (int a = 0; a < n; a++) {
			pocz[a] = gr;
			ile[a] = a < n2 ? 2 : 4;
			gr += ile[a];
		}
		final int N = gr;
		// bots have fixed names: existing ones are reused, missing ones created, extra ones removed
		// (Carpet rejects killing and immediately re-creating a bot with the same name)
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (p instanceof EntityPlayerMPFake f && numerBota(p) >= N) f.kill(lv);
		for (int i = 0; i < czlowiek.length; i++)        // new Python connection: the human goes back to spectators
			if (czlowiek[i] && gracze[i] != null) {
				ServerPlayer c = gracze[i];
				if (!c.isRemoved()) c.setGameMode(GameType.SPECTATOR);
				if (zaparkowany != null && !zaparkowany.isRemoved()) zaparkowany.setGameMode(GameType.SURVIVAL);
			}
		zaparkowany = null;
		gracze = new ServerPlayer[N];
		klatka = new int[N];
		kWKlatce = new int[N];
		cel = new int[N];
		for (int a = 0; a < n; a++)
			for (int k = 0; k < ile[a]; k++) {
				int i = pocz[a] + k;
				klatka[i] = a;
				kWKlatce[i] = k;
				cel[i] = k == 0 ? pocz[a] + 1 : pocz[a];
			}
		wypadl = new boolean[N];
		zginal = new boolean[N];
		czlowiek = new boolean[N];
		bron = new int[N];
		nastepnaBron = new int[N];
		maska = new int[N];
		nastepnaMaska = new int[N];
		mikstura = new int[N];
		nastepnaMikstura = new int[N];
		wcisniety = new boolean[N];
		trzyma = new int[N];
		trafStrzala = new int[N];
		zablokowane = new int[N];
		postawione = new int[N];
		przerwaPrawego = new int[N];
		pudlo = new int[N];
		teren = new int[n][];
		lawaT = new boolean[n][];
		for (int a = 0; a < n; a++) {
			zbudujKlatke(lv, a);
			for (int k = 0; k < ile[a]; k++) {
				String imie = nazwa(pocz[a] + k);
				if (server.getPlayerList().getPlayerByName(imie) != null) continue;
				Vec3 poz = new Vec3(srodekX(a) + (k == 0 ? -4 : 4) + 0.5, Y0 + 1, srodekZ(a) + (k - 1.5) * 3 + 0.5);
				EntityPlayerMPFake.createFake(imie, server, poz, k == 0 ? -90 : 90, 0,
						Level.OVERWORLD, GameType.SURVIVAL, false);
			}
		}
		buforObs = ByteBuffer.allocate(8 + 4 * RAW * N).order(ByteOrder.LITTLE_ENDIAN);
		tempoGry = Math.max(1f, tempo);
		server.tickRateManager().setTickRate(20f);   // spawning at normal speed
		tickiGry = 0;
		tickiSpawnu = 0;
		stan = Stan.SPAWN;
		LOG.info("pvpmost: Python connected, arenas {} (1v3: {}), players {}, tick rate {}", n, m4, N, tempo);
	}

	static String nazwa(int i) { return String.format("bot%03d", i); }

	static int numerBota(ServerPlayer p) {
		String s = p.getGameProfile().name();
		if (!s.startsWith("bot")) return -1;
		try { return Integer.parseInt(s.substring(3)); } catch (NumberFormatException e) { return -1; }
	}

	static boolean wszyscyGotowi() {
		int brak = 0;
		for (int i = 0; i < gracze.length; i++) {
			if (gracze[i] == null || gracze[i].isRemoved()) {
				gracze[i] = server.getPlayerList().getPlayerByName(nazwa(i));
				if (gracze[i] == null) brak++;
			}
		}
		if (brak > 0 && ++tickiSpawnu % 100 == 0)
			LOG.warn("pvpmost: still missing {} bots (e.g. {})", brak, pierwszyBrakujacy());
		return brak == 0;
	}

	static String pierwszyBrakujacy() {
		for (int i = 0; i < gracze.length; i++) if (gracze[i] == null) return nazwa(i);
		return "-";
	}

	static void krok() throws IOException {
		int n = gracze.length;
		for (int i = 0; i < n; i++)
			if (czlowiek[i] && gracze[i].isRemoved()) zwolnijSlot(i, false);
		// 1. observations
		buforObs.clear();
		buforObs.putInt(tickiGry++);
		buforObs.putInt(n);
		for (int i = 0; i < n; i++) zapiszObs(i);
		wyj.write(buforObs.array(), 0, buforObs.position());
		wyj.flush();
		// deaths already reported to Python in this observation; a death from a hit dealt below has to wait
		// for the next observation, otherwise the reset would clear the flag before Python sees it
		boolean[] zgloszone = zginal.clone();
		// 2. actions, reset requests (0 no, 1 reset, 2 reset with new random terrain, 3 reset on flat ground),
		// weapon / items / potion for the next round, target (opponent index within the arena)
		ByteBuffer b = czytaj(4 * ACT * n + nAren + 4 * n);
		float[][] akcje = new float[n][ACT];
		for (int i = 0; i < n; i++)
			for (int j = 0; j < ACT; j++) akcje[i][j] = b.getFloat();
		byte[] resety = new byte[nAren];
		b.get(resety);
		for (int i = 0; i < n; i++) nastepnaBron[i] = b.get() & 0xff;
		for (int i = 0; i < n; i++) nastepnaMaska[i] = b.get() & 0xff;
		for (int i = 0; i < n; i++) nastepnaMikstura[i] = b.get() & 0xff;
		for (int i = 0; i < n; i++) {
			int c = b.get() & 0xff, a = klatka[i];
			if (c < ile[a] && c != kWKlatce[i]) cel[i] = pocz[a] + c;
		}
		// random order within the arena every tick (like the random order of packets from several clients);
		// a fixed order gave player "A" an edge in simultaneous exchanges of hits
		int[] kol = new int[4];
		for (int a = 0; a < nAren; a++) {
			int k4 = ile[a];
			for (int k = 0; k < k4; k++) kol[k] = k;
			for (int k = k4 - 1; k > 0; k--) { int j = los.nextInt(k + 1); int t = kol[k]; kol[k] = kol[j]; kol[j] = t; }
			for (int k = 0; k < k4; k++) {
				int i = pocz[a] + kol[k];
				if (!zginal[i] && !czlowiek[i] && !wypadl[i]) zastosuj(i, akcje[i]);
			}
		}
		for (int a = 0; a < nAren; a++) {
			int kod = resety[a], p0 = pocz[a];
			if (ile[a] == 2) {
				boolean koniec = kod != 0 || zgloszone[p0] || zgloszone[p0 + 1];
				if (koniec && (czlowiek[p0] || czlowiek[p0 + 1])) wynikDlaCzlowieka(a, zgloszone);
				if (koniec || pozaKlatka(p0) || pozaKlatka(p0 + 1))
					resetujArene(a, Math.max(1, kod));
			} else {
				if (kod != 0) { resetujArene(a, kod); continue; }
				boolean poza = false;
				for (int k = 0; k < 4; k++) {
					int i = p0 + k;
					if (wypadl[i]) continue;
					if (zgloszone[i]) { zaparkuj(i); zginal[i] = false; }   // died in 1v3: out of the arena until the round ends
					else poza |= pozaKlatka(i);
				}
				if (poza) resetujArene(a, 1);
			}
		}
	}

	/** Safety net: a bot fell out of the arena (should not happen) or disappeared. */
	static boolean pozaKlatka(int i) {
		ServerPlayer p = gracze[i];
		if (p.isRemoved()) return true;
		if (wypadl[i]) return false;
		int a = klatka[i];
		return Math.abs(p.getX() - srodekX(a) - 0.5) > POL + 1 || Math.abs(p.getZ() - srodekZ(a) - 0.5) > POL + 1
				|| p.getY() < Y0 - DNO - 1 || p.getY() > Y0 + WYS + 1;
	}

	static final Holder<MobEffect>[] EFEKTY = efekty();
	@SuppressWarnings("unchecked")
	static Holder<MobEffect>[] efekty() {
		return new Holder[]{MobEffects.STRENGTH, MobEffects.SPEED, MobEffects.FIRE_RESISTANCE, MobEffects.REGENERATION,
				MobEffects.POISON, MobEffects.SLOWNESS, MobEffects.WEAKNESS};
	}

	static void zapiszObs(int i) {
		ServerPlayer p = gracze[i];
		ServerPlayer o = gracze[cel[i]];
		int a = klatka[i];
		Vec3 v = p.getDeltaMovement();
		ByteBuffer b = buforObs;
		b.putFloat((float) (p.getX() - srodekX(a) - 0.5));
		b.putFloat((float) (p.getY() - Y0 - 1));
		b.putFloat((float) (p.getZ() - srodekZ(a) - 0.5));
		b.putFloat((float) v.x);
		b.putFloat((float) v.y);
		b.putFloat((float) v.z);
		b.putFloat(Mth.wrapDegrees(p.getYRot()));
		b.putFloat(p.getXRot());
		b.putFloat(p.getHealth());
		b.putFloat(p.getFoodData().getFoodLevel());
		b.putFloat(p.getFoodData().getSaturationLevel());
		b.putFloat(p.getAttackStrengthScale(0.5f));
		b.putFloat(p.hurtTime);
		b.putFloat(p.getInvulnerableTime());
		b.putFloat(p.onGround() ? 1 : 0);
		b.putFloat(p.isSprinting() ? 1 : 0);
		b.putFloat(!wypadl[i] && !wypadl[cel[i]] && celuje(p, o) ? 1 : 0);
		b.putFloat(zginal[i] ? 1 : 0);
		b.putFloat((float) p.distanceTo(o));
		b.putFloat(p.isShiftKeyDown() ? 1 : 0);
		b.putFloat(czlowiek[i] ? 1 : 0);
		b.putFloat(bron[i]);
		b.putFloat(p.getArmorValue());
		// --- items (23–49); everything the player sees in its own inventory, and about the opponent: what it holds, whether
		// it draws/eats/blocks, its arrow in flight and bobber — Python takes only the visible fields from the opponent's row
		var inv = p.getInventory();
		b.putFloat(inv.getSelectedSlot());                                    // 23 selected slot
		b.putFloat(kategoria(p.getMainHandItem()));                           // 24 item in hand (category)
		b.putFloat(p.isUsingItem() ? 1 : 0);                                  // 25 using an item (drawing, eating, shield)
		b.putFloat(p.isUsingItem() ? p.getTicksUsingItem() : 0);              // 26 for how many ticks
		b.putFloat(p.isBlocking() ? 1 : 0);                                   // 27 shield raised and effective
		ItemStack tarcza = p.getOffhandItem();
		b.putFloat(tarcza.is(Items.SHIELD) ? p.getCooldowns().getCooldownPercent(tarcza, 0f) : 0);   // 28 shield disabled
		b.putFloat(ile(inv, Items.ARROW));                                    // 29 arrows
		b.putFloat(ile(inv, Items.GOLDEN_APPLE));                             // 30 golden apples
		b.putFloat(ile(inv, Items.COOKED_BEEF));                              // 31 steaks
		b.putFloat(ile(inv, Items.COBBLESTONE));                              // 32 blocks
		b.putFloat(maska[i]);                                                 // 33 round item set
		b.putFloat(p.getAbsorptionAmount());                                  // 34 absorption hearts
		b.putFloat(p.hasEffect(MobEffects.REGENERATION) ? 1 : 0);             // 35 regeneration
		FishingHook hak = p.fishing;
		Entity zaczep = hak == null ? null : hak.getHookedIn();
		boolean zaczepWroga = zaczep instanceof ServerPlayer zp && zp != p && klatkaGracza(zp) == a;
		b.putFloat(hak == null ? 0 : zaczepWroga ? 2 : 1);                    // 36 bobber: none / in the air / hooked
		b.putFloat(hak == null ? 0 : (float) (hak.getX() - srodekX(a) - 0.5));   // 37–39 bobber (relative to the arena)
		b.putFloat(hak == null ? 0 : (float) (hak.getY() - Y0 - 1));
		b.putFloat(hak == null ? 0 : (float) (hak.getZ() - srodekZ(a) - 0.5));
		AbstractArrow s = (maska[i] & Z_LUK) != 0 ? strzalaWLocie(p, o, a) : null;
		Vec3 sv = s == null ? Vec3.ZERO : s.getDeltaMovement();
		b.putFloat(s == null ? 0 : (float) (s.getX() - srodekX(a) - 0.5));  // 40–45 the arrow in flight closest to the opponent
		b.putFloat(s == null ? 0 : (float) (s.getY() - Y0 - 1));
		b.putFloat(s == null ? 0 : (float) (s.getZ() - srodekZ(a) - 0.5));
		b.putFloat((float) sv.x);
		b.putFloat((float) sv.y);
		b.putFloat((float) sv.z);
		b.putFloat(s == null ? 0 : 1);                                        // 46 arrow present
		b.putFloat(trafStrzala[i]);                                           // 47 arrow hits this round
		b.putFloat(zablokowane[i]);                                           // 48 hits taken on the shield this round
		b.putFloat(postawione[i]);                                            // 49 blocks placed this round
		// 50–124: blocks around the player in ITS frame (forward f, right r), levels -1 (below the feet), 0, +1
		float yr = p.getYRot() * Mth.DEG_TO_RAD;
		double fx = -Mth.sin(yr), fz = Mth.cos(yr), rx = -Mth.cos(yr), rz = -Mth.sin(yr);
		ServerLevel lv = (ServerLevel) p.level();
		int pol = VOX / 2;
		for (int dy = -1; dy <= 1; dy++)
			for (int f = -pol; f <= pol; f++)
				for (int r = -pol; r <= pol; r++) {
					BlockPos bp = BlockPos.containing(p.getX() + f * fx + r * rx, p.getY() + dy, p.getZ() + f * fz + r * rz);
					b.putFloat(lv.getBlockState(bp).isAir() ? 0 : 1);
				}
		// --- stage 6 (125–248)
		ItemStack s6 = inv.getItem(6);
		b.putFloat(s6.is(Items.LAVA_BUCKET) ? 1 : s6.is(Items.BUCKET) ? 2 : 0);   // 125 bucket: with lava / empty / none
		b.putFloat(ileLeczenia(inv));                                          // 126 healing potions
		ItemStack s8 = inv.getItem(8);
		b.putFloat(s8.isEmpty() ? 0 : mikstura[i]);                           // 127 potion kind in slot 8
		b.putFloat(s8.getCount());                                            // 128 count
		for (Holder<MobEffect> ef : EFEKTY) {                                 // 129–142 effects: level (0 none), seconds
			MobEffectInstance e = p.getEffect(ef);
			b.putFloat(e == null ? 0 : e.getAmplifier() + 1);
			b.putFloat(e == null ? 0 : Math.min(e.getDuration(), 12000) / 20f);
		}
		b.putFloat(p.getRemainingFireTicks() / 20f);                          // 143 how long it will keep burning (s)
		b.putFloat(p.isInLava() ? 1 : 0);                                     // 144 in lava
		b.putFloat(p.isOnFire() ? 1 : 0);                                     // 145 burning (visible from afar)
		for (int k = 0; k < 4; k++) {                                         // 146–149 I can see player k of the arena (no occlusion)
			int j = k < ile[a] ? pocz[a] + k : -1;
			b.putFloat(j < 0 || j == i ? 0 : !wypadl[i] && !wypadl[j] && widzi(p, gracze[j]) ? 1 : 0);
		}
		b.putFloat(wypadl[i] ? 1 : 0);                                        // 150 out of play until the round ends
		// 151–199: surface height in a MAPA×MAPA grid around the player (its frame), relative to its feet;
		// 200–248: lava on the surface of that column
		int pm = MAPA / 2;
		float[] lawy = new float[MAPA * MAPA];
		int stopy = Mth.floor(p.getY() + 0.01);
		for (int f = -pm; f <= pm; f++)
			for (int r = -pm; r <= pm; r++) {
				int x = Mth.floor(p.getX() + f * fx + r * rx), z = Mth.floor(p.getZ() + f * fz + r * rz);
				int wys = -6;
				float lw = 0;
				for (int dy = 2; dy >= -5; dy--) {
					BlockState bs = lv.getBlockState(new BlockPos(x, stopy + dy, z));
					if (bs.isAir() || bs.is(Blocks.FIRE)) continue;
					if (bs.is(Blocks.LAVA)) { lw = 1; wys = dy; break; }
					wys = dy + 1;
					break;
				}
				b.putFloat(wys);
				lawy[(f + pm) * MAPA + (r + pm)] = lw;
			}
		for (float lw : lawy) b.putFloat(lw);
	}

	static int klatkaGracza(ServerPlayer p) {
		for (int j = 0; j < gracze.length; j++) if (gracze[j] == p) return klatka[j];
		return -1;
	}

	static int ile(net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.item.Item it) {
		int n = 0;
		for (int k = 0; k < inv.getContainerSize(); k++) {
			ItemStack st = inv.getItem(k);
			if (st.is(it)) n += st.getCount();
		}
		return n;
	}

	static int ileLeczenia(net.minecraft.world.entity.player.Inventory inv) {
		ItemStack st = inv.getItem(7);
		return st.is(Items.SPLASH_POTION) ? st.getCount() : 0;
	}

	/** Category of the item in hand: 0 nothing, 1 sword, 2 axe, 3 bow, 4 golden apple, 5 steak, 6 fishing rod, 7 blocks, 8 other,
	 *  9 lava bucket, 10 empty bucket, 11 splash potion, 12 drinkable potion. */
	static int kategoria(ItemStack st) {
		if (st.isEmpty()) return 0;
		if (st.is(Items.DIAMOND_SWORD)) return 1;
		if (st.is(Items.DIAMOND_AXE)) return 2;
		if (st.is(Items.BOW)) return 3;
		if (st.is(Items.GOLDEN_APPLE)) return 4;
		if (st.is(Items.COOKED_BEEF)) return 5;
		if (st.is(Items.FISHING_ROD)) return 6;
		if (st.getItem() instanceof BlockItem) return 7;
		if (st.is(Items.LAVA_BUCKET)) return 9;
		if (st.is(Items.BUCKET)) return 10;
		if (st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION)) return 11;
		if (st.is(Items.POTION)) return 12;
		return 8;
	}

	/** Player p's arrow in flight closest to opponent o (the one the opponent sees and dodges). */
	static AbstractArrow strzalaWLocie(ServerPlayer p, ServerPlayer o, int a) {
		AbstractArrow naj = null;
		double d = Double.MAX_VALUE;
		for (AbstractArrow s : p.level().getEntitiesOfClass(AbstractArrow.class, pudloKlatki(a),
				e -> e.getOwner() == p && e.getDeltaMovement().lengthSqr() > 0.01)) {
			double ds = s.distanceToSqr(o);
			if (ds < d) { d = ds; naj = s; }
		}
		return naj;
	}

	/** Whether player p's crosshair is on opponent o within attack reach (like the client raycast). */
	static boolean celuje(ServerPlayer p, ServerPlayer o) {
		Vec3 oko = p.getEyePosition();
		double zasieg = p.entityInteractionRange();
		Vec3 koniec = oko.add(p.getViewVector(1f).scale(zasieg));
		AABB box = o.getBoundingBox().inflate(o.getPickRadius());
		if (box.contains(oko)) return true;
		var traf = box.clip(oko, koniec);
		if (traf.isEmpty()) return false;
		HitResult blok = p.level().clip(new ClipContext(oko, koniec, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return blok.getType() == HitResult.Type.MISS
				|| blok.getLocation().distanceToSqr(oko) > traf.get().distanceToSqr(oko);
	}

	/** Whether some part of q (eyes, middle or feet) is visible from p's eye — terrain can occlude. */
	static boolean widzi(ServerPlayer p, ServerPlayer q) {
		Vec3 oko = p.getEyePosition();
		for (double h : new double[]{q.getEyeHeight(), q.getBbHeight() * 0.5, 0.2}) {
			Vec3 cel = q.position().add(0, h, 0);
			HitResult r = p.level().clip(new ClipContext(oko, cel, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (r.getType() == HitResult.Type.MISS) return true;
		}
		return false;
	}

	static void zastosuj(int i, float[] akc) {
		// NaN in the action = this player is driven by something else (e.g. the ported pvp-bot-fabric in turniej_obcy.py) — leave it alone
		if (Float.isNaN(akc[0])) return;
		ServerPlayer p = gracze[i];
		ServerPlayer o = gracze[cel[i]];
		boolean skok = akc[2] > 0.5f, sprint = akc[3] > 0.5f, cios = akc[4] > 0.5f, kucanie = akc[7] > 0.5f;
		float przod = Mth.clamp(akc[0], -1f, 1f), bok = Mth.clamp(akc[1], -1f, 1f);
		// movement through Carpet's action pack: every tick it writes zza/xxa of fake players itself
		// (a direct write to p.zza would be overwritten with zero); sneaking multiplies speed by 0.3
		EntityPlayerActionPack ap = ((ServerPlayerInterface) p).getActionPack();
		ap.setSneaking(kucanie);
		ap.setForward(przod);
		ap.setStrafing(bok);
		p.setJumping(skok);
		// sprint as in the client: forward only, not sneaking, food > 6
		ap.setSprinting(sprint && przod > 0 && !kucanie && p.getFoodData().getFoodLevel() > 6);
		float yaw = p.getYRot() + Mth.clamp(akc[5], -MAX_OBROT_YAW, MAX_OBROT_YAW);
		float pitch = Mth.clamp(p.getXRot() + Mth.clamp(akc[6], -MAX_OBROT_PITCH, MAX_OBROT_PITCH), -90f, 90f);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		int slot = Math.round(akc[8]);                  // 0 = no change, 1–9 = hotbar slot 0–8
		if (slot >= 1 && slot <= 9 && p.getInventory().getSelectedSlot() != slot - 1) {
			if (p.isUsingItem()) p.releaseUsingItem();
			p.getInventory().setSelectedSlot(slot - 1);
		}
		boolean wrogGra = !wypadl[cel[i]];
		if (VANILLA_KLIK) {
			klikiJakKlient(i, p, o, akc, cios && wrogGra);
			return;
		}
		uzyj(i, p, akc[9] > 0.5f);
		// as in the client: while the player is using something (shield up, eating, drawing a bow), left-click is ignored
		if (cios && !p.isUsingItem()) {
			// like the client: an attack packet (when the crosshair is on the opponent), then an arm swing,
			// which resets the attack bar on the server.
			// akc[4]: 1 = the server computes the crosshair (play without lag), 2 = a lagged client saw a hit
			// (Python checked the crosshair on what the player saw; the server, like an anticheat with ping tolerance,
			// checks reach: 3 blocks + 1, and terrain occlusion), 3 = the client saw a miss (swing only).
			boolean trafia = wrogGra && (akc[4] < 1.5f ? celuje(p, o)
					: akc[4] < 2.5f && p.isWithinEntityInteractionRange(o.getBoundingBox(), 1.0) && widzi(p, o));
			if (trafia) p.attack(o);
			p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			p.resetAttackStrengthTicker();
			p.resetLastActionTime();
		}
	}

	/** Order and delays as in Minecraft.handleKeybinds: while the player is using something, attack clicks are lost
	 *  (and a released right button ends the use); otherwise attack (miss = 10 ticks of attack lockout); then a held right
	 *  button starts a use when nothing is in use and 4 ticks have passed since the previous use. */
	static void klikiJakKlient(int i, ServerPlayer p, ServerPlayer o, float[] akc, boolean cios) {
		boolean chce = akc[9] > 0.5f;
		if (przerwaPrawego[i] > 0) przerwaPrawego[i]--;
		if (pudlo[i] > 0) pudlo[i]--;
		if (p.isUsingItem()) {
			if (!chce) p.releaseUsingItem();
		} else if (cios && pudlo[i] == 0) {
			boolean trafia = akc[4] < 1.5f ? celuje(p, o)
					: akc[4] < 2.5f && p.isWithinEntityInteractionRange(o.getBoundingBox(), 1.0) && widzi(p, o);
			if (trafia) p.attack(o);
			else pudlo[i] = 10;
			p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			p.resetAttackStrengthTicker();
			p.resetLastActionTime();
		}
		if (chce && !p.isUsingItem() && przerwaPrawego[i] == 0) {
			uzyjRaz(i, p);
			przerwaPrawego[i] = 4;
		}
		wcisniety[i] = chce;
	}

	/** One right-button use: a block onto the face under the crosshair or the item in hand (and if the hand does nothing — the shield). */
	static void uzyjRaz(int i, ServerPlayer p) {
		ServerLevel lv = (ServerLevel) p.level();
		ItemStack reka = p.getMainHandItem();
		if (reka.getItem() instanceof BlockItem) {
			HitResult hr = p.pick(p.blockInteractionRange(), 1f, false);
			if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
				int przed = reka.getCount();
				p.gameMode.useItemOn(p, lv, reka, InteractionHand.MAIN_HAND, bhr);
				if (reka.getCount() < przed) postawione[i]++;
			}
		} else {
			var wynik = p.gameMode.useItem(p, lv, reka, InteractionHand.MAIN_HAND);
			if (!wynik.consumesAction() && p.getOffhandItem().is(Items.SHIELD))
				p.gameMode.useItem(p, lv, p.getOffhandItem(), InteractionHand.OFF_HAND);
		}
	}

	/** Right button as in the client: click (rising edge) = use the item in hand, and if the hand does nothing
	 *  (sword, axe, fist) — the shield from the off hand; holding = drawing a bow / eating / drinking / blocking;
	 *  release = shoot / interrupt. Bucket: pours lava onto the block under the crosshair (empty: picks lava up).
	 *  A splash potion is thrown immediately. Blocks: placed on the face under the crosshair, every 4 ticks while held. */
	static void uzyj(int i, ServerPlayer p, boolean chce) {
		ServerLevel lv = (ServerLevel) p.level();
		ItemStack reka = p.getMainHandItem();
		boolean blok = reka.getItem() instanceof BlockItem;
		if (chce) {
			trzyma[i]++;
			if (!wcisniety[i] || (blok && trzyma[i] % 4 == 0)) {
				if (blok) {
					HitResult hr = p.pick(p.blockInteractionRange(), 1f, false);
					if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
						int przed = reka.getCount();
						p.gameMode.useItemOn(p, lv, reka, InteractionHand.MAIN_HAND, bhr);
						if (reka.getCount() < przed) postawione[i]++;
					}
				} else if (!p.isUsingItem()) {
					var wynik = p.gameMode.useItem(p, lv, reka, InteractionHand.MAIN_HAND);
					if (!wynik.consumesAction() && p.getOffhandItem().is(Items.SHIELD))
						p.gameMode.useItem(p, lv, p.getOffhandItem(), InteractionHand.OFF_HAND);
				}
			}
		} else {
			trzyma[i] = 0;
			if (wcisniety[i] && p.isUsingItem()) p.releaseUsingItem();
		}
		wcisniety[i] = chce;
	}

	// ---------------------------------------------------------------- networking

	static ByteBuffer czytaj(int ile) throws IOException {
		if (buforAkcji == null || buforAkcji.capacity() < ile)
			buforAkcji = ByteBuffer.allocate(Math.max(ile, 4096)).order(ByteOrder.LITTLE_ENDIAN);
		buforAkcji.clear();
		wej.readFully(buforAkcji.array(), 0, ile);
		buforAkcji.limit(ile);
		return buforAkcji;
	}

	static void rozlacz() {
		try { if (gniazdo != null) gniazdo.close(); } catch (IOException ignored) {}
		gniazdo = null;
		stan = Stan.CZEKA;
		// without Python nobody may die (pvp-bots keep fighting, and a dead fake player disappears from the server)
		for (ServerPlayer p : gracze)
			if (p != null && !p.isRemoved()) {
				((ServerPlayerInterface) p).getActionPack().stopAll();
				if (p instanceof EntityPlayerMPFake) p.getAbilities().invulnerable = true;
			}
		server.tickRateManager().setTickRate(20f);
	}

	// ---------------------------------------------------------------- human vs network

	static int wykonajWalcz(ServerPlayer p, int ty, int siec) {
		String odp = HASLO != null && !zweryfikowani.contains(p.getUUID()) ? "First /haslo <password>." : walcz(p, ty, siec);
		// straight to the player: sendSuccess is swallowed by the gamerule send_command_feedback=false
		p.sendSystemMessage(Component.literal(odp));
		LOG.info("pvpmost: /walcz from {} -> {}", p.getGameProfile().name(), odp);
		return 1;
	}

	/** /walcz: enter the last 1v1 arena instead of the bot (only on a server at normal speed), change weapons or
	 *  leave. ty/siec = weapon code (0 fists, 1 sword, 2 axe) or -1 = no choice. */
	static String walcz(ServerPlayer p, int ty, int siec) {
		if (p instanceof EntityPlayerMPFake) return "This command is for real players.";
		for (int i = 0; i < czlowiek.length; i++)
			if (czlowiek[i] && gracze[i] == p) {
				if (ty >= 0) {                              // already fighting: only a weapon change, from the next round
					bronCz = ty;
					bronSieciCz = siec;
					resetujArene(klatka[i], 1);
					return "New round: you — " + NAZWY_BRONI[ty] + ", network — " + NAZWY_BRONI[siec] + ".";
				}
				zwolnijSlot(i, true);
				return "You left the arena. Score: " + wygraneCz + " wins, " + przegraneCz + " losses, "
						+ czasCz + " timeouts.";
			}
		if (stan != Stan.GRA || n2 < 3 || tempoGry > 20.5f)
			return "1v1 fights only work on the show server while the show is running.";
		int a = n2 - 1, slot = pocz[a] + 1;
		if (czlowiek[slot]) return "The arena is taken by " + gracze[slot].getGameProfile().name() + ".";
		zaparkowany = gracze[slot];
		zaparkowany.setGameMode(GameType.SPECTATOR);
		zaparkowany.teleportTo(server.overworld(), srodekX(a) + 0.5, Y0 + WYS + 20, srodekZ(a) + 0.5, Set.of(), 0f, 0f, false);
		gracze[slot] = p;
		czlowiek[slot] = true;
		p.setGameMode(GameType.SURVIVAL);
		bronCz = Math.max(0, ty);
		bronSieciCz = siec >= 0 ? siec : bronCz;
		wygraneCz = przegraneCz = czasCz = 0;
		resetujArene(a, 1);
		return "You are fighting the network 1v1! You: " + NAZWY_BRONI[bronCz] + ", network: " + NAZWY_BRONI[bronSieciCz]
				+ ". Rounds last up to 60 s. Change weapons: /walcz <yours> <network's> (piesci/miecz/siekiera = fists/sword/axe). Leave: /walcz";
	}

	static void zwolnijSlot(int slot, boolean doWidzow) {
		ServerPlayer p = gracze[slot];
		czlowiek[slot] = false;
		if (zaparkowany != null && !zaparkowany.isRemoved()) {
			gracze[slot] = zaparkowany;
			zaparkowany.setGameMode(GameType.SURVIVAL);
		}
		zaparkowany = null;
		int a = klatka[slot];
		if (doWidzow && !p.isRemoved()) {
			p.getInventory().clearContent();
			p.setGameMode(GameType.SPECTATOR);
			p.teleportTo(server.overworld(), srodekX(a) + 0.5, Y0 + 10, srodekZ(a) - POL - 6.5, Set.of(), 0f, 35f, false);
		}
		resetujArene(a, 1);
	}

	static void wynikDlaCzlowieka(int a, boolean[] zgloszone) {
		int p0 = pocz[a];
		int ja = czlowiek[p0] ? p0 : p0 + 1, on = ja == p0 ? p0 + 1 : p0;
		String tekst;
		if (zgloszone[ja]) { przegraneCz++; tekst = "Loss — the network took you down."; }
		else if (zgloszone[on]) { wygraneCz++; tekst = "Win! You took the network down."; }
		else { czasCz++; tekst = "Time is up (60 s)."; }
		gracze[ja].sendSystemMessage(Component.literal(tekst + "  Score: " + wygraneCz + " W / " + przegraneCz
				+ " L / " + czasCz + " timeouts"));
	}

	/** Loadout for the round: clears the inventory (armor too) and puts the weapon into the first slot (a new item every
	 *  round = full durability). With a weapon (sword, axe) full diamond armor, with fists — no armor.
	 *  Hotbar: 0 weapon, 1 bow, 2 golden apples, 3 steaks, 4 fishing rod, 5 blocks, 6 lava bucket, 7 healing potions (splash),
	 *  8 a potion from the list; arrows in the backpack; shield in the off hand. */
	static void uzbroj(ServerPlayer p, int kod, int m, int rodzaj) {
		var inv = p.getInventory();
		inv.clearContent();
		if (kod == 1) inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
		else if (kod == 2) inv.setItem(0, new ItemStack(Items.DIAMOND_AXE));
		if ((m & Z_LUK) != 0) { inv.setItem(1, new ItemStack(Items.BOW)); inv.setItem(9, new ItemStack(Items.ARROW, 16)); }
		if ((m & Z_JEDZENIE) != 0) { inv.setItem(2, new ItemStack(Items.GOLDEN_APPLE, 2)); inv.setItem(3, new ItemStack(Items.COOKED_BEEF, 8)); }
		if ((m & Z_WEDKA) != 0) inv.setItem(4, new ItemStack(Items.FISHING_ROD));
		if ((m & Z_BLOKI) != 0) inv.setItem(5, new ItemStack(Items.COBBLESTONE, 32));
		if ((m & Z_LAWA) != 0) inv.setItem(6, new ItemStack(Items.LAVA_BUCKET));
		if ((m & Z_LECZENIE) != 0) {
			ItemStack lecz = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HEALING);
			lecz.set(DataComponents.MAX_STACK_SIZE, 8);
			lecz.setCount(3);
			inv.setItem(7, lecz);
		}
		if ((m & Z_MIKSTURA) != 0 && rodzaj >= 1 && rodzaj <= N_MIKSTUR) inv.setItem(8, zrobMiksture(rodzaj));
		if ((m & Z_TARCZA) != 0) p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		if (kod != 0) {
			p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		}
		inv.setSelectedSlot(0);
		if (!(p instanceof EntityPlayerMPFake))            // real client: also switch its hotbar selection
			p.connection.send(new ClientboundSetHeldSlotPacket(0));
	}
}
