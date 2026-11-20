package cn.bfm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TaCZ's own crawl key is replaced by the stance key.  Without this, pressing Z
 * would toggle TaCZ crawl and this mod's prone at the same time.
 *
 * <p>TaCZ 1.2.0 (Forge 1.20.1) routes the key press ({@code onCrawlPress()}), the controller button
 * ({@code onCrawlControllerPress}) and the hold-to-crawl tick ({@code onCrawlHoldingInput}) through the private
 * {@code onCrawlInput(boolean)}; the 1.21.1 build had a single {@code onCrawlPress(InputEvent.Key)}. Cancelling the
 * shared input method covers all three. Returning false reports the input as not handled, as TaCZ does when crawl is
 * unavailable.</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.input.CrawlKey", remap = false)
public abstract class TaczCrawlKeyMixin {
    @Inject(method = "onCrawlInput(Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void battlefieldMovement$ownStanceKey(boolean press, CallbackInfoReturnable<Boolean> ci) {
        ci.setReturnValue(false);
    }
}
