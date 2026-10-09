package qupath.ext.bentofx;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import software.coley.bentofx.control.HeaderPane;
import software.coley.bentofx.control.Headers;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.function.Consumer;

/**
 * A {@link HeaderPane} that adds a "+" (new viewer) button to leaves that hold viewers.
 * <p>
 * Normally the "+" sits right after the last tab, drawn by {@link ViewerTabHeaders}. This class adds the
 * fallback: a "+" in the corner of the tab bar, shown only when the inline one is not (tabs fill the bar,
 * or the tabs are on the left/right side).
 * <p>
 * The action is passed in through the leaf's {@link javafx.scene.Node#getProperties() properties} under
 * {@link #ADD_VIEWER_HANDLER}: {@link HeaderPane}'s constructor calls {@link #createButtonArray()} itself,
 * before any field of this subclass is initialised, so this class cannot take the handler as a
 * constructor argument and keep it in a field (same trap as {@code DragGroups.newBento()}).
 */
final class ViewerTabHeaderPane extends HeaderPane {

    /** Key in the leaf's properties map holding a {@code Consumer<DockContainerLeaf>}. */
    static final String ADD_VIEWER_HANDLER = "qupath.ext.bentofx.addViewerHandler";

    ViewerTabHeaderPane(DockContainerLeaf container) {
        super(container);
    }

    /** "+" (when needed) first, then BentoFX's own "▼" (tab list) and "≡" (container menu) buttons. */
    @Override
    protected Node[] createButtonArray() {
        // Called from the super constructor: no instance state may be used here
        Node[] bento = super.createButtonArray();
        Node[] all = new Node[bento.length + 1];
        all[0] = createCornerAddButton();
        System.arraycopy(bento, 0, all, 1, bento.length);
        return all;
    }

    private Button createCornerAddButton() {
        DockContainerLeaf leaf = getContainer();
        Button button = newAddButton(leaf, "corner-button");

        BooleanBinding wanted = hostsViewersBinding(leaf);
        // getHeaders() is already the strip of this layout pass (see HeaderPane.recomputeLayout)
        Headers headers = getHeaders();
        if (headers instanceof ViewerTabHeaders inline)
            wanted = wanted.and(inline.inlineAddShownProperty().not());
        button.visibleProperty().bind(wanted);
        button.managedProperty().bind(button.visibleProperty());
        return button;
    }

    /** A "+" button that asks the extension (through the leaf's properties) to add a viewer tab to {@code leaf}. */
    @SuppressWarnings("unchecked")
    static Button newAddButton(DockContainerLeaf leaf, String styleClass) {
        Button button = new Button("+");
        button.setEllipsisString("+");
        button.getStyleClass().addAll(styleClass, "add-viewer-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("New viewer"));
        button.setOnAction(e -> {
            Object handler = leaf.getProperties().get(ADD_VIEWER_HANDLER);
            if (handler instanceof Consumer<?> c)
                ((Consumer<DockContainerLeaf>) c).accept(leaf);
        });
        return button;
    }

    /** Only leaves that host viewers get a "+" (not the analysis tabs, nor a leaf with only captured dialogs). */
    static boolean hostsViewers(DockContainerLeaf leaf) {
        return leaf.getDockables().stream().anyMatch(d -> d.getDragGroupMask() == DragGroups.VIEWER);
    }

    private static BooleanBinding hostsViewersBinding(DockContainerLeaf leaf) {
        return Bindings.createBooleanBinding(() -> hostsViewers(leaf), leaf.getDockables());
    }
}
