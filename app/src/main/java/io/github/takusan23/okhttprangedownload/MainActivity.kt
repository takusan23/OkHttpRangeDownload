package io.github.takusan23.okhttprangedownload

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.takusan23.okhttprangedownload.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val viewModel by viewModels<MainActivityViewModel>()

    /** URL */
    private val URL = ""

    /** ファイル名 */
    private val FILE_NAME = "download.mp4"

    /** Activity Result API コールバック */
    private val callback = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        if (uri != null) {
            // ViewModelに書いたダウンロード関数を呼ぶ
            viewModel.start(URL, uri, FILE_NAME)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        viewBinding.startButton.setOnClickListener {
            // 選ばせる
            callback.launch(FILE_NAME)
        }

        // 進捗
        viewModel.progressLiveData.observe(this) { progress ->
            println(progress)
            viewBinding.progressBar.progress = progress
            if (progress == 100) {
                Toast.makeText(this, "おわり", Toast.LENGTH_SHORT).show()
            }
        }

    }
}