package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.SplitPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes over QuPath's "Show analysis pane" (Shift+A) toggle.
 * <p>
 * QuPath's own handler ({@code QuPathMainPaneManager.setAnalysisPaneVisible}) assumes the main
 * SplitPane holds [analysisTabPane, mainViewerPane]. After the Bento conversion it holds a single
 * Bento root, so on "hide" it reads {@code getDividers().get(0)} (throws) and on "show" it
 * calls {@code getItems().setAll(...)}, which would evict the Bento root.
 * <p>
 * The menu item, toolbar button and accelerator all go through
 * {@code CommonActions.SHOW_ANALYSIS_PANE}, whose selected property is bound bidirectionally to
 * {@code qupath.showAnalysisPaneProperty()}. We unbind that and bind our own property instead,
 * so QuPath's listener never fires.
 * <p>
 * Hiding removes the left-most container (the one holding the analysis leaf) from the Bento root;
 * showing puts it back first and restores its width. BentoFX's own "collapse" is not used: it only
 * shrinks a leaf to its tab headers, and refuses when the leaf is the only child.
 */
final class AnalysisPaneToggle {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisPaneToggle.class);

    private final DockContainerBranch root;
    private final DockContainerLeaf analysisLeaf;
    private final BooleanProperty visible = new SimpleBooleanProperty(true);

    private DockContainer hiddenHost;
    private double lastWidth = 300;

    AnalysisPaneToggle(QuPathGUI qupath, DockContainerBranch root, DockContainerLeaf analysisLeaf) {
        this.root = root;
        this.analysisLeaf = analysisLeaf;

        // Keep the leaf (and its tabs) alive even if the user drags every tab out of it
        analysisLeaf.setPruneWhenEmpty(false);

        var action = qupath.getCommonActions().SHOW_ANALYSIS_PANE;
        action.selectedProperty().unbindBidirectional(qupath.showAnalysisPaneProperty());
        action.selectedProperty().bindBidirectional(visible);
        visible.addListener((v, o, n) -> {
            if (n) show(); else hide();
        });
    }

    /** The direct child of the root that contains the analysis leaf (the leaf itself unless it was split). */
    private DockContainer host() {
        DockContainer c = analysisLeaf;
        while (c.getParentContainer() != null && c.getParentContainer() != root)
            c = c.getParentContainer();
        return c.getParentContainer() == root ? c : null;
    }

    private void hide() {
        if (hiddenHost != null)
            return;
        DockContainer host = host();
        if (host == null) {
            logger.warn("Analysis container is not attached to the Bento root; nothing to hide");
            return;
        }
        double w = host.asRegion().getWidth();
        if (w > 0)
            lastWidth = Math.max(w, PanelFitter.MIN_PANEL_W);
        if (root.removeContainer(host))
            hiddenHost = host;
    }

    private void show() {
        if (hiddenHost == null)
            return;
        DockContainer host = hiddenHost;
        hiddenHost = null;

        insertFirst(host);
        SplitPane.setResizableWithParent(host.asRegion(), false);
        applyWidth();
        Platform.runLater(this::applyWidth);   // layout may rebalance dividers once more
    }

    private void applyWidth() {
        double total = root.getWidth();
        if (total > 0 && !root.getDividers().isEmpty())
            root.setDividerPosition(0, Math.min(lastWidth / total, 0.9));
    }

    /**
     * addContainer(0, c) should do it. The existing code notes that some BentoFX builds append to
     * childContainers but insert at the index in items, so verify and fall back to append-only.
     */
    private void insertFirst(DockContainer host) {
        root.addContainer(0, host);
        boolean inSync = root.getChildContainers().getFirst() == host
                && root.getItems().getFirst() == host.asRegion();
        if (inSync)
            return;

        logger.warn("addContainer(0, ...) left childContainers/items out of sync; rebuilding order");
        List<DockContainer> all = new ArrayList<>(root.getChildContainers());
        all.remove(host);
        for (DockContainer c : new ArrayList<>(root.getChildContainers()))
            root.removeContainer(c);
        root.addContainer(host);
        for (DockContainer c : all)
            root.addContainer(c);
    }
}
