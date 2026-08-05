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
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.PrefsRepository
import top.windyvalley.magicsushi.ui.screen.GameScreen
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.viewmodel.GameViewModel
import top.windyvalley.magicsushi.viewmodel.GameViewModelFactory

class MainActivity : ComponentActivity() {

    private val prefsRepo by lazy { PrefsRepository(applicationContext) }
    private val soundPlayer by lazy { SoundPlayer(applicationContext) }

    private val viewModel: GameViewModel by viewModels {
        GameViewModelFactory(prefsRepo, soundPlayer)
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

    override fun onDestroy() {
        super.onDestroy()
        soundPlayer.release()
    }
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