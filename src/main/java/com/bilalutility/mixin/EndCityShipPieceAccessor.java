package com.bilalutility.mixin;

import net.minecraft.structure.SimpleStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the exact vanilla template path stored on each generated structure piece. */
@Mixin(SimpleStructurePiece.class)
public interface EndCityShipPieceAccessor {
    @Accessor("templateIdString")
    String bilal$getTemplateIdString();
}
