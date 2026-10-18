/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Execute the production coordinator with deliberately delayed transport and DIM workers. */
public final class InstrumentCloseReplay {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final class Rig implements InstrumentPanelCloseCoordinator.Host,
            InstrumentPanelLaunchCoordinator.Scheduler {
        final ArrayDeque<Runnable> io = new ArrayDeque<>();
        final List<Runnable> deadlines = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final InstrumentPanelCloseCoordinator controller = new InstrumentPanelCloseCoordinator(this, this);
        InstrumentPanelCloseCoordinator.Owner owner = InstrumentPanelCloseCoordinator.Owner.FREE;
        int finishes, revokes, mode = 3, releasePackets;
        boolean panel = true, token = true;
        public long now() { return 0; }
        public void after(long delay, Runnable action) { deadlines.add(action); }
        public void finishPanel() { panel = false; finishes++; }
        public void revokeToken(BooleanSupplier current, Runnable done) {
            io.add(() -> { if (current.getAsBoolean()) { token = false; revokes++; done.run(); } });
        }
        public void readOwner(BooleanSupplier current, Consumer<InstrumentPanelCloseCoordinator.Owner> done) {
            // The transport result can arrive after cancellation. Coordinator must discard it.
            io.add(() -> done.accept(owner));
        }
        public void restoreDim(BooleanSupplier current, Consumer<Boolean> done) {
            io.add(() -> {
                if (!current.getAsBoolean()) return;
                mode = 1; releasePackets++; done.accept(true);
            });
        }
        public void trace(String message) { log.add(message); }
        void on() { controller.cancel(); panel = true; token = true; mode = 3; }
        void drain() { while (!io.isEmpty()) io.remove().run(); }
        void expire() { for (Runnable task : new ArrayList<>(deadlines)) task.run(); }
    }
    public static void main(String[] args) {
        Rig r = new Rig();
        for (int cycle=0; cycle<10; cycle++) {
            r.on(); r.controller.close();
            check(!r.panel, "panel closes synchronously");
            r.drain();
            check(!r.token && r.mode==1 && r.releasePackets==cycle+1, "complete OFF cycle");
            r.on(); r.expire();
            check(r.panel && r.token && r.mode==3, "old deadline leaves ON intact");
        }
        // ON overtakes queued token revoke, owner read, or DIM restore respectively.
        for (int phase=0; phase<3; phase++) {
            r = new Rig(); r.controller.close();
            for (int i=0; i<phase; i++) r.io.remove().run();
            r.on(); r.drain(); r.expire();
            check(r.panel && r.token && r.mode==3 && r.releasePackets==0, "late OFF phase "+phase);
            check(r.finishes==1, "no asynchronous removal of the new task");
        }
        for (InstrumentPanelCloseCoordinator.Owner owner : new InstrumentPanelCloseCoordinator.Owner[]{
                InstrumentPanelCloseCoordinator.Owner.OTHER, InstrumentPanelCloseCoordinator.Owner.UNKNOWN}) {
            r = new Rig(); r.owner=owner; r.controller.close(); r.drain();
            check(!r.panel && !r.token && r.mode==3 && r.releasePackets==0, "preserve other/unknown owner");
        }
        for (int phase=0; phase<3; phase++) {
            r = new Rig(); r.controller.close();
            for (int i=0; i<phase; i++) r.io.remove().run();
            r.expire(); r.drain();
            check(r.releasePackets==0, "timeout invalidates queued writes");
        }
        r = new Rig(); r.controller.close(); r.io.remove().run();
        r.on(); r.controller.close(); r.drain();
        check(r.releasePackets==1 && r.mode==1 && r.finishes==2, "OFF ON OFF honors only latest close");
        System.out.println("PASS 10 cycles; late revoke/owner/DIM/deadline; foreign/unknown owner; OFF ON OFF");
    }
}
