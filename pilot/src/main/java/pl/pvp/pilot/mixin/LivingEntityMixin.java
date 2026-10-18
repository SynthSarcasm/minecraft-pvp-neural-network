package pl.pvp.pilot.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.pvp.pilot.Assistant;

/** The client receives a damage event from the server (with the entity that dealt it) - assistant: whoever hits me becomes the target. */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void pvppilot$zranienie(DamageSource zrodlo, CallbackInfo ci) {
		Assistant.zraniony((LivingEntity) (Object) this, zrodlo);
	}
}
