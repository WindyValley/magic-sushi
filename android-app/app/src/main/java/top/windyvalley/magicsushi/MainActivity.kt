package top.windyvalley.magicsushi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import top.windyvalley.magicsushi.ui.screen.GameScreen
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.viewmodel.GameViewModel
import top.windyvalley.magicsushi.viewmodel.GameViewModelFactory

class MainActivity : ComponentActivity() {

    // FIX_PLAN P0-2：依赖来自 Application 作用域，不再由 Activity 创建。
    // 此前 SoundPlayer 是本类的 by lazy 属性，而 GameViewModel 跨配置变更
    // 存活 —— 旋屏后旧 Activity 的 onDestroy() 会 release 掉 VM 仍在使用
    // 的 SoundPool，导致音效永久失效。
    private val app: MagicSushiApp
        get() = application as MagicSushiApp

    private val viewModel: GameViewModel by viewModels {
        GameViewModelFactory(app.prefsRepo, app.soundPlayer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ✅ 新增：观察 Activity 生命周期，让 VM 知道何时暂停/恢复
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                else -> { /* no-op for other events */ }
            }
        })

        setContent {
            MagicSushiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GameScreen(viewModel)
                }
            }
        }
    }

    // 刻意不再重写 onDestroy() 调用 soundPlayer.release()：
    // SoundPlayer 现在是 Application 作用域的单例，被多个 Activity 实例
    // 共享。在此处 release 会让旋屏后存活的 ViewModel 拿到已销毁的
    // SoundPool（FIX_PLAN P0-2）。其生命周期随进程结束自然回收。
}

@Composable
fun GameScreenPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🍣 Magic Sushi\nLoading...",
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GameScreenPlaceholderPreview() {
    MagicSushiTheme {
        GameScreenPlaceholder()
    }
}