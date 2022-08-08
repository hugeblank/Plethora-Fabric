package pw.switchcraft.plethora.gameplay.modules.glasses.objects.object3d

import dan200.computercraft.api.lua.IArguments
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.network.PacketByteBuf
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import pw.switchcraft.plethora.api.IWorldLocation
import pw.switchcraft.plethora.api.method.BasicMethod
import pw.switchcraft.plethora.api.method.ContextKeys.ORIGIN
import pw.switchcraft.plethora.api.method.FutureMethodResult
import pw.switchcraft.plethora.api.method.IUnbakedContext
import pw.switchcraft.plethora.api.method.getVec3dNullable
import pw.switchcraft.plethora.core.ContextHelpers
import pw.switchcraft.plethora.gameplay.modules.glasses.canvas.CanvasClient
import pw.switchcraft.plethora.gameplay.modules.glasses.objects.BaseObject
import pw.switchcraft.plethora.gameplay.modules.glasses.objects.ObjectGroup.Group3d
import pw.switchcraft.plethora.gameplay.modules.glasses.objects.ObjectRegistry.ORIGIN_3D
import pw.switchcraft.plethora.util.ByteBufUtils

class ObjectRoot3d(
  id: Int,
  parent: Int
) : BaseObject(id, parent, ORIGIN_3D), Group3d {
  private var origin: Vec3d = Vec3d.ZERO
  private var worldKey = Identifier("minecraft", "overworld")

  fun recenter(world: World, origin: Vec3d) {
    val worldKey = world.registryKey.value

    if (origin != this.origin || worldKey != this.worldKey) {
      this.origin = origin
      this.worldKey = worldKey
      setDirty()
    }
  }

  override fun readInitial(buf: PacketByteBuf) {
    origin = ByteBufUtils.readVec3d(buf)
    worldKey = buf.readIdentifier()
  }

  override fun writeInitial(buf: PacketByteBuf) {
    ByteBufUtils.writeVec3d(buf, origin)
    buf.writeIdentifier(worldKey)
  }

  override fun draw(canvas: CanvasClient, matrices: MatrixStack) {
    val children = canvas.getChildren(id()) ?: return

    val mc = MinecraftClient.getInstance()
    val entity = mc.cameraEntity ?: return
    if (entity.world?.registryKey?.value != worldKey) return

    // TODO: Determine a better way of handling this.
    val view = mc.gameRenderer.camera.pos
    val distance = mc.gameRenderer.viewDistance
    if (origin.squaredDistanceTo(view.x, view.y, view.z) > distance * distance) return

    matrices.push()

    matrices.translate(-view.x + origin.x, -view.y + origin.y, -view.z + origin.z)

    canvas.drawChildren(children.iterator(), matrices)

    matrices.pop()
  }

  companion object {
    @JvmField
    val RECENTER = BasicMethod.of(
      "recenter", "function([offsetX: number, offsetY: number, offsetZ: number]) -- Recenter this canvas relative to the current position.",
      { unbaked, args -> recenter(unbaked, args) }, false
    )
    private fun recenter(unbaked: IUnbakedContext<ObjectRoot3d>, args: IArguments): FutureMethodResult {
      val ctx = unbaked.bake()
      val location: IWorldLocation = ctx.getContext(ORIGIN, IWorldLocation::class.java)
      val offset = args.getVec3dNullable(0) ?: Vec3d.ZERO
      val target = ContextHelpers.safeFromTarget(unbaked)
      target.recenter(location.world, location.loc.add(offset))
      return FutureMethodResult.empty()
    }
  }
}