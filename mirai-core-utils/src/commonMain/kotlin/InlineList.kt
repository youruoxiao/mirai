/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

@file:Suppress("UNCHECKED_CAST")

package net.mamoe.mirai.utils

/*
 * Copied from kotlinx.coroutines. Copyright 2016-2021 JetBrains s.r.o.
 */


/*
 * Inline class that represents a mutable list, but does not allocate an underlying storage
 * for zero and one elements.
 * Cannot be parametrized with `List<*>`.
 */
@JvmInline
public value class InlineList<E>(@PublishedApi internal val holder: Any? = null) {
    public operator fun plus(element: E): InlineList<E> {
        assert(element !is List<*>) // Lists are prohibited
        return when (holder) {
            null -> InlineList(element)
            is ArrayList<*> -> {
                (holder as ArrayList<E>).add(element)
                InlineList(holder)
            }
            else -> {
                val list = ArrayList<E>(4)
                list.add(holder as E)
                list.add(element)
                InlineList(list)
            }
        }
    }

    public inline fun forEachReversed(action: (E) -> Unit) {
        when (holder) {
            null -> return
            !is ArrayList<*> -> action(holder as E)
            else -> {
                val list = holder as ArrayList<E>
                for (i in (list.size - 1) downTo 0) {
                    action(list[i])
                }
            }
        }
    }
}
