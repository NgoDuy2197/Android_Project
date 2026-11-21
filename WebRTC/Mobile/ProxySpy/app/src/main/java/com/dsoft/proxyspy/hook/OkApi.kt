package com.dsoft.proxyspy.hook

import java.lang.reflect.Method

/**
 * Reflective view of the OkHttp/Okio classes **of the hooked app**.
 *
 * The hook cannot link against OkHttp directly: the inspected app has its own copy, in its own
 * class loader, and a type from our APK would not be assignable to it. So every call goes through
 * reflection on classes resolved from the app's loader.
 *
 * OkHttp 3 and OkHttp 4 differ in the static factory signatures (`create(MediaType, byte[])` vs
 * `create(byte[], MediaType)`), so factories are discovered by scanning rather than assumed.
 */
class OkApi private constructor(private val cl: ClassLoader) {

    val interceptor: Class<*>? = find("okhttp3.Interceptor")
    private val chain = find("okhttp3.Interceptor\$Chain")
    private val request = find("okhttp3.Request")
    private val response = find("okhttp3.Response")
    private val responseBuilder = find("okhttp3.Response\$Builder")
    private val requestBody = find("okhttp3.RequestBody")
    private val responseBody = find("okhttp3.ResponseBody")
    private val mediaType = find("okhttp3.MediaType")
    private val protocol = find("okhttp3.Protocol")
    private val buffer = find("okio.Buffer")
    private val byteString = find("okio.ByteString")

    val usable: Boolean =
        interceptor != null && chain != null && request != null && response != null && requestBody != null &&
            responseBody != null && mediaType != null && buffer != null

    private fun find(name: String): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    // ------------------------------------------------------------------ generic call helpers

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = pick(target.javaClass, name, args.size) ?: return null
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private fun pick(c: Class<*>, name: String, argc: Int): Method? {
        var k: Class<*>? = c
        while (k != null) {
            k.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == argc }
                ?.let { return it }
            k = k.superclass
        }
        return c.methods.firstOrNull { it.name == name && it.parameterTypes.size == argc }
    }

    // ------------------------------------------------------------------ Interceptor.Chain
    //
    // The concrete chain class (RealInterceptorChain) is internal, so its methods are read off the
    // public interface and invoked on the instance.

    fun chainRequest(c: Any): Any? = runCatching {
        chain!!.getMethod("request").invoke(c)
    }.getOrNull()

    fun chainProceed(c: Any, req: Any): Any = chain!!.getMethod("proceed", request!!).let { m ->
        try {
            m.invoke(c, req)!!
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // ------------------------------------------------------------------ Request

    fun reqUrl(req: Any): String = call(req, "url")?.toString() ?: ""
    fun reqMethod(req: Any): String = call(req, "method") as? String ?: "GET"
    fun reqHeaders(req: Any): List<Pair<String, String>> = headerList(call(req, "headers"))
    fun reqBodyObj(req: Any): Any? = call(req, "body")

    fun reqBodyContentType(body: Any): String = call(body, "contentType")?.toString() ?: ""

    /** Materialises a request body. Returns null when it must not be read (duplex / streaming). */
    fun readRequestBody(body: Any?): ByteArray? {
        if (body == null) return null
        val b = buffer ?: return null
        // A one-shot or duplex body can only be written once: reading it here would leave the
        // app with an empty request. Those are skipped rather than captured.
        if (call(body, "isDuplex") == true || call(body, "isOneShot") == true) return null
        return runCatching {
            val sink = b.getDeclaredConstructor().newInstance()
            val writeTo = pick(body.javaClass, "writeTo", 1) ?: return null
            writeTo.isAccessible = true
            writeTo.invoke(body, sink)
            call(sink, "readByteArray") as? ByteArray
        }.getOrNull()
    }

    /** Rebuilds a request with a new URL / headers / body. Any null argument keeps the original. */
    fun rebuildRequest(
        req: Any,
        url: String?,
        method: String?,
        headers: List<Pair<String, String>>?,
        body: ByteArray?,
        bodyContentType: String?,
    ): Any {
        return runCatching {
            var builder = call(req, "newBuilder") ?: return req
            if (url != null) builder =
                callExact(builder, "url", arrayOf(String::class.java), arrayOf(url)) ?: builder
            if (headers != null) {
                builder = call(builder, "headers", buildHeaders(headers) ?: return@runCatching req) ?: builder
            }
            val m = method ?: reqMethod(req)
            if (body != null) {
                val ct = bodyContentType?.takeIf { it.isNotEmpty() }
                    ?: reqBodyObj(req)?.let { reqBodyContentType(it) } ?: ""
                val rb = makeRequestBody(body, ct) ?: return@runCatching req
                builder = callExact(builder, "method", arrayOf(String::class.java, requestBody!!), arrayOf(m, rb))
                    ?: builder
            } else if (method != null) {
                val old = reqBodyObj(req)
                builder = callExact(builder, "method", arrayOf(String::class.java, requestBody!!), arrayOf(m, old))
                    ?: builder
            }
            call(builder, "build") ?: req
        }.getOrDefault(req)
    }

    private fun callExact(target: Any, name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
        runCatching {
            val m = target.javaClass.getMethod(name, *types)
            m.isAccessible = true
            m.invoke(target, *args)
        }.getOrNull()

    // ------------------------------------------------------------------ Response

    fun resCode(res: Any): Int = (call(res, "code") as? Int) ?: -1
    fun resMessage(res: Any): String = call(res, "message") as? String ?: ""
    fun resHeaders(res: Any): List<Pair<String, String>> = headerList(call(res, "headers"))
    fun resBodyObj(res: Any): Any? = call(res, "body")
    fun resBodyContentType(res: Any): String =
        resBodyObj(res)?.let { call(it, "contentType")?.toString() } ?: ""

    /** Declared length of the response body, or -1 when the server did not say. */
    fun resContentLength(res: Any): Long =
        runCatching { (resBodyObj(res)?.let { call(it, "contentLength") } as? Long) ?: -1L }
            .getOrDefault(-1L)

    /** Consumes the response body. The caller must put the bytes back with [rebuildResponse]. */
    fun readResponseBody(res: Any): ByteArray? =
        runCatching { resBodyObj(res)?.let { call(it, "bytes") as? ByteArray } }.getOrNull()

    fun rebuildResponse(
        res: Any,
        code: Int?,
        message: String?,
        headers: List<Pair<String, String>>?,
        body: ByteArray?,
        contentType: String?,
    ): Any = runCatching {
        var b = call(res, "newBuilder") ?: return res
        if (code != null) b = callExact(b, "code", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(code)) ?: b
        if (message != null) b = call(b, "message", message) ?: b
        if (headers != null) b = call(b, "headers", buildHeaders(headers) ?: return@runCatching res) ?: b
        if (body != null) {
            val ct = contentType?.takeIf { it.isNotEmpty() } ?: resBodyContentType(res)
            val rb = makeResponseBody(body, ct) ?: return@runCatching res
            b = callExact(b, "body", arrayOf(responseBody!!), arrayOf(rb)) ?: b
        }
        call(b, "build") ?: res
    }.getOrDefault(res)

    /** Builds a complete response out of nothing — used to answer a mocked call locally. */
    fun mockResponse(req: Any, code: Int, message: String, headers: List<Pair<String, String>>, body: ByteArray, contentType: String): Any? =
        runCatching {
            val b = responseBuilder!!.getDeclaredConstructor().newInstance()
            callExact(b, "request", arrayOf(request!!), arrayOf(req))
            val http11 = protocol?.getField("HTTP_1_1")?.get(null)
            if (http11 != null) callExact(b, "protocol", arrayOf(protocol!!), arrayOf(http11))
            callExact(b, "code", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(code))
            call(b, "message", message)
            val hs = buildHeaders(headers)
            if (hs != null) call(b, "headers", hs)
            val rb = makeResponseBody(body, contentType)
            if (rb != null) callExact(b, "body", arrayOf(responseBody!!), arrayOf(rb))
            call(b, "build")
        }.getOrNull()

    // ------------------------------------------------------------------ Headers / bodies

    private fun headerList(headers: Any?): List<Pair<String, String>> {
        if (headers == null) return emptyList()
        val n = (call(headers, "size") as? Int) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>(n)
        for (i in 0 until n) {
            val k = call(headers, "name", i) as? String ?: continue
            val v = call(headers, "value", i) as? String ?: ""
            out.add(k to v)
        }
        return out
    }

    /** okhttp3.Headers via its Builder — `Headers.of(Map)` drops duplicate keys. */
    private fun buildHeaders(pairs: List<Pair<String, String>>): Any? = runCatching {
        val hb = Class.forName("okhttp3.Headers\$Builder", false, cl).getDeclaredConstructor().newInstance()
        for ((k, v) in pairs) {
            if (k.isBlank()) continue
            callExact(hb, "add", arrayOf(String::class.java, String::class.java), arrayOf(k, v))
        }
        call(hb, "build")
    }.getOrNull()

    fun parseMediaType(value: String): Any? {
        val cls = mediaType
        if (value.isEmpty() || cls == null) return null
        return runCatching {
            val m = cls.methods.firstOrNull {
                it.name == "parse" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            m?.invoke(null, value)
        }.getOrNull()
    }

    fun makeRequestBody(bytes: ByteArray, contentType: String): Any? =
        makeBody(requestBody, bytes, contentType)

    fun makeResponseBody(bytes: ByteArray, contentType: String): Any? =
        makeBody(responseBody, bytes, contentType)

    /**
     * Finds a `create(...)` factory taking a byte array and a media type, in whichever argument
     * order this OkHttp version declares, and fills any extra (offset, count) arguments.
     */
    private fun makeBody(owner: Class<*>?, bytes: ByteArray, contentType: String): Any? {
        if (owner == null) return null
        val mt = parseMediaType(contentType)
        val candidates = owner.methods.filter {
            it.name == "create" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.any { p -> p == ByteArray::class.java } &&
                it.parameterTypes.any { p -> p == mediaType }
        }.sortedBy { it.parameterTypes.size }
        for (m in candidates) {
            val args = m.parameterTypes.mapIndexed { i, p ->
                when {
                    p == ByteArray::class.java -> bytes
                    p == mediaType -> mt
                    p == Int::class.javaPrimitiveType -> if (i == m.parameterTypes.size - 1) bytes.size else 0
                    else -> null
                }
            }.toTypedArray()
            runCatching { return m.invoke(null, *args) }
        }
        return null
    }

    // ------------------------------------------------------------------ okio.ByteString

    fun byteStringBytes(bs: Any): ByteArray? = runCatching { call(bs, "toByteArray") as? ByteArray }.getOrNull()

    fun byteStringOf(bytes: ByteArray): Any? = runCatching {
        byteString?.getMethod("of", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            ?.invoke(null, bytes, 0, bytes.size)
    }.getOrNull() ?: runCatching {
        byteString?.getMethod("of", ByteArray::class.java)?.invoke(null, bytes)
    }.getOrNull()

    companion object {
        private val cache = HashMap<ClassLoader, OkApi>()

        @Synchronized
        fun of(cl: ClassLoader): OkApi = cache.getOrPut(cl) { OkApi(cl) }
    }
}
