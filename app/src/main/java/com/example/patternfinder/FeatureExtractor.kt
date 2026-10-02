package com.example.patternfinder

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.Serializable

object FeatureExtractor {

    // 初始化 OpenCV（由 App 启动时调用，见下面 opencv init 说明）
    private var initialized = false
    fun init() {
        if (!initialized) {
            System.loadLibrary("opencv_java4")
            initialized = true
        }
    }

    /** 提取一张图的 ORB 特征 */
    fun extract(bitmap: Bitmap): Features? {
        init()
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(mat, mat, Size(300.0, 300.0))
            val orb = ORB.create(500)
            val kp = MatOfKeyPoint()
            val desc = Mat()
            orb.detectAndCompute(mat, Mat(), kp, desc)
            if (desc.empty()) null else Features(desc)
        } catch (e: Exception) {
            null
        }
    }

    /** 两张图的匹配分数（匹配点数量） */
    fun matchScore(a: Features, b: Features): Int {
        init()
        return try {
            val matcher = BFMatcher(org.opencv.core.Core.NORM_HAMMING, true)
            val matches = MatOfDMatch()
            matcher.match(a.desc, b.desc, matches)
            matches.toArray().count { it.distance < 50 }
        } catch (e: Exception) {
            0
        }
    }

    /** 可序列化的特征包装 */
    class Features(val desc: Mat) : Serializable {
        // Mat 不直接可序列化，存成字节
        private fun writeObject(out: java.io.ObjectOutputStream) {
            val bytes = ByteArray(desc.total().toInt() * desc.elemSize().toInt())
            desc.get(0, 0, bytes)
            out.writeInt(desc.rows())
            out.writeInt(desc.cols())
            out.writeInt(desc.type())
            out.writeObject(bytes)
        }

        private fun readObject(input: java.io.ObjectInputStream) {
            val rows = input.readInt()
            val cols = input.readInt()
            val type = input.readInt()
            val bytes = input.readObject() as ByteArray
            desc.put(0, 0, bytes)
        }
    }
}
