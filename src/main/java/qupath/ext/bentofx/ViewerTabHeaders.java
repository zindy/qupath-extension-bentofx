package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
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
 * Only horizontal strips (tabs at the top or bottom) get the inline button; for tabs on the left or right
 * the corner button is always used.
 */
final class ViewerTabHeaders extends Headers {

    private final DockContainerLeaf leaf;
    /** Null for a vertical strip. */
    private final Button addButton;
    private final BooleanProperty inlineAddShown = new SimpleBooleanProperty(false);
    private boolean relayoutPending;

    ViewerTabHeaders(DockContainerLeaf container, Orientation orientation, Side side) {
        super(container, orientation, side);
        this.leaf = container;
        if (orientation == Orientation.HORIZONTAL) {
            addButton = ViewerTabHeaderPane.newAddButton(container, "inline-add-button");
            // Starts hidden; layoutHorizontal() decides. Must not be bound: LinearItemPane-style
            // layout toggles visible/managed itself.
            addButton.setVisible(false);
            addButton.setManaged(false);
            getChildren().add(addButton);
        } else {
            addButton = null;
        }
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

    @Override
    protected void layoutHorizontal() {
        if (addButton == null) {
            super.layoutHorizontal();
            return;
        }
        // Same as LinearItemPane.layoutHorizontal(), but over the tabs only (not the "+" button)
        List<Node> tabs = getChildren().stream().filter(n -> n != addButton).toList();
        final int maxX = (int) getWidth();
        int x = 0;

        // Offset initial X value to keep the selected tab in view
        Node viewTarget = keepInViewProperty().get();
        if (viewTarget != null) {
            double offset = 0;
            for (Node child : tabs) {
                offset += child.getBoundsInParent().getWidth();
                if (child == viewTarget) {
                    if (offset > maxX)
                        x = (int) (maxX - offset);
                    break;
                }
            }
        }

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
