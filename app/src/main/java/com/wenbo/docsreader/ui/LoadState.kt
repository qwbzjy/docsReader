package com.wenbo.docsreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** 异步加载状态。 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Success<T>(val value: T) : Load<T>
    data class Failure(val message: String) : Load<Nothing>
}

/** key 变化时执行 loader，得到加载状态。 */
@Composable
fun <T> rememberLoaded(key: Any?, loader: suspend () -> T): Load<T> {
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key) {
        state = try {
            Load.Success(loader())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Load.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
    return state
}
