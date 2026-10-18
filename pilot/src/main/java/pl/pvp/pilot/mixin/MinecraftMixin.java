package pl.pvp.pilot.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.pvp.pilot.Assistant;

/** During an assistant fight an attack only goes through on the target under the crosshair (no misses, no bystanders or blocks). */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void pvppilot$atak(CallbackInfoReturnable<Boolean> cir) {
		if (Assistant.blokujAtak()) cir.setReturnValue(false);
	}
}
