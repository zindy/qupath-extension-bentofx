package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pixel-fixed panels, proportional viewers.
 * <p>
 * BentoFX (0.16.0) has no size policy of its own: a branch is a JavaFX SplitPane, which stores
 * divider positions as fractions <i>by index</i>. Adding/removing a child therefore shifts the
 * fractions onto different panes, and only {@code setContainerSizePx} (first/last child only) maps pixels
 * to fractions. This class adds the missing policy on top:
 * <ul>
 *   <li><b>Fixed</b>: a child whose dockables are all panels (analysis tabs / captured dialogs, i.e. none
 *       is a viewer). It keeps its pixel size along the branch orientation, and is marked
 *       {@code SplitPane.setResizableWithParent(false)} so window resizes don't touch it.</li>
 *   <li><b>Flex</b>: everything else (viewers). The space left after the fixed children is shared in
 *       proportion to the sizes the flex children had before the change.</li>
 * </ul>
 * How it works: on every ContainerChildAdded/Removed event it snapshots the children's current sizes
 * (JavaFX layout is lazy, so right after the structural change the old layout sizes are still there,
 * including any divider drag the user made), then after layout recomputes all divider positions of that
 * branch with the same pixel&lt;-&gt;fraction mapping the SplitPaneSkin uses:
 * {@code pos_k = (sum(size_0..k) + k*d + d/2) / total}.
 * <p>
 * Sizes for children that have none yet: a replaced container inherits the size of the one it
 * replaced (viewer splits); a re-added fixed container gets its last size back (analysis pane
 * toggle); a new fixed container gets the size its captured window had.
 */
final class PaneSizing {

    private static final Logger logger = LoggerFactory.getLogger(PaneSizing.class);

    /** Added to a captured window's height: the leaf also shows a tab strip. */
    private static final double TAB_STRIP_ALLOWANCE = 32;
    private static final double DEFAULT_FIXED_PX = 300;
    private static final double MIN_FLEX_PX = 80;

    /** Original window size {width, height} of captured dockables. */
    private final Map<Dockable, double[]> declared = new WeakHashMap<>();
    /** Last size of fixed containers that were removed (so they can come back at the same size). */
    private final Map<DockContainer, Double> remembered = new WeakHashMap<>();
    private final Map<DockContainerBranch, Pending> pending = new IdentityHashMap<>();

    private static final class Pending {
        final Map<DockContainer, Double> sizes = new IdentityHashMap<>();
        final ArrayDeque<Double> orphans = new ArrayDeque<>();   // sizes of removed flex children
        boolean waiting;
    }

    /** Record the size the captured window had, as the starting size of any pane created for it. */
    void declare(Dockable dockable, double width, double height) {
        if (width > 0 && height > 0)
            declared.put(dockable, new double[]{width, height + TAB_STRIP_ALLOWANCE});
    }

    /** Register with {@code bento.events().addEventListener(...)}. */
    void onDockEvent(DockEvent event) {
        if (event instanceof DockEvent.ContainerChildAdded added)
            touch(added.container(), null);
        else if (event instanceof DockEvent.ContainerChildRemoved removed)
            touch(removed.container(), removed.child());
    }

    // ------------------------------------------------------------------ snapshot

    private void touch(DockContainerBranch branch, DockContainer removed) {
        boolean horizontal = branch.getOrientation() == Orientation.HORIZONTAL;
        Pending p = pending.get(branch);
        if (p == null) {
            p = new Pending();
            // Not laid out yet after this change, so these are still the user-visible sizes.
            for (DockContainer c : branch.getChildContainers()) {
                double s = extent(c, horizontal);
                if (s > 0)
                    p.sizes.put(c, s);
            }
            pending.put(branch, p);
            Platform.runLater(() -> run(branch));
        }
        if (removed != null) {
            double s = extent(removed, horizontal);
            if (s > 0) {
                if (isPanelOnly(removed))
                    remembered.put(removed, s);
                else
                    p.orphans.add(s);
            }
        }
    }

    private void run(DockContainerBranch branch) {
        Pending p = pending.get(branch);
        if (p == null)
            return;
        if (apply(branch, p)) {
            pending.remove(branch);
            return;
        }
        defer(branch, p);
    }

    /** Branch not laid out yet (new branch, size 0): retry when it gets a size. */
    private void defer(DockContainerBranch branch, Pending p) {
        if (p.waiting)
            return;
        p.waiting = true;
        ChangeListener<Number> listener = new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Number> obs, Number o, Number n) {
                if (pending.get(branch) != p) {
                    detach();
                } else if (apply(branch, p)) {
                    pending.remove(branch);
                    detach();
                }
            }
            private void detach() {
                branch.widthProperty().removeListener(this);
                branch.heightProperty().removeListener(this);
            }
        };
        branch.widthProperty().addListener(listener);
        branch.heightProperty().addListener(listener);
    }

    // ------------------------------------------------------------------ apply

    /** @return false if the branch is not ready yet (no size / dividers), true when done or nothing to do */
    private boolean apply(DockContainerBranch branch, Pending p) {
        List<DockContainer> kids = new ArrayList<>(branch.getChildContainers());
        int n = kids.size();
        if (n < 2)
            return true;

        boolean horizontal = branch.getOrientation() == Orientation.HORIZONTAL;
        Insets in = branch.getInsets();
        double total = horizontal
                ? branch.getWidth() - in.getLeft() - in.getRight()
                : branch.getHeight() - in.getTop() - in.getBottom();
        Node divider = branch.getChildrenUnmodifiable().stream()
                .filter(x -> x.getStyleClass().contains("split-pane-divider"))
                .findFirst().orElse(null);
        if (total <= 0 || divider == null || branch.getDividers().size() != n - 1)
            return false;

        // The skin converts positions with prefWidth(-1) of the divider for both orientations.
        double d = divider.prefWidth(-1);
        double room = total - (n - 1) * d;
        if (room <= 0)
            return true;

        double minFixed = horizontal ? PanelFitter.MIN_PANEL_W : PanelFitter.MIN_PANEL_H;
        ArrayDeque<Double> orphans = new ArrayDeque<>(p.orphans);
        boolean[] fixed = new boolean[n];
        double[] size = new double[n];       // pixels for fixed; weight for flex
        double fixedSum = 0, knownFlexSum = 0;
        int flexCount = 0, knownFlexCount = 0;

        for (int i = 0; i < n; i++) {
            DockContainer c = kids.get(i);
            fixed[i] = isPanelOnly(c);
            Double known = p.sizes.get(c);
            if (fixed[i]) {
                double s = known != null ? known
                        : remembered.getOrDefault(c, declaredExtent(c, horizontal));
                size[i] = Math.max(s, minFixed);
                fixedSum += size[i];
            } else {
                flexCount++;
                if (known == null && !orphans.isEmpty())
                    known = orphans.poll();         // replacement inherits the replaced size
                if (known != null) {
                    size[i] = known;
                    knownFlexSum += known;
                    knownFlexCount++;
                } else {
                    size[i] = -1;                   // filled below
                }
            }
        }

        // Only panels in this branch: nothing is flexible, leave sizes to the SplitPane,
        // but let the last one follow the window so no gap opens up.
        if (flexCount == 0) {
            for (int i = 0; i < n; i++)
                SplitPane.setResizableWithParent(kids.get(i).asRegion(), i == n - 1);
            return true;
        }

        double avg = knownFlexCount > 0 ? knownFlexSum / knownFlexCount : 1;
        double weightSum = 0;
        for (int i = 0; i < n; i++) {
            if (!fixed[i]) {
                if (size[i] < 0)
                    size[i] = avg;
                weightSum += size[i];
            }
        }

        // Not enough room for the flex children: shrink the fixed ones, flex keeps a minimum.
        double flexRoom = room - fixedSum;
        double wanted = flexCount * MIN_FLEX_PX;
        if (flexRoom < wanted && fixedSum > 0) {
            double scale = Math.max(0, room - wanted) / fixedSum;
            fixedSum = 0;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    size[i] *= scale;
                    fixedSum += size[i];
                }
            }
            flexRoom = room - fixedSum;
        }

        double[] px = new double[n];
        for (int i = 0; i < n; i++)
            px[i] = fixed[i] ? size[i] : flexRoom * size[i] / weightSum;

        double[] pos = new double[n - 1];
        double acc = 0;
        for (int k = 0; k < n - 1; k++) {
            acc += px[k];
            pos[k] = (acc + k * d + d / 2) / total;
        }

        for (int i = 0; i < n; i++)
            SplitPane.setResizableWithParent(kids.get(i).asRegion(), !fixed[i]);
        branch.setDividerPositions(pos);

        if (logger.isDebugEnabled())
            logger.debug("Sized branch {} ({}): px={} fixed={}",
                    branch.getIdentifier(), horizontal ? "H" : "V", java.util.Arrays.toString(px), java.util.Arrays.toString(fixed));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** A container holding no viewer (and at least one dockable): analysis tabs and/or captured panels. */
    private static boolean isPanelOnly(DockContainer c) {
        List<Dockable> ds = c.getDockables();
        return !ds.isEmpty() && ds.stream().noneMatch(d -> d.getDragGroupMask() == DragGroups.VIEWER);
    }

    private static double extent(DockContainer c, boolean horizontal) {
        var r = c.asRegion();
        return horizontal ? r.getWidth() : r.getHeight();
    }

    private double declaredExtent(DockContainer c, boolean horizontal) {
        double best = 0;
        for (Dockable d : c.getDockables()) {
            double[] wh = declared.get(d);
            if (wh != null)
                best = Math.max(best, horizontal ? wh[0] : wh[1]);
        }
        return best > 0 ? best : DEFAULT_FIXED_PX;
    }
}
