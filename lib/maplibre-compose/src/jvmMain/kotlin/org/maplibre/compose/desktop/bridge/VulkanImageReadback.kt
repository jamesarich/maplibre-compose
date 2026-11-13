package org.maplibre.compose.desktop.bridge

import java.nio.ByteBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memByteBuffer
import org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT
import org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT
import org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT
import org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
import org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
import org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT
import org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
import org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO
import org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE
import org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers
import org.lwjgl.vulkan.VK10.vkAllocateMemory
import org.lwjgl.vulkan.VK10.vkBeginCommandBuffer
import org.lwjgl.vulkan.VK10.vkBindBufferMemory
import org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer
import org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier
import org.lwjgl.vulkan.VK10.vkCreateBuffer
import org.lwjgl.vulkan.VK10.vkCreateCommandPool
import org.lwjgl.vulkan.VK10.vkCreateFence
import org.lwjgl.vulkan.VK10.vkDestroyBuffer
import org.lwjgl.vulkan.VK10.vkDestroyCommandPool
import org.lwjgl.vulkan.VK10.vkDestroyFence
import org.lwjgl.vulkan.VK10.vkEndCommandBuffer
import org.lwjgl.vulkan.VK10.vkFreeMemory
import org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VK10.vkInvalidateMappedMemoryRanges
import org.lwjgl.vulkan.VK10.vkMapMemory
import org.lwjgl.vulkan.VK10.vkQueueSubmit
import org.lwjgl.vulkan.VK10.vkResetFences
import org.lwjgl.vulkan.VK10.vkUnmapMemory
import org.lwjgl.vulkan.VK10.vkWaitForFences
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkMappedMemoryRange
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkSubmitInfo
import org.maplibre.compose.map.MapExtent

/**
 * Copies a [VulkanImage] of 4-byte pixels into host memory, for an OpenGL context that cannot
 * import the image's memory. [image] must stay in [layout] between copies, which run on the
 * renderer thread after MapLibre's writes completed.
 */
internal class VulkanImageReadback
private constructor(
  private val vulkan: VulkanDevice,
  private val image: Long,
  private val layout: Int,
  val extent: MapExtent,
) : AutoCloseable {
  private var buffer = NULL
  private var memory = NULL
  private var coherent = false
  private var commandPool = NULL
  private var commandBuffer: VkCommandBuffer? = null
  private var fence = NULL

  /** The pixels of the last [copy], tightly packed rows from the top. */
  var pixels: ByteBuffer? = null
    private set

  private fun create() {
    val size = extent.physicalWidth.toLong() * extent.physicalHeight * 4
    MemoryStack.stackPush().use { stack ->
      val out = stack.mallocLong(1)
      val bufferInfo =
        VkBufferCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
          .size(size)
          .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
          .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
      checkVulkan(vkCreateBuffer(vulkan.device, bufferInfo, null, out), "vkCreateBuffer")
      buffer = out[0]

      val requirements = VkMemoryRequirements.calloc(stack)
      vkGetBufferMemoryRequirements(vulkan.device, buffer, requirements)
      val properties = VkPhysicalDeviceMemoryProperties.calloc(stack)
      vkGetPhysicalDeviceMemoryProperties(vulkan.physicalDevice, properties)
      val memoryType =
        hostReadableMemoryType(
          (0..<properties.memoryTypeCount()).map { properties.memoryTypes(it).propertyFlags() },
          requirements.memoryTypeBits(),
        )
      check(memoryType >= 0) { "No host-visible Vulkan memory type found for the map readback" }
      coherent =
        (properties.memoryTypes(memoryType).propertyFlags() and
          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0
      val allocateInfo =
        VkMemoryAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          .allocationSize(requirements.size())
          .memoryTypeIndex(memoryType)
      checkVulkan(vkAllocateMemory(vulkan.device, allocateInfo, null, out), "vkAllocateMemory")
      memory = out[0]
      checkVulkan(vkBindBufferMemory(vulkan.device, buffer, memory, 0), "vkBindBufferMemory")
      val mapped = stack.mallocPointer(1)
      checkVulkan(vkMapMemory(vulkan.device, memory, 0, size, 0, mapped), "vkMapMemory")
      pixels = memByteBuffer(mapped[0], size.toInt())

      val poolInfo =
        VkCommandPoolCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
          .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
          .queueFamilyIndex(vulkan.graphicsQueueFamily)
      checkVulkan(
        vkCreateCommandPool(vulkan.device, poolInfo, null, out),
        "vkCreateCommandPool",
      )
      commandPool = out[0]
      val commandInfo =
        VkCommandBufferAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
          .commandPool(commandPool)
          .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
          .commandBufferCount(1)
      val commandOut = stack.mallocPointer(1)
      checkVulkan(
        vkAllocateCommandBuffers(vulkan.device, commandInfo, commandOut),
        "vkAllocateCommandBuffers",
      )
      commandBuffer = VkCommandBuffer(commandOut[0], vulkan.device)

      val fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
      checkVulkan(vkCreateFence(vulkan.device, fenceInfo, null, out), "vkCreateFence")
      fence = out[0]
    }
  }

  /** Copies the image into [pixels] and waits for the copy. */
  fun copy() {
    val commands = checkNotNull(commandBuffer)
    MemoryStack.stackPush().use { stack ->
      checkVulkan(
        vkBeginCommandBuffer(
          commands,
          VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT),
        ),
        "vkBeginCommandBuffer",
      )
      val imageBarrier =
        VkImageMemoryBarrier.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
          .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
          .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
          .oldLayout(layout)
          .newLayout(layout)
          .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
          .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
          .image(image)
      imageBarrier
        .subresourceRange()
        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        .baseMipLevel(0)
        .levelCount(1)
        .baseArrayLayer(0)
        .layerCount(1)
      vkCmdPipelineBarrier(
        commands,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        null,
        null,
        imageBarrier,
      )
      val region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0)
      region[0]
        .imageSubresource()
        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        .mipLevel(0)
        .baseArrayLayer(0)
        .layerCount(1)
      region[0].imageExtent().set(extent.physicalWidth, extent.physicalHeight, 1)
      vkCmdCopyImageToBuffer(commands, image, layout, buffer, region)
      val bufferBarrier =
        VkBufferMemoryBarrier.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
          .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
          .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
          .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
          .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
          .buffer(buffer)
          .offset(0)
          .size(VK_WHOLE_SIZE)
      vkCmdPipelineBarrier(
        commands,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        null,
        bufferBarrier,
        null,
      )
      checkVulkan(vkEndCommandBuffer(commands), "vkEndCommandBuffer")

      checkVulkan(vkResetFences(vulkan.device, fence), "vkResetFences")
      val submit =
        VkSubmitInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
          .pCommandBuffers(stack.pointers(commands))
      checkVulkan(vkQueueSubmit(vulkan.graphicsQueue, submit, fence), "vkQueueSubmit")
      checkVulkan(vkWaitForFences(vulkan.device, fence, true, -1L), "vkWaitForFences")
      if (!coherent) {
        checkVulkan(
          vkInvalidateMappedMemoryRanges(
            vulkan.device,
            VkMappedMemoryRange.calloc(stack)
              .sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
              .memory(memory)
              .offset(0)
              .size(VK_WHOLE_SIZE),
          ),
          "vkInvalidateMappedMemoryRanges",
        )
      }
    }
  }

  override fun close() {
    vulkan.waitIdle()
    pixels = null
    if (fence != NULL) {
      vkDestroyFence(vulkan.device, fence, null)
      fence = NULL
    }
    if (commandPool != NULL) {
      vkDestroyCommandPool(vulkan.device, commandPool, null)
      commandPool = NULL
      commandBuffer = null
    }
    if (buffer != NULL) {
      vkDestroyBuffer(vulkan.device, buffer, null)
      buffer = NULL
    }
    if (memory != NULL) {
      vkUnmapMemory(vulkan.device, memory)
      vkFreeMemory(vulkan.device, memory, null)
      memory = NULL
    }
  }

  companion object {
    /** Prepares to read [image], which MapLibre leaves in [layout], at [extent]'s size. */
    fun create(image: VulkanImage, layout: Int, extent: MapExtent): VulkanImageReadback {
      val readback = VulkanImageReadback(image.vulkan, image.handle, layout, extent)
      try {
        readback.create()
        return readback
      } catch (error: RuntimeException) {
        readback.close()
        throw error
      }
    }
  }
}

/**
 * The index of the memory type, among those [typeBits] allows, that the host reads from fastest:
 * cached host-visible memory first, then any host-visible memory. Returns -1 when none is
 * host-visible. [propertyFlags] holds each type's `VkMemoryPropertyFlags`, by index.
 */
internal fun hostReadableMemoryType(propertyFlags: List<Int>, typeBits: Int): Int {
  val allowed = propertyFlags.indices.filter { (typeBits and (1 shl it)) != 0 }
  fun has(index: Int, flags: Int) = (propertyFlags[index] and flags) == flags
  return allowed.firstOrNull {
    has(it, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
  } ?: allowed.firstOrNull { has(it, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) } ?: -1
}
