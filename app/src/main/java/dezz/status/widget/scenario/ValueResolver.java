/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

/** Adapter implemented by each connector to expose normalized values to the local engine. */
@FunctionalInterface
public interface ValueResolver {
    /** Returns a normalized snapshot, or {@link Input#unavailable()} when it cannot be read. */
    Input resolve(ValueReference reference);
}
