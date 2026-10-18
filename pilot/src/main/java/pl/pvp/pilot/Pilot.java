package pl.pvp.pilot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Mth;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pilot: the network drives a real Minecraft client.
 *
 * Python (show.py --pilot, assistant.py) connects to 127.0.0.1:8790 and sends messages:
 *   1 + 10 × float32 (LE)  action for a tick: forward, strafe (+1 = left), jump, sprint, attack, yaw turn, pitch turn,
 *                          sneak, slot (0 = no change, 1–6 = slot 0–5), right button (0/1, pressed state)
 *   2 + uint16 + UTF-8     start recording the screen to an mp4 file (ffmpeg, constant 60 fps)
 *   3                      stop recording
 *   4 + uint16 + UTF-8     chat command to send (e.g. "haslo …", "walcz siekiera siekiera")
 *   5 + uint16 + UTF-8     camera mode (through the eyes of the player with this name)
 *   6 + uint16 + UTF-8     assistant: Python with the network is ready (assistant.py) — see Assistant.java
 *   7 + uint8              assistant test: 1 = on/off key, 2 = fight/stop, 3 = next target, 4 = screenshot, 5 = respawn
 * Actions are turned into what a human does: movement keys, left button as a click (the client itself
 * checks the crosshair and ignores the attack while the player is using something), right button held, hotbar slots, camera turns.
 */
public class Pilot implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("pvppilot");
	static final int PORT = 8790;
	static final int FPS = 60;
	static final ConcurrentLinkedQueue<float[]> akcje = new ConcurrentLinkedQueue<>();
	static final ConcurrentLinkedQueue<String> komendy = new ConcurrentLinkedQueue<>();
	static volatile String nagrajDo = null;      // file to start recording to (set by the socket thread)
	static volatile boolean zakoncz = false;
	static volatile String kamera = null;          // camera mode: name of the player whose eyes we look through
	static boolean poprzedniZamach = false;
	static int slotBroni = 0;
	static volatile boolean polaczony = false;   // Python (show.py --pilot / assistant.py) connected to the socket
	static volatile OutputStream doPythona = null;
	static Process ffmpeg;
	static OutputStream doFfmpeg;
	static long startNs, zapisane;
	static int szer, wys;
	static final ExecutorService zapis = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "pvppilot-zapis");
		t.setDaemon(true);
		return t;
	});

	@Override
	public void onInitializeClient() {
		Thread t = new Thread(Pilot::nasluch, "pvppilot-gniazdo");
		t.setDaemon(true);
		t.start();
		ClientTickEvents.END_CLIENT_TICK.register(Pilot::tick);
		Assistant.init();
		ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> {
			mc.options.pauseOnLostFocus = false;
			try {                                              // show-server password (file in the client directory)
				java.nio.file.Path plik = java.nio.file.Path.of("pilot_haslo.txt");
				if (java.nio.file.Files.exists(plik)) komendy.add("haslo " + java.nio.file.Files.readString(plik).trim());
			} catch (IOException ignored) {
			}
		});
		LOG.info("pvppilot: waiting for the network on port {}", PORT);
	}

	static void nasluch() {
		try (ServerSocket ss = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())) {
			while (true) {
				try (Socket s = ss.accept()) {
					s.setTcpNoDelay(true);
					LOG.info("pvppilot: network connected");
					doPythona = s.getOutputStream();
					polaczony = true;
					DataInputStream in = new DataInputStream(s.getInputStream());
					byte[] buf = new byte[40];
					while (true) {
						int typ = in.read();
						if (typ < 0) break;
						if (typ == 1) {
							in.readFully(buf);
							ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
							float[] a = new float[10];
							for (int i = 0; i < 10; i++) a[i] = b.getFloat();
							akcje.add(a);
							// turn immediately (without waiting for the client tick) and send it to the server right away: the server
							// always sees it on the next tick — a constant 1-tick delay instead of a jittery 1–2 (steadier aiming)
							// (the assistant turns by itself at the start of the client tick — with the regular movement packet, no extra packets)
							if (!Assistant.wlaczona) Minecraft.getInstance().execute(() -> obroc(a[5], a[6]));
						} else if (typ == 2 || typ == 4) {
							byte[] t = new byte[in.readUnsignedShort()];
							in.readFully(t);
							String tekst = new String(t, StandardCharsets.UTF_8);
							if (typ == 2) nagrajDo = tekst;
							else komendy.add(tekst);
						} else if (typ == 5) {
							byte[] t = new byte[in.readUnsignedShort()];
							in.readFully(t);
							kamera = new String(t, StandardCharsets.UTF_8);
							LOG.info("pvppilot: camera on {}", kamera);
						} else if (typ == 3) {
							zakoncz = true;
						} else if (typ == 7) {                      // test: "press" an assistant key / take a screenshot
							int co = in.read();
							Minecraft.getInstance().execute(() -> Assistant.test(co));
						} else if (typ == 6) {                      // assistant: Python with the network is ready (version description)
							byte[] t = new byte[in.readUnsignedShort()];
							in.readFully(t);
							Assistant.wersjaSieci = new String(t, StandardCharsets.UTF_8);
							LOG.info("pvppilot: assistant — network ready: {}", Assistant.wersjaSieci);
						}
					}
				} catch (IOException e) {
					LOG.warn("pvppilot: connection dropped ({})", e.toString());
				}
				polaczony = false;
				doPythona = null;
				Assistant.wersjaSieci = null;
				akcje.clear();
				zakoncz = true;
			}
		} catch (IOException e) {
			LOG.error("pvppilot: cannot open port {}", PORT, e);
		}
	}

	/** Camera mode: the camera is in the eyes of a fighting bot; the client's own hand mirrors what the bot holds and does
	 *  (item, shield, drawing/eating, swings), and the hotbar and hearts show its state. The server sees us as a spectator. */
	static void kamerzysta(Minecraft mc, String nazwa) {
		net.minecraft.world.entity.player.Player cel = null;
		for (var gr : mc.level.players())
			if (gr.getGameProfile().name().equalsIgnoreCase(nazwa)) cel = gr;
		if (cel == null) return;
		var ja = mc.player;
		if (mc.getCameraEntity() != cel) mc.setCameraEntity(cel);
		if (mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL)
			mc.gameMode.setLocalMode(net.minecraft.world.level.GameType.SURVIVAL);
		ja.getAbilities().flying = true;
		ja.getAbilities().mayfly = true;
		ja.setDeltaMovement(0, 0, 0);
		// inventory as in the arena: 0 weapon, 1 bow, 2 golden apples, 3 steaks, 4 fishing rod, 5 cobblestone; the bot's held item picks the slot
		var inv = ja.getInventory();
		var reka = cel.getMainHandItem();
		int slot = 0;
		if (reka.is(net.minecraft.world.item.Items.BOW)) slot = 1;
		else if (reka.is(net.minecraft.world.item.Items.GOLDEN_APPLE)) slot = 2;
		else if (reka.is(net.minecraft.world.item.Items.COOKED_BEEF)) slot = 3;
		else if (reka.is(net.minecraft.world.item.Items.FISHING_ROD)) slot = 4;
		else if (reka.is(net.minecraft.world.item.Items.COBBLESTONE)) slot = 5;
		if (slot == 0 && !reka.isEmpty()) inv.setItem(0, reka.copy());
		if (inv.getItem(1).isEmpty()) {
			inv.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
			inv.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 2));
			inv.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF, 8));
			inv.setItem(4, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FISHING_ROD));
			inv.setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 32));
		}
		if (slot != 0) inv.setItem(slot, reka.copy());
		inv.setSelectedSlot(slot);
		// the hotbar at the bottom shows the observed player's inventory — the client does not know it, so we fill it in
		var invCel = cel.getInventory();
		for (int k = 0; k < 6; k++) if (k != slot) invCel.setItem(k, inv.getItem(k).copy());
		invCel.setSelectedSlot(slot);
		for (var es : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.OFFHAND,
				net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET})
			ja.setItemSlot(es, cel.getItemBySlot(es).copy());
		ja.setHealth(Math.max(1f, cel.getHealth()));
		ja.setAbsorptionAmount(cel.getAbsorptionAmount());
		// using (shield / bow / food) and swings
		if (cel.isUsingItem()) {
			if (!ja.isUsingItem() || ja.getUsedItemHand() != cel.getUsedItemHand()) ja.startUsingItem(cel.getUsedItemHand());
		} else if (ja.isUsingItem()) {
			ja.stopUsingItem();
		}
		boolean zamach = cel.isSwinging();
		if (zamach && !poprzedniZamach) {
			ja.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
			ja.resetAttackStrengthTicker();
		}
		poprzedniZamach = zamach;
	}

	static void tick(Minecraft mc) {
		if (mc.player == null) return;                        // commands wait until the player is on the server
		String k = komendy.poll();                            // one per tick, so the server can process them
		if (k != null) mc.player.connection.sendCommand(k);
		String kam = kamera;
		if (kam != null && !kam.isEmpty() && mc.level != null) {
			kamerzysta(mc, kam);
			return;
		}
		if (Assistant.wlaczona) return;                        // network actions are executed by the assistant (Assistant.poczatekTicku)
		float[] a = null;
		float dyaw = 0, dpitch = 0;
		boolean cios = false;
		int slot = 0;
		for (float[] x; (x = akcje.poll()) != null; ) {      // usually one per tick; if they pile up, turns are summed
			a = x;
			dyaw += x[5];
			dpitch += x[6];
			cios |= x[4] > 0.5f;
			if (x[8] > 0.5f) slot = Math.round(x[8]);
		}
		if (a == null) return;
		Options o = mc.options;
		o.keyUp.setDown(a[0] > 0.5f);
		o.keyDown.setDown(a[0] < -0.5f);
		o.keyLeft.setDown(a[1] > 0.5f);
		o.keyRight.setDown(a[1] < -0.5f);
		o.keyJump.setDown(a[2] > 0.5f);
		o.keySprint.setDown(a[3] > 0.5f);
		o.keyShift.setDown(a[7] > 0.5f);
		if (slot >= 1 && slot <= 6) mc.player.getInventory().setSelectedSlot(slot - 1);
		o.keyUse.setDown(a[9] > 0.5f);
		if (cios) KeyMapping.click(o.keyAttack.getDefaultKey());
	}

	/** Message to Python (assistant); a dropped connection is detected by the socket thread. */
	static void wyslij(byte[] dane) {
		OutputStream o = doPythona;
		if (o == null) return;
		try {
			synchronized (Pilot.class) {
				o.write(dane);
				o.flush();
			}
		} catch (IOException e) {
			LOG.warn("pvppilot: cannot send to Python ({})", e.toString());
		}
	}

	static void obroc(float dyaw, float dpitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) return;
		mc.player.setYRot(mc.player.getYRot() + dyaw);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dpitch, -90f, 90f));
		mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
				mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
	}

	/** After every rendered frame (mixin in GameRenderer.render): the image goes to ffmpeg at a constant 60 fps. */
	public static void poKlatce() {
		Minecraft mc = Minecraft.getInstance();
		if (zakoncz) {
			zakoncz = false;
			zakonczNagrywanie();
		}
		String plik = nagrajDo;
		if (plik != null && ffmpeg == null) {
			nagrajDo = null;
			RenderTarget rt = mc.gameRenderer.mainRenderTarget();
			szer = rt.width;
			wys = rt.height;
			try {
				ffmpeg = new ProcessBuilder("/opt/homebrew/bin/ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo",
						"-pix_fmt", "rgba", "-s", szer + "x" + wys, "-r", String.valueOf(FPS), "-i", "-",
						"-vf", "scale=1280:-2,format=yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", plik)
						.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				doFfmpeg = ffmpeg.getOutputStream();
				startNs = System.nanoTime();
				zapisane = 0;
				LOG.info("pvppilot: recording {}x{} -> {}", szer, wys, plik);
			} catch (IOException e) {
				LOG.error("pvppilot: ffmpeg failed to start", e);
				ffmpeg = null;
			}
		}
		if (ffmpeg == null) return;
		long cel = (System.nanoTime() - startNs) * FPS / 1_000_000_000L + 1;   // how many frames there should be by now
		if (cel <= zapisane) return;                                          // game faster than 60 fps: skip
		final int ile = (int) Math.min(cel - zapisane, 10);                   // slower: repeat the frame
		zapisane = cel;
		RenderTarget rt = mc.gameRenderer.mainRenderTarget();
		if (rt.width != szer || rt.height != wys) return;                      // the window was resized meanwhile
		final OutputStream wyj = doFfmpeg;
		Screenshot.takeScreenshot(rt, img -> {
			byte[] px = new byte[szer * wys * 4];
			img.getPixelBytes().duplicate().get(px);
			img.close();
			zapis.submit(() -> {
				try {
					for (int i = 0; i < ile; i++) wyj.write(px);
				} catch (IOException ignored) {
				}
			});
		});
	}

	static void zakonczNagrywanie() {
		if (ffmpeg == null) return;
		final Process p = ffmpeg;
		final OutputStream wyj = doFfmpeg;
		ffmpeg = null;
		doFfmpeg = null;
		zapis.submit(() -> {
			try {
				wyj.close();
				p.waitFor();
				LOG.info("pvppilot: recording saved");
			} catch (Exception e) {
				LOG.warn("pvppilot: problem while closing the recording", e);
			}
		});
	}
}
