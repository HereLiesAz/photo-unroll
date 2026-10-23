package com.hereliesaz.photounroll.video

import com.hereliesaz.photounroll.imaging.ImageCodec
import com.hereliesaz.photounroll.imaging.RgbaImage
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nsUrl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.appliesPreferredTrackTransform
import platform.AVFoundation.duration
import platform.AVFoundation.maximumSize
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object VideoFrames {
    actual suspend fun extract(file: PlatformFile, maxDim: Int, onProgress: (Float) -> Unit): List<RgbaImage> =
        withContext(Dispatchers.Default) {
            val asset = AVURLAsset.URLAssetWithURL(file.nsUrl, null)
            val length = CMTimeGetSeconds(asset.duration)
            require(length.isFinite() && length > 0) { "Could not read the video's length" }
            val n = frameCountFor(length)
            val generator = AVAssetImageGenerator.assetImageGeneratorWithAsset(asset)
            generator.appliesPreferredTrackTransform = true // upright, honoring the video's rotation
            generator.maximumSize = CGSizeMake(maxDim.toDouble(), maxDim.toDouble())

            val frames = ArrayList<RgbaImage>(n)
            for (i in 0 until n) {
                val time = CMTimeMakeWithSeconds(length * (i + 0.5) / n, 600)
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    val cg = generator.copyCGImageAtTime(time, null, error.ptr)
                        ?: error("Could not decode a frame: ${error.value?.localizedDescription}")
                    val jpeg = UIImageJPEGRepresentation(UIImage.imageWithCGImage(cg), 0.92)
                    CGImageRelease(cg)
                    frames += ImageCodec.decode(checkNotNull(jpeg).toByteArray())
                }
                onProgress((i + 1f) / n)
            }
            frames
        }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
