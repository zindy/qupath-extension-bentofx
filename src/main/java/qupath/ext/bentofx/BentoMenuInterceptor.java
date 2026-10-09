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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Intercepts native QuPath viewer context menus and redirects multiview actions
 * (addRow/addColumn) to BentoFX layout operations.
 */
public class BentoMenuInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(BentoMenuInterceptor.class);

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
     * use BentoFX splitting instead of QuPath's SplitPaneGrid.
     */
    public void registerViewer(QuPathViewer viewer) {
        if (viewer == null || viewer.getView() == null) return;
        registered.add(viewer);

        String addRowText = QuPathResources.getString("Action.View.Multiview.addRow");
        String addColText = QuPathResources.getString("Action.View.Multiview.addColumn");

        viewer.getView().setOnContextMenuRequested(event -> {
            Platform.runLater(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof ContextMenu menu && menu.isShowing()) {
                        rebindMenuItems(menu.getItems(), addRowText, addColText, viewer);
                    }
                }
            });
        });
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

    private void rebindMenuItems(List<MenuItem> items, String targetRowText, String targetColText, QuPathViewer viewer) {
        for (MenuItem item : items) {
            if (item instanceof Menu subMenu) {
                rebindMenuItems(subMenu.getItems(), targetRowText, targetColText, viewer);
            } else if (targetRowText.equals(item.getText())) {
                rememberOriginal(viewer, item);
                item.setOnAction(e -> addBentoRow(viewer));
            } else if (targetColText.equals(item.getText())) {
                rememberOriginal(viewer, item);
                item.setOnAction(e -> addBentoColumn(viewer));
            }
        }
    }

    public void addBentoRow(QuPathViewer viewer) {
        splitBentoViewer(viewer, Orientation.VERTICAL);
    }

    public void addBentoColumn(QuPathViewer viewer) {
        splitBentoViewer(viewer, Orientation.HORIZONTAL);
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
    
    private void rememberOriginal(QuPathViewer viewer, MenuItem item) {
        originalActions.computeIfAbsent(viewer, v -> new java.util.IdentityHashMap<>())
                .putIfAbsent(item, item.getOnAction());   // first call sees QuPath's own handler
    }

    /**
     * Undo everything this interceptor attached to QuPath: the active-viewer listener, the rebound
     * "add row"/"add column" context-menu items (back to QuPath's own handlers), and the
     * context-menu-requested hooks on the viewers.
     */
    public void dispose() {
        disposed = true;
        QuPathGUI.getInstance().getViewerManager().activeViewerProperty().removeListener(activeViewerListener);
        originalActions.values().forEach(items -> items.forEach((item, handler) -> item.setOnAction(handler)));
        originalActions.clear();
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