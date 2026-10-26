package dev.inkleaf.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.inkleaf.core.ui.InkLeafTheme

/**
 * 唯一的 Activity。
 *
 * ## 导航：手写的小栈，而不是 navigation-compose
 *
 * 全应用只有 5 个目的地、其中 2 个带一个字符串参数（docId）。
 * navigation-compose 需要一份路由图定义 + 类型安全的参数提取，
 * 而它能带来的东西（深层链接、Dialog 目的地、多返回栈）这里一个都不用。
 * 用一个"用 `|` 拼起来的字符串"当返回栈，体积是 30 行、零依赖、
 * 且在 `rememberSaveable` 里天然可恢复（String 一定可存进 Bundle，
 * 而 List 要靠 Serializable 的隐式约定）。
 *
 * 什么时候应该换掉它：出现第三个带参数的目的地，或者需要深层链接。
 * 到那时这个替换是局部的（只改这个文件）。
 *
 * ## 返回键
 *
 * 必须走 [BackHandler]（它内部用 OnBackInvokedCallback）。
 * Manifest 里 `enableOnBackInvokedCallback="true"`，targetSdk >= 36 时
 * `onBackPressed()` 不会被调用 —— 用旧 API 写的返回逻辑在 Android 16+ 上会直接退出应用。
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as InkLeafApplication).container

        setContent {
            val settings by container.settings.collectAsState()
            // 屏幕方向锁定（M31）。作用在 **Activity** 上而不是某个界面里：
            // 书架、阅读、设置是同一个窗口，只锁阅读界面的话，从书架切进书里
            // 那一刻屏幕会先按重力转一次再锁住 —— 那一次转动就够让人放弃这个开关了。
            //
            // LOCKED 的语义是"以此刻的方向为准"：用户举起手机摆好姿势再打开开关，
            // 锁住的就是那个方向。关掉回到 UNSPECIFIED（跟随系统旋转设置）。
            LaunchedEffect(settings.rotationLock) {
                requestedOrientation = if (settings.rotationLock) {
                    ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // 全局 Material 主题跟着**界面明暗**走（设置页里那个三档），
            // 与书里的纸色无关 —— 书架用浅色、书里用夜间纸是正常用法。
            // 阅读界面会在这个主题内部再套一层自己的纸墨主题（见 `ReaderScreen`）。
            val dark = settings.uiDarkMode.resolveDark(isSystemInDarkTheme())

            InkLeafTheme(darkTheme = dark) {
                // 默认深色模式下 XML 主题仍是浅色纸面（保证首帧不闪白），
                // 这里用 Compose 的背景把整个窗口覆盖掉。
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation(container)
                }
            }
        }
    }

    /**
     * 音量键翻页（M27）：阅读界面在 [AppContainer.readerVolumeKeyHandler] 注册了
     * 回调、且设置开着时，音量键被转交为翻页；否则照常调音量。
     *
     * onKeyDown 与 onKeyUp 必须**同时**接管：只吞 DOWN 的话，UP 仍会走系统
     * 音量管线，部分机型会出一声键音或音量气泡。
     * 长按连续翻页是 onKeyDown 的 repeat 自带的，不需要额外处理。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (consumeVolumeKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (consumeVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 音量下键=下一页、音量上键=上一页（与主流阅读器一致的拇指方向）。 */
    private fun consumeVolumeKey(keyCode: Int): Boolean {
        if (!container.settings.value.volumeKeyTurn) return false
        val handler = container.readerVolumeKeyHandler ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> handler(true)
            KeyEvent.KEYCODE_VOLUME_UP -> handler(false)
            else -> return false
        }
        return true
    }
}

@Composable
private fun AppNavigation(container: AppContainer) {
    var stack by rememberSaveable { mutableStateOf(LIBRARY) }
    val routes = stack.split('|').filter { it.isNotBlank() }.ifEmpty { listOf(LIBRARY) }
    val route = routes.last()

    fun push(next: String) {
        stack = (routes + next).joinToString("|")
    }

    fun pop() {
        val remaining = routes.dropLast(1)
        stack = if (remaining.isEmpty()) LIBRARY else remaining.joinToString("|")
    }

    BackHandler(enabled = routes.size > 1) { pop() }

    when {
        route == LIBRARY -> LibraryScreen(
            container = container,
            onOpenDocument = { docId -> push("reader/$docId") },
            onOpenImport = { push(IMPORT) },
            onOpenSettings = { push(SETTINGS) },
            onOpenStats = { push(STATS) },
        )

        route == IMPORT -> ImportScreen(
            container = container,
            onClose = { pop() },
            onImported = { pop() },
        )

        route == SETTINGS -> SettingsScreen(container = container, onClose = { pop() })

        route == STATS -> StatsScreen(container = container, onClose = { pop() })

        route.startsWith("reader/") -> {
            // 路由形如 `reader/{docId}` 或 `reader/{docId}?note={noteId}`。
            // 带 note 是从笔记清单"跳回原位"进来的（A3）：目标用**笔记 id**，
            // 不用偏移 —— 偏移要等排版结果出来才算得出，而笔记 id 在库里就有。
            val (readerDocId, noteId) = ReaderRoute.parse(route)
            ReaderScreen(
                container = container,
                docId = readerDocId,
                initialNoteId = noteId,
                onClose = { pop() },
                onOpenNotes = { push("notes/$readerDocId") },
            )
        }

        route.startsWith("notes/") -> NotesScreen(
            container = container,
            docId = route.removePrefix("notes/"),
            onClose = { pop() },
            onOpenNote = { noteId ->
                push(ReaderRoute.build(route.removePrefix("notes/"), noteId))
            },
        )

        else -> LibraryScreen(
            container = container,
            onOpenDocument = { docId -> push("reader/$docId") },
            onOpenImport = { push(IMPORT) },
            onOpenSettings = { push(SETTINGS) },
            onOpenStats = { push(STATS) },
        )
    }
}

private const val LIBRARY = "library"
private const val IMPORT = "import"
private const val SETTINGS = "settings"
private const val STATS = "stats"
