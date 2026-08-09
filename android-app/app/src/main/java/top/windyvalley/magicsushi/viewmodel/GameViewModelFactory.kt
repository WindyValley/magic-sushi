package top.windyvalley.magicsushi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.HistoryRepository
import top.windyvalley.magicsushi.data.PrefsRepository
import top.windyvalley.magicsushi.data.SnapshotRepository

/**
 * GameViewModelFactory.kt — manual DI for [GameViewModel].
 *
 * The AndroidX `ViewModel` system instantiates ViewModels via a no-arg
 * constructor by default. Since [GameViewModel] needs a [PrefsRepository]
 * and a [SoundPlayer] (both of which require a `Context` to construct),
 * we wire them through this factory and pass the factory into the
 * `ViewModelProvider` API in the Activity / Composable.
 *
 * ---
 * ## Why a manual factory (not Hilt)?
 *
 * The MVP is intentionally dependency-injection-framework-free:
 *
 * - **KISS** — the project is small (~25 files, 1 ViewModel). Hilt would
 *   add Gradle plugins, kapt, generated code, and `@HiltAndroidApp` /
 *   `@HiltViewModel` annotations for very little benefit.
 * - **Faster builds** — no annotation processing round-trip.
 * - **Easier to read** — the DI graph is one file the size of this one.
 *
 * If the project grows to multiple ViewModels with overlapping
 * dependencies, switching to Hilt is a mechanical refactor (one
 * `@HiltViewModel` per VM, one `HiltViewModelFactory` per Activity).
 *
 * ## Usage
 *
 * From an Activity (`MagicSushiApp` exposes singletons via the
 * application context):
 *
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     private val viewModel: GameViewModel by viewModels {
 *         val app = application as MagicSushiApp
 *         GameViewModelFactory(
 *             prefsRepo = app.prefsRepo,
 *             soundPlayer = app.soundPlayer,
 *         )
 *     }
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         setContent {
 *             val state by viewModel.state.collectAsState()
 *             // … render `state` …
 *         }
 *     }
 * }
 * ```
 *
 * From a Composable (preferred when there's no Activity-level state to
 * bridge — keeps the Compose tree self-contained):
 *
 * ```kotlin
 * @Composable
 * fun GameScreen() {
 *     val app = LocalContext.current.applicationContext as MagicSushiApp
 *     val vm: GameViewModel = viewModel(
 *         factory = GameViewModelFactory(app.prefsRepo, app.soundPlayer)
 *     )
 *     val state by vm.state.collectAsState()
 *     // …
 * }
 * ```
 *
 * ## Re-creation across config changes
 *
 * `ViewModelProvider` retains the same VM instance across configuration
 * changes (rotation, dark/light theme switch, etc.). The factory is
 * called **once per VM lifetime** — `create` is invoked the first time
 * the VM is requested for a given `ViewModelStoreOwner`, and the
 * resulting VM is cached. Subsequent `viewModels()` calls return the
 * same instance.
 *
 * This means [PrefsRepository] and [SoundPlayer] are captured by the
 * VM **once** and reused. If you ever need to swap them at runtime
 * (e.g. for hot-reload during development), you'd need a different
 * mechanism (e.g. an `AssistedInject`-style factory).
 *
 * ## Threading
 *
 * `create` is called on the main thread (it's invoked from
 * `ComponentActivity.onCreate()` / `Composable` recomposition). The
 * factory itself is stateless and cheap to construct — no synchronization
 * needed.
 *
 * @see GameViewModel for the ViewModel being constructed
 * @see PrefsRepository for the persistence dependency
 * @see SoundPlayer for the audio dependency
 */
class GameViewModelFactory(
    private val prefsRepo: PrefsRepository,
    private val historyRepo: HistoryRepository,
    private val soundPlayer: SoundPlayer,
    private val snapshotRepo: SnapshotRepository,
) : ViewModelProvider.Factory {

    /**
     * Construct a [GameViewModel] with the captured [prefsRepo] and
     * [soundPlayer] dependencies.
     *
     * Throws [IllegalArgumentException] if [modelClass] is not assignable
     * from [GameViewModel] — the framework only ever asks for known VM
     * types, so this branch is defensive against future refactors that
     * might accidentally try to route a different VM through this
     * factory.
     *
     * The `@Suppress("UNCHECKED_CAST")` is the standard one for
     * `ViewModelProvider.Factory` implementations: the `as T` cast
     * cannot be checked at runtime because of JVM type erasure, but it
     * is provably safe because of the `isAssignableFrom` guard above.
     *
     * @param modelClass the class of the ViewModel to create. Must be
     *                   [GameViewModel] or a subclass.
     * @return a new [GameViewModel] instance with the injected
     *         dependencies.
     * @throws IllegalArgumentException if [modelClass] is not
     *         [GameViewModel] or a subclass.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            return GameViewModel(prefsRepo, historyRepo, soundPlayer, snapshotRepo) as T
        }
        throw IllegalArgumentException(
            "Unknown ViewModel class: ${modelClass.name}"
        )
    }
}
