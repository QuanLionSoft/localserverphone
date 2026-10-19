package dev.pocketpaas.data.remote

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One request per connection; headers have a fixed deadline independent of deployment time. */
internal class RemoteConnectionGuard(
    private val connections: AtomicInteger,
    private val maximum: Int,
    private val headerTimeoutMillis: Long,
) : ChannelDuplexHandler() {
    private var admitted = false
    private var initialized = false
    private var requested = false
    private var finalResponse = false
    private var deadline: ScheduledFuture<*>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (initialized) {
            if (admitted) ctx.fireChannelActive()
            return
        }
        initialized = true
        if (connections.incrementAndGet() > maximum) {
            connections.decrementAndGet()
            ctx.close()
            return
        }
        admitted = true
        deadline = ctx.executor().schedule({ ctx.close() }, headerTimeoutMillis, TimeUnit.MILLISECONDS)
        ctx.fireChannelActive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (!admitted || (msg is HttpRequest && requested)) {
            ReferenceCountUtil.release(msg)
            ctx.close()
            return
        }
        if (msg is HttpRequest) {
            requested = true
            deadline?.cancel(false)
            deadline = null
        }
        ctx.fireChannelRead(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        deadline?.cancel(false)
        deadline = null
        if (admitted) {
            admitted = false
            connections.decrementAndGet()
        }
        ctx.fireChannelInactive()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is HttpResponse) finalResponse = msg.status().code() >= 200
        if (msg is LastHttpContent && finalResponse) {
            // Netty can defer ordinary connection shutdown until an advertised request body
            // drains. A rejected upload must close after its response even if no body arrives.
            val completion = promise.unvoid()
            completion.addListener(ChannelFutureListener.CLOSE)
            ctx.write(msg, completion)
            ctx.flush()
        } else {
            ctx.write(msg, promise)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Parser failures never echo raw headers, URLs, credentials or decoder messages.
        ctx.close()
    }
}
