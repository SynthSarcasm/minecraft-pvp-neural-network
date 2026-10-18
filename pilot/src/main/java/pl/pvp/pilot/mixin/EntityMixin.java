package pl.pvp.pilot.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.pvp.pilot.Assistant;

/** During an assistant fight mouse movement does not turn the camera (the network aims); turning the network off returns the mouse immediately. */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void pvppilot$turn(double dx, double dy, CallbackInfo ci) {
		if (Assistant.blokujMysz((Entity) (Object) this)) ci.cancel();
	}
}
