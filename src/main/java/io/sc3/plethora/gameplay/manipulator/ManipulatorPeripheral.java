package io.sc3.plethora.gameplay.manipulator;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Direction;
import io.sc3.plethora.api.IWorldLocation;
import io.sc3.plethora.api.WorldLocation;
import io.sc3.plethora.api.method.ContextKeys;
import io.sc3.plethora.api.method.CostHelpers;
import io.sc3.plethora.api.module.BasicModuleContainer;
import io.sc3.plethora.api.module.IModuleAccess;
import io.sc3.plethora.api.module.IModuleContainer;
import io.sc3.plethora.api.module.IModuleHandler;
import io.sc3.plethora.api.reference.ConstantReference;
import io.sc3.plethora.api.reference.IReference;
import io.sc3.plethora.core.*;
import io.sc3.plethora.gameplay.modules.ModulePeripheral;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static io.sc3.plethora.api.reference.Reference.blockEntity;

public class ManipulatorPeripheral {
    @Nullable
    public static IPeripheral getPeripheral(@Nonnull ManipulatorBlockEntity manipulator, Direction direction) {
        ManipulatorType manipulatorType = manipulator.getManipulatorType();
        if (manipulatorType == null) return null;
        final int size = manipulatorType.size();

        final int stackHash = manipulator.getStackHash();

        final ItemStack[] stacks = new ItemStack[size];
        Set<Identifier> modules = new HashSet<>();
        Set<Pair<IModuleHandler, ItemStack>> moduleHandlers = new HashSet<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = manipulator.getStack(i);
            if (stack.isEmpty()) continue;

            stack = stacks[i] = stack.copy();

            if (!(stack.getItem() instanceof IModuleHandler handler)) continue;

            Identifier module = handler.getModule();
            // TODO: Check module blacklist

            modules.add(module);
            moduleHandlers.add(new Pair<>(handler, stack));
        }

        if (modules.isEmpty()) return null;

        final IModuleContainer container = new BasicModuleContainer(modules);
        Map<Identifier, ManipulatorAccess> accessMap = new HashMap<>();

        IReference<IModuleContainer> containerRef = new ConstantReference<IModuleContainer>() {
            @Nonnull
            @Override
            public IModuleContainer get() throws LuaException {
                if (manipulator.isRemoved()) throw new LuaException("Manipulator is no longer there");

                for (int i = 0; i < size; i++) {
                    ItemStack oldStack = stacks[i];
                    ItemStack newStack = manipulator.getStack(i);
                    if (oldStack != null && !ItemStack.areItemsEqual(oldStack, newStack)) {
                        IModuleHandler moduleHandler = (IModuleHandler) oldStack.getItem();
                        throw new LuaException("The " + moduleHandler.getModule() + " module has been removed");
                    }
                }

                return container;
            }

            @Nonnull
            @Override
            public IModuleContainer safeGet() throws LuaException {
                if (manipulator.isRemoved()) throw new LuaException("Manipulator is no longer there");

                if (stackHash != manipulator.getStackHash()) {
                    throw new LuaException("A module has changed");
                }

                return container;
            }
        };

        ContextFactory<IModuleContainer> factory = ContextFactory.of(container, containerRef)
            .withCostHandler(CostHelpers.getCostHandler(manipulator))
            .withModules(container, containerRef)
            .addContext(ContextKeys.ORIGIN, manipulator, blockEntity(manipulator))
            .addContext(ContextKeys.ORIGIN, new WorldLocation(manipulator.getWorld(), manipulator.getPos()));

        for (Pair<IModuleHandler, ItemStack> handler : moduleHandlers) {
            Identifier module = handler.getLeft().getModule();
            ManipulatorAccess access = accessMap.get(module);
            if (access == null) {
                accessMap.put(module, access = new ManipulatorAccess(manipulator, handler.getLeft(), container));
            }

            handler.getLeft().getAdditionalContext(handler.getRight(), access, factory);
        }

        Pair<List<RegisteredMethod<?>>, List<UnbakedContext<?>>> paired = MethodRegistry.instance
            .getMethodsPaired(factory.getBaked());
        if (paired.getLeft().isEmpty()) return null;

        ModulePeripheral peripheral = new ModulePeripheral("manipulator", manipulator, paired, manipulator.getRunner(),
            factory.getAttachments(), stackHash);
        for (ManipulatorAccess access : accessMap.values()) {
            access.wrapper = peripheral;
        }
        return peripheral;
    }

    private static final class ManipulatorAccess implements IModuleAccess {
        private AttachableWrapperPeripheral wrapper;

        private final ManipulatorBlockEntity manipulator;
        private final IWorldLocation location;
        private final Identifier module;
        private final IModuleContainer container;

        private ManipulatorAccess(ManipulatorBlockEntity manipulator, IModuleHandler module, IModuleContainer container) {
            this.manipulator = manipulator;
            location = new WorldLocation(Objects.requireNonNull(manipulator.getWorld()), manipulator.getPos());
            this.module = module.getModule();
            this.container = container;
        }

        @Nonnull
        @Override
        public Object getOwner() {
            return manipulator;
        }

        @Nonnull
        @Override
        public IWorldLocation getLocation() {
            return location;
        }

        @Nonnull
        @Override
        public IModuleContainer getContainer() {
            return container;
        }

        @Nonnull
        @Override
        public NbtCompound getData() {
            return manipulator.getModuleData(module);
        }

        @Nonnull
        @Override
        public MinecraftServer getServer() {
            return Objects.requireNonNull(Objects.requireNonNull(manipulator.getWorld()).getServer());
        }

        @Override
        public void markDataDirty() {
            manipulator.markModuleDataDirty();
        }

        @Override
        public void queueEvent(@Nonnull String event, @Nullable Object... args) {
            if (wrapper != null) wrapper.queueEvent(event, args);
        }
    }
}
