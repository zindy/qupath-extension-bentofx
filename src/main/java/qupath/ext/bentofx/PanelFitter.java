package qupath.ext.bentofx;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Prepares the root of a captured dialog so that it fills a BentoFX leaf.
 * <p>
 * Dialog content is usually designed for a window that is sized to the scene, so the root often
 * has {@code min/max = USE_PREF_SIZE} (LogViewer, InstanSegController, ...). Inside a layout that
 * gives it more room than its preferred size, such a node is simply centred at its preferred size.
 * <p>
 * Two steps:
 * <ol>
 *   <li>{@link #peel}: drop anonymous wrappers (e.g. the {@code new BorderPane(pane)} that a
 *       dialog puts around its real content) until reaching the "meat".</li>
 *   <li>{@link #uncapChain}: walk down the dominant-child chain (BorderPane centre, ScrollPane
 *       content, TitledPane content, sole child of a Pane) and remove size caps and fill limits.</li>
 * </ol>
 * Must be called on the JavaFX application thread.
 */
final class PanelFitter {

    private static final Logger logger = LoggerFactory.getLogger(PanelFitter.class);

    /** Floors so that a pane can never be dragged down to zero/negative size. */
    static final double MIN_PANEL_W = 160;
    static final double MIN_PANEL_H = 120;
    static final double MIN_VIEWER_W = 160;
    static final double MIN_VIEWER_H = 120;

    private PanelFitter() {}

    /**
     * @param root       root of the captured scene (already detached from its stage)
     * @param scrollable wrap in a ScrollPane so the panel can be smaller than its minimum size
     *                   (recommended for narrow, form-like panels such as InstanSeg)
     * @return the node to hand to {@code Dockable.setNode(...)}
     */
    static Fitted fit(Parent root, boolean scrollable) {
        logChain("captured root", root);

        List<Runnable> undo = new ArrayList<>();
        Node content = peel(root, undo);
        uncapChain(content);

        logChain("fitted content", content);

        if (!scrollable)
            return new Fitted(content, () -> undoAll(undo));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPadding(Insets.EMPTY);
        scroll.getStyleClass().add("edge-to-edge");
        // A ScrollPane's own minimum is only a few pixels: without a floor the SplitPane
        // lets the leaf be squashed to nothing.
        enforceMinSize(scroll, MIN_PANEL_W, MIN_PANEL_H);
        return new Fitted(scroll, () -> {
            scroll.setContent(null);
            undoAll(undo);
        });
    }

    /**
     * Result of {@link #fit}: the node to dock, and an action that restores the original node hierarchy
     * (wrappers and parent/child links; size-cap changes are left in place, they are harmless in a window).
     */
    record Fitted(Node node, Runnable undo) {}

    private static void undoAll(List<Runnable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--)
            undo.get(i).run();
    }

    /**
     * The minimum sizes a node had before {@link #enforceMinSize} raised them. QuPath sets an explicit
     * minimum of 1x1 on every viewer pane (otherwise the canvas, which is not resizable, makes the current
     * size the minimum), so "computed" is NOT the original value and must not be used for restoring.
     * Weak keys: the values do not reference the nodes.
     */
    private static final Map<Node, double[]> ORIGINAL_MIN = new WeakHashMap<>();

    /** Undo {@link #enforceMinSize}: put back exactly the minimum sizes the node had before. */
    static void resetMinSize(Node node) {
        double[] original = ORIGINAL_MIN.remove(node);
        if (original != null && node instanceof Region r) {
            r.setMinWidth(original[0]);
            r.setMinHeight(original[1]);
        }
    }

    /**
     * Raise (never lower) the minimum size of a region. A minimum that is USE_PREF_SIZE is left alone,
     * since that is already at least as large as the preferred size.
     * Minimum sizes propagate: DockContainerLeaf is a StackPane and ContentWrapper a BorderPane, so
     * the leaf's minimum follows its dockable's node, and SplitPane honours item minimums.
     */
    static void enforceMinSize(Node node, double minW, double minH) {
        if (!(node instanceof Region r))
            return;
        double w = r.getMinWidth();
        double h = r.getMinHeight();
        boolean raiseW = w != Region.USE_PREF_SIZE && w < minW;
        boolean raiseH = h != Region.USE_PREF_SIZE && h < minH;
        if (raiseW || raiseH)
            ORIGINAL_MIN.putIfAbsent(node, new double[]{w, h});
        if (raiseW)
            r.setMinWidth(minW);
        if (raiseH)
            r.setMinHeight(minH);
    }

    // ------------------------------------------------------------------ peeling

    /** Remove pure wrappers until the first node that actually carries something. */
    static Node peel(Node node, List<Runnable> undo) {
        while (isPureWrapper(node)) {
            Parent wrapper = (Parent) node;
            Node child = soleChild(wrapper);
            detach(wrapper, child);
            undo.add(() -> reattach(wrapper, child));
            logger.debug("Peeled {} -> {}", describe(wrapper), describe(child));
            node = child;
        }
        return node;
    }

    /**
     * A wrapper is only peeled if dropping it cannot change appearance: exact layout class,
     * no stylesheets (fx:root controllers often carry their own CSS!), no id, no inline style,
     * no padding, and exactly one managed child.
     */
    private static boolean isPureWrapper(Node node) {
        if (!(node instanceof Parent parent))
            return false;
        Class<?> c = node.getClass();
        boolean known = c == Group.class || c == Pane.class || c == StackPane.class
                || c == VBox.class || c == HBox.class || c == BorderPane.class
                || c == AnchorPane.class;
        if (!known)
            return false;
        if (!parent.getStylesheets().isEmpty())
            return false;
        if (node.getId() != null)
            return false;
        String style = node.getStyle();
        if (style != null && !style.isBlank())
            return false;
        if (parent instanceof Region r && !Insets.EMPTY.equals(r.getPadding()))
            return false;
        return soleChild(parent) != null;
    }

    private static void detach(Parent wrapper, Node child) {
        if (wrapper instanceof BorderPane bp && bp.getCenter() == child)
            bp.setCenter(null);
        else if (wrapper instanceof Pane p)
            p.getChildren().remove(child);
        else if (wrapper instanceof Group g)
            g.getChildren().remove(child);
    }

    private static void reattach(Parent wrapper, Node child) {
        detachFromParent(child);
        if (wrapper instanceof BorderPane bp)
            bp.setCenter(child);
        else if (wrapper instanceof Pane p)
            p.getChildren().add(child);
        else if (wrapper instanceof Group g)
            g.getChildren().add(child);
    }

    /** Remove a node from whatever simple container currently holds it (no-op if it has no parent). */
    static void detachFromParent(Node node) {
        Parent parent = node.getParent();
        if (parent == null)
            return;
        if (parent instanceof BorderPane bp) {
            // Bento binds ContentWrapper.center to the selected tab; a bound property cannot be set.
            // Callers must release such nodes first (clear the leaf's selection).
            if (bp.centerProperty().isBound()) return;
            if (bp.getCenter() == node) bp.setCenter(null);
            else if (bp.getTop() == node) bp.setTop(null);
            else if (bp.getBottom() == node) bp.setBottom(null);
            else if (bp.getLeft() == node) bp.setLeft(null);
            else if (bp.getRight() == node) bp.setRight(null);
        } else if (parent instanceof Pane p) {
            p.getChildren().remove(node);
        } else if (parent instanceof Group g) {
            g.getChildren().remove(node);
        }
    }

    // ------------------------------------------------------------------ uncapping

    /** Walk the dominant-child chain, removing size caps and adding grow hints. */
    static void uncapChain(Node start) {
        Node node = start;
        while (node != null) {
            if (node instanceof Region r)
                uncap(r);
            if (node instanceof ScrollPane sp) {
                sp.setFitToWidth(true);
                sp.setFitToHeight(true);
            }
            Node child = dominantChild(node);
            if (child != null)
                grow(node, child);
            node = child;
        }
    }

    private static void uncap(Region r) {
        if (r.getMaxWidth() != Double.MAX_VALUE)
            r.setMaxWidth(Double.MAX_VALUE);
        if (r.getMaxHeight() != Double.MAX_VALUE)
            r.setMaxHeight(Double.MAX_VALUE);
        // USE_PREF_SIZE as minimum means "never shrink below preferred"
        if (r.getMinWidth() == Region.USE_PREF_SIZE)
            r.setMinWidth(Region.USE_COMPUTED_SIZE);
        if (r.getMinHeight() == Region.USE_PREF_SIZE)
            r.setMinHeight(Region.USE_COMPUTED_SIZE);
    }

    private static void grow(Node parent, Node child) {
        if (parent instanceof VBox v) {
            v.setFillWidth(true);
            VBox.setVgrow(child, Priority.ALWAYS);
        } else if (parent instanceof HBox h) {
            h.setFillHeight(true);
            HBox.setHgrow(child, Priority.ALWAYS);
        } else if (parent instanceof AnchorPane) {
            AnchorPane.setTopAnchor(child, 0.0);
            AnchorPane.setBottomAnchor(child, 0.0);
            AnchorPane.setLeftAnchor(child, 0.0);
            AnchorPane.setRightAnchor(child, 0.0);
        }
        // BorderPane centre and StackPane already fill once max is lifted
    }

    // ------------------------------------------------------------------ child selection

    /** The child that carries the panel's "meat", or null if there is no single obvious one. */
    private static Node dominantChild(Node node) {
        if (node instanceof BorderPane bp)
            return bp.getCenter();
        if (node instanceof ScrollPane sp)
            return sp.getContent();
        if (node instanceof TitledPane tp)
            return tp.getContent();
        if (node instanceof Control)
            return null;            // SplitPane, TabPane, TableView...: they manage their own children
        if (node instanceof Parent p)
            return soleChild(p);
        return null;
    }

    /** The single managed, visible child, or null if there are zero or several (or BorderPane sides). */
    private static Node soleChild(Parent p) {
        if (p instanceof BorderPane bp) {
            boolean sidesEmpty = bp.getTop() == null && bp.getBottom() == null
                    && bp.getLeft() == null && bp.getRight() == null;
            return sidesEmpty ? bp.getCenter() : null;
        }
        List<Node> kids = p.getChildrenUnmodifiable().stream()
                .filter(n -> n.isManaged() && n.isVisible())
                .toList();
        return kids.size() == 1 ? kids.get(0) : null;
    }

    // ------------------------------------------------------------------ diagnostics

    /** Logs class / min / pref / max along the dominant chain, to see where a cap sits. */
    static void logChain(String label, Node start) {
        if (!logger.isDebugEnabled())
            return;
        StringBuilder sb = new StringBuilder(label).append('\n');
        Node node = start;
        int depth = 0;
        while (node != null) {
            sb.append("  ".repeat(depth++)).append(describe(node)).append('\n');
            node = dominantChild(node);
        }
        logger.debug(sb.toString());
    }

    private static String describe(Node n) {
        StringBuilder sb = new StringBuilder(n.getClass().getSimpleName());
        if (n.getId() != null)
            sb.append('#').append(n.getId());
        if (n instanceof Region r) {
            sb.append(String.format(" min=%s,%s pref=%s,%s max=%s,%s",
                    sz(r.getMinWidth()), sz(r.getMinHeight()),
                    sz(r.getPrefWidth()), sz(r.getPrefHeight()),
                    sz(r.getMaxWidth()), sz(r.getMaxHeight())));
            if (!r.getStylesheets().isEmpty())
                sb.append(" [own stylesheets]");
        }
        return sb.toString();
    }

    private static String sz(double d) {
        if (d == Region.USE_COMPUTED_SIZE) return "COMPUTED";
        if (d == Region.USE_PREF_SIZE) return "PREF";
        if (d == Double.MAX_VALUE) return "MAX";
        return String.valueOf(d);
    }
}
