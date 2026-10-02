package com.example.patternfinder

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var btnCapture: Button
    private lateinit var btnScan: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    // 索引文件，存到 App 私有目录
    private val indexFile by lazy { File(filesDir, "pattern_index.dat") }

    // 内存中的索引：Uri -> ORB 描述子
    private var index = HashMap<String, FeatureExtractor.Features>()

    // 拍照
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            searchInIndex(bitmap)
        }
    }

    // 申请权限
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startScan()
        } else {
            Toast.makeText(this, "需要相册权限才能搜索", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 简单布局（代码里搭 UI，省去 XML，MVP 够用）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "准备就绪"
            textSize = 16f
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
        }

        btnScan = Button(this).apply {
            text = "① 扫描相册建索引"
            setOnClickListener { checkPermissionAndScan() }
        }

        btnCapture = Button(this).apply {
            text = "② 拍照搜索图案"
            setOnClickListener { takePicture.launch(null) }
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = ResultAdapter()
        }

        root.addView(statusText)
        root.addView(progressBar)
        root.addView(btnScan)
        root.addView(btnCapture)
        root.addView(recyclerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(root)

        // 加载上次建好的索引
        loadIndex()
    }

    private fun checkPermissionAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            requestPermission.launch(perm)
        }
    }

    /** 扫描相册，提取每张图的 ORB 特征，存本地 */
    private fun startScan() {
        statusText.text = "正在扫描相册..."
        progressBar.visibility = ProgressBar.VISIBLE
        index.clear()

        scope.launch {
            withContext(Dispatchers.IO) {
                val uris = queryAllImages()
                var count = 0
                uris.forEach { uri ->
                    try {
                        val bmp = contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                        if (bmp != null) {
                            val feat = FeatureExtractor.extract(bmp)
                            if (feat != null) {
                                index[uri.toString()] = feat
                            }
                        }
                    } catch (_: Exception) { }
                    count++
                    val p = count * 100 / uris.size
                    withContext(Dispatchers.Main) {
                        progressBar.progress = p
                        statusText.text = "扫描中 $count / ${uris.size}"
                    }
                }
                saveIndex()
            }
            progressBar.visibility = ProgressBar.GONE
            statusText.text = "索引完成，共 ${index.size} 张图"
        }
    }

    /** 读取相册中所有图片的 Uri */
    private fun queryAllImages(): List<Uri> {
        val list = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            }
        }
        return list
    }

    /** 拍照后，在索引里找最像的前 20 张 */
    private fun searchInIndex(target: Bitmap) {
        if (index.isEmpty()) {
            Toast.makeText(this, "请先扫描相册建索引", Toast.LENGTH_LONG).show()
            return
        }
        statusText.text = "搜索中..."
        scope.launch {
            val results = withContext(Dispatchers.Default) {
                val targetFeat = FeatureExtractor.extract(target) ?: return@withContext emptyList()
                index.entries
                    .map { (uri, feat) -> uri to FeatureExtractor.matchScore(targetFeat, feat) }
                    .filter { it.second > 15 }
                    .sortedByDescending { it.second }
                    .take(20)
            }
            (recyclerView.adapter as ResultAdapter).submit(results.map { Uri.parse(it.first) })
            statusText.text = "找到 ${results.size} 张相似图案"
        }
    }

    private fun saveIndex() {
        try {
            ObjectOutputStream(FileOutputStream(indexFile)).use { it.writeObject(index) }
        } catch (_: Exception) { }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            ObjectInputStream(indexFile.inputStream()).use {
                index = it.readObject() as HashMap<String, FeatureExtractor.Features>
            }
            statusText.text = "已加载索引，共 ${index.size} 张图"
        } catch (_: Exception) { }
    }
}
