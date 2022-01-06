/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

package net.mamoe.mirai.event

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import net.mamoe.mirai.utils.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.resume
import kotlin.reflect.KClass


/**
 * 挂起当前协程, 直到监听到事件 [E] 的广播并通过 [filter], 返回这个事件实例.
 *
 * @param filter 过滤器. 返回 `true` 时表示得到了需要的实例. 返回 `false` 时表示继续监听
 *
 * @see EventChannel.subscribe 普通地监听一个事件
 * @see EventChannel.syncFromEvent 挂起当前协程, 并尝试从事件中同步一个值
 *
 * @since 2.10
 */
public suspend inline fun <reified E : Event> EventChannel<*>.nextEvent(
    priority: EventPriority = EventPriority.NORMAL,
    noinline filter: suspend (E) -> Boolean = { true }
): E = coroutineScope { this@nextEvent.nextEventImpl(E::class, this@coroutineScope, priority, filter) }
// useIncomingFlow(priority) { flow -> flow.filterIsInstance<E>().filter(filter).first() }

/**
 * 挂起当前协程, 监听事件 [E], 并尝试从这个事件中**获取**一个值, 在超时时抛出 [TimeoutCancellationException]
 *
 * @param mapper 过滤转换器. 返回非 null 则代表得到了需要的值. [syncFromEvent] 会返回这个值
 *
 * @see asyncFromEvent 本函数的异步版本
 * @see EventChannel.subscribe 普通地监听一个事件
 * @see nextEvent 挂起当前协程, 并获取下一个事件实例
 *
 * @see syncFromEventOrNull 本函数的在超时后返回 `null` 的版本
 *
 * @throws Throwable 当 [mapper] 抛出任何异常时, 本函数会抛出该异常
 *
 * @since 2.10
 */
public suspend inline fun <reified E : Event, R : Any> EventChannel<*>.syncFromEvent(
    priority: EventPriority = EventPriority.NORMAL,
    noinline mapper: suspend (E) -> R?
): R = coroutineScope { this@syncFromEvent.syncFromEventImpl(E::class, this, priority, mapper) }
// useIncomingFlow(priority) { flow -> flow.filterIsInstance<E>().mapNotNull(mapper).first() }

/**
 * 创建一个*冷*的 [Flow], 在使用该 [Flow] 时启动监听器监听事件, 将监听到的事件*发送*到该 [Flow].
 *
 * [block] 将会在创建监听器后立即调用, 其参数为上述 [Flow].
 * 该 [Flow] 只可在 [block] 内部使用, 否则 [Flow.collect] 将会一直挂起. TODO
 *
 * ## [Flow] 的使用
 *
 * [Flow] 只可在 [block] 内部使用, 可以使用任意次. 当且仅当每次调用终止操作时创建一个监听器. 多次调用终止操作会创建多个事件监听器, 互相独立.
 *
 * 若 [block] 抛出异常, 所有本函数内创建的事件监听器都会被停止.
 *
 * ## 使用示例
 *
 * ### 挂起协程, 直到获取下一个类型为 `E` 的事件
 * ```
 * useIncomingFlow { flow -> flow.filterIsInstance<E>().first() }
 * ```
 *
 * ### 挂起协程, 直到获取下一个满足条件的类型为 `E` 的事件
 * ```
 * useIncomingFlow { flow -> flow.filterIsInstance<E>().filter { it.someConditions() }.first() }
 * ```
 *
 * ### 挂起协程, 直到获取下一个满足条件的类型为 `E` 的事件, 并[拦截][Event.intercept] 该事件
 * ```
 * useIncomingFlow { flow -> flow.filterIsInstance<E>().filter { it.someConditions() }.onEach { it.intercept() }.first() }
 * ```
 *
 * ### 实现 [nextEvent]
 *
 * [nextEvent] 相当于特化的 [useIncomingFlow]. 作为示例, [nextEvent] 可以这样使用 [useIncomingFlow] 实现 (实际上它以另一种效率更高的方式实现):
 * ```
 * public suspend inline fun <reified E : Event> EventChannel<*>.nextEvent(
 *     priority: EventPriority = EventPriority.NORMAL,
 *     noinline filter: suspend (E) -> Boolean = { true }
 * ): E = useIncomingFlow(priority = priority) { flow -> flow.filterIsInstance<E>().filter(filter).first() }
 * ```
 *
 * ### 实现 [syncFromEvent]
 *
 * 作为示例, [syncFromEvent] 可以这样使用 [useIncomingFlow] 实现:
 * ```
 * public suspend inline fun <reified E : Event> EventChannel<*>.syncFromEvent(
 *     priority: EventPriority = EventPriority.NORMAL,
 *     noinline mapper: suspend (E) -> Boolean = { true }
 * ): E = useIncomingFlow(priority = priority) { flow -> flow.filterIsInstance<E>().mapNotNull(mapper).first() }
 * ```
 *
 * @since 2.10
 */
@MiraiExperimentalApi
public inline fun <R, E : Event> EventChannel<E>.useIncomingFlow(
    priority: EventPriority = EventPriority.NORMAL,
    block: (Flow<E>) -> R
): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    var channels: InlineList<SendChannel<E>> = InlineList()
    var listeners: InlineList<Listener<E>> = InlineList()

    return trySafely(
        block = {
            block(
                channelFlow {
                    channels += this.channel
                    val listener = subscribeAlways(baseEventClass, priority = priority) {
                        try {
                            send(it)
                        } catch (_: ClosedSendChannelException) {

                        }
                    }
                    listeners += listener
                    awaitClose { listener.complete() } // so this flow is endless until [block] completes.
                }
            )
        },
        finally = {
            // TODO: 06/01/2022 do not use internal ExceptionCollector here
            withExceptionCollector {
                channels.forEachReversed { runCollecting { it.close() } }
                listeners.forEachReversed { runCollecting { it.complete() } }
            }
        }
    )
//    val listener = subscribe(baseEventClass, priority = priority) {
//        try {
//            channel.send(it)
//            ListeningStatus.LISTENING
//        } catch (_: ClosedSendChannelException) {
//            ListeningStatus.STOPPED
//        }
//    }
//    return trySafely(
//        block = {
//            block(channel.receiveAsFlow())
//        },
//        finally = {
//            listener.complete()
//            channel.close()
//        }
//    )
}


/**
 * @since 2.10
 */
@PublishedApi
internal suspend fun <E : Event> EventChannel<Event>.nextEventImpl(
    eventClass: KClass<E>,
    coroutineScope: CoroutineScope,
    priority: EventPriority,
    filter: suspend (E) -> Boolean
): E = suspendCancellableCoroutine { cont ->
    var listener: Listener<E>? = null
    listener = parentScope(coroutineScope)
        .subscribe(eventClass, priority = priority) { event ->
            if (!filter(event)) return@subscribe ListeningStatus.LISTENING

            try {
                cont.resume(event)
            } finally {
                listener?.complete() // ensure completed on exceptions
            }
            return@subscribe ListeningStatus.STOPPED
        }

    cont.invokeOnCancellation {
        runCatching { listener.cancel("nextEvent outer scope cancelled", it) }
    }
}

/**
 * @since 2.10
 */
@PublishedApi
internal suspend fun <E : Event, R> EventChannel<*>.syncFromEventImpl(
    eventClass: KClass<E>,
    coroutineScope: CoroutineScope,
    priority: EventPriority,
    mapper: suspend (E) -> R?
): R = suspendCancellableCoroutine { cont ->
    parentScope(coroutineScope).subscribe(eventClass, priority = priority) { event ->
        try {
            cont.resumeWith(kotlin.runCatching {
                mapper.invoke(event) ?: return@subscribe ListeningStatus.LISTENING
            })
        } catch (_: Exception) {
        }
        return@subscribe ListeningStatus.STOPPED
    }
}
