package com.aistudio.alphapulse.di

import android.content.Context
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.network.BinanceWebSocketManager
import com.aistudio.alphapulse.data.network.BybitProvider
import com.aistudio.alphapulse.data.network.FailoverExchangeProvider
import com.aistudio.alphapulse.data.network.HyperliquidProvider
import com.aistudio.alphapulse.data.network.ResilientBinanceClient
import com.aistudio.alphapulse.data.repository.AlphaPulseRepository
import java.io.File

/**
 * Minimal manual dependency container (Phase 0 composition seam).
 *
 * Every long-lived subsystem (network client, Room database, WebSocket
 * manager, repository) is created lazily and shared process-wide from this
 * single seam instead of being constructed ad-hoc inside ViewModel/Service.
 *
 * Test seam: unit/integration tests call [overrideForTesting] first, before
 * any production component is resolved, to substitute fakes/in-memory
 * implementations (e.g. Robolectric + in-memory Room). Production code always
 * resolves through [repository]/[database].
 */
object ServiceLocator {

    @Volatile
    private var overridden: Graph? = null

    @Volatile
    private var production: Graph? = null

/** Immutable wiring of the app's long-lived dependencies. */
class Graph(
    val client: ResilientBinanceClient,
    val database: AppDatabase,
    val wsManager: BinanceWebSocketManager,
    val repository: AlphaPulseRepository,
    val cacheDir: File?,
    val failoverProvider: FailoverExchangeProvider
)

    /**
     * Resolve the process-wide [Graph], building the production graph from
     * [context] on first access.
     */
    fun graph(context: Context): Graph {
        overridden?.let { return it }
        return production ?: synchronized(this) {
            production ?: buildProduction(context.applicationContext).also { production = it }
        }
    }

    /**
     * Install a test graph. Must be called before any UI/service component
     * resolves the locator. Test teardown should call [reset] to restore the
     * production graph for subsequent tests in the same JVM.
     */
    fun overrideForTesting(graph: Graph) {
        synchronized(this) { overridden = graph }
    }

    /** Drop the test override so production wiring is used again. */
    fun reset() {
        synchronized(this) { overridden = null }
    }

    /** Explicitly drop the built production graph (used by test teardown). */
    fun clearProduction() {
        synchronized(this) { production = null }
    }

    fun database(context: Context): AppDatabase = graph(context).database

    fun repository(context: Context): AlphaPulseRepository = graph(context).repository

    fun failoverProvider(context: Context): FailoverExchangeProvider = graph(context).failoverProvider

    private fun buildProduction(appContext: Context): Graph {
        val client = ResilientBinanceClient()
        val database = AppDatabase.getDatabase(appContext)
        val wsManager = BinanceWebSocketManager()
        val repository = AlphaPulseRepository(
            client = client,
            database = database,
            cacheDir = appContext.cacheDir,
            wsManager = wsManager
        )
        val failoverProvider = FailoverExchangeProvider(
            binanceProvider = com.aistudio.alphapulse.data.network.BinanceProvider(client, wsManager),
            bybitProvider = BybitProvider(),
            hyperliquidProvider = HyperliquidProvider()
        )
        return Graph(client, database, wsManager, repository, appContext.cacheDir, failoverProvider)
    }
}
