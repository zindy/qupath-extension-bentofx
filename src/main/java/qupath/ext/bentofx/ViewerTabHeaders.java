package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.input.ScrollEvent;
import software.coley.bentofx.control.Headers;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.List;

/**
 * The tab strip of a leaf, with a browser-style "+" button placed right after the last tab.
 * <p>
 * The button is the <i>last child</i> of the strip but is not a tab: {@link #add(Node)} keeps it last when
 * tabs are appended, and {@link #layoutHorizontal()} lays the tabs out exactly like
 * {@code LinearItemPane} does and then places the button after the last one. BentoFX itself only ever
 * looks for {@code Header} children (and indexes into the dockables list, not the children), so an extra
 * child is invisible to it.
 * <p>
 * The button is shown only when the leaf holds viewers, and only when there is room for it after the last
 * tab. Otherwise {@link #inlineAddShownProperty()} is false and {@link ViewerTabHeaderPane} shows its
 * corner button instead, so there is always exactly one "+" in a viewer leaf.
 * <p>
 * Both directions are handled: for tabs at the top or bottom the button goes to the right of the last tab,
 * for tabs at the left or right it goes below it.
 * <p>
 * <b>Scrolling.</b> When there are more tabs than fit, the strip can be scrolled with the mouse wheel: the
 * horizontal (tilt) wheel, or the ordinary wheel while the strip overflows. Each step moves to the next tab
 * boundary, so tabs come into view whole. BentoFX only knows how to keep the selected tab in view, so the
 * layouts here keep a scroll offset instead: selecting a tab that is out of view scrolls just far enough to
 * show it, selecting one that is in view moves nothing.
 */
final class ViewerTabHeaders extends Headers {

    private final DockContainerLeaf leaf;
    private final Button addButton;
    private final BooleanProperty inlineAddShown = new SimpleBooleanProperty(false);
    private boolean relayoutPending;

    /** How far the tabs are scrolled along the strip, in pixels: 0 shows the first tab at the start. */
    private double scrollOffset;
    /** Bring the selected tab into view at the next layout (set when the selection or the tabs change). */
    private boolean revealSelected = true;
    /** Wheel movement not yet enough for a step (touchpads send many small amounts). */
    private double wheelAccumulated;
    private static final double WHEEL_STEP = 40;

    ViewerTabHeaders(DockContainerLeaf container, Orientation orientation, Side side) {
        super(container, orientation, side);
        this.leaf = container;
        addButton = ViewerTabHeaderPane.newAddButton(container, "inline-add-button");
        // Starts hidden; layoutHorizontal() / layoutVertical() decide. Must not be bound: LinearItemPane-style
        // layout toggles visible/managed itself.
        addButton.setVisible(false);
        addButton.setManaged(false);
        getChildren().add(addButton);

        // Scrolling: the selected tab is brought into view when the selection or the tabs change
        keepInViewProperty().addListener((obs, old, cur) -> revealSelected = true);
        getChildren().addListener((ListChangeListener<Node>) c -> revealSelected = true);
        addEventHandler(ScrollEvent.SCROLL, this::onScroll);
    }

    private boolean horizontal() {
        return getOrientation() == Orientation.HORIZONTAL;
    }

    /** The tabs, in order (everything but the "+" button). */
    private List<Node> tabs() {
        return getChildren().stream().filter(n -> n != addButton).toList();
    }

    private double extentOf(Node tab) {
        var b = tab.getBoundsInParent();
        return horizontal() ? b.getWidth() : b.getHeight();
    }

    /** Where each tab starts along the strip, then the end of the last one. */
    private double[] boundaries(List<Node> tabs) {
        double[] edges = new double[tabs.size() + 1];
        for (int i = 0; i < tabs.size(); i++)
            edges[i + 1] = edges[i] + (int) extentOf(tabs.get(i));
        return edges;
    }

    private double viewExtent() {
        return horizontal() ? (int) getWidth() : (int) getHeight();
    }

    private void onScroll(ScrollEvent e) {
        double dx = e.getDeltaX(), dy = e.getDeltaY();
        // Tilt wheel, else the ordinary wheel; in a vertical strip the other way round
        double delta = horizontal() ? (dx != 0 ? dx : dy) : (dy != 0 ? dy : dx);
        if (delta == 0)
            return;
        List<Node> tabs = tabs();
        double[] edges = boundaries(tabs);
        double maxOffset = Math.max(0, edges[edges.length - 1] - viewExtent());
        if (maxOffset <= 0)
            return;   // everything fits: nothing to scroll, leave the event to others

        // A positive delta is the content moving forward, i.e. looking back towards the first tab
        wheelAccumulated += delta;
        int steps = (int) (Math.abs(wheelAccumulated) / WHEEL_STEP);
        if (steps > 0) {
            int direction = wheelAccumulated < 0 ? 1 : -1;
            wheelAccumulated -= Math.signum(wheelAccumulated) * steps * WHEEL_STEP;
            double target = scrollOffset;
            for (int i = 0; i < steps; i++)
                target = nextBoundary(edges, target, direction);
            scrollOffset = Math.max(0, Math.min(maxOffset, target));
            revealSelected = false;   // the user has taken over until the selection changes
            requestLayout();
        }
        e.consume();
    }

    /** The tab boundary after (direction 1) or before (-1) the given position. */
    private static double nextBoundary(double[] edges, double from, int direction) {
        if (direction > 0) {
            for (double edge : edges)
                if (edge > from + 0.5)
                    return edge;
            return edges[edges.length - 1];
        }
        for (int i = edges.length - 1; i >= 0; i--)
            if (edges[i] < from - 0.5)
                return edges[i];
        return 0;
    }

    /**
     * Settle {@link #scrollOffset} for this layout pass: keep it in range, and if asked bring the selected tab
     * into view with the least movement.
     */
    private void settleScrollOffset(List<Node> tabs) {
        double[] edges = boundaries(tabs);
        double view = viewExtent();
        double maxOffset = Math.max(0, edges[edges.length - 1] - view);
        Node selected = keepInViewProperty().get();
        int index = selected == null ? -1 : tabs.indexOf(selected);
        if (revealSelected && index >= 0) {
            if (edges[index + 1] - scrollOffset > view)
                scrollOffset = edges[index + 1] - view;   // beyond the far end: align its far edge
            if (edges[index] < scrollOffset)
                scrollOffset = edges[index];              // before the near end (or wider than the strip)
        }
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset));
    }

    /** True while the inline "+" is visible, i.e. the corner "+" is not needed. */
    BooleanProperty inlineAddShownProperty() {
        return inlineAddShown;
    }

    /** Append a tab: before the "+" if there is one, which must stay last. */
    @Override
    public void add(Node node) {
        var children = getChildren();
        int n = children.size();
        if (addButton != null && n > 0 && children.get(n - 1) == addButton)
            children.add(n - 1, node);
        else
            children.add(node);
    }

    /**
     * Ask for one more layout pass once this one has finished (a {@code requestLayout()} made from inside
     * {@code layoutChildren()} is wiped when the pass ends). It settles by itself: a pass that finds every
     * tab already at its real width does not ask again.
     */
    private void relayoutLater() {
        if (relayoutPending)
            return;
        relayoutPending = true;
        Platform.runLater(() -> {
            relayoutPending = false;
            requestLayout();
        });
    }

    /** The same as {@link #layoutHorizontal()}, along the other axis: the "+" goes below the last tab. */
    @Override
    protected void layoutVertical() {
        List<Node> tabs = tabs();
        final int maxY = (int) getHeight();
        // The scroll offset (see the class comment) in place of "always keep the selected tab at the far end"
        settleScrollOffset(tabs);
        int y = (int) -scrollOffset;

        boolean overflow = false;
        boolean heightsChanged = false;
        for (Node child : tabs) {
            if (child instanceof Parent childParent)
                childParent.layout();
            var childBounds = child.getBoundsInParent();
            double childWidth = computeChildPerpendicularSize(childBounds, Orientation.VERTICAL);
            double childHeight = childBounds.getHeight();
            boolean visible = y + childHeight >= 0 && y < maxY;

            if (!child.visibleProperty().isBound()) {
                child.setManaged(visible);
                child.setVisible(visible);
            }
            if (visible) {
                layoutInArea(child, 0, y, childWidth, childHeight,
                        0, Insets.EMPTY, true, false,
                        HPos.LEFT, VPos.TOP);
                // Go by the real height after the resize (see layoutHorizontal)
                double actualHeight = child.getBoundsInParent().getHeight();
                if (Math.abs(actualHeight - childHeight) > 0.5) {
                    childHeight = actualHeight;
                    heightsChanged = true;
                }
            } else {
                overflow = true;
            }
            y += (int) childHeight;
        }
        overflowingProperty().set(overflow);
        if (heightsChanged)
            relayoutLater();

        // The "+" goes right below the last tab, if it is wanted and fits: as wide as the strip, and square
        double width = getWidth();
        for (Node tab : tabs)
            width = Math.max(width, tab.getBoundsInParent().getWidth());
        if (width <= 0)
            width = computeChildPerpendicularSize(addButton.getBoundsInParent(), Orientation.VERTICAL);
        double height = Math.max(addButton.prefHeight(width), width);
        boolean show = !overflow && ViewerTabHeaderPane.hostsViewers(leaf) && y + height <= maxY;
        addButton.setManaged(show);
        addButton.setVisible(show);
        if (show)
            addButton.resizeRelocate(0, y, width, height);
        inlineAddShown.set(show);
    }

    @Override
    protected void layoutHorizontal() {
        if (addButton == null) {
            super.layoutHorizontal();
            return;
        }
        // Same as LinearItemPane.layoutHorizontal(), but over the tabs only (not the "+" button), and with a
        // scroll offset in place of "always keep the selected tab at the far end"
        List<Node> tabs = tabs();
        final int maxX = (int) getWidth();
        settleScrollOffset(tabs);
        int x = (int) -scrollOffset;

        boolean overflow = false;
        boolean widthsChanged = false;
        for (Node child : tabs) {
            if (child instanceof Parent childParent)
                childParent.layout();
            var childBounds = child.getBoundsInParent();
            double childWidth = childBounds.getWidth();
            double childHeight = computeChildPerpendicularSize(childBounds, Orientation.HORIZONTAL);
            boolean visible = x + childWidth >= 0 && x < maxX;

            if (!child.visibleProperty().isBound()) {
                child.setManaged(visible);
                child.setVisible(visible);
            }
            if (visible) {
                layoutInArea(child, x, 0, childWidth, childHeight,
                        0, Insets.EMPTY, false, true,
                        HPos.LEFT, VPos.TOP);
                // childWidth was measured *before* this resize. When a title changes (a long image name
                // replaced by "New viewer" when a project closes, say) the tab has just been resized to
                // its new preferred width, so go by the real width, or the tabs after it (and the "+")
                // would be placed where this tab used to end.
                double actualWidth = child.getBoundsInParent().getWidth();
                if (Math.abs(actualWidth - childWidth) > 0.5) {
                    childWidth = actualWidth;
                    widthsChanged = true;
                }
            } else {
                overflow = true;
            }
            x += (int) childWidth;
        }
        overflowingProperty().set(overflow);
        if (widthsChanged)
            relayoutLater();

        // The "+" goes right after the last tab, if it is wanted and fits
        // Full height of the strip, like BentoFX's own corner buttons, so the icon is centred between its
        // top and bottom. (The tabs themselves can be shorter than the strip.)
        double height = getHeight();
        for (Node tab : tabs)
            height = Math.max(height, tab.getBoundsInParent().getHeight());
        if (height <= 0)
            height = computeChildPerpendicularSize(addButton.getBoundsInParent(), Orientation.HORIZONTAL);
        double width = Math.max(addButton.prefWidth(height), height);
        boolean show = !overflow && ViewerTabHeaderPane.hostsViewers(leaf) && x + width <= maxX;
        addButton.setManaged(show);
        addButton.setVisible(show);
        if (show) {
            // resizeRelocate rather than layoutInArea(): a Button's max size is its preferred size, so
            // layoutInArea would not stretch it to the area and it would stay top-aligned at its own height.
            addButton.resizeRelocate(x, 0, width, height);
        }
        inlineAddShown.set(show);
    }
}