package ac.grim.grimac.internal.storage.instance;

import ac.grim.grimac.api.storage.model.ServerStartupRecord;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Whether a server startup can still write to the store.
 *
 * <p>Grim backs this with the ownership lease: a startup is alive only while
 * its persistent id's lease is unexpired and names that startup as owner.
 * History rendering consults it so a dead server's open sessions read as
 * crashed before any repair has run. Crash repair consults it so a live
 * server is never repaired out from under itself.</p>
 */
@ApiStatus.Internal
@FunctionalInterface
public interface StartupLiveness {
    boolean isAlive(@NotNull ServerStartupRecord startup);
}
