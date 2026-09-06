package ac.grim.grimac.internal.storage.history;

import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.category.EventStreamCategory;
import ac.grim.grimac.api.storage.event.ViolationEvent;
import ac.grim.grimac.api.storage.history.SessionSummary;
import ac.grim.grimac.api.storage.kind.ops.EntityOps;
import ac.grim.grimac.api.storage.kind.ops.EventStreamOps;
import ac.grim.grimac.api.storage.model.ServerStartupRecord;
import ac.grim.grimac.api.storage.model.SessionRecord;
import ac.grim.grimac.api.storage.model.ViolationRecord;
import ac.grim.grimac.api.storage.query.Page;
import ac.grim.grimac.api.storage.query.Queries;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.checks.InMemoryCheckCatalogPersistence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings({"removal", "unchecked"})
class HistoryServiceImplStartupTest {
    private final UUID player = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private int startupQueries;

    private HistoryServiceImpl history(boolean batchCounts, boolean resolveStartups,
                                       UUID startupId, ServerStartupRecord startup) {
        SessionRecord session = new SessionRecord(sessionId, player, "legacy-server", 1000, 2000,
                2000, "legacy-version", "vanilla", 47, "1.8.8", null, startupId, List.of());
        DataStore store = (DataStore) Proxy.newProxyInstance(DataStore.class.getClassLoader(),
                new Class<?>[]{DataStore.class}, (proxy, method, args) -> {
                    Object result;
                    switch (method.getName()) {
                        case "countSessionsByPlayer" -> result = 1L;
                        case "countViolationsInSession" -> result = 7L;
                        case "countUniqueChecksInSession" -> result = 2L;
                        case "query" -> {
                            Queries.ListSessionsByPlayer query = (Queries.ListSessionsByPlayer) args[1];
                            assertEquals(player, query.player());
                            assertNull(query.cursor());
                            result = new Page<>(List.of(session), null);
                        }
                        case "execute" -> {
                            if (args[0] instanceof EventStreamOps.CountManyOp<?>) {
                                result = Map.of(sessionId, 7L);
                            } else if (args[0] instanceof EventStreamOps.CountDistinctOp) {
                                result = 2L;
                            } else if (args[0] instanceof EntityOps.GetManyOp<?, ?>) {
                                startupQueries++;
                                result = startup == null ? List.of() : List.of(startup);
                            } else {
                                throw new AssertionError("Unexpected operation: " + args[0]);
                            }
                        }
                        default -> throw new AssertionError("Unexpected datastore call: " + method.getName());
                    }
                    return CompletableFuture.completedFuture(result);
                });
        HistoryServiceImpl history = new HistoryServiceImpl(store,
                new CheckRegistry(new InMemoryCheckCatalogPersistence()), 10, 1000);
        if (batchCounts) {
            EventStreamCategory<ViolationEvent, ViolationRecord> category =
                    (EventStreamCategory<ViolationEvent, ViolationRecord>) Proxy.newProxyInstance(
                            EventStreamCategory.class.getClassLoader(), new Class<?>[]{EventStreamCategory.class},
                            (proxy, method, args) -> {
                                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                                throw new AssertionError("Unexpected category call: " + method.getName());
                            });
            history.withV2Violations(category);
        }
        if (resolveStartups) history.withV2Startups(Categories.SERVER_STARTUP);
        return history;
    }

    private SessionSummary summary(HistoryServiceImpl history) {
        SessionSummary result = history.listSessions(player, null, 10).toCompletableFuture().join().items().get(0);
        assertEquals(sessionId, result.sessionId());
        assertEquals(1, result.sessionOrdinal());
        assertEquals(7, result.violationCount());
        assertEquals(2, result.uniqueCheckCount());
        return result;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingStartupIdWithoutResolverKeepsLegacyMetadata(boolean batchCounts) {
        SessionSummary result = summary(history(batchCounts, false, null, null));
        assertEquals("legacy-server", result.serverName());
        assertEquals("legacy-version", result.grimVersion());
        assertEquals(0, startupQueries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingStartupIdWithResolverDoesNotQueryNullId(boolean batchCounts) {
        SessionSummary result = summary(history(batchCounts, true, null, null));
        assertEquals("legacy-server", result.serverName());
        assertEquals(0, startupQueries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingStartupRowKeepsLegacyMetadata(boolean batchCounts) {
        SessionSummary result = summary(history(batchCounts, true, UUID.randomUUID(), null));
        assertEquals("legacy-server", result.serverName());
        assertEquals("legacy-version", result.grimVersion());
        assertEquals(1, startupQueries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownStartupStillOverridesLegacyMetadata(boolean batchCounts) {
        UUID startupId = UUID.randomUUID();
        ServerStartupRecord startup = new ServerStartupRecord(startupId, UUID.randomUUID(),
                "resolved-server", "resolved-version", "1.21.11", null, 1, 2, 3, null, null);
        SessionSummary result = summary(history(batchCounts, true, startupId, startup));
        assertEquals("resolved-server", result.serverName());
        assertEquals("resolved-version", result.grimVersion());
        assertEquals(1, startupQueries);
    }
}
