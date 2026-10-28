package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleMap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRuleMap.class)
public abstract class GameRuleMapRegioniumThreadSafetyMixin {
    @WrapMethod(method = "copyOf")
    private static GameRuleMap regionium$syncCopy(GameRuleMap source, Operation<GameRuleMap> original) {
        synchronized (source) {
            return original.call(source);
        }
    }

    @WrapMethod(method = "set")
    private <T> void regionium$syncSet(GameRule<T> rule, T value, Operation<Void> original) {
        synchronized (this) {
            original.call(rule, value);
        }
    }

    @WrapMethod(method = "remove")
    private <T> T regionium$syncRemove(GameRule<T> rule, Operation<T> original) {
        synchronized (this) {
            return original.call(rule);
        }
    }

    @WrapMethod(method = "size")
    private int regionium$syncSize(Operation<Integer> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "toString")
    private String regionium$syncToString(Operation<String> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "hashCode")
    private int regionium$syncHashCode(Operation<Integer> original) {
        synchronized (this) {
            return original.call();
        }
    }
}
