package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.ViewerManager;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Predicate;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.util.BentoUtils;

/**
 * QuPath extension integrating BentoFX layout management.
 */
public class BentofxExtension implements QuPathExtension {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bentofx.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(BentofxExtension.class);

	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	private boolean isInstalled = false;

	/**
	 * Persistent preference (shown in QuPath's Preferences window): initialise BentoFX automatically when
	 * QuPath starts. Off by default, since it replaces QuPath's own layout. Read once, at startup.
	 */
	private static final BooleanProperty enableOnStartupProperty = PathPrefs.createPersistentPreference(
			"bentofx.enableOnStartup", false);

	private Bento bento;
	private DockBuilding builder;
	private DockContainerBranch rootBranch;
	private DockContainerLeaf viewerLeaf;
	private DockContainerLeaf analysisLeaf;

	private BentoMenuInterceptor menuInterceptor;
	private AnalysisPaneToggle analysisToggle;
	private PaneSizing paneSizing;

	/** True while BentoFX is initialised; the toolbar button follows it. */
	private final BooleanProperty bentoActive = new SimpleBooleanProperty(false);
	private CapturedWindows capturedWindows;

	// Remembered so that deactivation can rebuild QuPath's own layout
	private SplitPane mainSplitPane;
	private TabPane analysisTabPane;
	private String bentoCssUrl;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreferenceToPane(qupath);
		addMenuItem(qupath);
		addToolbarButton(qupath);
		enableOnStartupIfRequested(qupath);
	}

	/**
	 * Add the "enable on startup" option to QuPath's Preferences window, in its own section.
	 */
	private void addPreferenceToPane(QuPathGUI qupath) {
		var propertyItem = new PropertyItemBuilder<>(enableOnStartupProperty, Boolean.class)
				.name(resources.getString("pref.enable-on-startup"))
				.category(EXTENSION_NAME)
				.description(resources.getString("pref.enable-on-startup.description"))
				.build();
		qupath.getPreferencePane()
				.getPropertySheet()
				.getItems()
				.add(propertyItem);
	}

	/**
	 * If the preference is set, initialise BentoFX (same as Extensions > BentoFX > Initialisation) as soon
	 * as QuPath's main window is showing. Extensions are installed while QuPath is still being built, before
	 * its window exists, and {@link #bentoSetup()} needs the finished GUI, so it can't run directly from here.
	 */
	private void enableOnStartupIfRequested(QuPathGUI qupath) {
		if (!enableOnStartupProperty.get())
			return;
		Stage stage = qupath.getStage();
		if (stage == null || stage.isShowing()) {
			Platform.runLater(this::autoSetup);
			return;
		}
		stage.showingProperty().addListener(new ChangeListener<Boolean>() {
			@Override
			public void changed(ObservableValue<? extends Boolean> obs, Boolean wasShowing, Boolean showing) {
				if (showing) {
					obs.removeListener(this);   // once only
					Platform.runLater(BentofxExtension.this::autoSetup);
				}
			}
		});
	}

	private void autoSetup() {
		if (bento != null)
			return;
		logger.info("Initialising BentoFX at startup (see the BentoFX extension preferences)");
		try {
			bentoSetup();
		} catch (Exception e) {
			logger.error("Could not initialise BentoFX at startup", e);
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.startup-failed"));
		}
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		
		MenuItem initItem = new MenuItem("Initialisation");
		initItem.setOnAction(e -> bentoSetup());
		menu.getItems().add(initItem);

		MenuItem captureItem = new MenuItem("Capture floating windows");
		captureItem.setOnAction(e -> bentoCapture());
		menu.getItems().add(captureItem);

		MenuItem deactivateItem = new MenuItem("Deactivate BentoFX");
		deactivateItem.setOnAction(e -> bentoTeardown());
		menu.getItems().add(deactivateItem);
	}

	/**
	 * A magnet button at the right-hand end of QuPath's main toolbar that captures floating windows
	 * (same as Extensions > BentoFX > Capture floating windows). Only enabled while BentoFX is initialised.
	 */
	private void addToolbarButton(QuPathGUI qupath) {
		ToolBar toolBar = qupath.getToolBar();
		if (toolBar == null) {
			logger.warn("QuPath's toolbar is not available; the capture button was not added");
			return;
		}
		Button button = new Button();
		button.setGraphic(IconFactory.createFontAwesome('\uf076'));   // FontAwesome "magnet"
		button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
		button.setFocusTraversable(false);
		button.setTooltip(new Tooltip("Capture floating windows into BentoFX\n(initialise BentoFX first)"));
		button.disableProperty().bind(bentoActive.not());
		button.setOnAction(e -> bentoCapture());
		toolBar.getItems().addAll(new Separator(Orientation.VERTICAL), button);
	}

	/**
	 * Convert the QuPath layout into BentoFX panels
	 */
	private void bentoSetup() {
		if (bento != null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-already-setup"));
			logger.error(resources.getString("error.bento-already-setup"));
			return;
		}

		// Build Bento root
		bento = DragGroups.newBento();

		// Browser-style "+" button for every leaf that holds viewers: a corner fallback here ...
		// (Both factories must be set before any leaf is built: a leaf creates its header pane in its constructor.)
		bento.controlsBuilding().setHeaderPaneFactory(leaf -> {
			leaf.getProperties().put(ViewerTabHeaderPane.ADD_VIEWER_HANDLER,
					(Consumer<DockContainerLeaf>) l -> {
						if (menuInterceptor != null)
							menuInterceptor.addViewerTab(l);
					});
			return new ViewerTabHeaderPane(leaf);
		});
		// ... and its inline version, right after the last tab
		bento.controlsBuilding().setHeadersFactory(ViewerTabHeaders::new);
		bento.placeholderBuilding().setDockablePlaceholderFactory(d -> new Label("Empty Dockable"));
		bento.placeholderBuilding().setContainerPlaceholderFactory(c -> new Label("Empty Container"));

		builder = bento.dockBuilding();
		rootBranch = builder.root("root");
		analysisLeaf = builder.leaf("analysis");
		viewerLeaf = builder.leaf("panels");

		rootBranch.setOrientation(Orientation.HORIZONTAL);
		rootBranch.addContainers(analysisLeaf, viewerLeaf);
		analysisLeaf.setSide(Side.TOP);
		viewerLeaf.setSide(Side.TOP);
		rootBranch.setContainerSizePx(analysisLeaf, 300);

		// These leaves shouldn't auto-expand
		DockContainerBranch.setResizableWithParent(analysisLeaf, false);

		var qupath = QuPathGUI.getInstance();
		// If the analysis pane was hidden, let QuPath restore its own layout before we rebuild it
		qupath.showAnalysisPaneProperty().set(true);
		var viewerManager = qupath.getViewerManager();
		var analysisPane = qupath.getAnalysisTabPane();

		logger.debug("Getting the parent split pane...");
		Node node = analysisPane;
		while (node != null && !(node instanceof SplitPane)) {
			node = node.getParent();
		}

		if (node instanceof SplitPane splitPane) {
			mainSplitPane = splitPane;
			analysisTabPane = analysisPane;
			splitPane.getItems().clear();
			splitPane.getItems().add(rootBranch);

			logger.debug("Intercepting Bento events in parent...");
			splitPane.setOnDragDropped(event -> {
				if (event.getGestureSource() != null) {
					Object source = event.getGestureSource();
					String nodeClass = source.getClass().getName();
					if (nodeClass.contains("bentofx") || nodeClass.contains("bento")) {
						logger.info("Caught Bento drop event: {}", nodeClass);
						event.setDropCompleted(true);
						event.consume();
					}
				}
			});
		} else {
			logger.warn("No SplitPane found in parent chain!");
		}

		// Load CSS safely from classpath resources
		URL cssUrl = getClass().getResource("/bento.css");
		if (cssUrl != null) {
			logger.debug("Applying CSS from {}", cssUrl.toExternalForm());
			var scene = qupath.getStage().getScene();
			if (scene != null) {
				bentoCssUrl = cssUrl.toExternalForm();
				scene.getStylesheets().add(bentoCssUrl);
			}
		} else {
			logger.warn("Could not find /bento.css in resources.");
		}

		// Add analysis tab pane contents
		logger.debug("Adding analysis tabs to Bento...");
		analysisPane.getTabs().stream()
			.filter(tab -> tab.getContent() != null)
			.forEach(tab -> {
				Dockable dockable = builder.dockable();
				dockable.setTitle(tab.getText());
				dockable.setNode(tab.getContent());
				dockable.setClosable(false);  // Remove close button
				dockable.setDragGroupMask(DragGroups.ANALYSIS);
				analysisLeaf.addDockables(dockable);
			});

		// Add viewers
		logger.debug("Adding viewers to Bento...");
		List<QuPathViewer> viewers = viewerManager.getAllViewers();

		menuInterceptor = new BentoMenuInterceptor(builder, rootBranch, viewerLeaf);
		bento.events().addEventListener(menuInterceptor::onDockEvent);

		for (int i = 0; i < viewers.size(); i++) {
			QuPathViewer viewer = viewers.get(i);
			logger.debug("{}/{} viewers added...", i + 1, viewers.size());

			Dockable dockable = builder.dockable();
			dockable.setTitle(BentoMenuInterceptor.EMPTY_VIEWER_TITLE);   // replaced by the image name once tracked
			var v = viewer.getView();

			// Keep QuPath's file drop, but let Bento tab drags through
			ViewerDragDrop.install(qupath, v);
			PanelFitter.enforceMinSize(v, PanelFitter.MIN_VIEWER_W, PanelFitter.MIN_VIEWER_H);

			dockable.setNode(v);
			menuInterceptor.trackViewer(dockable, viewer);

			if (i == 0) {
				dockable.setClosable(false);
			}

			dockable.setDragGroupMask(DragGroups.VIEWER);
			viewerLeaf.addDockables(dockable);
		}

		// Register current viewers and intercept context menu actions
		viewers = viewerManager.getAllViewers();
		for (QuPathViewer viewer : viewers) {
			menuInterceptor.registerViewer(viewer);
		}

		// Take over Shift+A / View > Show analysis pane (QuPath's handler would break the layout)
		analysisToggle = new AnalysisPaneToggle(qupath, rootBranch, analysisLeaf);

		// Fixed pixel size for panel panes, proportional for viewers. Registered after the initial
		// structure is built, so it only reacts to later changes (splits, closes, drops, show/hide).
		paneSizing = new PaneSizing();
		bento.events().addEventListener(paneSizing::onDockEvent);

		// Closing a captured tab hands the dialog back to its original Stage
		capturedWindows = new CapturedWindows(this::revealDockable);
		bento.events().addEventListener(capturedWindows::onDockEvent);

		rootBranch.requestFocus();
		rootBranch.requestLayout();

		bentoActive.set(true);
		logger.info("BentoFX layout initialised with {} viewer(s)", viewerManager.getAllViewers().size());

	}

	/**
	 * Undo {@link #bentoSetup()}: release docked dialogs to their own windows, give Shift+A and the
	 * viewer context-menu items back to QuPath, rebuild QuPath's analysis tabs and put all viewers in a
	 * single row of QuPath's own viewer grid.
	 */
	private void bentoTeardown() {
		if (bento == null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-not-setup"));
			logger.error(resources.getString("error.bento-not-setup"));
			return;
		}

		QuPathGUI qupath = QuPathGUI.getInstance();
		var viewerManager = qupath.getViewerManager();

		// 0. Remember what we need before anything moves
		boolean analysisVisible = analysisToggle.isVisible();
		double analysisWidth = analysisToggle.currentWidth();
		double total = rootBranch.getWidth();
		QuPathViewer active = viewerManager.getActiveViewer();

		// 1. Stop reacting to layout events, then let go of every node Bento is displaying. Bento binds
		//    ContentWrapper.center to the selected tab's node, so a node can only be moved elsewhere once the
		//    selection is cleared ("A bound value cannot be set" otherwise). Clearing the selection is Bento's
		//    own release path: it unbinds and puts a placeholder in; no events, no pruning.
		paneSizing.dispose();
		menuInterceptor.dispose();        // add row/column items, active-viewer listener
		List<DockContainerLeaf> leaves = new ArrayList<>();
		collectLeaves(rootBranch, leaves);
		if (analysisToggle.hiddenHost() != null)
			collectLeaves(analysisToggle.hiddenHost(), leaves);   // analysis pane currently hidden
		for (DockContainerLeaf leaf : leaves)
			leaf.selectDockable(null);

		// 2. Give docked dialogs back to their own windows (and show them again)
		capturedWindows.releaseAll();

		// 3. Hand the remaining hooks back to QuPath
		analysisToggle.dispose(qupath);   // Shift+A -> QuPath's own property again
		List<QuPathViewer> viewers = new ArrayList<>(viewerManager.getAllViewers());
		for (QuPathViewer viewer : viewers) {
			ViewerDragDrop.uninstall(qupath, viewer.getView());
			PanelFitter.resetMinSize(viewer.getView());
		}

		// 4. Viewers: one row in QuPath's own grid. The grid still has the shape it had before BentoFX was
		//    initialised (e.g. 2x2), with stale references to viewer nodes that Bento has since taken over.
		//    Drop every row but the first with the grid's own removeRow(int), which updates its private row
		//    list, the main SplitPane and the divider bindings (it does not touch the viewers), then fill the
		//    first row with all viewers.
		removeExtraGridRows(viewerManager);
		SplitPane grid = (SplitPane) viewerManager.getRegion();
		SplitPane row = (SplitPane) grid.getItems().get(0);
		row.getItems().setAll(viewers.stream().map(QuPathViewer::getView).toList());
		viewerManager.resetGridSize();

		// 5. Analysis tabs: their content nodes were moved into Bento. Setting the content again re-attaches
		//    it to the TabPane's own content region.
		for (Tab tab : analysisTabPane.getTabs()) {
			Node content = tab.getContent();
			if (content != null) {
				tab.setContent(null);
				tab.setContent(content);
			}
		}

		// 6. Main layout exactly as QuPathMainPaneManager.setAnalysisPaneVisible(true) builds it
		mainSplitPane.setOnDragDropped(null);
		mainSplitPane.getItems().setAll(analysisTabPane, viewerManager.getRegion());
		mainSplitPane.setDividerPosition(0, total > 0 ? Math.min(analysisWidth / total, 0.5) : 0.15);

		// 7. Stylesheet
		var scene = qupath.getStage().getScene();
		if (scene != null && bentoCssUrl != null)
			scene.getStylesheets().remove(bentoCssUrl);

		// 8. Forget the Bento state, so that "Initialisation" can be run again
		bento = null;
		builder = null;
		rootBranch = null;
		viewerLeaf = null;
		analysisLeaf = null;
		menuInterceptor = null;
		analysisToggle = null;
		paneSizing = null;
		capturedWindows = null;
		mainSplitPane = null;
		analysisTabPane = null;
		bentoCssUrl = null;
		bentoActive.set(false);

		// 9. QuPath's handler is back in charge: use it to hide the pane if it was hidden
		if (!analysisVisible)
			qupath.showAnalysisPaneProperty().set(false);

		// 10. Keep the same active viewer
		if (active != null) {
			Platform.runLater(() -> {
				viewerManager.setActiveViewer(active);
				active.getView().requestFocus();
			});
		}

		logger.info("BentoFX deactivated; {} viewer(s) in a single row", viewers.size());
		Dialogs.showInfoNotification(resources.getString("name"), "BentoFX deactivated");
	}

	/**
	 * Find all non-main QuPath windows and return their root panes.
	 */
	private record CapturedStage(String title, Stage stage, Parent root) {}

	private List<CapturedStage> getDialogWindowsPanes() {
		List<CapturedStage> result = new ArrayList<>();
		Stage mainStage = QuPathGUI.getInstance().getStage();

		List<Window> windows = new ArrayList<>(Window.getWindows());

		for (Window window : windows) {
			if (window instanceof Stage stage) {
				if (stage != mainStage && stage.isShowing() && !capturedWindows.isCapturedStage(stage) &&
					stage.getTitle() != null && !stage.getTitle().isEmpty()) {

					String windowTitle = stage.getTitle();
					if (stage.getScene() != null) {
						Parent pane = stage.getScene().getRoot();
						// Decouple root before closing stage
						stage.getScene().setRoot(new Group()); 
						result.add(new CapturedStage(windowTitle, stage, pane));
						logger.debug("Found floating window: {}", windowTitle);
					}
					stage.close();
				}
			}
		}
		return result;
	}

	/**
	 * Capture floating dialog windows and dock them into BentoFX.
	 */
	private void bentoCapture() {
		if (bento == null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-not-setup"));
			logger.error(resources.getString("error.bento-not-setup"));
			return;
		}

		List<CapturedStage> captured = getDialogWindowsPanes();

		for (CapturedStage cs : captured) {
			String title = cs.title();
			Parent pane = cs.root();

			double paneWidth = pane.getBoundsInLocal().getWidth();
			double paneHeight = pane.getBoundsInLocal().getHeight();
			logger.debug("Processing window '{}' - width={}", title, paneWidth);

			boolean narrow = paneWidth < 400;

			Dockable dockable = builder.dockable();
			dockable.setTitle(title);
			// Peel anonymous wrappers, lift USE_PREF_SIZE caps, add grow hints.
			// Narrow form-like panels (InstanSeg) also get a ScrollPane so they can shrink.
			PanelFitter.Fitted fitted = PanelFitter.fit(pane, narrow);
			dockable.setNode(fitted.node());
			// Remember the window's own size as the starting size of any pane made for it
			paneSizing.declare(dockable, paneWidth, paneHeight);

			// FLEX: the width only picks the initial leaf; the panel can then be dragged anywhere
			dockable.setDragGroupMask(DragGroups.FLEX);
			dockable.setClosable(true);

			// Never use the leaf fields directly: a leaf that was emptied by dragging is pruned from the
			// tree, and adding to it would silently put the panel in a detached, invisible leaf.
			DockContainerLeaf target = narrow ? analysisTarget() : viewerTarget();
			if (target.addDockable(dockable)) {
				target.selectDockable(dockable);
				// Remember the original Stage so closing the tab can give the dialog back
				capturedWindows.register(dockable, cs.stage(), pane, fitted);
				logger.debug("Docked '{}' into leaf {}", title, target.getIdentifier());
			} else {
				logger.warn("Could not dock '{}' into leaf {}; restoring its window", title, target.getIdentifier());
				fitted.undo().run();
				PanelFitter.detachFromParent(pane);
				cs.stage().getScene().setRoot(pane);
				cs.stage().show();
			}
		}

		Dialogs.showInfoNotification(
			resources.getString("name"),
			captured.isEmpty() ? resources.getString("info.no-capture") : resources.getString("info.windows-captured")
		);
	}

	/**
	 * Remove rows 1..n-1 of ViewerManager's private SplitPaneGrid, bottom up. Uses reflection: the grid and
	 * its removeRow(int) are package-private. If that fails, the extra rows are at least taken off the screen.
	 */
	private static void removeExtraGridRows(ViewerManager viewerManager) {
		SplitPane main = (SplitPane) viewerManager.getRegion();
		int rows = main.getItems().size();
		if (rows <= 1)
			return;
		try {
			var field = ViewerManager.class.getDeclaredField("splitPaneGrid");
			field.setAccessible(true);
			Object grid = field.get(viewerManager);
			var removeRow = grid.getClass().getDeclaredMethod("removeRow", int.class);
			removeRow.setAccessible(true);
			for (int r = rows - 1; r >= 1; r--)
				removeRow.invoke(grid, r);
		} catch (Exception e) {
			logger.error("Could not remove the extra viewer grid rows; hiding them instead", e);
			main.getItems().remove(1, main.getItems().size());
		}
	}

	/** Bring a docked captured dialog to the front: show its pane, select its tab, focus it. */
	private void revealDockable(Dockable dockable) {
		DockContainerLeaf leaf = dockable.getContainer();
		if (leaf != null && !isAttached(leaf))
			analysisToggle.ensureVisible();   // docked in the currently hidden analysis pane
		leaf = dockable.getContainer();
		if (leaf != null && isAttached(leaf))
			leaf.selectDockable(dockable);
		QuPathGUI.getInstance().getStage().toFront();
		Node node = dockable.getNode();
		if (node != null)
			node.requestFocus();
	}

	// ------------------------------------------------------------------ capture targets

	private static void collectLeaves(DockContainer c, List<DockContainerLeaf> out) {
		if (c instanceof DockContainerLeaf leaf)
			out.add(leaf);
		else if (c instanceof DockContainerBranch branch)
			for (DockContainer child : branch.getChildContainers())
				collectLeaves(child, out);
	}

	/** True if the container is part of the live tree below the Bento root. */
	private boolean isAttached(DockContainer c) {
		for (DockContainer p = c; p != null; p = p.getParentContainer())
			if (p == rootBranch)
				return true;
		return false;
	}

	private static DockContainerLeaf findLeaf(DockContainer from, Predicate<DockContainerLeaf> test) {
		if (from instanceof DockContainerLeaf leaf)
			return test.test(leaf) ? leaf : null;
		if (from instanceof DockContainerBranch branch)
			for (DockContainer child : branch.getChildContainers()) {
				DockContainerLeaf found = findLeaf(child, test);
				if (found != null)
					return found;
			}
		return null;
	}

	/** The analysis leaf, shown first if the analysis pane is currently hidden. */
	private DockContainerLeaf analysisTarget() {
		if (!isAttached(analysisLeaf))
			analysisToggle.ensureVisible();
		if (isAttached(analysisLeaf))
			return analysisLeaf;
		logger.warn("Analysis leaf is not in the layout; docking into the first available leaf");
		return anyLeafOrNew();
	}

	/** Leaf of the active viewer, else any attached leaf holding a viewer, else a new leaf. */
	private DockContainerLeaf viewerTarget() {
		QuPathViewer active = QuPathGUI.getInstance().getViewerManager().getActiveViewer();
		if (active != null && active.getView() != null) {
			Node view = active.getView();
			DockContainerLeaf leaf = findLeaf(rootBranch,
					l -> l.getDockables().stream().anyMatch(d -> d.getNode() == view));
			if (leaf != null)
				return leaf;
		}
		DockContainerLeaf leaf = findLeaf(rootBranch,
				l -> l.getDockables().stream().anyMatch(d -> d.getDragGroupMask() == DragGroups.VIEWER));
		if (leaf != null)
			return leaf;
		if (!isAttached(viewerLeaf))
			logger.warn("The original viewer leaf is no longer in the layout");
		return anyLeafOrNew();
	}

	private DockContainerLeaf anyLeafOrNew() {
		DockContainerLeaf leaf = findLeaf(rootBranch, l -> !l.getDockables().isEmpty());
		if (leaf != null)
			return leaf;
		leaf = builder.leaf("captured-" + System.nanoTime());
		leaf.setSide(Side.TOP);
		rootBranch.addContainer(leaf);
		return leaf;
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}