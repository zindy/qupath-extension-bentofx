package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.localization.QuPathResources;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.ViewerManager;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.util.BentoUtils;
import software.coley.bentofx.event.DockEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Intercepts QuPath's multi-view menu items (in the viewer context menu and in the View menu) and redirects
 * them to BentoFX: add row/column split the pane, close/detach/attach act on BentoFX panes, and the fixed-grid
 * items (grid sizes, remove row/column, reset sizes), which only make sense for QuPath's own grid, tell the
 * user they are not implemented instead of silently creating viewers in a grid that is no longer shown.
 */
public class BentoMenuInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(BentoMenuInterceptor.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bentofx.ui.strings");

    private final DockBuilding builder;
    private final DockContainerBranch rootBranch;
    private final DockContainerLeaf defaultViewerLeaf;

    private record LeafMatch(DockContainerBranch parentBranch, DockContainerLeaf leaf) {}

    /** Tab title of a viewer that has no image. */
    static final String EMPTY_VIEWER_TITLE = "New Viewer";

    private final Map<Dockable, QuPathViewer> viewerByDockable = new HashMap<>();

    /**
     * The original onAction of each context-menu item we rebound, per viewer, so that dispose() can put it back.
     * Per viewer because those handlers capture the viewer: the entry is dropped when the viewer is closed.
     */
    private final Map<QuPathViewer, Map<MenuItem, EventHandler<ActionEvent>>> originalActions =
            new java.util.IdentityHashMap<>();

    /** Same as {@link #originalActions}, for the items of QuPath's own View > Multi-view menu. */
    private final Map<MenuItem, EventHandler<ActionEvent>> menuBarOriginals = new java.util.IdentityHashMap<>();

    /** Viewers whose onContextMenuRequested we set (QuPath itself leaves it null). */
    private final java.util.Set<QuPathViewer> registered =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private boolean disposed;

    private final ChangeListener<QuPathViewer> activeViewerListener =
            (obs, oldViewer, newViewer) -> showViewerTab(newViewer);

    public BentoMenuInterceptor(DockBuilding builder, DockContainerBranch rootBranch, DockContainerLeaf defaultViewerLeaf) {
        this.builder = builder;
        this.rootBranch = rootBranch;
        this.defaultViewerLeaf = defaultViewerLeaf;

        QuPathGUI.getInstance().getViewerManager().activeViewerProperty().addListener(activeViewerListener);
        rebindMenuBar();
    }

    /** Remember which dockable hosts which viewer, so closing the tab can close the viewer. */
    public void trackViewer(Dockable dockable, QuPathViewer viewer) {
        viewerByDockable.put(dockable, viewer);
        bindTitle(dockable, viewer);
    }

    private record TitleBinding(QuPathViewer viewer, InvalidationListener listener,
                                WeakInvalidationListener weakListener) {}

    private final Map<Dockable, TitleBinding> titleBindings = new HashMap<>();

    /**
     * Keep a viewer tab's title in step with its image: the image name (as QuPath itself displays it, so the
     * "mask image names" preference is respected) or {@link #EMPTY_VIEWER_TITLE} when there is no image.
     * Listens to the same things QuPath uses for the title of a detached viewer: the viewer's image data, the
     * name-masking preference and the project. The listeners on the global properties are weak and are also
     * removed explicitly when the viewer tab closes or this interceptor is disposed.
     */
    private void bindTitle(Dockable dockable, QuPathViewer viewer) {
        unbindTitle(dockable);
        InvalidationListener listener = obs -> dockable.setTitle(viewerTitle(viewer));
        WeakInvalidationListener weak = new WeakInvalidationListener(listener);
        viewer.imageDataProperty().addListener(listener);
        PathPrefs.maskImageNamesProperty().addListener(weak);
        QuPathGUI.getInstance().projectProperty().addListener(weak);
        titleBindings.put(dockable, new TitleBinding(viewer, listener, weak));
        dockable.setTitle(viewerTitle(viewer));
    }

    private void unbindTitle(Dockable dockable) {
        TitleBinding b = titleBindings.remove(dockable);
        if (b == null)
            return;
        b.viewer().imageDataProperty().removeListener(b.listener());
        PathPrefs.maskImageNamesProperty().removeListener(b.weakListener());
        QuPathGUI.getInstance().projectProperty().removeListener(b.weakListener());
    }

    private static String viewerTitle(QuPathViewer viewer) {
        var imageData = viewer.getImageData();
        String name = imageData == null ? null : QuPathGUI.getInstance().getDisplayedImageName(imageData);
        return name == null || name.isBlank() ? EMPTY_VIEWER_TITLE : name;
    }

    /** True while a viewer close is in progress, so tab-selection events don't override its choice of active viewer. */
    private boolean closeInProgress = false;

    /** The viewer most recently asked to become active; older deferred requests are dropped. */
    private QuPathViewer latestActivation;

    /** True while showViewerTab selects a tab because the active viewer changed (so no re-activation). */
    private boolean selectingForActiveViewer;

    private void activateViewer(QuPathViewer viewer) {
        latestActivation = viewer;
        ViewerManager vm = QuPathGUI.getInstance().getViewerManager();
        vm.setActiveViewer(viewer);                  // immediate; no-op if already active
        Platform.runLater(() -> {
            // A deferred request must not override a newer one, nor activate a viewer whose tab is no longer the
            // selected one (reordering tabs selects several in quick succession). Without these checks, two stale
            // requests keep re-activating each other's viewer and showViewerTab keeps switching tabs: flicker.
            if (latestActivation != viewer || !isTabSelected(viewer))
                return;
            // Real focus last: QuPath's own focus listener treats this as "the active viewer"
            viewer.getView().requestFocus();
            vm.setActiveViewer(viewer);              // in case focus landed elsewhere in between
        });
    }

    private boolean isTabSelected(QuPathViewer viewer) {
        if (viewer == null || viewer.getView() == null)
            return false;
        LeafMatch match = findLeafAndParent(rootBranch, viewer.getView());
        if (match == null)
            return false;
        Dockable selected = match.leaf().getSelectedDockable();
        return selected != null && selected.getNode() == viewer.getView();
    }

    /** Register with bento.events().addEventListener(...) */
    public void onDockEvent(DockEvent event) {
        if (disposed)
            return;
        logger.trace("Dock event: {}", event.getClass().getSimpleName());

        // A pane left the main window (e.g. a tab was dragged out): see whether it was the last viewer there
        if (event instanceof DockEvent.DockableRemoved)
            scheduleViewerAreaCheck();

        // Tab clicked (or otherwise selected): make its viewer the active one
        if (event instanceof DockEvent.DockableSelected selected) {
            QuPathViewer viewer = viewerByDockable.get(selected.dockable());
            logger.debug("Tab selected: '{}' -> viewer {} (closeInProgress={})",
                    selected.dockable().getTitle(), viewer, closeInProgress);
            if (viewer != null) {
                selected.dockable().setTitle(viewerTitle(viewer));   // e.g. the project entry was renamed
                // Not when we only selected this tab because the active viewer changed: it is active already
                if (!closeInProgress && !selectingForActiveViewer)
                    activateViewer(viewer);
            }
            return;
        }
            
        if (!(event instanceof DockEvent.DockableClosing closing))
            return;
        QuPathViewer viewer = viewerByDockable.get(closing.dockable());
        if (viewer == null)
            return;

        QuPathGUI qupath = QuPathGUI.getInstance();
        ViewerManager vm = qupath.getViewerManager();

        // 0. QuPath always needs a viewer, and the main window keeps one: keep the pane of the last one
        //    (of all, or in the main window) and close only its image
        if (viewerByDockable.size() <= 1) {
            closing.cancel();
            closeImageOnly(viewer, "info.last-viewer");
            return;
        }
        if (isOnlyViewerInMain(closing.dockable())) {
            closing.cancel();
            closeImageOnly(viewer, "info.last-main-viewer");
            return;
        }

        // 1. Prompts to save changes; false means the user cancelled
        if (!qupath.closeViewer(viewer)) {
            closing.cancel();
            return;
        }

        // 2. Don't leave a dead viewer as the active one
        var all = vm.getAllViewers();
        int idx = all.indexOf(viewer);
        QuPathViewer target = null;
        if (idx > 0)
            target = all.get(idx - 1);
        else if (all.size() > 1)
            target = all.get(1);
        
        if (target != null && vm.getActiveViewer() == viewer) {
            final QuPathViewer newActive = target;
            closeInProgress = true;
            vm.setActiveViewer(newActive);
            Platform.runLater(() -> {
                vm.setActiveViewer(newActive);
                closeInProgress = false;
            });        
        }

        // 3. Detach listeners/bindings
        viewer.closeViewer();

        // 4. ViewerManager never removes closed viewers from its list
        try {
            var field = ViewerManager.class.getDeclaredField("viewers");
            field.setAccessible(true);
            ((List<?>) field.get(vm)).remove(viewer);
        } catch (Exception e) {
            logger.error("Could not remove closed viewer from ViewerManager", e);
        }

        viewerByDockable.remove(closing.dockable());
        // Don't keep the closed viewer reachable through our bookkeeping
        originalActions.remove(viewer);
        registered.remove(viewer);
        unbindTitle(closing.dockable());
    }

    /**
     * Attaches a listener to a viewer so its right-click menu actions
     * use BentoFX instead of QuPath's SplitPaneGrid.
     */
    public void registerViewer(QuPathViewer viewer) {
        if (viewer == null || viewer.getView() == null) return;
        registered.add(viewer);

        viewer.getView().setOnContextMenuRequested(event -> {
            Platform.runLater(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof ContextMenu menu && menu.isShowing()) {
                        rebindMenuItems(menu.getItems(),
                                originalActions.computeIfAbsent(viewer, v -> new java.util.IdentityHashMap<>()),
                                () -> viewer, true);
                    }
                }
            });
        });
    }

    /** Menu text (QuPath's own localised strings) -> what BentoFX does instead, given the viewer concerned. */
    private Map<String, Consumer<QuPathViewer>> menuActions() {
        Map<String, Consumer<QuPathViewer>> m = new HashMap<>();
        Consumer<QuPathViewer> noGrid = v -> notifyInfo("info.grid-not-implemented");
        Consumer<QuPathViewer> noRemove = v -> notifyInfo("info.remove-not-implemented");
        putIfKnown(m, "Action.View.Multiview.addRow", this::addBentoRow);
        putIfKnown(m, "Action.View.Multiview.addColumn", this::addBentoColumn);
        putIfKnown(m, "Action.View.Multiview.removeRow", noRemove);
        putIfKnown(m, "Action.View.Multiview.removeColumn", noRemove);
        putIfKnown(m, "Action.View.Multiview.resetGridSize", v -> notifyInfo("info.reset-sizes-not-implemented"));
        putIfKnown(m, "Action.View.Multiview.closeViewer", this::closeBentoViewer);
        for (String key : new String[] {"grid1x1", "grid1x2", "grid2x1", "grid2x2", "grid3x3"})
            putIfKnown(m, "ViewerActions." + key, noGrid);
        putIfKnown(m, "ViewerActions.detachViewer", this::detachBentoViewer);
        putIfKnown(m, "ViewerActions.attachViewer", this::attachBentoViewer);
        return m;
    }

    private static void putIfKnown(Map<String, Consumer<QuPathViewer>> m, String resourceKey, Consumer<QuPathViewer> action) {
        try {
            m.put(QuPathResources.getString(resourceKey), action);
        } catch (Exception e) {
            logger.debug("QuPath has no string '{}'; that menu item is left alone", resourceKey);
        }
    }

    private static void notifyInfo(String key) {
        Dialogs.showInfoNotification(resources.getString("name"), resources.getString(key));
    }

    /** The same redirection for QuPath's own View > Multi-view menu, which acts on the active viewer. */
    private void rebindMenuBar() {
        try {
            QuPathGUI qupath = QuPathGUI.getInstance();
            Menu menu = qupath.getMenu(QuPathResources.getString("Menu.View") + ">"
                    + QuPathResources.getString("Menu.View.Multiview"), false);
            if (menu == null) {
                logger.debug("View > Multi-view menu not found; only the viewer context menu is redirected");
                return;
            }
            rebindMenuItems(menu.getItems(), menuBarOriginals,
                    () -> qupath.getViewerManager().getActiveViewer(), false);
        } catch (Exception e) {
            logger.warn("Could not redirect the View > Multi-view menu", e);
        }
    }

    /** Bring the tab hosting this viewer to the front, if it isn't already. */
    private void showViewerTab(QuPathViewer viewer) {
        if (viewer == null || viewer.getView() == null)
            return;
        LeafMatch match = findLeafAndParent(rootBranch, viewer.getView());
        if (match == null)
            return;
        DockContainerLeaf leaf = match.leaf();
        for (Dockable d : leaf.getDockables()) {
            if (d.getNode() == viewer.getView()) {
                if (leaf.getSelectedDockable() != d) {
                    selectingForActiveViewer = true;
                    try {
                        leaf.selectDockable(d);
                    } finally {
                        selectingForActiveViewer = false;
                    }
                }
                return;
            }
        }
    }

    /**
     * Replace the handler of every known multi-view item (recursing into submenus), remembering the original
     * in {@code originals} so that {@link #dispose()} can put it back.
     *
     * @param contextMenu true for a viewer's context menu: QuPath shows "Detach" or "Attach" there by asking
     *                    its own grid, which knows nothing of BentoFX panes, so show the one that applies.
     */
    private void rebindMenuItems(List<MenuItem> items, Map<MenuItem, EventHandler<ActionEvent>> originals,
                                 Supplier<QuPathViewer> viewer, boolean contextMenu) {
        Map<String, Consumer<QuPathViewer>> actions = menuActions();
        String detachText = textOf("ViewerActions.detachViewer");
        String attachText = textOf("ViewerActions.attachViewer");
        rebind(items, originals, viewer, actions, contextMenu, detachText, attachText);
    }

    private void rebind(List<MenuItem> items, Map<MenuItem, EventHandler<ActionEvent>> originals,
                        Supplier<QuPathViewer> viewer, Map<String, Consumer<QuPathViewer>> actions,
                        boolean contextMenu, String detachText, String attachText) {
        for (MenuItem item : items) {
            if (item instanceof Menu subMenu)
                rebind(subMenu.getItems(), originals, viewer, actions, contextMenu, detachText, attachText);
            Consumer<QuPathViewer> action = item instanceof Menu ? null : actions.get(item.getText());
            if (action == null)
                continue;
            if (!originals.containsKey(item))
                originals.put(item, item.getOnAction());   // first call sees QuPath's own handler
            item.setOnAction(e -> action.accept(viewer.get()));
            if (contextMenu && viewer.get() != null) {
                boolean floating = isFloating(viewer.get());
                if (item.getText().equals(detachText))
                    item.setVisible(!floating);
                else if (item.getText().equals(attachText))
                    item.setVisible(floating);
            }
        }
    }

    private static String textOf(String resourceKey) {
        try {
            return QuPathResources.getString(resourceKey);
        } catch (Exception e) {
            return "";
        }
    }

    public void addBentoRow(QuPathViewer viewer) {
        splitBentoViewer(viewer, Orientation.VERTICAL);
    }

    public void addBentoColumn(QuPathViewer viewer) {
        splitBentoViewer(viewer, Orientation.HORIZONTAL);
    }

    // ------------------------------------------------------------------ close / detach / attach

    private Dockable dockableFor(QuPathViewer viewer) {
        for (var entry : viewerByDockable.entrySet())
            if (entry.getValue() == viewer)
                return entry.getKey();
        return null;
    }

    /** True if the container is part of the live tree below the main window's Bento root. */
    private boolean isInMainLayout(DockContainer c) {
        for (DockContainer p = c; p != null; p = p.getParentContainer())
            if (p == rootBranch)
                return true;
        return false;
    }

    private long countViewersInMain() {
        List<DockContainerLeaf> leaves = new ArrayList<>();
        BentofxExtension.collectLeaves(rootBranch, leaves);
        return leaves.stream().flatMap(l -> l.getDockables().stream())
                .filter(viewerByDockable::containsKey).count();
    }

    /** True if this viewer pane is in the main window and is the only viewer pane there. */
    private boolean isOnlyViewerInMain(Dockable dockable) {
        DockContainerLeaf leaf = dockable.getContainer();
        return leaf != null && isInMainLayout(leaf) && countViewersInMain() <= 1;
    }

    private boolean viewerAreaCheckPending;

    /**
     * The main window always keeps a viewer area. A pane can leave it in ways we cannot intercept (BentoFX's
     * own drag-out of a tab removes it and opens a window in one go), so look again once that operation is
     * over: if no viewer is left in the main window, put an empty one in a leaf of its own, as "Detach viewer"
     * does. Without it the analysis pane would fill the whole window.
     */
    private void scheduleViewerAreaCheck() {
        if (viewerAreaCheckPending)
            return;
        viewerAreaCheckPending = true;
        Platform.runLater(() -> {
            viewerAreaCheckPending = false;
            if (!disposed)
                ensureViewerArea();
        });
    }

    private void ensureViewerArea() {
        if (countViewersInMain() > 0)
            return;
        ViewerManager vm = QuPathGUI.getInstance().getViewerManager();
        QuPathViewer previouslyActive = vm.getActiveViewer();
        QuPathViewer replacement = createRegisteredViewer();
        if (replacement == null)
            return;
        registerViewer(replacement);
        DockContainerLeaf leaf = builder.leaf("viewer-leaf-" + System.nanoTime());
        leaf.setSide(Side.TOP);
        rootBranch.addContainer(leaf);
        leaf.addDockable(newViewerDockable(replacement));   // selects it, which makes it the active viewer ...
        if (previouslyActive != null && previouslyActive != replacement)
            activateViewer(previouslyActive);               // ... so hand that back to the one the user was using
        logger.debug("The main window had no viewer left; added an empty one");
    }

    /** True if the viewer's pane lives in a window of its own (detached), not in the main layout. */
    private boolean isFloating(QuPathViewer viewer) {
        Dockable d = dockableFor(viewer);
        return d != null && d.getContainer() != null && !isInMainLayout(d.getContainer());
    }

    /** Close only the image of a viewer that has to stay (prompts to save changes first). */
    private void closeImageOnly(QuPathViewer viewer, String messageKey) {
        if (QuPathGUI.getInstance().closeViewer(viewer))
            notifyInfo(messageKey);
    }

    /**
     * "Close viewer": close the viewer's BentoFX pane (its tab), which prompts to save changes and then
     * removes the viewer exactly as the tab's own close button does (see {@link #onDockEvent}). The last viewer
     * cannot be removed (QuPath needs one), so only its image is closed.
     */
    public void closeBentoViewer(QuPathViewer viewer) {
        if (viewer == null)
            return;
        Dockable dockable = dockableFor(viewer);
        DockContainerLeaf leaf = dockable == null ? null : dockable.getContainer();
        if (leaf == null) {
            logger.warn("Could not find the BentoFX pane of viewer {}", viewer);
            return;
        }
        if (!dockable.isClosable())
            closeImageOnly(viewer, "info.last-viewer");
        else
            leaf.closeDockable(dockable);   // DockableClosing -> onDockEvent does the rest
    }

    /**
     * "Detach viewer": move the viewer's pane into a window of its own, the same thing that happens when its
     * tab is dragged out of the window. If it is the only viewer in the main window, an empty viewer is left
     * behind in its place (as QuPath does with its own grid), so the main window keeps a viewer area.
     */
    public void detachBentoViewer(QuPathViewer viewer) {
        if (viewer == null)
            return;
        Dockable dockable = dockableFor(viewer);
        DockContainerLeaf leaf = dockable == null ? null : dockable.getContainer();
        if (leaf == null) {
            logger.warn("Could not find the BentoFX pane of viewer {}", viewer);
            return;
        }
        if (!isInMainLayout(leaf)) {
            notifyInfo("info.viewer-already-floating");
            return;
        }
        if (!dockable.isCanBeDroppedToNewWindow()) {
            notifyInfo("info.viewer-cannot-detach");
            return;
        }
        Scene scene = leaf.getScene();

        // Is this the only viewer left in the main window?
        if (countViewersInMain() <= 1) {
            QuPathViewer replacement = createRegisteredViewer();
            if (replacement != null) {
                registerViewer(replacement);
                leaf.addDockable(newViewerDockable(replacement));   // not selected yet, so not shown yet
            }
        }

        if (!leaf.removeDockable(dockable)) {
            logger.warn("Could not remove viewer pane '{}' from its leaf", dockable.getTitle());
            return;
        }
        Stage stage = leaf.getBento().stageBuilding().newStageForDockable(scene, leaf, dockable);
        stage.show();
        stage.toFront();
        stage.requestFocus();
        activateViewer(viewer);
    }

    /** "Attach viewer": put a detached viewer's pane back into the main layout and close its window. */
    public void attachBentoViewer(QuPathViewer viewer) {
        if (viewer == null)
            return;
        Dockable dockable = dockableFor(viewer);
        DockContainerLeaf current = dockable == null ? null : dockable.getContainer();
        if (current == null) {
            logger.warn("Could not find the BentoFX pane of viewer {}", viewer);
            return;
        }
        if (isInMainLayout(current)) {
            notifyInfo("info.viewer-already-docked");
            return;
        }
        DockContainerLeaf target = mainViewerLeaf();
        if (target == null) {
            logger.warn("No leaf in the main layout to attach viewer pane '{}' to", dockable.getTitle());
            return;
        }
        // Leaves the floating window empty, which closes it (DragDropStage closes when emptied)
        if (!current.removeDockable(dockable))
            return;
        if (target.addDockable(dockable)) {
            target.selectDockable(dockable);
            activateViewer(viewer);
        } else {
            logger.warn("Could not attach viewer pane '{}' to leaf {}", dockable.getTitle(), target.getIdentifier());
        }
    }

    /** Leaf of the active viewer if that is in the main layout, else any main-layout leaf with viewers. */
    private DockContainerLeaf mainViewerLeaf() {
        List<DockContainerLeaf> leaves = new ArrayList<>();
        BentofxExtension.collectLeaves(rootBranch, leaves);
        QuPathViewer active = QuPathGUI.getInstance().getViewerManager().getActiveViewer();
        for (DockContainerLeaf l : leaves)
            if (l.getDockables().stream().anyMatch(d -> viewerByDockable.get(d) == active && active != null))
                return l;
        for (DockContainerLeaf l : leaves)
            if (l.getDockables().stream().anyMatch(viewerByDockable::containsKey))
                return l;
        return leaves.isEmpty() ? null : leaves.get(0);
    }

    private int indexOfChild( DockContainerBranch parent, Node child) {
        List<Node> children =
                getChildrenReflectively(parent);

        return children.indexOf(child);
    }

    private void moveChild( DockContainerBranch parent, Node child, int targetIndex) {
        List<Node> children =
                getChildrenReflectively(parent);

        children.remove(child);

        if (targetIndex > children.size())
            targetIndex = children.size();

        children.add(targetIndex, child);
    }


    private void dumpTree(DockContainer c, String indent, StringBuilder sb) {
        if (c instanceof DockContainerBranch b) {
            boolean mismatch = b.getItems().size() != b.getChildContainers().size();
            for (int i = 0; !mismatch && i < b.getItems().size(); i++)
                mismatch = b.getItems().get(i) != b.getChildContainers().get(i).asRegion();
            sb.append(indent).append("Branch ").append(b.getIdentifier())
              .append(' ').append(b.getOrientation())
              .append(" children=").append(b.getChildContainers().size())
              .append(" items=").append(b.getItems().size())
              .append(String.format(" size=%.0fx%.0f", b.getWidth(), b.getHeight()))
              .append(" dividers=").append(java.util.Arrays.toString(b.getDividerPositions()))
              .append(mismatch ? "  <-- MISMATCH" : "").append('\n');
            for (DockContainer child : b.getChildContainers())
                dumpTree(child, indent + "  ", sb);
        } else if (c instanceof DockContainerLeaf l) {
            sb.append(indent).append("Leaf ").append(l.getIdentifier())
              .append(String.format(" size=%.0fx%.0f", l.getWidth(), l.getHeight()))
              .append(" [");
            for (Dockable d : l.getDockables()) {
                Node n = d.getNode();
                sb.append(d.getTitle())
                  .append(n != null && n.getScene() != null ? "" : " (NO SCENE)")
                  .append(", ");
            }
            sb.append("]\n");
        }
    }
    
    private void logTree(String label) {
        StringBuilder sb = new StringBuilder(label).append('\n');
        dumpTree(rootBranch, "", sb);
        logger.debug(sb.toString());
    }

    /**
     * Wrap a freshly created viewer in a BentoFX tab: file drops, minimum size, drag group and
     * title/close tracking, exactly as for a viewer created by splitting. Not yet added to any leaf.
     */
    private Dockable newViewerDockable(QuPathViewer newViewer) {
        Dockable newDockable = builder.dockable();
        // The title follows the image (see trackViewer / bindTitle); no numbering needed
        newDockable.setTitle(EMPTY_VIEWER_TITLE);

        Node viewerNode = newViewer.getView();
        ViewerDragDrop.install(QuPathGUI.getInstance(), viewerNode);
        PanelFitter.enforceMinSize(viewerNode, PanelFitter.MIN_VIEWER_W, PanelFitter.MIN_VIEWER_H);

        newDockable.setNode(viewerNode);
        newDockable.setDragGroupMask(DragGroups.VIEWER);
        trackViewer(newDockable, newViewer);
        return newDockable;
    }

    /**
     * Browser-style "new tab": create a viewer, add it as the last tab of {@code leaf}, select that tab and
     * make the new viewer the active one (selecting the tab does this, see {@link #onDockEvent}).
     */
    public void addViewerTab(DockContainerLeaf leaf) {
        if (leaf == null || disposed)
            return;
        QuPathViewer newViewer = createRegisteredViewer();
        if (newViewer == null)
            return;
        registerViewer(newViewer);

        Dockable newDockable = newViewerDockable(newViewer);
        if (!leaf.addDockable(newDockable)) {
            logger.warn("Could not add a new viewer tab to leaf {}", leaf.getIdentifier());
            return;
        }
        leaf.selectDockable(newDockable);   // fires DockableSelected -> activateViewer(newViewer)
        activateViewer(newViewer);          // explicit as well; a repeated request is harmless
    }

    private void splitBentoViewer(QuPathViewer targetViewer, Orientation splitOrientation) {
        if (targetViewer == null)
            return;
    
        // 1. Create the new QuPath viewer
        QuPathViewer newViewer = createRegisteredViewer();
        if (newViewer == null)
            return;
        registerViewer(newViewer);
    
        logTree("before split");

        // 2. Create its BentoFX Dockable
        Dockable newDockable = newViewerDockable(newViewer);
    
        // 3. Find the leaf containing the calling viewer
        LeafMatch match = findLeafAndParent(rootBranch, targetViewer.getView());
        if (match == null) {
            logger.warn("Could not find BentoFX leaf for viewer {}", targetViewer);
            defaultViewerLeaf.addDockables(newDockable);
            rootBranch.requestLayout();
            return;
        }
    
        DockContainerLeaf targetLeaf = match.leaf();
        DockContainerBranch parentBranch = match.parentBranch();
    
        // 4. Create a leaf containing the new viewer
        DockContainerLeaf newLeaf = builder.leaf("viewer-leaf-" + System.nanoTime());
        newLeaf.addDockables(newDockable);
        newLeaf.setSide(Side.TOP);
    
        // 5. Wrap target + new leaf in a new split (never use addContainer(index, ...);
        //    BentoFX 0.16.0 appends to childContainers but inserts at index in items)
        DockContainerBranch splitBranch = builder.branch("viewer-split-" + System.nanoTime());
        splitBranch.setOrientation(splitOrientation);

        parentBranch.replaceContainer(targetLeaf, splitBranch);
        splitBranch.addContainers(targetLeaf, newLeaf);   // append only: orders stay in sync

        logTree("after split");

        // 6. Force layout
        rootBranch.requestLayout();

        // 7. Make the new viewer the active one
        QuPathGUI.getInstance().getViewerManager().setActiveViewer(newViewer);
    }

    private QuPathViewer createRegisteredViewer() {
        ViewerManager viewerManager = QuPathGUI.getInstance().getViewerManager();
        try {
            Method createMethod = ViewerManager.class.getDeclaredMethod("createViewer");
            createMethod.setAccessible(true);
            return (QuPathViewer) createMethod.invoke(viewerManager);
        } catch (Exception e) {
            logger.error("Failed to create new viewer via ViewerManager reflection", e);
            return null;
        }
    }

    private LeafMatch findLeafAndParent(DockContainerBranch branch, Node node) {
        if (branch == null || node == null)
            return null;
    
        for (DockContainer child : branch.getChildContainers()) {
            if (child instanceof DockContainerLeaf leaf) {
                for (Dockable dockable : leaf.getDockables()) {
                    if (dockable.getNode() == node)
                        return new LeafMatch(branch, leaf);
                }
            } else if (child instanceof DockContainerBranch subBranch) {
                LeafMatch match = findLeafAndParent(subBranch, node);
                if (match != null)
                    return match;
            }
        }
        return null;
    }
    
    /**
     * Undo everything this interceptor attached to QuPath: the active-viewer listener, the rebound
     * multi-view menu items (back to QuPath's own handlers), and the
     * context-menu-requested hooks on the viewers.
     */
    public void dispose() {
        disposed = true;
        QuPathGUI.getInstance().getViewerManager().activeViewerProperty().removeListener(activeViewerListener);
        originalActions.values().forEach(items -> items.forEach((item, handler) -> item.setOnAction(handler)));
        originalActions.clear();
        menuBarOriginals.forEach((item, handler) -> item.setOnAction(handler));
        menuBarOriginals.clear();
        for (QuPathViewer viewer : registered) {
            if (viewer.getView() != null)
                viewer.getView().setOnContextMenuRequested(null);
        }
        registered.clear();
        for (Dockable d : new java.util.ArrayList<>(titleBindings.keySet()))
            unbindTitle(d);
        viewerByDockable.clear();
    }

    @SuppressWarnings("unchecked")
    private List<Node> getChildrenReflectively(Parent parent) {
        if (parent == null) return new ArrayList<>();
        try {
            Method method = Parent.class.getDeclaredMethod("getChildren");
            method.setAccessible(true);
            return (List<Node>) method.invoke(parent);
        } catch (Exception e) {
            logger.error("Failed to access children reflectively from Parent", e);
            return new ArrayList<>();
        }
    }
}