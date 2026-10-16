package qupath.ext.bentofx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.control.DragDropStage;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * A saved BentoFX layout: how the panes are split and sized, which tabs each holds and in what order, which
 * tab is selected, where each pane's tab bar is, which panes are minimised, and the windows that were torn out.
 * <p>
 * It is a description of the <i>layout</i>, not of its content. A tab is recorded by kind and, for analysis tabs
 * and captured dialogs, by title; the tabs themselves are the live ones, moved around when the layout is
 * restored. Viewers are interchangeable: they are taken in order, and more are created if the saved layout has
 * more than there are now (their images are not part of a layout). A captured dialog whose window is not open
 * is left out. Anything the saved layout does not mention is handed back to the caller to place.
 * <p>
 * A captured dialog that is not open when the layout is restored (at startup, say) cannot be put back, and
 * QuPath creates such windows on demand, so it cannot be opened for it either. What is kept instead is its
 * {@link Slot}: where it lived. The caller holds on to the slots of the dialogs that were missing and uses one
 * when that dialog is next captured.
 * <p>
 * This class knows nothing of QuPath: what it needs from the application is in {@link Host}.
 */
final class LayoutSnapshot {

    private static final Logger logger = LoggerFactory.getLogger(LayoutSnapshot.class);

    static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double DEFAULT_SIZE_PX = 300;

    private static final String VIEWER = "viewer";
    private static final String ANALYSIS = "analysis";
    private static final String DIALOG = "dialog";

    int version = VERSION;
    /** False if the analysis pane was hidden (Shift+A) when the layout was saved. */
    boolean analysisVisible = true;
    Node main;
    List<Floating> floating = new ArrayList<>();
    /** Where each captured dialog lived, for dialogs that are not open when the layout is restored. */
    List<Slot> slots = new ArrayList<>();

    // ------------------------------------------------------------------ the saved description

    static final class Tab {
        String kind;     // viewer, analysis or dialog
        String title;    // matters for analysis tabs and dialogs only (a viewer's title is its image)

        Tab() {}

        Tab(String kind, String title) {
            this.kind = kind;
            this.title = title;
        }
    }

    /** A split ("branch") or a pane with tabs ("leaf"). */
    static final class Node {
        String type;
        // branch
        String orientation;
        List<Node> children;
        List<Double> sizes;       // pixels of each child along the split; for a minimised pane, its size when open
        // leaf
        String side;
        boolean collapsed;
        boolean anchor;           // the analysis pane's own leaf, which the "Show analysis pane" toggle works on
        int selected = -1;
        List<Tab> tabs;
    }

    /**
     * The home of a captured dialog: the analysis pane, the viewer pane, a pane of its own along the left or right
     * edge of the window (a rail), or a pane split off one side of the analysis or viewer pane, with its tab side,
     * size and whether it was minimised. A dialog anywhere else has no slot and is placed by the usual rule
     * (narrow into the analysis pane, else the viewers).
     */
    static final class Slot {
        String title;
        String placement;         // analysis, viewers, edge or beside
        String edge;              // "edge": LEFT or RIGHT of the window; "beside": the side of the anchor pane
        String anchor;            // "beside": analysis or viewers, the pane it sat next to
        String side;
        boolean collapsed;
        double size;              // the pane's size along the window; its open size if it was minimised
    }

    /** A window BentoFX opened for torn-off tabs. Kept as a single pane, however it was split. */
    static final class Floating {
        double x, y, width, height;
        String side;
        int selected = -1;
        List<Tab> tabs;
    }

    /** What restoring a layout needs from the application. */
    interface Host {
        DockBuilding builder();

        DockContainerBranch root();

        /** The analysis leaf: kept alive across a restore, as the analysis toggle holds on to it. */
        DockContainerLeaf anchor();

        /** A new viewer pane that is not in any leaf yet, or null if one cannot be made. */
        Dockable newViewer();

        PaneSizing sizing();

        void setAnalysisVisible(boolean visible);
    }

    /**
     * @param unplaced tabs the saved layout did not account for (or all of them, if restoring failed half-way):
     *                 the caller puts them somewhere before running {@code finish}
     * @param pendingSlots slots of the captured dialogs the saved layout had, and that are not open now
     * @param finish   sizes the panes, minimises the ones that were minimised, and opens the saved windows;
     *                 needs the layout to be on screen, so it runs last
     */
    record Result(List<Dockable> unplaced, List<Slot> pendingSlots, Runnable finish, boolean failed) {}

    // ------------------------------------------------------------------ capture

    /**
     * @param root        the main window's Bento root
     * @param hiddenHost  the analysis container while it is hidden (it is not in {@code root} then), else null
     * @param hiddenWidth the width it will have when shown again
     * @param anchor      the analysis leaf
     */
    static LayoutSnapshot capture(DockContainerBranch root, DockContainer hiddenHost, double hiddenWidth,
                                  DockContainerLeaf anchor) {
        LayoutSnapshot s = new LayoutSnapshot();
        s.analysisVisible = hiddenHost == null;
        boolean horizontal = root.getOrientation() == Orientation.HORIZONTAL;
        Node n = new Node();
        n.type = "branch";
        n.orientation = root.getOrientation().name();
        n.children = new ArrayList<>();
        n.sizes = new ArrayList<>();
        if (hiddenHost != null) {
            n.children.add(captureContainer(hiddenHost, anchor));
            n.sizes.add(hiddenWidth);
        }
        for (DockContainer c : root.getChildContainers()) {
            n.children.add(captureContainer(c, anchor));
            n.sizes.add(extent(c, horizontal));
        }
        s.main = n;
        collectSlots(s, root, hiddenHost, anchor);
        return s;
    }

    private static void collectSlots(LayoutSnapshot s, DockContainerBranch root, DockContainer hiddenHost,
                                     DockContainerLeaf anchor) {
        List<DockContainerLeaf> leaves = new ArrayList<>();
        if (hiddenHost != null)
            collectLeaves(hiddenHost, leaves);
        collectLeaves(root, leaves);
        boolean horizontal = root.getOrientation() == Orientation.HORIZONTAL;
        for (DockContainerLeaf leaf : leaves) {
            List<Dockable> dialogs = leaf.getDockables().stream().filter(d -> DIALOG.equals(kindOf(d))).toList();
            if (dialogs.isEmpty())
                continue;
            String placement = null;
            String edge = null;
            if (leaf == anchor)
                placement = "analysis";
            else if (leaf.getDockables().stream().anyMatch(d -> VIEWER.equals(kindOf(d))))
                placement = "viewers";
            else if (horizontal && (edge = edgeOf(leaf, root)) != null)
                placement = "edge";
            String anchorKind = null;
            if (placement == null) {
                String[] beside = besideOf(leaf, root, anchor);
                if (beside != null) {
                    placement = "beside";
                    anchorKind = beside[0];
                    edge = beside[1];
                }
            }
            if (placement == null)
                continue;
            for (Dockable d : dialogs) {
                Slot slot = new Slot();
                slot.title = d.getTitle();
                slot.placement = placement;
                slot.edge = edge;
                slot.anchor = anchorKind;
                slot.side = leaf.getSide() == null ? null : leaf.getSide().name();
                slot.collapsed = leaf.isCollapsed();
                slot.size = extent(leaf, true);
                s.slots.add(slot);
            }
        }
    }

    /**
     * For a pane that is not a full-height edge column: the pane it sits next to, and on which side of it. The nearest
     * neighbour that is, or holds, the analysis pane or a viewer pane. Null if there is none.
     *
     * @return {analysis or viewers, TOP/BOTTOM/LEFT/RIGHT of that pane}
     */
    private static String[] besideOf(DockContainerLeaf leaf, DockContainerBranch root, DockContainerLeaf analysisAnchor) {
        DockContainer node = leaf;
        while (node != root) {
            DockContainerBranch parent = node.getParentContainer();
            if (parent == null)
                return null;
            List<DockContainer> kids = parent.getChildContainers();
            int me = kids.indexOf(node);
            boolean vertical = parent.getOrientation() == Orientation.VERTICAL;
            for (int distance = 1; distance < kids.size(); distance++) {
                for (int direction : new int[] {-1, 1}) {
                    int i = me + direction * distance;
                    if (i < 0 || i >= kids.size())
                        continue;
                    List<DockContainerLeaf> inside = new ArrayList<>();
                    collectLeaves(kids.get(i), inside);
                    String kind = inside.contains(analysisAnchor) ? "analysis"
                            : inside.stream().anyMatch(l -> l.getDockables().stream().anyMatch(d -> VIEWER.equals(kindOf(d)))) ? "viewers" : null;
                    if (kind != null) {
                        // direction -1: the neighbour comes first, so this pane is below / to the right of it
                        Side side = vertical ? (direction < 0 ? Side.BOTTOM : Side.TOP) : (direction < 0 ? Side.RIGHT : Side.LEFT);
                        return new String[] {kind, side.name()};
                    }
                }
            }
            node = parent;
        }
        return null;
    }

    /**
     * LEFT or RIGHT if the pane is a full-height column along that edge of the window: every split above it is
     * side by side. A pane that shares its column with others (a vertical split) is not an edge pane.
     */
    private static String edgeOf(DockContainerLeaf leaf, DockContainerBranch root) {
        boolean right = true, left = true;
        DockContainer node = leaf;
        while (node != root) {
            DockContainerBranch parent = node.getParentContainer();
            if (parent == null)
                return null;
            if (parent.getOrientation() != Orientation.HORIZONTAL)
                return null;
            List<DockContainer> kids = parent.getChildContainers();
            if (kids.getLast() != node)
                right = false;
            if (kids.getFirst() != node)
                left = false;
            node = parent;
        }
        if (right && !left)
            return "RIGHT";
        if (left && !right)
            return "LEFT";
        // Alone in its row: its tabs say which edge it is for
        return leaf.getSide() == Side.RIGHT ? "RIGHT" : leaf.getSide() == Side.LEFT ? "LEFT" : null;
    }

    /** Record a window BentoFX opened: its bounds and all the tabs in its leaves. */
    void addFloating(Stage stage, List<DockContainerLeaf> leaves) {
        List<Dockable> all = new ArrayList<>();
        for (DockContainerLeaf leaf : leaves)
            all.addAll(leaf.getDockables());
        if (all.isEmpty())
            return;
        Floating f = new Floating();
        f.x = stage.getX();
        f.y = stage.getY();
        f.width = stage.getWidth();
        f.height = stage.getHeight();
        f.tabs = tabsOf(all);
        DockContainerLeaf first = leaves.get(0);
        f.side = first.getSide() == null ? null : first.getSide().name();
        Dockable selected = first.getSelectedDockable();
        f.selected = selected == null ? -1 : all.indexOf(selected);
        floating.add(f);
    }

    private static Node captureContainer(DockContainer c, DockContainerLeaf anchor) {
        Node n = new Node();
        if (c instanceof DockContainerLeaf leaf) {
            n.type = "leaf";
            n.side = leaf.getSide() == null ? null : leaf.getSide().name();
            n.collapsed = leaf.isCollapsed();
            n.anchor = leaf == anchor;
            n.tabs = tabsOf(leaf.getDockables());
            Dockable selected = leaf.getSelectedDockable();
            n.selected = selected == null ? -1 : leaf.getDockables().indexOf(selected);
        } else if (c instanceof DockContainerBranch branch) {
            n.type = "branch";
            n.orientation = branch.getOrientation().name();
            n.children = new ArrayList<>();
            n.sizes = new ArrayList<>();
            boolean horizontal = branch.getOrientation() == Orientation.HORIZONTAL;
            for (DockContainer child : branch.getChildContainers()) {
                n.children.add(captureContainer(child, anchor));
                n.sizes.add(extent(child, horizontal));
            }
        }
        return n;
    }

    private static List<Tab> tabsOf(List<Dockable> dockables) {
        List<Tab> tabs = new ArrayList<>();
        for (Dockable d : dockables)
            tabs.add(new Tab(kindOf(d), d.getTitle()));
        return tabs;
    }

    private static String kindOf(Dockable d) {
        int mask = d.getDragGroupMask();
        if (mask == DragGroups.VIEWER)
            return VIEWER;
        if (mask == DragGroups.ANALYSIS)
            return ANALYSIS;
        return DIALOG;
    }

    private static double extent(DockContainer c, boolean horizontal) {
        if (c instanceof DockContainerLeaf leaf && leaf.isCollapsed())
            return uncollapsedSize(leaf);
        Region r = c.asRegion();
        return horizontal ? r.getWidth() : r.getHeight();
    }

    /**
     * The size a minimised pane returns to. BentoFX keeps it (protected) for its own use; without it a restored
     * pane would reopen at some default size, so it is read by reflection, with a default if that fails.
     */
    private static double uncollapsedSize(DockContainerLeaf leaf) {
        try {
            Method m = DockContainerLeaf.class.getDeclaredMethod("getUncollapsedSize");
            m.setAccessible(true);
            double size = (double) m.invoke(leaf);
            return size > 0 ? size : DEFAULT_SIZE_PX;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("Could not read the open size of a minimised pane ({}); using a default", e.toString());
            return DEFAULT_SIZE_PX;
        }
    }

    // ------------------------------------------------------------------ JSON

    String toJson() {
        return GSON.toJson(this);
    }

    /** @return the layout, or null if the text is not a layout this version understands */
    static LayoutSnapshot fromJson(String json) {
        try {
            LayoutSnapshot s = GSON.fromJson(json, LayoutSnapshot.class);
            if (s == null || s.version != VERSION || s.main == null || s.main.children == null) {
                logger.warn("Saved layout is not in a format this version understands");
                return null;
            }
            return s;
        } catch (RuntimeException e) {
            logger.warn("Could not read the saved layout: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ restore

    /** A saved node with the live tabs it will hold (empty panes already dropped). */
    private static final class Resolved {
        final Node spec;
        final List<Resolved> children = new ArrayList<>();
        final List<Double> sizes = new ArrayList<>();
        final List<Dockable> dockables = new ArrayList<>();

        Resolved(Node spec) {
            this.spec = spec;
        }
    }

    private static final class ResolvedFloating {
        final Floating spec;
        final List<Dockable> dockables;

        ResolvedFloating(Floating spec, List<Dockable> dockables) {
            this.spec = spec;
            this.dockables = dockables;
        }
    }

    /** The tabs there are, in the order they are in the layout now. */
    private static final class Pool {
        final List<Dockable> remaining = new ArrayList<>();
        final Set<String> dialogsTaken = new HashSet<>();

        Dockable take(Tab tab) {
            for (Iterator<Dockable> it = remaining.iterator(); it.hasNext(); ) {
                Dockable d = it.next();
                if (Objects.equals(kindOf(d), tab.kind) && (VIEWER.equals(tab.kind) || Objects.equals(d.getTitle(), tab.title))) {
                    it.remove();
                    if (DIALOG.equals(tab.kind))
                        dialogsTaken.add(tab.title);
                    return d;
                }
            }
            return null;
        }
    }

    /**
     * Take the current layout apart and rebuild it as saved, from the same tabs.
     * <p>
     * Before calling this: tabs in windows of their own must have been brought back into the main layout, and
     * the analysis pane must be showing (its leaf is the anchor and is reused). After it: place
     * {@link Result#unplaced()}, then run {@link Result#finish()}.
     */
    Result apply(Host host) {
        DockContainerBranch root = host.root();
        DockContainerLeaf anchor = host.anchor();

        List<DockContainerLeaf> oldLeaves = new ArrayList<>();
        collectLeaves(root, oldLeaves);
        Pool pool = new Pool();
        for (DockContainerLeaf leaf : oldLeaves)
            pool.remaining.addAll(leaf.getDockables());
        List<Dockable> everything = new ArrayList<>(pool.remaining);

        try {
            // A minimised anchor must be open again before it moves, or it would arrive in a state it cannot leave
            if (anchor.isCollapsed())
                anchor.toggleCollapse(anchor.getDockables().isEmpty() ? null : anchor.getDockables().getFirst());
            anchor.setPruneWhenEmpty(false);

            // Decide who goes where first, so that nothing is taken apart for a plan that cannot be carried out
            Resolved plan = resolve(main, pool, host, everything, true);
            List<ResolvedFloating> windows = new ArrayList<>();
            for (Floating f : floating) {
                List<Dockable> tabs = new ArrayList<>();
                for (Tab t : f.tabs) {
                    Dockable d = takeOrCreate(t, pool, host, everything);
                    if (d != null)
                        tabs.add(d);
                }
                if (!tabs.isEmpty())
                    windows.add(new ResolvedFloating(f, tabs));
            }

            // Take apart
            if (anchor.getParentContainer() != null)
                anchor.getParentContainer().removeContainer(anchor);
            for (DockContainerLeaf leaf : oldLeaves)
                for (Dockable d : new ArrayList<>(leaf.getDockables()))
                    leaf.removeDockable(d);
            for (DockContainer child : new ArrayList<>(root.getChildContainers()))
                root.removeContainer(child);

            // Rebuild
            List<Runnable> sizing = new ArrayList<>();
            List<DockContainerLeaf> toCollapse = new ArrayList<>();
            root.setOrientation(Orientation.valueOf(main.orientation));
            Map<DockContainer, Double> rootSizes = new IdentityHashMap<>();
            for (int i = 0; i < plan.children.size(); i++) {
                DockContainer c = build(plan.children.get(i), host, sizing, toCollapse);
                root.addContainer(c);
                rootSizes.put(c, plan.sizes.get(i));
            }
            if (anchor.getParentContainer() == null) {
                logger.warn("The saved layout has no analysis pane; adding it at the left");
                Branches.insert(host.builder(), root, 0, anchor);
                rootSizes.put(anchor, DEFAULT_SIZE_PX);
            }
            sizing.addFirst(() -> host.sizing().applyNow(root, rootSizes));

            Runnable finish = () -> finish(host, sizing, toCollapse, windows);
            List<Slot> pending = new ArrayList<>();
            if (slots != null)
                for (Slot slot : slots)
                    if (!pool.dialogsTaken.contains(slot.title))
                        pending.add(slot);
            return new Result(new ArrayList<>(pool.remaining), pending, finish, false);

        } catch (RuntimeException e) {
            logger.error("Restoring the layout failed half-way; the tabs are put back in a default arrangement", e);
            List<Dockable> loose = new ArrayList<>();
            for (Dockable d : everything)
                if (d.getContainer() == null)
                    loose.add(d);
            if (anchor.getParentContainer() == null) {
                try {
                    Branches.insert(host.builder(), root, 0, anchor);
                } catch (RuntimeException ignored) {
                    // nothing more can be done
                }
            }
            return new Result(loose, List.of(), () -> {}, true);
        }
    }

    private Dockable takeOrCreate(Tab tab, Pool pool, Host host, List<Dockable> everything) {
        Dockable d = pool.take(tab);
        if (d == null && VIEWER.equals(tab.kind)) {
            d = host.newViewer();
            if (d != null)
                everything.add(d);
        }
        return d;
    }

    private Resolved resolve(Node spec, Pool pool, Host host, List<Dockable> everything, boolean isRoot) {
        Resolved r = new Resolved(spec);
        if ("leaf".equals(spec.type)) {
            for (Tab t : spec.tabs) {
                Dockable d = takeOrCreate(t, pool, host, everything);
                if (d != null)
                    r.dockables.add(d);
            }
            return r.dockables.isEmpty() && !spec.anchor ? null : r;
        }
        for (int i = 0; i < spec.children.size(); i++) {
            Resolved child = resolve(spec.children.get(i), pool, host, everything, false);
            if (child != null) {
                r.children.add(child);
                r.sizes.add(spec.sizes != null && i < spec.sizes.size() ? spec.sizes.get(i) : DEFAULT_SIZE_PX);
            }
        }
        if (isRoot)
            return r;
        if (r.children.isEmpty())
            return null;
        // A split with a single pane left is just that pane (BentoFX never keeps such a branch either)
        return r.children.size() == 1 ? r.children.getFirst() : r;
    }

    private DockContainer build(Resolved r, Host host, List<Runnable> sizing, List<DockContainerLeaf> toCollapse) {
        if ("leaf".equals(r.spec.type)) {
            DockContainerLeaf leaf = r.spec.anchor ? host.anchor() : host.builder().leaf(newId());
            leaf.setSide(parseSide(r.spec.side));
            for (Dockable d : r.dockables)
                leaf.addDockable(d);
            int selected = r.spec.selected >= 0 && r.spec.selected < r.dockables.size() ? r.spec.selected : 0;
            if (!r.dockables.isEmpty())
                leaf.selectDockable(r.dockables.get(selected));
            if (r.spec.collapsed)
                toCollapse.add(leaf);
            return leaf;
        }
        DockContainerBranch branch = host.builder().branch(newId());
        branch.setOrientation(Orientation.valueOf(r.spec.orientation));
        Map<DockContainer, Double> sizes = new IdentityHashMap<>();
        int slot = sizing.size();
        sizing.add(null);   // a branch is sized before the branches inside it, which need the size it gets
        for (int i = 0; i < r.children.size(); i++) {
            DockContainer c = build(r.children.get(i), host, sizing, toCollapse);
            branch.addContainer(c);
            sizes.put(c, r.sizes.get(i));
        }
        sizing.set(slot, () -> host.sizing().applyNow(branch, sizes));
        return branch;
    }

    private void finish(Host host, List<Runnable> sizing, List<DockContainerLeaf> toCollapse,
                        List<ResolvedFloating> windows) {
        DockContainerBranch root = host.root();
        try {
            // Top-down, laying out in between so that each branch has the size it will really have
            layoutNow(root);
            for (Runnable r : sizing) {
                r.run();
                layoutNow(root);
            }
            for (DockContainerLeaf leaf : toCollapse) {
                if (!leaf.isCollapsed() && !leaf.getDockables().isEmpty())
                    leaf.toggleCollapse(leaf.getDockables().getFirst());
                if (!leaf.isCollapsed())
                    logger.debug("Pane {} could not be minimised again", leaf.getIdentifier());
            }
            if (!analysisVisible)
                host.setAnalysisVisible(false);
            for (ResolvedFloating w : windows)
                openFloating(root, w);
        } catch (RuntimeException e) {
            logger.error("Finishing the restored layout failed", e);
        }
    }

    private void openFloating(DockContainerBranch root, ResolvedFloating w) {
        Floating f = w.spec;
        Dockable first = w.dockables.getFirst();
        double width = f.width > 0 ? f.width : DEFAULT_SIZE_PX;
        double height = f.height > 0 ? f.height : DEFAULT_SIZE_PX;
        DragDropStage stage = root.getBento().stageBuilding().newStageForDockable(root.getScene(), first, width, height);
        DockContainerLeaf leaf = first.getContainer();
        if (leaf != null) {
            leaf.setSide(parseSide(f.side));
            for (int i = 1; i < w.dockables.size(); i++)
                leaf.addDockable(w.dockables.get(i));
            int selected = f.selected >= 0 && f.selected < w.dockables.size() ? f.selected : 0;
            leaf.selectDockable(w.dockables.get(selected));
        }
        // Only where the window can be seen: the screens may have changed since the layout was saved
        if (!Screen.getScreensForRectangle(f.x, f.y, width, height).isEmpty()) {
            stage.setX(f.x);
            stage.setY(f.y);
        }
        stage.show();
    }

    // ------------------------------------------------------------------ helpers

    private static void layoutNow(DockContainerBranch root) {
        Scene scene = root.getScene();
        if (scene == null)
            return;
        Parent top = scene.getRoot();
        top.applyCss();
        top.layout();
    }

    private static Side parseSide(String name) {
        if (name == null)
            return Side.TOP;
        try {
            return Side.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Side.TOP;
        }
    }

    private static String newId() {
        return "restored-" + System.nanoTime();
    }

    static void collectLeaves(DockContainer c, List<DockContainerLeaf> out) {
        if (c instanceof DockContainerLeaf leaf)
            out.add(leaf);
        else if (c instanceof DockContainerBranch branch)
            for (DockContainer child : branch.getChildContainers())
                collectLeaves(child, out);
    }
}