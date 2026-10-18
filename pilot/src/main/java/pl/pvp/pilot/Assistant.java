package pl.pvp.pilot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PvP assistant: the network helps a human play on ANY server (without our mod on the server).
 *
 * Keys (configurable in Options -> Controls -> "PvP network"):
 *   R - network ON / OFF (turning it off immediately hands control back to the human and releases the keys),
 *   V - accept the suggested target (the network starts fighting) / stop the fight,
 *   B - next suggestion (the next player in range).
 * While the network is on, it does not attack anyone by itself: it only suggests a target (the nearest player in range).
 * Exception: a player who damages the user immediately becomes the target (if the network is not fighting anyone).
 *
 * During a fight the mod builds a RAW observation every tick (249 numbers, exactly like PvpBridge.zapiszObs on the training
 * server) from the CLIENT's world - for itself and for the target - and sends it to Python (training/assistant.py). Python
 * sends back an action (10 numbers like ACT), which the mod executes like a human: movement keys, camera turn (≤ 45°/30°
 * per tick), attack click (blocked when the crosshair is not on the target - the network will not hit a bystander by
 * accident), right button, hotbar slots.
 *
 * Messages to Python (the same socket 127.0.0.1:8790 as the pilot mode):
 *   10 + int32 tick + uint8 new fight + float32 ping (ms) + uint16 mask of virtual slots 0-8 + float32[2 × 249]
 *      (row 0 = me, row 1 = the target)
 *   11                       end of fight
 * From Python: 1 = action (as in Pilot), 6 + uint16 + UTF-8 = "network ready" (version description for the overlay).
 */
public class Assistant {
	static final int RAW = 249;
	static final double ZASIEG_SUGESTII = 12.0;      // players farther away are not suggested
	static final double ZASIEG_WALKI = 32.0;         // target farther away = end of fight (the client will lose it soon anyway)
	static final double POL_ARENY = 8.0;             // the virtual arena center follows when the player moves farther than this
	static final float MAX_YAW = 45f, MAX_PITCH = 30f;

	static KeyMapping klWlacz, klCel, klNastepny;
	static volatile boolean wlaczona = false;
	static volatile boolean walka = false;
	static volatile String wersjaSieci = null;
	static UUID celId, sugestiaId;
	static int celEncja = -1;                         // target entity id: a respawned player has the same UUID but a new entity
	static long tpJa = -1000, tpCel = -1000;
	static int niewidocznyTicki = 0;
	static final int MAX_NIEWIDOCZNY = 60;            // 3 s without line of sight = end of fight
	static UUID zakonczonyId = null;                  // target of the last fight: not suggested for a moment
	static long zakonczonyDo = 0;
	static String celNazwa = "", powodWalki = "";
	static String komunikat = "";
	static long komunikatDo = 0;
	static long tick = 0;
	static int tickWalki = 0;
	static boolean nowaWalka = false;
	static double srodekX, srodekZ, podlogaY;       // virtual "arena" center (in training: the center of the 23×23 arena)
	static Vec3 poprzedniaPozCelu = null;
	static long zamachCelu = -1000;                   // tick of the target's last swing (to estimate its attack bar)
	static boolean poprzedniZamachCelu = false;
	static final int[] realny = new int[9];           // virtual network slot -> hotbar slot (-1 = none)
	static int maskaSlotow = 0;
	static long trafionyCelTick = -1000;
	static float yawPrzed, pitchPrzed;                // rotation before the network's decision (for a smooth picture between ticks)
	static boolean obrocono = false;
	static Vec3 poprzedniaPozJa = null;
	static int tickTeleportu = -1000;
	static int rodzajMikstury = 0;
	static int ostatniaSugestiaTick = 0;
	static String atakujacyTez = "";
	static long atakujacyTezDo = 0;

	static void init() {
		KeyMapping.Category kat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pvppilot", "siec"));
		klWlacz = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pvppilot.wlacz", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, kat));
		klCel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pvppilot.cel", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, kat));
		klNastepny = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pvppilot.nastepny", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, kat));
		ClientTickEvents.START_CLIENT_TICK.register(Assistant::poczatekTicku);
		ClientTickEvents.END_CLIENT_TICK.register(Assistant::koniecTicku);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("pvppilot", "nakladka"), Assistant::rysuj);
	}

	static void pokaz(String tekst) {
		komunikat = tekst;
		komunikatDo = System.currentTimeMillis() + 4000;
		Pilot.LOG.info("pvppilot assistant: {}", tekst);
	}


	static void koniecTicku(Minecraft mc) {
		tick++;
		if (obrocono && mc.player != null) {
			// the 26.3 client camera renders a turn as a transition yRotO -> yRot in the frames between ticks; the player tick
			// has already copied the new rotation into yRotO, so restore "before" - the picture flows, logic and packets unchanged
			mc.player.yRotO = yawPrzed;
			mc.player.xRotO = pitchPrzed;
		}
		obrocono = false;
		if (mc.player == null || mc.level == null) {
			if (walka) zakonczWalke("disconnected from the server");
			wlaczona = false;
			return;
		}
		while (klWlacz.consumeClick()) {
			wlaczona = !wlaczona;
			if (!wlaczona) {
				if (walka) zakonczWalke("network turned off");
				pokaz("Network OFF - you are in control");
			} else {
				pokaz(wersjaSieci == null ? "Network on, but Python is not running (start assistant.py)" : "Network ON - pick a target");
			}
		}
		while (klNastepny.consumeClick()) if (wlaczona && !walka) nastepnaSugestia(mc, true);
		while (klCel.consumeClick()) {
			if (!wlaczona) {
				pokaz("Turn the network on first (" + klWlacz.getTranslatedKeyMessage().getString() + ")");
			} else if (walka) {
				zakonczWalke("stopped by you");
			} else {
				Player s = gracz(mc, sugestiaId);
				if (s == null) pokaz("No target in range");
				else zacznijWalke(mc, s, "target accepted");
			}
		}
		if (!wlaczona) return;
		if (!walka) {
			nastepnaSugestia(mc, false);
			return;
		}
		Player cel = gracz(mc, celId);
		LocalPlayer ja = mc.player;
		if (ja.isDeadOrDying()) { zakonczWalke("you died"); return; }
		if (cel == null) {                                       // the server removes a killed player right away - that is how a win is recognised
			zakonczWalke(tick - trafionyCelTick < 10 ? "target defeated! (disappeared right after a hit)" : "target left the range");
			return;
		}
		if (cel.hurtTime > 0) trafionyCelTick = tick;
		if (cel.isDeadOrDying()) { zakonczWalke("target defeated!"); return; }
		if (cel.getId() != celEncja) { zakonczWalke("target died and respawned (new entity)"); return; }
		if (poprzedniaPozJa != null && ja.position().distanceToSqr(poprzedniaPozJa) > 9) tpJa = tick;
		if (poprzedniaPozCelu != null && cel.position().distanceToSqr(poprzedniaPozCelu) > 9) tpCel = tick;
		if (tick - tpJa < 10 && tick - tpCel < 10) {          // both moved at once = end of a round / duel (e.g. the /walcz arena)
			zakonczWalke("end of round (both teleported)");
			return;
		}
		niewidocznyTicki = widzi(ja, cel) ? 0 : niewidocznyTicki + 1;
		if (niewidocznyTicki >= MAX_NIEWIDOCZNY) { zakonczWalke("target out of sight for 3 s"); return; }
		if (cel.isSpectator()) { zakonczWalke("target is a spectator"); return; }
		if (ja.distanceTo(cel) > ZASIEG_WALKI) { zakonczWalke("target ran away"); return; }
		if (wersjaSieci == null || !Pilot.polaczony) { zakonczWalke("Python disconnected"); return; }
		if (mc.gui.screen() != null) { puscKlawisze(mc); return; }   // chat / inventory open: no control
		wyslijObserwacje(mc, ja, cel);
	}

	static Player gracz(Minecraft mc, UUID id) {
		if (id == null || mc.level == null) return null;
		for (Player p : mc.level.players()) if (p.getUUID().equals(id) && !p.isRemoved()) return p;
		return null;
	}

	static List<Player> kandydaci(Minecraft mc) {
		List<Player> l = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (p == mc.player || p.isSpectator() || p.isDeadOrDying() || p.isRemoved()) continue;
			if (p.getUUID().equals(zakonczonyId) && System.currentTimeMillis() < zakonczonyDo) continue;
			if (p.distanceTo(mc.player) <= ZASIEG_SUGESTII) l.add(p);
		}
		l.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
		return l;
	}

	static void nastepnaSugestia(Minecraft mc, boolean dalej) {
		List<Player> l = kandydaci(mc);
		if (l.isEmpty()) { sugestiaId = null; return; }
		int ten = -1;
		for (int i = 0; i < l.size(); i++) if (l.get(i).getUUID().equals(sugestiaId)) ten = i;
		if (dalej) sugestiaId = l.get((ten + 1) % l.size()).getUUID();
		else if (ten < 0) sugestiaId = l.get(0).getUUID();
	}

	static void zacznijWalke(Minecraft mc, Player cel, String powod) {
		if (wersjaSieci == null || !Pilot.polaczony) {
			pokaz("Python is not running - start assistant.py (the network cannot fight)");
			return;
		}
		LocalPlayer ja = mc.player;
		celId = cel.getUUID();
		celEncja = cel.getId();
		tpJa = tpCel = -1000;
		niewidocznyTicki = 0;
		celNazwa = cel.getGameProfile().name();
		powodWalki = powod;
		walka = true;
		nowaWalka = true;
		tickWalki = 0;
		srodekX = (ja.getX() + cel.getX()) / 2;
		srodekZ = (ja.getZ() + cel.getZ()) / 2;
		podlogaY = ja.getY();                                   // "arena floor" = my feet at the start of the fight
		poprzedniaPozCelu = cel.position();
		poprzedniaPozJa = ja.position();
		tickTeleportu = -1000;
		zamachCelu = tick - 1000;
		trafionyCelTick = -1000;
		Pilot.akcje.clear();
		pokaz("FIGHT with " + celNazwa + " (" + powod + ")");
		mapujSloty(ja);                                         // for the log
		StringBuilder sb = new StringBuilder();
		String[] nazwy = {"weapon", "bow", "golden apples", "food", "fishing rod", "blocks", "bucket", "healing", "potion"};
		for (int v = 0; v < 9; v++)
			sb.append(nazwy[v]).append('=').append(realny[v] < 0 ? "-" : (realny[v] + 1) + ":" + ja.getInventory().getItem(realny[v]).getHoverName().getString()).append("  ");
		Pilot.LOG.info("pvppilot assistant: network hotbar -> {} (potion kind {})", sb, rodzajMikstury);
	}

	static void zakonczWalke(String powod) {
		boolean byla = walka;
		walka = false;
		if (celId != null) {
			zakonczonyId = celId;
			zakonczonyDo = System.currentTimeMillis() + 3000;
			if (celId.equals(sugestiaId)) sugestiaId = null;
		}
		celId = null;
		Minecraft mc = Minecraft.getInstance();
		puscKlawisze(mc);
		Pilot.akcje.clear();
		if (byla) {
			Pilot.wyslij(new byte[]{11});
			pokaz("Fight over: " + powod);
		}
	}

	/** A player damaged the user -> automatically the target (when the network is on and not fighting). Called from a mixin. */
	public static void zraniony(LivingEntity kto, DamageSource zrodlo) {
		Minecraft mc = Minecraft.getInstance();
		if (kto != mc.player || !wlaczona) return;
		if (!(zrodlo.getEntity() instanceof Player atak) || atak == mc.player) return;
		if (walka) {
			if (!atak.getUUID().equals(celId)) {
				atakujacyTez = atak.getGameProfile().name();
				atakujacyTezDo = System.currentTimeMillis() + 3000;
			} else {
				zamachCelu = tick;
			}
			return;
		}
		zacznijWalke(mc, atak, "attacked you");
	}

	/** During a fight the network holds the camera: the human's mouse movement does not disturb its aim (called from the Entity.turn mixin). */
	public static boolean blokujMysz(Entity e) {
		return walka && e == Minecraft.getInstance().player;
	}

	/** An attack click during a fight only goes through when the crosshair is on the target (called from the Minecraft.startAttack
	 *  mixin): the network does not hit a bystander by accident, does not mine blocks and does not get the 10-tick miss lockout. */
	public static boolean blokujAtak() {
		if (!walka) return false;
		HitResult hr = Minecraft.getInstance().hitResult;
		return !(hr instanceof EntityHitResult e && e.getEntity().getUUID().equals(celId));
	}

	/** Test without a keyboard (assistant.py --scenariusz): a click through the same key binding a human uses. */
	static void test(int co) {
		Minecraft mc = Minecraft.getInstance();
		KeyMapping k = co == 1 ? klWlacz : co == 2 ? klCel : co == 3 ? klNastepny : null;
		if (k != null) {
			KeyMapping.click(KeyMappingHelper.getBoundKeyOf(k));
			Pilot.LOG.info("pvppilot assistant: test - click {}", k.getTranslatedKeyMessage().getString());
		} else if (co == 5) {
			if (mc.player != null && mc.player.isDeadOrDying()) mc.player.respawn();
		} else if (co == 4) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(),
					m -> Pilot.LOG.info("pvppilot assistant: screenshot {}", m.getString()));
		}
	}


	static void ustaw(Options o, KeyMapping k, boolean d) {
		boolean przelacznik = k instanceof ToggleKeyMapping && (
				(k == o.keyShift && o.toggleCrouch().get()) || (k == o.keySprint && o.toggleSprint().get())
						|| (k == o.keyAttack && o.toggleAttack().get()) || (k == o.keyUse && o.toggleUse().get()));
		if (przelacznik) {                      // in "toggle" mode setDown(true) flips the state
			if (k.isDown() != d) k.setDown(true);
		} else {
			k.setDown(d);
		}
	}

	static void puscKlawisze(Minecraft mc) {
		Options o = mc.options;
		for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyShift, o.keyUse})
			ustaw(o, k, false);
		KeyMapping.setAll();
	}

	static void poczatekTicku(Minecraft mc) {
		if (!walka || mc.player == null || mc.gui.screen() != null) return;
		float[] a = null;
		float dyaw = 0, dpitch = 0;
		boolean cios = false;
		int slot = 0;
		for (float[] x; (x = Pilot.akcje.poll()) != null; ) {
			a = x;
			dyaw += x[5];
			dpitch += x[6];
			cios |= x[4] > 0.5f;
			if (x[8] > 0.5f) slot = Math.round(x[8]);
		}
		if (a == null) return;
		Options o = mc.options;
		LocalPlayer ja = mc.player;
		ustaw(o, o.keyUp, a[0] > 0.5f);
		ustaw(o, o.keyDown, a[0] < -0.5f);
		ustaw(o, o.keyLeft, a[1] > 0.5f);
		ustaw(o, o.keyRight, a[1] < -0.5f);
		ustaw(o, o.keyJump, a[2] > 0.5f);
		ustaw(o, o.keySprint, a[3] > 0.5f);
		ustaw(o, o.keyShift, a[7] > 0.5f);
		if (slot >= 1 && slot <= 9 && realny[slot - 1] >= 0) ja.getInventory().setSelectedSlot(realny[slot - 1]);
		ustaw(o, o.keyUse, a[9] > 0.5f);
		// camera turn like a mouse movement, limited as in training; the server gets it with this tick's regular movement packet
		yawPrzed = ja.getYRot();
		pitchPrzed = ja.getXRot();
		obrocono = true;
		ja.setYRot(ja.getYRot() + Mth.clamp(dyaw, -MAX_YAW, MAX_YAW));
		ja.setXRot(Mth.clamp(ja.getXRot() + Mth.clamp(dpitch, -MAX_PITCH, MAX_PITCH), -90f, 90f));
		if (cios) KeyMapping.click(KeyMappingHelper.getBoundKeyOf(o.keyAttack));
	}


	static int pingMs(Minecraft mc) {
		PlayerInfo pi = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return pi == null ? 0 : Math.max(0, pi.getLatency());
	}

	static void wyslijObserwacje(Minecraft mc, LocalPlayer ja, Player cel) {
		mapujSloty(ja);
		boolean teleport = (poprzedniaPozJa != null && ja.position().distanceToSqr(poprzedniaPozJa) > 9)
				|| (poprzedniaPozCelu != null && cel.position().distanceToSqr(poprzedniaPozCelu) > 9);
		poprzedniaPozJa = ja.position();
		if (teleport && tickWalki - tickTeleportu < 10) teleport = false;   // me and the target moved in adjacent ticks = one reset
		if (teleport) {
			tickTeleportu = tickWalki;
			srodekX = (ja.getX() + cel.getX()) / 2;
			srodekZ = (ja.getZ() + cel.getZ()) / 2;
			podlogaY = ja.getY();
			poprzedniaPozCelu = cel.position();
			zamachCelu = tick - 1000;
			nowaWalka = true;
		}
		// virtual arena: the center follows the player when it moves farther than POL_ARENY (the network knows a 23×23 arena)
		double dx = ja.getX() - srodekX, dz = ja.getZ() - srodekZ;
		if (Math.abs(dx) > POL_ARENY) srodekX += dx - Math.signum(dx) * POL_ARENY;
		if (Math.abs(dz) > POL_ARENY) srodekZ += dz - Math.signum(dz) * POL_ARENY;
		double dy = ja.getY() - podlogaY;                          // training terrain: -4..+5 relative to the floor
		if (dy > 4) podlogaY += dy - 4;
		if (dy < -3) podlogaY += dy + 3;
		boolean zamach = cel.isSwinging();
		if (zamach && !poprzedniZamachCelu) zamachCelu = tick;
		poprzedniZamachCelu = zamach;
		// target velocity from positions, converted like the server physics: horizontal x0.546 on ground / x0.91 in the air,
		// vertical (dy - 0.08) x 0.98
		Vec3 pozCelu = cel.position();
		Vec3 d = poprzedniaPozCelu == null ? Vec3.ZERO : pozCelu.subtract(poprzedniaPozCelu);
		if (d.lengthSqr() > 4) d = Vec3.ZERO;                  // teleport
		double tarcie = cel.onGround() ? 0.546 : 0.91;
		Vec3 vCelu = new Vec3(d.x * tarcie, (d.y - 0.08) * 0.98, d.z * tarcie);
		poprzedniaPozCelu = pozCelu;

		ByteBuffer b = ByteBuffer.allocate(1 + 4 + 1 + 4 + 2 + 2 * RAW * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 10);
		b.putInt(tickWalki++);
		b.put((byte) (nowaWalka ? 1 : 0));
		nowaWalka = false;
		b.putFloat(pingMs(mc));
		b.putShort((short) maskaSlotow);
		zapiszObs(b, mc, ja, cel, ja.getDeltaMovement(), true);
		zapiszObs(b, mc, cel, ja, vCelu, false);
		Pilot.wyslij(b.array());
	}

	static final Holder<MobEffect>[] EFEKTY = efekty();
	@SuppressWarnings("unchecked")
	static Holder<MobEffect>[] efekty() {
		return new Holder[]{MobEffects.STRENGTH, MobEffects.SPEED, MobEffects.FIRE_RESISTANCE, MobEffects.REGENERATION,
				MobEffects.POISON, MobEffects.SLOWNESS, MobEffects.WEAKNESS};
	}

	/** One RAW row - fields and order as in PvpBridge.zapiszObs (described in training/bridge.py). "ja" = full knowledge
	 *  about oneself; about the target (ja == false) only what the client sees: position, movement, angles, HP from entity data,
	 *  item in hand, blocking / drawing / eating, swing (estimated bar), bobber, arrow in flight, fire, lava. */
	static void zapiszObs(ByteBuffer b, Minecraft mc, Player p, Player o, Vec3 v, boolean ja) {
		Level lv = p.level();
		b.putFloat((float) (p.getX() - srodekX));
		b.putFloat((float) Mth.clamp(p.getY() - podlogaY, -6, 8));
		b.putFloat((float) (p.getZ() - srodekZ));
		b.putFloat((float) v.x);
		b.putFloat((float) v.y);
		b.putFloat((float) v.z);
		b.putFloat(Mth.wrapDegrees(p.getYRot()));
		b.putFloat(p.getXRot());
		b.putFloat(p.getHealth());
		b.putFloat(ja ? p.getFoodData().getFoodLevel() : 20);
		b.putFloat(ja ? p.getFoodData().getSaturationLevel() : 5);
		b.putFloat(ja ? p.getAttackStrengthScale(0.5f) : paskCelu(p));
		b.putFloat(p.hurtTime);
		b.putFloat(0);   // 13 invulnerability: the training server always reported 0
		b.putFloat(p.onGround() ? 1 : 0);
		b.putFloat(p.isSprinting() ? 1 : 0);
		b.putFloat(ja ? celownikNaCelu(mc) : celuje(p, o));
		b.putFloat(p.isDeadOrDying() ? 1 : 0);
		b.putFloat(p.distanceTo(o));
		b.putFloat(p.isShiftKeyDown() ? 1 : 0);
		b.putFloat(0);                                                          // 20 "human" (not used by the network)
		int bron = ja ? kodBroni(realny[0] >= 0 ? p.getInventory().getItem(realny[0]) : ItemStack.EMPTY) : kodBroni(p.getMainHandItem());
		b.putFloat(bron);
		b.putFloat(p.getArmorValue());
		// --- items (23-49)
		Inventory inv = p.getInventory();
		b.putFloat(ja ? slotWirtualny(inv.getSelectedSlot()) : 0);
		b.putFloat(kategoria(p.getMainHandItem()));
		b.putFloat(p.isUsingItem() ? 1 : 0);
		b.putFloat(p.isUsingItem() ? p.getTicksUsingItem() : 0);
		b.putFloat(p.isBlocking() ? 1 : 0);
		ItemStack tarcza = p.getOffhandItem();
		b.putFloat(ja && tarcza.is(Items.SHIELD) ? p.getCooldowns().getCooldownPercent(tarcza, 0f) : 0);
		b.putFloat(ja ? Math.min(16, ile(inv, Items.ARROW) + ile(inv, Items.SPECTRAL_ARROW) + ile(inv, Items.TIPPED_ARROW)) : 0);
		b.putFloat(ja ? Math.min(2, ileW(inv, 2)) : 0);
		b.putFloat(ja ? Math.min(8, ileW(inv, 3)) : 0);
		b.putFloat(ja ? Math.min(32, ileW(inv, 5)) : 0);
		b.putFloat(ja ? zestaw(p) : 0);
		b.putFloat(p.getAbsorptionAmount());
		b.putFloat(ja && p.hasEffect(MobEffects.REGENERATION) ? 1 : 0);
		FishingHook hak = p.fishing;
		Entity zaczep = hak == null ? null : hak.getHookedIn();
		b.putFloat(hak == null ? 0 : zaczep == o ? 2 : 1);
		b.putFloat(hak == null ? 0 : (float) (hak.getX() - srodekX));
		b.putFloat(hak == null ? 0 : (float) Mth.clamp(hak.getY() - podlogaY, -6, 12));
		b.putFloat(hak == null ? 0 : (float) (hak.getZ() - srodekZ));
		AbstractArrow s = strzalaWLocie(p, o);
		Vec3 sv = s == null ? Vec3.ZERO : s.getDeltaMovement();
		b.putFloat(s == null ? 0 : (float) (s.getX() - srodekX));
		b.putFloat(s == null ? 0 : (float) Mth.clamp(s.getY() - podlogaY, -6, 12));
		b.putFloat(s == null ? 0 : (float) (s.getZ() - srodekZ));
		b.putFloat((float) sv.x);
		b.putFloat((float) sv.y);
		b.putFloat((float) sv.z);
		b.putFloat(s == null ? 0 : 1);
		b.putFloat(0);                                                          // 47-49 round counters (unused)
		b.putFloat(0);
		b.putFloat(0);
		// 50-124: blocks around the player in ITS frame (forward f, right r), levels -1, 0, +1
		float yr = p.getYRot() * Mth.DEG_TO_RAD;
		double fx = -Mth.sin(yr), fz = Mth.cos(yr), rx = -Mth.cos(yr), rz = -Mth.sin(yr);
		for (int dy = -1; dy <= 1; dy++)
			for (int f = -2; f <= 2; f++)
				for (int r = -2; r <= 2; r++) {
					BlockPos bp = BlockPos.containing(p.getX() + f * fx + r * rx, p.getY() + dy, p.getZ() + f * fz + r * rz);
					b.putFloat(lv.getBlockState(bp).isAir() ? 0 : 1);
				}
		// --- stage 6 (125-248)
		ItemStack s6 = ja && realny[6] >= 0 ? inv.getItem(realny[6]) : ItemStack.EMPTY;
		b.putFloat(s6.is(Items.LAVA_BUCKET) ? 1 : s6.is(Items.BUCKET) ? 2 : 0);
		b.putFloat(ja ? Math.min(3, ileW(inv, 7)) : 0);
		b.putFloat(ja && realny[8] >= 0 ? rodzajMikstury : 0);
		b.putFloat(ja ? Math.min(2, ileW(inv, 8)) : 0);
		for (Holder<MobEffect> ef : EFEKTY) {                                   // effects: known for oneself, not for the target
			MobEffectInstance e = ja ? p.getEffect(ef) : null;
			b.putFloat(e == null ? 0 : e.getAmplifier() + 1);
			b.putFloat(e == null ? 0 : Math.min(e.getDuration(), 12000) / 20f);
		}
		b.putFloat(p.getRemainingFireTicks() > 0 ? p.getRemainingFireTicks() / 20f : p.isOnFire() ? 1 : 0);
		b.putFloat(p.isInLava() ? 1 : 0);
		b.putFloat(p.isOnFire() ? 1 : 0);
		// 146-149 I see player k of the "arena": k = 0 is me, k = 1 the target (player 0 = me, player 1 = the target)
		boolean widac = widzi(p, o);
		b.putFloat(ja ? 0 : widac ? 1 : 0);
		b.putFloat(ja ? (widac ? 1 : 0) : 0);
		b.putFloat(0);
		b.putFloat(0);
		b.putFloat(0);                                                          // 150 out of play
		// 151-199 surface height 7×7 around the player (its frame) relative to the feet, 200-248 lava
		float[] lawy = new float[49];
		int stopy = Mth.floor(p.getY() + 0.01);
		for (int f = -3; f <= 3; f++)
			for (int r = -3; r <= 3; r++) {
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
				lawy[(f + 3) * 7 + (r + 3)] = lw;
			}
		for (float lw : lawy) b.putFloat(lw);
	}

	/** The target's attack bar (unknown to the client): time since its last swing / its attack speed from the weapon in hand. */
	static float paskCelu(Player o) {
		ItemStack st = o.getMainHandItem();
		double tempo = st.is(ItemTags.SWORDS) ? 1.6 : st.is(ItemTags.AXES) ? 1.0 : st.is(Items.TRIDENT) ? 1.1
				: st.is(Items.MACE) ? 0.6 : st.is(ItemTags.SPEARS) ? 1.0 : 4.0;
		double cykl = 20.0 / tempo;
		return (float) Mth.clamp((tick - zamachCelu + 0.5) / cykl, 0, 1);
	}

	static float celownikNaCelu(Minecraft mc) {
		HitResult hr = mc.hitResult;
		return hr instanceof EntityHitResult e && e.getEntity().getUUID().equals(celId) ? 1 : 0;
	}

	/** Whether player p's crosshair points at o within the 3-block attack reach (like PvpBridge.celuje) - for a target looking at me. */
	static float celuje(Player p, Player o) {
		Vec3 oko = p.getEyePosition();
		Vec3 koniec = oko.add(p.getViewVector(1f).scale(3.0));
		AABB box = o.getBoundingBox().inflate(o.getPickRadius());
		if (box.contains(oko)) return 1;
		var traf = box.clip(oko, koniec);
		if (traf.isEmpty()) return 0;
		HitResult blok = p.level().clip(new ClipContext(oko, koniec, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return blok.getType() == HitResult.Type.MISS || blok.getLocation().distanceToSqr(oko) > traf.get().distanceToSqr(oko) ? 1 : 0;
	}

	static boolean widzi(Player p, Player q) {
		Vec3 oko = p.getEyePosition();
		for (double h : new double[]{q.getEyeHeight(), q.getBbHeight() * 0.5, 0.2}) {
			HitResult r = p.level().clip(new ClipContext(oko, q.position().add(0, h, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (r.getType() == HitResult.Type.MISS) return true;
		}
		return false;
	}

	static AbstractArrow strzalaWLocie(Player p, Player o) {
		AbstractArrow naj = null;
		double d = Double.MAX_VALUE;
		for (AbstractArrow s : p.level().getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(32),
				e -> e.getOwner() == p && e.getDeltaMovement().lengthSqr() > 0.01)) {
			double ds = s.distanceToSqr(o);
			if (ds < d) { d = ds; naj = s; }
		}
		return naj;
	}


	/** Item category as in PvpBridge.kategoria, but for arbitrary items on someone else's server. */
	static int kategoria(ItemStack st) {
		if (st.isEmpty()) return 0;
		if (st.is(ItemTags.SWORDS)) return 1;
		if (st.is(ItemTags.AXES)) return 2;
		if (st.is(Items.BOW)) return 3;
		if (st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) return 4;
		if (st.has(DataComponents.FOOD)) return 5;
		if (st.is(Items.FISHING_ROD)) return 6;
		if (st.getItem() instanceof BlockItem) return 7;
		if (st.is(Items.LAVA_BUCKET)) return 9;
		if (st.is(Items.BUCKET)) return 10;
		if (st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION)) return 11;
		if (st.is(Items.POTION)) return 12;
		return 8;
	}

	static int kodBroni(ItemStack st) {
		return st.is(ItemTags.SWORDS) ? 1 : st.is(ItemTags.AXES) ? 2 : 0;
	}

	/** Potion kind as in training (1-10) or 0 = does not match; splash healing = -1 (separate slot 7). */
	static int rodzaj(ItemStack st) {
		boolean rzucana = st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION);
		if (!rzucana && !st.is(Items.POTION)) return 0;
		PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
		if (pc == null) return 0;
		for (MobEffectInstance e : pc.getAllEffects()) {
			Holder<MobEffect> ef = e.getEffect();
			if (ef.equals(MobEffects.INSTANT_HEALTH)) return rzucana ? -1 : 0;
			if (ef.equals(MobEffects.STRENGTH)) return rzucana ? 5 : 1;
			if (ef.equals(MobEffects.SPEED)) return rzucana ? 6 : 2;
			if (ef.equals(MobEffects.FIRE_RESISTANCE)) return rzucana ? 0 : 3;
			if (ef.equals(MobEffects.REGENERATION)) return rzucana ? 0 : 4;
			if (!rzucana) continue;
			if (ef.equals(MobEffects.INSTANT_DAMAGE)) return 7;
			if (ef.equals(MobEffects.POISON)) return 8;
			if (ef.equals(MobEffects.SLOWNESS)) return 9;
			if (ef.equals(MobEffects.WEAKNESS)) return 10;
		}
		return 0;
	}

	/** The network knows the training hotbar: 0 weapon, 1 bow, 2 golden apples, 3 food, 4 fishing rod, 5 blocks, 6 lava bucket,
	 *  7 splash healing potions, 8 another potion. On someone else's server these items are looked up on the real hotbar
	 *  (slots 1-9) and mapped; a slot that is missing cannot be picked by the network (Python masks that choice). */
	static void mapujSloty(Player p) {
		Inventory inv = p.getInventory();
		java.util.Arrays.fill(realny, -1);
		boolean[] zajety = new boolean[9];
		int wybrany = inv.getSelectedSlot();
		ItemStack w = inv.getItem(wybrany);
		if (kodBroni(w) > 0) realny[0] = wybrany;
		for (int k = 0; k < 9 && realny[0] < 0; k++) if (inv.getItem(k).is(ItemTags.AXES)) realny[0] = k;
		for (int k = 0; k < 9 && realny[0] < 0; k++) if (inv.getItem(k).is(ItemTags.SWORDS)) realny[0] = k;
		for (int k = 0; k < 9 && realny[0] < 0; k++) if (inv.getItem(k).isEmpty()) realny[0] = k;   // fists
		if (realny[0] < 0) realny[0] = wybrany;
		zajety[realny[0]] = true;
		boolean strzaly = ile(inv, Items.ARROW) + ile(inv, Items.SPECTRAL_ARROW) + ile(inv, Items.TIPPED_ARROW) > 0;
		int najMikstura = 0, najPriorytet = 99;
		int[] priorytet = {0, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6};                // 1..10 -> order (offensive first)
		for (int k = 0; k < 9; k++) {
			if (zajety[k]) continue;
			ItemStack st = inv.getItem(k);
			int v = -1;
			if (st.is(Items.BOW) && strzaly) v = 1;
			else if (st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) v = 2;
			else if (st.has(DataComponents.FOOD) && !st.is(Items.CHORUS_FRUIT)) v = 3;
			else if (st.is(Items.FISHING_ROD)) v = 4;
			else if (st.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().isSolid()) v = 5;
			else if (st.is(Items.LAVA_BUCKET) || st.is(Items.BUCKET)) {
				v = 6;
				if (realny[6] >= 0 && st.is(Items.LAVA_BUCKET) && !inv.getItem(realny[6]).is(Items.LAVA_BUCKET)) {
					zajety[realny[6]] = false;      // a lava bucket takes precedence over an empty one
					realny[6] = -1;
				}
			}
			else {
				int r = rodzaj(st);
				if (r == -1) v = 7;
				else if (r > 0) {
					int pr = 0;
					for (int q = 0; q < priorytet.length; q++) if (priorytet[q] == r) pr = q;
					if (realny[8] < 0 || pr < najPriorytet) {
						if (realny[8] >= 0) zajety[realny[8]] = false;
						realny[8] = k;
						najPriorytet = pr;
						najMikstura = r;
						zajety[k] = true;
					}
					continue;
				}
			}
			if (v > 0 && realny[v] < 0) {
				realny[v] = k;
				zajety[k] = true;
			}
		}
		rodzajMikstury = najMikstura;
		maskaSlotow = 0;
		for (int v = 0; v < 9; v++) if (realny[v] >= 0) maskaSlotow |= 1 << v;
	}

	static int slotWirtualny(int realnySlot) {
		for (int v = 0; v < 9; v++) if (realny[v] == realnySlot) return v;
		return 0;
	}

	static int ileW(Inventory inv, int v) {
		return realny[v] < 0 ? 0 : inv.getItem(realny[v]).getCount();
	}

	static int zestaw(Player p) {
		int m = 0;
		if (realny[1] >= 0) m |= 1;
		if (realny[2] >= 0 || realny[3] >= 0) m |= 2;
		if (p.getOffhandItem().is(Items.SHIELD)) m |= 4;
		if (realny[4] >= 0) m |= 8;
		if (realny[5] >= 0) m |= 16;
		if (realny[6] >= 0) m |= 32;
		if (realny[7] >= 0) m |= 64;
		if (realny[8] >= 0) m |= 128;
		return m;
	}

	static int ile(Inventory inv, net.minecraft.world.item.Item it) {
		int n = 0;
		for (int k = 0; k < inv.getContainerSize(); k++) {
			ItemStack st = inv.getItem(k);
			if (st.is(it)) n += st.getCount();
		}
		return n;
	}


	static void rysuj(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		List<String> linie = new ArrayList<>();
		List<Integer> kolory = new ArrayList<>();
		String kW = klWlacz.getTranslatedKeyMessage().getString(), kC = klCel.getTranslatedKeyMessage().getString(),
				kN = klNastepny.getTranslatedKeyMessage().getString();
		if (!wlaczona) {
			linie.add("PvP network: OFF  [" + kW + "]");
			kolory.add(0xFFFF5555);
		} else {
			linie.add("PvP network: ON  [" + kW + " = turn off]");
			kolory.add(0xFF55FF55);
			if (wersjaSieci == null || !Pilot.polaczony) {
				linie.add("No Python: start assistant.py");
				kolory.add(0xFFFFAA00);
			}
			if (walka) {
				Player c = gracz(mc, celId);
				String d = c == null ? "?" : String.format("%.1f", mc.player.distanceTo(c));
				linie.add("FIGHT: " + celNazwa + "  " + d + " m  [" + kC + " = stop]");
				kolory.add(0xFFFF5555);
				linie.add("(" + powodWalki + ", ping " + pingMs(mc) + " ms)");
				kolory.add(0xFFAAAAAA);
				if (System.currentTimeMillis() < atakujacyTezDo) {
					linie.add("Also attacking you: " + atakujacyTez);
					kolory.add(0xFFFFAA00);
				}
			} else {
				Player s = gracz(mc, sugestiaId);
				if (s == null) {
					linie.add(System.currentTimeMillis() < zakonczonyDo ? "No target (previous target released)"
							: "No target (nobody within " + (int) ZASIEG_SUGESTII + " m)");
					kolory.add(0xFFAAAAAA);
				} else {
					linie.add("Suggestion: " + s.getGameProfile().name() + "  " + String.format("%.1f", mc.player.distanceTo(s))
							+ " m  [" + kC + " = fight, " + kN + " = next]");
					kolory.add(0xFFFFFF55);
				}
			}
		}
		if (System.currentTimeMillis() < komunikatDo) {
			linie.add(komunikat);
			kolory.add(0xFFFFFFFF);
		}
		int szer = 0;
		for (String l : linie) szer = Math.max(szer, mc.font.width(l));
		int h = linie.size() * 10;
		g.fill(2, 2, 8 + szer, 6 + h, 0x90000000);
		for (int i = 0; i < linie.size(); i++) g.text(mc.font, linie.get(i), 5, 5 + i * 10, kolory.get(i), true);
	}
}
