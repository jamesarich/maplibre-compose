package org.maplibre.compose.desktop.bridge

import java.nio.ByteBuffer
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glTexSubImage2D
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER
import org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin

/** How a [LinuxOpenGlMapHost] moves each frame from the map's producer into Compose's context. */
internal enum class LinuxFrameTransfer {
  /** Compose imports the producer image's memory and samples it in place. */
  SharedMemory,

  /**
   * The host copies each Vulkan frame into host memory and uploads it to an ordinary OpenGL
   * texture, for drivers that cannot import Vulkan memory, such as Mesa's v3d and panfrost.
   */
  Readback,
}

/**
 * The transfer for [producer] when Compose's OpenGL context [importsMemoryFds]: exposes both
 * `GL_EXT_memory_object` and `GL_EXT_memory_object_fd`. Only a Vulkan producer can fall back to
 * [LinuxFrameTransfer.Readback]; an OpenGL producer imports the same memory into its own context.
 */
internal fun linuxFrameTransfer(
  producer: MapRenderBackend,
  importsMemoryFds: Boolean,
): LinuxFrameTransfer =
  if (producer == MapRenderBackend.Vulkan && !importsMemoryFds) LinuxFrameTransfer.Readback
  else LinuxFrameTransfer.SharedMemory

/** The texture Compose draws a [LinuxOpenGlMapHost] frame from, in Compose's GL context. */
internal interface LinuxComposeTexture : AutoCloseable {
  /** The GL name of the texture. */
  val textureName: Int

  /**
   * For presenting only: the context handles are zero and the make-current hook is a no-op, since
   * Compose already owns this GL context.
   */
  fun target(generation: Long): OpenGlTextureTarget

  /** Forgets names allocated by a lost context, which the replacement context must not delete. */
  fun abandon()
}

/** An ordinary RGBA8 texture in Compose's GL context that [upload] fills from host memory. */
internal class LinuxOpenGlUploadedTexture private constructor(private val extent: MapExtent) :
  LinuxComposeTexture {
  override var textureName: Int = 0
    private set

  override fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context =
        EglContextHandles(NativeHandle(0), NativeHandle(0), NativeHandle(0), NativeHandle(0)),
      textureName = textureName,
      textureTarget = GL_TEXTURE_2D,
      format = GL_RGBA8,
      origin = TextureOrigin.TopLeft,
      makeContextCurrent = {},
      extent = extent,
      generation = generation,
    )

  private fun create() {
    ensureCapabilities()
    clearGlErrors()
    withUnpackState {
      textureName = glGenTextures()
      glBindTexture(GL_TEXTURE_2D, textureName)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
      glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA8,
        extent.physicalWidth,
        extent.physicalHeight,
        0,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        null as ByteBuffer?,
      )
    }
    checkGl("glTexImage2D")
  }

  /** Replaces the texture's contents with [pixels]: tightly packed RGBA8 rows from the top. */
  fun upload(pixels: ByteBuffer) {
    ensureCapabilities()
    clearGlErrors()
    withUnpackState {
      glBindTexture(GL_TEXTURE_2D, textureName)
      glTexSubImage2D(
        GL_TEXTURE_2D,
        0,
        0,
        0,
        extent.physicalWidth,
        extent.physicalHeight,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixels,
      )
    }
    checkGl("glTexSubImage2D")
  }

  /** Runs [action] with client-memory unpacking, then restores the state Compose had. */
  private inline fun withUnpackState(action: () -> Unit) {
    val texture = glGetInteger(GL_TEXTURE_BINDING_2D)
    val unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)
    val alignment = glGetInteger(GL_UNPACK_ALIGNMENT)
    val rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH)
    try {
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
      glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
      glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
      action()
    } finally {
      glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength)
      glPixelStorei(GL_UNPACK_ALIGNMENT, alignment)
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
      glBindTexture(GL_TEXTURE_2D, texture)
    }
  }

  override fun close() {
    if (textureName == 0) return
    runCatching {
      ensureCapabilities()
      glDeleteTextures(textureName)
      textureName = 0
    }
  }

  override fun abandon() {
    textureName = 0
  }

  companion object {
    fun create(extent: MapExtent): LinuxOpenGlUploadedTexture {
      val texture = LinuxOpenGlUploadedTexture(extent)
      try {
        texture.create()
        return texture
      } catch (error: RuntimeException) {
        texture.close()
        throw error
      }
    }
  }
}
