package su.kiriru.nvidiamistralapi.util

import java.util.*

inline fun <T> Deque<T>.removeEach(action: (T) -> Unit) {
    while (true) {
        val element = this.pollFirst() ?: break
        action(element)
    }
}
