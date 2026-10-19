package qupath.ext.bentofx;

import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.ContextHelpViewer;
import qupath.lib.gui.localization.QuPathResources;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Keeps QuPath's "Context help" window working after it was captured into BentoFX (docked, or torn out into a
 * window of BentoFX's own).
 * <p>
 * QuPath's {@link ContextHelpViewer} updates its top half from a mouse-moved filter, but that filter ignores
 * everything while the viewer's own Stage is not showing, and a captured window's Stage is hidden (its content
 * lives in a BentoFX pane). So the pane stayed on its default text. While the window is captured, this class
 * does that one job in its place: it watches the mouse in every window, finds the help text of the node under it
 * exactly as QuPath does (accessible help, else tooltip text, from the node up through its parents) and writes
 * it into the viewer's own label.
 * <p>
 * That needs the viewer's private {@code label}, {@code helpText} and {@code defaultText} fields, which are
 * reached by reflection (QuPath's jars are on the class path, not a named module). If they are not there, in a
 * different QuPath version, {@link #create} returns null and the pane simply keeps its default text.
 */
final class ContextHelpBridge {

    private static final Logger logger = LoggerFactory.getLogger(ContextHelpBridge.class);

    private final Stage helpStage;
    private final Node panel;
    private final Label label;
    private final StringProperty helpText;
    private final StringProperty defaultText;

    private final Set<Window> watched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EventHandler<MouseEvent> filter = this::onMouseMoved;
    private final ListChangeListener<Window> windowListener = this::onWindowsChanged;
    private Node lastNode;

    private ContextHelpBridge(Stage helpStage, Node panel, Label label,
                              StringProperty helpText, StringProperty defaultText) {
        this.helpStage = helpStage;
        this.panel = panel;
        this.label = label;
        this.helpText = helpText;
        this.defaultText = defaultText;
    }

    /**
     * @param stage the Stage that was captured
     * @param panel the node it now lives in (hovering over it must not replace the text being read)
     * @return a bridge (not yet installed) if {@code stage} is QuPath's Context help window, else null
     */
    static ContextHelpBridge create(Stage stage, Node panel) {
        try {
            if (!QuPathResources.getString("ContextHelp.title").equals(stage.getTitle()))
                return null;
            ContextHelpViewer viewer = ContextHelpViewer.getInstance(QuPathGUI.getInstance());
            if (viewer.getStage() != stage)
                return null;
            return new ContextHelpBridge(stage, panel,
                    (Label) field(viewer, "label"),
                    (StringProperty) field(viewer, "helpText"),
                    (StringProperty) field(viewer, "defaultText"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("Context help will not follow the mouse while captured ({})", e.toString());
            return null;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = ContextHelpViewer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    void install() {
        for (Window w : Window.getWindows())
            watch(w);
        Window.getWindows().addListener(windowListener);
        logger.debug("Context help bridge installed");
    }

    void dispose() {
        Window.getWindows().removeListener(windowListener);
        for (Window w : new ArrayList<>(watched))
            unwatch(w);
        logger.debug("Context help bridge removed");
    }

    private void onWindowsChanged(ListChangeListener.Change<? extends Window> change) {
        while (change.next()) {
            for (Window w : change.getRemoved())
                unwatch(w);
            for (Window w : change.getAddedSubList())
                watch(w);
        }
    }

    private void watch(Window w) {
        // Stages only, as QuPath does: not menus, tooltips and the like
        if (w instanceof Stage && w != helpStage && watched.add(w))
            w.addEventFilter(MouseEvent.MOUSE_MOVED, filter);
    }

    private void unwatch(Window w) {
        if (watched.remove(w))
            w.removeEventFilter(MouseEvent.MOUSE_MOVED, filter);
    }

    private void onMouseMoved(MouseEvent event) {
        if (helpStage.isShowing()) {   // QuPath's own filter is doing this
            lastNode = null;
            return;
        }
        Node node = event.getPickResult().getIntersectedNode();
        if (node == lastNode)
            return;
        lastNode = node;
        if (isInside(node, panel))     // reading the help: leave what it says
            return;
        String help = findHelpText(event, node);
        if (help == null || help.isEmpty()) {
            helpText.bind(defaultText);
            label.setOpacity(0.5);
        } else {
            helpText.unbind();
            helpText.set(help);
            label.setOpacity(1.0);
        }
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent())
            if (n == ancestor)
                return true;
        return false;
    }

    // Same lookup as QuPath's ContextHelpViewer: the text of the nearest node under the cursor that has some
    private static String findHelpText(MouseEvent event, Node node) {
        if (node == null)
            return null;
        String help = helpOrTooltipText(node);
        if (help != null && !help.isBlank() && node.isVisible()) {
            var local = node.screenToLocal(event.getScreenX(), event.getScreenY());
            if (node.contains(local))
                return help;
        }
        return findHelpText(event, node.getParent());
    }

    private static String helpOrTooltipText(Node node) {
        String help = node.getAccessibleHelp();
        if (help != null)
            return help;
        Tooltip tooltip = null;
        if (node instanceof Control control)
            tooltip = control.getTooltip();
        if (tooltip == null && node.getProperties().get("Tooltip") instanceof Tooltip tt)
            tooltip = tt;
        return tooltip == null ? null : tooltip.getText();
    }
}
