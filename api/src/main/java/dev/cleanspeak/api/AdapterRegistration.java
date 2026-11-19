package dev.cleanspeak.api;

import java.util.Objects;

/**
 * Informational registration for another plugin or optional adapter.
 *
 * <p>Registrations are shown in staff status output so operators can see which
 * routes are actually integrated instead of assuming a command is protected.
 * They do not modify routing and do not make a route protected.</p>
 *
 * @param id stable integration id, for example {@code my-pm-plugin}
 * @param version plugin or adapter version
 * @param route command or route names, for example {@code /tell,/msg,/reply}
 * @param preDelivery {@code true} when the integration checks messages before
 *                    delivery rather than observing after the fact
 * @param notes short operator-facing detail; must not contain player text
 */
public record AdapterRegistration(
        String id,
        String version,
        String route,
        boolean preDelivery,
        String notes
) {

    /**
     * Validates a registration.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public AdapterRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(notes, "notes");
    }
}
