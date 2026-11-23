package com.hopa.sidecar

import com.hopa.backend.link.BackendClient
import com.hopa.backend.link.BackendLink
import com.hopa.backend.pairing.BackendCredentials
import com.hopa.backend.pairing.BackendPairing as CorePairing
import com.hopa.contracts.chat.Attachment
import com.hopa.contracts.json.HopaJson
import com.hopa.contracts.wire.Approver
import com.hopa.sidecar.Protocol.asObject
import com.hopa.sidecar.Protocol.text
import com.hopa.sidecar.Protocol.type
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val USAGE = """hopa-sidecar — the HOPA engine host for Windows

Usage:
  hopa-sidecar                              speak NDJSON on stdin/stdout (the window)
  hopa-sidecar --turn "say hello"           run one turn headless and print JSON
  hopa-sidecar --pair-backend <url>         admit this PC to the backend, then speak NDJSON
  hopa-sidecar --version

Options:
  --turn <goal>          run one turn, print the turn envelope on stdout, exit
  --data-dir <path>      conversations, records and attachments (default ~/.hopa/windows)
  --conversation <id>    continue that conversation instead of creating one
  --pair-backend <url>   admit synchronously before the stdio loop, printing the QR on stderr
  --help                 print this text

Headless is deterministic by construction: no model key is loaded, so the engine
uses the scripted proposer, and no host is attached, so approvals deny and
actions refuse. Nothing in headless mode can allow an action."""

fun main(args: Array<String>) {
    val options = runCatching { CliOptions.parse(args) }.getOrElse { error ->
        System.err.println("hopa-sidecar: ${error.message}")
        System.err.println(USAGE)
        exitProcess(2)
    }
    if (options.help) {
        println(USAGE)
        return
    }
    if (options.version) {
        println("hopa-sidecar protocol v${Protocol.VERSION}")
        return
    }
    if (options.turn != null) {
        exitProcess(runHeadless(options))
    }
    runProtocol(options)
}

/** One turn through the real loop, the real gate and the fail-closed stubs. */
private fun runHeadless(options: CliOptions): Int {
    val io = Stdio()
    val dataDir = options.dataDir ?: defaultDataDir()
    val store = ChatStore(dataDir.resolve("conversations.json"))
    val records = RecordStore(dataDir.resolve("records.json"))
    val approvals = DenyAllApproval()
    val engine = Engine(
        io = io,
        hostDevice = HeadlessDevice(),
        approval = approvals,
        store = store,
        recordStore = records,
        config = RuntimeConfig(),
        forceStub = true,
        deviceAttached = false,
    )
    val outcome = runBlocking {
        engine.runTurn(
            id = "cli-1",
            goal = options.turn.orEmpty(),
            conversationId = options.conversationId,
        )
    }
    println(Protocol.headlessTurn("cli-1", outcome).toString())
    io.log(
        "headless turn done: records=${outcome.records.size} " +
            "approvals=${approvals.requests} chainOk=${outcome.chainOk}",
    )
    return 0
}

/** The window path: one message per line in, one message per line out. */
private fun runProtocol(options: CliOptions) {
    val io = Stdio()
    val device = HostDevice(io)
    val dataDir = options.dataDir ?: defaultDataDir()
    val store = ChatStore(dataDir.resolve("conversations.json"))
    val approvals = PendingApprovals(dataDir.resolve("approvals.json"))
    val records = RecordStore(dataDir.resolve("records.json"))
    val config = RuntimeConfig()
    val approval = HostApproval(io, approvals)
    val attachments = Attachments(dataDir.resolve("attachments"))
    val tasks = TaskStore()
    val engine = Engine(io, device, approval, store, records, config)
    val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessionPath = dataDir.resolve("backend-session.json")
    val sessionStore = BackendSessionStore(sessionPath)
    val pairingGate = PairingGate()
    val runner = EngineTaskRunner(
        startTurn = { id, goal -> engine.startTurn(id, goal, conversationId = null) },
        cancelTurn = engine::cancel,
    )
    engine.onTurnEnd = runner::complete
    val gateways = GatewayHost(
        io = io,
        store = store,
        records = records,
        sessionStore = sessionStore,
        runner = runner,
        scope = backendScope,
    )

    fun startStoredSession(): Boolean {
        val session = sessionStore.load()
        if (session == null) {
            if (Files.exists(sessionPath)) {
                io.log("backend admission: stored session is unreadable; encrypted tasks stay off until this PC pairs again")
            } else {
                io.log("backend admission: none; encrypted tasks stay off until a phone pairs this PC")
            }
            return false
        }
        val contentKey = backendContentKey(session)
        if (contentKey == null) {
            io.log("backend admission: found device ${session.deviceId}, but its content key is invalid; encrypted tasks stay off")
            return false
        }
        io.log("backend admission: found device ${session.deviceId} in household ${session.householdId}")
        gateways.start(session, contentKey)
        return true
    }

    // `--pair-backend <url>` admits synchronously before the stdio loop, so a
    // host can launch the sidecar and watch stderr for the QR. A failure keeps
    // an existing stored session and lets the protocol command retry later.
    var started = false
    if (options.pairBackend != null) {
        try {
            runBlocking { BackendPairing(dataDir).admit(options.pairBackend, "Windows PC") }
            started = startStoredSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            io.log("backend admission failed (${failed.javaClass.simpleName})")
        }
    }
    if (!started) startStoredSession()

    io.send(Protocol.ready())
    val denied = approvals.restoreAndDeny()
    if (denied > 0) {
        io.log("denied $denied pending approval(s) on restart")
    }
    io.log("ready (protocol v${Protocol.VERSION}), conversations at $dataDir, runs=${records.count()}")
    while (true) {
        val line = readLine() ?: break
        if (line.isBlank()) continue
        val message = line.asObject() ?: continue
        when (message.type()) {
            "hello" -> io.send(Protocol.ready())
            "conversations.list" ->
                io.send(
                    Protocol.conversationList(
                        message.text("id"),
                        store.list(message.text("includeArchived").toBoolean()),
                    ),
                )

            "conversation.pin" ->
                io.send(
                    Protocol.conversationOpened(
                        message.text("id"),
                        store.setPinned(message.text("conversationId"), message.text("pinned").toBoolean()),
                    ),
                )

            "conversation.archive" ->
                io.send(
                    Protocol.conversationOpened(
                        message.text("id"),
                        store.setArchived(message.text("conversationId"), message.text("archived").toBoolean()),
                    ),
                )

            "conversation.create" ->
                io.send(Protocol.conversationOpened(message.text("id"), store.create()))

            "conversation.open" ->
                io.send(Protocol.conversationOpened(message.text("id"), store.get(message.text("conversationId"))))

            "conversation.search" ->
                io.send(Protocol.conversationList(message.text("id"), store.search(message.text("query"))))

            "conversation.rename" ->
                io.send(
                    Protocol.conversationOpened(
                        message.text("id"),
                        store.rename(message.text("conversationId"), message.text("title")),
                    ),
                )

            "conversation.delete" ->
                io.send(
                    Protocol.conversationDeleted(
                        message.text("id"),
                        store.delete(message.text("conversationId")),
                    ),
                )

            "conversation.export" ->
                io.send(
                    Protocol.conversationExported(
                        message.text("id"),
                        store.get(message.text("conversationId")),
                    ),
                )

            "configure" -> {
                config.update(message)
                io.log("configured: ${config.summary()}")
                io.send(Protocol.configured(message.text("id"), config.useBridge, config.modelProvider))
            }

            "turn.start" -> {
                val attachment = (message["attachment"] as? JsonObject)?.let {
                    HopaJson.element.decodeFromJsonElement(Attachment.serializer(), it)
                }
                engine.startTurn(
                    id = message.text("id"),
                    goal = message.text("goal"),
                    conversationId = message.text("conversationId").ifBlank { null },
                    attachment = attachment,
                )
            }

            "file.attach" -> {
                val path = message.text("path")
                val result = runCatching { attachments.accept(Path.of(path)) }
                io.send(
                    Protocol.fileAttached(
                        id = message.text("id"),
                        attachment = result.getOrNull(),
                        error = result.exceptionOrNull()?.message,
                    ),
                )
            }

            "file.remove" -> {
                attachments.remove(message.text("path"))
                io.send(Protocol.fileRemoved(message.text("id"), true))
            }

            "turn.cancel" -> {
                val turnId = message.text("id")
                if (turnId.isBlank()) engine.cancel() else engine.cancel(turnId)
            }

            // P4: the task shapes are implemented against protocol.md. The
            // transport that carries them to the phone is added with the phone
            // receiver; until then a task can be created and handed to "pc"
            // locally, and a handoff to "phone" fails honestly.
            "task.start" -> {
                val from = message.text("device").ifBlank { "pc" }
                val result = runCatching {
                    tasks.create(
                        id = message.text("taskId"),
                        goal = message.text("goal"),
                        from = from,
                    )
                }
                val task = result.getOrNull()
                if (task == null) {
                    io.send(Protocol.taskError(
                        message.text("id"), "task.start", "E_SCHEMA",
                        result.exceptionOrNull()?.message ?: "bad task",
                    ))
                } else {
                    io.send(Protocol.task(message.text("id"), task))
                }
            }

            "task.get" -> {
                val task = tasks.get(message.text("taskId"))
                if (task == null) {
                    io.send(Protocol.taskError(message.text("id"), "task.get", "E_SCHEMA", "unknown task"))
                } else {
                    io.send(Protocol.task(message.text("id"), task, "task.get"))
                }
            }

            "task.list" -> io.send(Protocol.taskList(message.text("id"), tasks.list()))

            "task.handoff" -> {
                val target = message.text("target")
                val result = runCatching { tasks.handoff(message.text("taskId"), target) }
                val task = result.getOrNull()
                if (task == null) {
                    io.send(Protocol.taskError(
                        message.text("id"), "task.handoff", "E_SCHEMA",
                        result.exceptionOrNull()?.message ?: "unknown task",
                    ))
                } else {
                    io.send(Protocol.task(message.text("id"), task, "task.handoff"))
                }
            }

            // The host forwards gateway task events; `from` is the server-set
            // device id, mapped to pc/phone. A `platform` on a result becomes
            // the approver label on the record (ADR-0024).
            "task.event" -> {
                val result = runCatching {
                    val platform = message.text("platform")
                    tasks.apply(
                        taskId = message.text("taskId"),
                        type = message.text("event"),
                        from = message.text("from").ifBlank { "pc" },
                        at = message.text("at").toLongOrNull() ?: System.currentTimeMillis(),
                        approver = platform.takeIf { it.isNotBlank() }?.let { Approver.fromPlatform(it) },
                        error = message.text("error").ifBlank { null },
                    )
                }
                val task = result.getOrNull()
                if (task == null) {
                    io.send(Protocol.taskError(
                        message.text("id"), "task.event", "E_SCHEMA",
                        result.exceptionOrNull()?.message ?: "unknown task",
                    ))
                } else {
                    io.send(Protocol.task(message.text("id"), task, "task.event"))
                }
            }

            "device.result" -> device.resolve(message.text("id"), message)

            // The .NET host owns the gateway socket. It tells the sidecar only
            // whether a phone peer is reachable, so a phone answer can be
            // attributed honestly and a forged one is refused.
            "gateway.state" -> config.setGatewayState(
                paired = message.text("paired").toBoolean(),
                phoneOnline = message.text("phoneOnline").toBoolean(),
            )
            "approval.result" -> {
                val id = message.text("id")
                val claimed = message.text("device").ifBlank { "pc" }
                val allow = message.text("allow").toBoolean()
                // ADR-0024: a phone answer cannot be trusted when no phone link
                // exists — there is no path for the phone to have answered.
                // Refuse it rather than believe the claim (fail closed).
                if (claimed == "phone" && !config.hasPhoneLink) {
                    io.log("refused an approval claiming the phone: no phone link is attached")
                    approval.resolve(id, false, "pc")
                } else {
                    approval.resolve(id, allow, claimed)
                }
            }

            // Pairing polls and prints the QR on stderr, so it must not block
            // the stdin loop. The reply carries identifiers only. Only one
            // admission runs at a time: a second command while the first is
            // unresolved is refused immediately, so two sessions are never
            // minted and the store is never written twice.
            "backend.pair" -> {
                val url = message.text("url").trim()
                val name = message.text("name").trim().ifBlank { "Windows PC" }
                if (url.isBlank()) {
                    io.send(Protocol.backendPair(ok = false, error = "IllegalArgumentException"))
                } else if (!pairingGate.tryStart()) {
                    io.send(Protocol.backendPair(ok = false, error = "already pairing"))
                } else {
                    backendScope.launch {
                        try {
                            BackendPairing(dataDir).admit(url, name)
                            val saved = sessionStore.load()
                                ?: throw IllegalStateException("admission did not store a session")
                            val key = backendContentKey(saved)
                                ?: throw IllegalStateException("admission stored an invalid content key")
                            gateways.start(saved, key)
                            io.send(Protocol.backendPair(ok = true))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failed: Exception) {
                            io.log("backend pairing failed (${failed.javaClass.simpleName})")
                            io.send(Protocol.backendPair(ok = false, error = failed.javaClass.simpleName))
                        } finally {
                            pairingGate.finish()
                        }
                    }
                }
            }

            "shutdown" -> return
            else -> io.log("unknown message: ${message.type()}")
        }
    }
}

/** `--pair-backend <url>`; a missing or flag-looking value is ignored. */
internal fun pairBackendUrl(args: Array<String>): String? {
    val index = args.indexOf("--pair-backend")
    if (index < 0) return null
    return args.getOrNull(index + 1)?.takeIf { it.isNotBlank() && !it.startsWith("--") }
}

/**
 * Builds and starts the gateway from a stored session. Both the boot path and
 * the `backend.pair` command go through this one helper, so refresh, diag state
 * and the turn adapter cannot drift between them.
 */
private class GatewayHost(
    private val io: Stdio,
    private val store: ChatStore,
    private val records: RecordStore,
    private val sessionStore: BackendSessionStore,
    private val runner: EngineTaskRunner,
    private val scope: CoroutineScope,
) {
    private var current: CoroutineScope? = null

    @Synchronized
    fun start(session: BackendSession, contentKey: ByteArray) {
        require(contentKey.size == CONTENT_KEY_BYTES) { "content key must be $CONTENT_KEY_BYTES bytes" }
        current?.cancel()
        var credentials = BackendCredentials(
            deviceId = session.deviceId,
            householdId = session.householdId,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAt = session.expiresAt,
            gatewayUrl = session.gatewayUrl,
        )
        val pairing = CorePairing(session.gatewayUrl)
        val link: BackendLink = BackendClient(
            credentials = { credentials },
            pushToken = { null },
            refresh = {
                // A refresh rotates the token pair but not the identity, so the
                // stored device and household are copied back in. An unreachable
                // backend is left as a throw for the client's own backoff; only
                // a successful rotation is persisted.
                val refreshed = pairing.refresh(credentials.refreshToken)
                credentials = refreshed.copy(
                    deviceId = session.deviceId,
                    householdId = session.householdId,
                    gatewayUrl = session.gatewayUrl,
                )
                runCatching {
                    sessionStore.save(
                        session.copy(
                            accessToken = refreshed.accessToken,
                            refreshToken = refreshed.refreshToken,
                            expiresAt = refreshed.expiresAt,
                        ),
                    )
                }.onFailure { failed ->
                    io.log("backend session could not be saved (${failed.javaClass.simpleName}); reconnecting with memory only")
                }
                credentials
            },
            deviceName = "Windows PC",
            appVersion = Protocol.VERSION.toString(),
        )
        val gateway = BackendGateway(
            link = link,
            contentKey = contentKey,
            run = runner::run,
            onDiagState = {
                buildJsonObject {
                    put("appVersion", Protocol.VERSION.toString())
                    put("conversations", store.list(includeArchived = true).size)
                    put("runs", records.count())
                }
            },
        )
        val live = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        current = live
        live.launch { gateway.runForever(live) }
    }

    private companion object {
        const val CONTENT_KEY_BYTES = 32
    }
}

private const val CONTENT_KEY_BYTES = 32

/** Decodes the stored content key; null means the file cannot be used. */
internal fun backendContentKey(session: BackendSession): ByteArray? =
    runCatching { Base64.getDecoder().decode(session.contentKeyBase64) }
        .getOrNull()
        ?.takeIf { it.size == CONTENT_KEY_BYTES }

private fun defaultDataDir(): Path =
    System.getenv("HOPA_DATA_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".hopa", "windows")

/** The `--turn` CLI. Unknown options fail closed instead of being ignored. */
internal data class CliOptions(
    val turn: String? = null,
    val dataDir: Path? = null,
    val conversationId: String? = null,
    val pairBackend: String? = null,
    val help: Boolean = false,
    val version: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var turn: String? = null
            var dataDir: Path? = null
            var conversationId: String? = null
            var pairBackend: String? = null
            var help = false
            var version = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                when {
                    arg == "--turn" -> {
                        turn = args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--turn needs a goal")
                    }

                    arg.startsWith("--turn=") -> turn = arg.removePrefix("--turn=")

                    arg == "--data-dir" -> {
                        dataDir = Path.of(
                            args.getOrNull(++index)
                                ?: throw IllegalArgumentException("--data-dir needs a path"),
                        )
                    }

                    arg.startsWith("--data-dir=") -> dataDir = Path.of(arg.removePrefix("--data-dir="))

                    arg == "--conversation" -> {
                        conversationId = args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--conversation needs an id")
                    }

                    arg.startsWith("--conversation=") ->
                        conversationId = arg.removePrefix("--conversation=")

                    arg == "--pair-backend" -> {
                        pairBackend = args.getOrNull(++index)
                            ?.takeIf { it.isNotBlank() && !it.startsWith("--") }
                            ?: throw IllegalArgumentException("--pair-backend needs a URL")
                    }

                    arg.startsWith("--pair-backend=") ->
                        pairBackend = arg.removePrefix("--pair-backend=").takeIf { it.isNotBlank() }

                    arg == "--help" || arg == "-h" -> help = true

                    arg == "--version" -> version = true

                    else -> throw IllegalArgumentException("unknown option: $arg")
                }
                index += 1
            }
            return CliOptions(
                turn = turn,
                dataDir = dataDir,
                conversationId = conversationId,
                pairBackend = pairBackend,
                help = help,
                version = version,
            )
        }
    }
}
