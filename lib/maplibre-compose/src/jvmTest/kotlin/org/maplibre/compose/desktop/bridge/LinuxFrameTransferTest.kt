package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.maplibre.compose.mlnffi.MapRenderBackend

class LinuxFrameTransferTest {

  @Test
  fun a_context_that_imports_memory_fds_shares_memory_with_either_producer() {
    assertEquals(
      LinuxFrameTransfer.SharedMemory,
      linuxFrameTransfer(MapRenderBackend.Vulkan, importsMemoryFds = true),
    )
    assertEquals(
      LinuxFrameTransfer.SharedMemory,
      linuxFrameTransfer(MapRenderBackend.OpenGl, importsMemoryFds = true),
    )
  }

  @Test
  fun only_a_vulkan_producer_reads_back_when_the_context_cannot_import_memory_fds() {
    assertEquals(
      LinuxFrameTransfer.Readback,
      linuxFrameTransfer(MapRenderBackend.Vulkan, importsMemoryFds = false),
    )
    // An OpenGL producer needs the same import in its own context, so it keeps failing loudly.
    assertEquals(
      LinuxFrameTransfer.SharedMemory,
      linuxFrameTransfer(MapRenderBackend.OpenGl, importsMemoryFds = false),
    )
  }

  @Test
  fun the_readback_buffer_prefers_cached_host_memory() {
    val types =
      listOf(
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        HostVisible or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        HostVisible or VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
      )

    assertEquals(2, hostReadableMemoryType(types, typeBits = 0b111))
    assertEquals(1, hostReadableMemoryType(types, typeBits = 0b011))
    assertEquals(-1, hostReadableMemoryType(types, typeBits = 0b001))
  }

  private companion object {
    const val HostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
  }
}
