package qupath.ext.bentofx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.ArrayList;
import java.util.List;

/**
 * Never leaves a viewer stuck in BentoFX's "collapsed" (minimised) state.
 * <p>
 * Clicking the selected tab collapses a leaf to its tab bar. BentoFX only allows that, <i>and</i> only allows
 * reversing it, while the leaf is the first or last of two or more children in a branch whose split
 * direction fits the tab bar's side. {@code setContainerCollapsed()} just returns {@code false} otherwise, so
 * a click on the tab does nothing. That can happen after the fact: drag the other viewer into a collapsed
 * leaf, and the leaf it came from disappears, leaving the collapsed leaf alone (or in a branch of another
 * direction). It then fills the window with nothing showing and cannot be expanded, only torn.
 * <p>
 * After each layout change this looks for collapsed leaves that can no longer be expanded by clicking, and
 * expands them. A collapsed leaf that is still valid is left alone.
 */
final class CollapseGuard {

    private static final Logger logger = LoggerFactory.getLogger(CollapseGuard.class);
    private static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");

    private final Bento bento;
    private boolean pending;
    private boolean disposed;

    CollapseGuard(Bento bento) {
        this.bento = bento;
    }

    /** Register with {@code bento.events().addEventListener(...)}. */
    void onDockEvent(DockEvent event) {
        if (event instanceof DockEvent.ContainerChildRemoved
                || event instanceof DockEvent.ContainerParentChanged
                || event instanceof DockEvent.DockableAdded
                || event instanceof DockEvent.DockableRemoved)
            schedule();
    }

    void dispose() {
        disposed = true;
    }

    /** After the operation that fired the event has finished (a drop fires several, in a row). */
    private void schedule() {
        if (pending || disposed)
            return;
        pending = true;
        Platform.runLater(() -> {
            pending = false;
            if (!disposed)
                expandStuckLeaves();
        });
    }

    private void expandStuckLeaves() {
        List<DockContainerLeaf> leaves = new ArrayList<>();
        for (var root : new ArrayList<>(bento.getRootContainers()))
            BentofxExtension.collectLeaves(root, leaves);
        for (DockContainerLeaf leaf : leaves) {
            if (leaf.isCollapsed() && !canToggle(leaf))
                expand(leaf);
        }
    }

    /** The conditions under which {@code DockContainerBranch.setContainerCollapsed()} will act on this leaf. */
    private static boolean canToggle(DockContainerLeaf leaf) {
        DockContainerBranch parent = leaf.getParentContainer();
        if (parent == null)
            return false;
        var children = parent.getChildContainers();
        if (children.size() <= 1)
            return false;
        int i = children.indexOf(leaf);
        if (i != 0 && i != children.size() - 1)
            return false;
        Side side = leaf.getSide();
        if (side == null)
            return false;
        Orientation orientation = parent.orientationProperty().get();
        if (orientation == Orientation.HORIZONTAL && (side == Side.TOP || side == Side.BOTTOM))
            return false;
        if (orientation == Orientation.VERTICAL && (side == Side.LEFT || side == Side.RIGHT))
            return false;
        return true;
    }

    /**
     * Un-collapse a leaf that BentoFX will no longer un-collapse. Collapsing a leaf (BentoFX's
     * {@code setCollapsedState()}) changes two things, and both have to be undone:
     * <ul>
     *   <li>its {@code collapsed} property, which the leaf's tab-bar pane binds the visibility <i>and
     *       managed state</i> of the content area to. Leave it set and the content stays hidden, and, being
     *       unmanaged, is never laid out again, even with a viewer attached to it;</li>
     *   <li>the CSS pseudo-class, which only styles it.</li>
     * </ul>
     * Sizes are deliberately left alone: such a leaf is alone in its branch, or has been moved into one of
     * another direction, where it keeps the room it was given. Only a collapsed leaf in a branch of the right
     * direction is squeezed to its tab bar, and that is the case that is never expanded here.
     */
    private static void expand(DockContainerLeaf leaf) {
        logger.info("Expanding a collapsed viewer panel that could no longer be expanded by clicking its tab");
        logger.debug("  before: {}", describe(leaf));
        leaf.collapsedProperty().set(false);
        leaf.pseudoClassStateChanged(COLLAPSED, false);

        // A collapsed leaf has nothing selected, so there may be nothing to show
        if (leaf.getSelectedDockable() == null && !leaf.getDockables().isEmpty())
            leaf.selectDockable(leaf.getDockables().get(0));

        // Once the layout has caught up
        PauseTransition later = new PauseTransition(Duration.millis(400));
        later.setOnFinished(e -> logger.debug("  after:  {}", describe(leaf)));
        later.play();
    }

    /** One line of state, for the log: where the leaf is, how big, and what it is showing. */
    private static String describe(DockContainerLeaf leaf) {
        StringBuilder sb = new StringBuilder();
        DockContainerBranch parent = leaf.getParentContainer();
        sb.append(String.format("leaf %.0fx%.0f, collapsed=%s, side=%s, parent=%s",
                leaf.getWidth(), leaf.getHeight(), leaf.isCollapsed(), leaf.getSide(),
                parent == null ? "none" : parent.orientationProperty().get() + " with " + parent.getChildContainers().size() + " child(ren)"));
        Dockable selected = leaf.getSelectedDockable();
        sb.append(", selected=").append(selected == null ? "none" : "'" + selected.getTitle() + "'");
        for (Dockable d : leaf.getDockables()) {
            Node node = d.getNode();
            sb.append(" | '").append(d.getTitle()).append("': ");
            if (node == null)
                sb.append("no node");
            else
                sb.append(String.format("%s %.0fx%.0f visible=%s inScene=%s parent=%s",
                        node.getClass().getSimpleName(),
                        node.getLayoutBounds().getWidth(), node.getLayoutBounds().getHeight(),
                        node.isVisible(), node.getScene() != null,
                        node.getParent() == null ? "none" : node.getParent().getClass().getSimpleName()));
        }
        if (selected != null && selected.getNode() != null) {
            sb.append(" || chain from the selected node up (class WxH, min/pref/max height):");
            for (Node n = selected.getNode(); n != null; n = n.getParent()) {
                sb.append(String.format(" <- %s %.0fx%.0f", n.getClass().getSimpleName(),
                        n.getLayoutBounds().getWidth(), n.getLayoutBounds().getHeight()));
                if (n instanceof Region r)
                    sb.append(String.format(" (%s/%s/%s)", size(r.getMinHeight()), size(r.getPrefHeight()), size(r.getMaxHeight())));
                if (n == leaf)
                    break;
            }
        }
        return sb.toString();
    }

    private static String size(double v) {
        if (v == Region.USE_COMPUTED_SIZE)
            return "auto";
        if (v == Region.USE_PREF_SIZE)
            return "pref";
        return v >= Double.MAX_VALUE ? "inf" : String.format("%.0f", v);
    }
}
