package io.github.takusan23.okhttprangedownload

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * [MainActivity]からUI以外のコードを持ってきた。
 * */
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    /** シングルトンにすべき */
    private val okHttpClient = OkHttpClient()

    /** 一時保存先 */
    private val tmpFolder = File(context.externalCacheDir, "split_file").apply { mkdir() }

    /** 合計バイト */
    private var totalByte = 0L

    /** 書き込みが終わったバイト */
    private var progressByte = 0L

    /** 進捗LiveData */
    private val _progressLiveData = MutableLiveData<Int>()

    /** 外部に公開する進捗LiveData */
    val progressLiveData: LiveData<Int> = _progressLiveData

    /**
     * ファイルダウンロードを開始する
     * @param fileName ファイル名
     * @param uri 保存先
     * @param url URL
     * */
    fun start(url: String, uri: Uri, fileName: String) {
        totalByte = 0L
        progressByte = 0L
        viewModelScope.launch {
            val responseHeader = getResponseHeader(url)
            // 合計サイズ
            val contentLength = responseHeader.headers["Content-Length"]!!.toLong()
            totalByte = contentLength
            // 分割。とりあえず5分割
            val splitList = splitByteList(contentLength, 5)
            // リクエスト
            splitList
                .mapIndexed { index, pair ->
                    // asyncで並列実行
                    async { requestFile(url, pair.first, pair.second, index, fileName) }
                }.map { deferred ->
                    // すべてのasyncを待つ
                    deferred.await()
                }
            // ファイルを結合
            val resultFile = multipleFileToOneFile(fileName)
            // ファイルを移動させて完成
            moveFile(resultFile, uri)
            // おしまい
            _progressLiveData.postValue(100)
            println("おわり")
        }
    }

    /**
     * ファイルダウンロードを開始する。ファイルはダウンロードフォルダに入る
     *
     * @param fileName ファイル名
     * @param url URL
     * */
    fun startMediaStoreVer(url: String, fileName: String) {
        totalByte = 0L
        progressByte = 0L
        viewModelScope.launch {
            val responseHeader = getResponseHeader(url)
            // 合計サイズ
            val contentLength = responseHeader.headers["Content-Length"]!!.toLong()
            totalByte = contentLength
            // 分割。とりあえず5分割
            val splitList = splitByteList(contentLength, 5)
            // リクエスト
            splitList
                .mapIndexed { index, pair ->
                    // asyncで並列実行
                    async { requestFile(url, pair.first, pair.second, index, fileName) }
                }.map { deferred ->
                    // すべてのasyncを待つ
                    deferred.await()
                }
            // ファイルを結合
            val resultFile = multipleFileToOneFile(fileName)
            // ファイルを移動させて完成
            val uri = insertFileToDownloadFolder(fileName)
            if (uri != null) {
                moveFile(resultFile, uri)
                // おしまい
                _progressLiveData.postValue(100)
                println("おわり")
            }
        }
    }


    /**
     * HEADリクエストを送信する
     * @param url URL
     * */
    private suspend fun getResponseHeader(url: String) = withContext(Dispatchers.IO) {
        // リクエスト
        val request = Request.Builder().apply {
            url(url)
            head() // bodyいらん
        }.build()
        return@withContext okHttpClient.newCall(request).execute()
    }

    /**
     * 分割して配列にして返す
     *
     * @param totalBytes 合計サイズ
     * @param splitCount 分割数
     * */
    private fun splitByteList(totalBytes: Long, splitCount: Int): ArrayList<Pair<Long, Long>> {
        // あまりが出ないほうがおかしいので余りを出す
        val amari = totalBytes % splitCount
        // あまり分を引いて一個のリクエストでのバイト数を決定
        val splitByte = (totalBytes - amari) / splitCount
        // 配列にして返す
        val byteList = arrayListOf<Pair<Long, Long>>()
        // 2回目のループなら1回目の値が入ってる。前の値
        var prevByte = 0L
        while (true) {
            // ピッタリ分けたいので
            if (totalBytes >= prevByte) {
                /***
                 * 最後余分に取得しないように。
                 * true(splitByte足しても足りない)ならsplitByteを足して、falseならtotalByteを渡して終了
                 * */
                val toByte = if (totalBytes > (prevByte + splitByte)) prevByte + splitByte else totalBytes
                byteList.add(Pair(prevByte, toByte))
                prevByte += splitByte + 1 // 1足して次のバイトからリクエストする
            } else break
        }
        return byteList
    }

    /**
     * 範囲リクエストを送信する
     *
     * @param fromByte こっから
     * @param toByte ここまでのバイト数を返す
     * @param count 何個目か
     * @param fileName ファイル名
     * */
    private suspend fun requestFile(url: String, fromByte: Long, toByte: Long, count: Int, fileName: String) = withContext(Dispatchers.IO) {
        // リクエスト
        val request = Request.Builder().apply {
            url(url)
            addHeader("Range", "bytes=${fromByte}-${toByte}")
            get()
        }.build()
        val response = okHttpClient.newCall(request).execute()
        val inputStream = response.body?.byteStream()
        // ファイル作成。拡張子に順番を入れる
        val splitFile = File(tmpFolder, "${fileName}.${count}").apply { createNewFile() }
        val splitFileOutputStream = splitFile.outputStream()
        // 書き込む
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = inputStream?.read(buffer)
            if (read == -1 || read == null) {
                // 終わりなら無限ループ抜けて高階関数よぶ
                break
            }
            splitFileOutputStream.write(buffer, 0, read)
            // 進捗
            progressByte += read
            val progress = ((progressByte / totalByte.toFloat()) * 100).toInt()
            // LiveData送信
            if (_progressLiveData.value != progress) {
                _progressLiveData.postValue(progress)
            }
        }
        inputStream?.close()
        splitFileOutputStream.close()
    }

    /**
     * すべてのファイルを一つにまとめて完成
     * @param fileName ファイル名
     * @return 結合ファイル
     * */
    private suspend fun multipleFileToOneFile(fileName: String) = withContext(Dispatchers.Default) {
        // 最終的なファイル
        val resultFile = File(context.getExternalFilesDir(null), fileName).apply { createNewFile() }
        tmpFolder.listFiles()
            ?.sortedBy { file -> file.extension } // 並び替え。男女男男女男女
            ?.map { file -> file.readBytes() } // readBytes()は2GBまでしか対応してない(さすがにないやろ)
            ?.forEach { bytes -> resultFile.appendBytes(bytes) }
        // フォルダを消す
        tmpFolder.deleteRecursively()
        // ファイルを返す
        return@withContext resultFile
    }

    /**
     * ファイルをUriの場所に書き込んで、元のファイル（[resultFile]）を削除する
     *
     * @param resultFile 完成したファイル
     * @param uri Activity Result APIでもらえるUri
     * */
    private suspend fun moveFile(resultFile: File, uri: Uri) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        // outputStreamをもらう
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(resultFile.readBytes()) // readBytes()は大きいファイルでは使うべきではない
        outputStream?.close()
        // 元のファイルを消す
        resultFile.deleteRecursively()
    }

    /**
     * MediaStore.Downloadを利用してダウンロードフォルダに入れる
     *
     * Android 10以降のみ対応
     * */
    private fun insertFileToDownloadFolder(fileName: String): Uri? {
        val contentResolver = context.contentResolver
        val contentUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        }
        // ダウンロードフォルダにデータを追加。Uriを受け取る
        val uri = contentResolver.insert(contentUri, contentValues)
        return uri
    }

}
