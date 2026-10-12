package qupath.ext.bentofx;

import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.ResourceBundle;

/**
 * The right-click menu of a BentoFX tab: where the tab bar of its pane sits (top, left, bottom, right) and
 * minimising the pane to just its tab bar.
 * <p>
 * Both are BentoFX features that had no way to be reached: a leaf has a {@link Side} for its tab bar, and
 * clicking the selected tab collapses the leaf to that bar. A collapse is only possible for the first or last
 * pane of a split, and only if the tab bar suits the split (left/right tabs in side-by-side panes, top/bottom
 * tabs in stacked ones), which is why a pane with its tabs on top, next to another pane in a row, never
 * minimised. Putting the tabs of a pane at the window's edge on the left or right makes it a one-column rail.
 * <p>
 * The side belongs to the pane (the leaf), not to the one tab: all its tabs move together.
 */
final class TabContextMenu {

    private static final Logger logger = LoggerFactory.getLogger(TabContextMenu.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bentofx.ui.strings");

    /** Register with {@code bento.events().addEventListener(...)}: every tab that is added gets the menu. */
    void onDockEvent(DockEvent event) {
        if (event instanceof DockEvent.DockableAdded added)
            install(added.dockable());
    }

    /** Give a tab the menu, unless something else already did. */
    static void install(Dockable dockable) {
        if (dockable.getContextMenuFactory() == null)
            dockable.setContextMenuFactory(TabContextMenu::build);
    }

    private static ContextMenu build(Dockable dockable) {
        DockContainerLeaf leaf = dockable.getContainer();
        if (leaf == null)
            return null;

        Menu position = new Menu(resources.getString("tab.position"));
        ToggleGroup group = new ToggleGroup();
        addSide(position, group, leaf, dockable, Side.TOP, "tab.position.top");
        addSide(position, group, leaf, dockable, Side.LEFT, "tab.position.left");
        addSide(position, group, leaf, dockable, Side.BOTTOM, "tab.position.bottom");
        addSide(position, group, leaf, dockable, Side.RIGHT, "tab.position.right");

        MenuItem collapse = new MenuItem(resources.getString(leaf.isCollapsed() ? "tab.restore" : "tab.minimise"));
        collapse.setOnAction(e -> toggleCollapse(leaf, dockable));

        return new ContextMenu(position, collapse);
    }

    private static void addSide(Menu menu, ToggleGroup group, DockContainerLeaf leaf, Dockable dockable,
                                Side side, String key) {
        RadioMenuItem item = new RadioMenuItem(resources.getString(key));
        item.setToggleGroup(group);
        item.setSelected(leaf.getSide() == side);
        item.setOnAction(e -> setSide(leaf, dockable, side));
        menu.getItems().add(item);
    }

    private static void setSide(DockContainerLeaf leaf, Dockable dockable, Side side) {
        if (leaf.getSide() == side)
            return;
        // Restore a minimised pane first: whether it can be restored depends on the side it was minimised with
        if (leaf.isCollapsed())
            leaf.toggleCollapse(dockable);
        leaf.setSide(side);
    }

    private static void toggleCollapse(DockContainerLeaf leaf, Dockable dockable) {
        boolean before = leaf.isCollapsed();
        try {
            leaf.toggleCollapse(dockable);
        } catch (RuntimeException ex) {
            logger.debug("Could not toggle the collapsed state of a pane", ex);
        }
        if (leaf.isCollapsed() == before)
            Dialogs.showInfoNotification(resources.getString("name"), resources.getString("info.cannot-minimise"));
    }
}
