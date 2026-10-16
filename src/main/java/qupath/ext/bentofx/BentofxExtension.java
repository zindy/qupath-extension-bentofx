package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.util.Duration;
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
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TabPaneSkin;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.Modality;
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

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Predicate;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.control.DragDropStage;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;
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

	/** A captured window narrower than this is docked into the analysis pane, a wider one next to the viewers. */
	private static final double NARROW_PANEL_PX = 400;

	/**
	 * Persistent preference (shown in QuPath's Preferences window): initialise BentoFX automatically when
	 * QuPath starts. Off by default, since it replaces QuPath's own layout. Read once, at startup.
	 */
	private static final BooleanProperty enableOnStartupProperty = PathPrefs.createPersistentPreference(
			"bentofx.enableOnStartup", false);

	/**
	 * Persistent preference: when BentoFX is initialised, restore the layout saved with Extensions > BentoFX >
	 * Save layout. Off by default.
	 */
	private static final BooleanProperty restoreLayoutProperty = PathPrefs.createPersistentPreference(
			"bentofx.restoreLayoutOnStart", false);

	/** The saved layout lives in QuPath's user directory. */
	private static final String LAYOUT_FILE_NAME = "bentofx-layout.json";

	/**
	 * Homes of captured dialogs that were part of a restored layout but are not open (so cannot be restored):
	 * each is used, and removed, when that dialog is next captured. See {@link LayoutSnapshot.Slot}.
	 */
	private final Map<String, LayoutSnapshot.Slot> pendingSlots = new LinkedHashMap<>();

	private Bento bento;
	private DockBuilding builder;
	private DockContainerBranch rootBranch;
	private DockContainerLeaf viewerLeaf;
	private DockContainerLeaf analysisLeaf;

	private BentoMenuInterceptor menuInterceptor;
	private AnalysisPaneToggle analysisToggle;
	private PaneSizing paneSizing;
	private CollapseGuard collapseGuard;

	private CapturedWindows capturedWindows;

	/** Toolbar button (and the separator before it); present only while BentoFX is initialised. */
	private Button captureButton;
	private Separator captureSeparator;

	// Remembered so that deactivation can rebuild QuPath's own layout
	private SplitPane mainSplitPane;
	private TabPane analysisTabPane;
	/** QuPath's analysis tabs as they were before BentoFX took their contents, in order, and the selected one. */
	private List<Tab> originalAnalysisTabs;
	/** Tabs that scripts have added to QuPath's analysis TabPane (which is hidden while BentoFX is on), and their panes. */
	private final Map<Tab, ScriptTab> scriptTabs = new IdentityHashMap<>();
	private ListChangeListener<Tab> analysisTabListener;
	private Tab originalSelectedAnalysisTab;
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
		var restoreItem = new PropertyItemBuilder<>(restoreLayoutProperty, Boolean.class)
				.name(resources.getString("pref.restore-layout"))
				.category(EXTENSION_NAME)
				.description(resources.getString("pref.restore-layout.description"))
				.build();
		qupath.getPreferencePane()
				.getPropertySheet()
				.getItems()
				.addAll(propertyItem, restoreItem);
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

		MenuItem saveLayoutItem = new MenuItem(resources.getString("menu.save-layout"));
		saveLayoutItem.setOnAction(e -> bentoSaveLayout());
		menu.getItems().add(saveLayoutItem);

		MenuItem restoreLayoutItem = new MenuItem(resources.getString("menu.restore-layout"));
		restoreLayoutItem.setOnAction(e -> bentoRestoreLayout(true));
		menu.getItems().add(restoreLayoutItem);

		MenuItem deactivateItem = new MenuItem("Deactivate BentoFX");
		deactivateItem.setOnAction(e -> bentoTeardown());
		menu.getItems().add(deactivateItem);
	}

	/**
	 * A magnet button at the right-hand end of QuPath's main toolbar that captures floating windows
	 * (same as Extensions > BentoFX > Capture floating windows). Added when BentoFX is initialised and
	 * removed again by {@link #removeToolbarButton(QuPathGUI)} when it is deactivated.
	 */
	private void addToolbarButton(QuPathGUI qupath) {
		if (captureButton != null)
			return;
		ToolBar toolBar = qupath.getToolBar();
		if (toolBar == null) {
			logger.warn("QuPath's toolbar is not available; the capture button was not added");
			return;
		}
		Button button = new Button();
		button.setGraphic(IconFactory.createFontAwesome('\uf076'));   // FontAwesome "magnet"
		button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
		button.setFocusTraversable(false);
		button.setTooltip(new Tooltip("Capture floating windows into BentoFX\n(also brings torn-off panels and detached viewers back)"));
		button.setOnAction(e -> bentoCapture());
		Separator separator = new Separator(Orientation.VERTICAL);
		toolBar.getItems().addAll(separator, button);
		captureButton = button;
		captureSeparator = separator;
	}

	/** Take the capture button (and its separator) off QuPath's toolbar again. */
	private void removeToolbarButton(QuPathGUI qupath) {
		ToolBar toolBar = qupath.getToolBar();
		if (toolBar != null && captureButton != null)
			toolBar.getItems().removeAll(captureSeparator, captureButton);
		captureButton = null;
		captureSeparator = null;
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
		// Tabs with a themed, evenly spaced title (see ThemedHeader)
		bento.controlsBuilding().setHeaderFactory((dockable, pane) -> new ThemedHeader(dockable, pane).withDragDrop());

		// Drop hint that shows the size a docked panel will really get (read at drag time; PaneSizing is
		// created later in this method)
		bento.controlsBuilding().setContentWrapperFactory(leaf -> {
			leaf.getProperties().put(SizedHintContentWrapper.DROP_EXTENT,
					(SizedHintContentWrapper.DropExtent) (dockable, horizontal) ->
							paneSizing != null ? paneSizing.dropExtent(dockable, horizontal) : -1);
			return new SizedHintContentWrapper(leaf);
		});
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
		// QuPath's own TabPane is not shown while BentoFX is on, and must not keep hold of tab contents: a skin lays
		// out the content of every tab it holds, which takes it away from the pane showing it. bentoTeardown gives
		// it a skin again.
		analysisPane.setSkin(null);
		analysisPane.getTabs().stream()
			.filter(tab -> tab.getContent() != null)
			.forEach(tab -> {
				Dockable dockable = newAnalysisDockable(tab);
				analysisLeaf.addDockables(dockable);
				// A tab with an id is a script's: it stays in the TabPane so that the script, which looks for
				// its own tab there by id before adding it again, still finds it (see ScriptTab)
				if (tab.getId() != null) {
					dockable.setClosable(true);
					scriptTabs.put(tab, new ScriptTab(tab, dockable));
				}
			});

		// QuPath's annotation list, measurement table and hierarchy view only update while their tab is selected in
		// QuPath's TabPane (AnalysisTabPane: "tab.getTabPane() == null || tab.isSelected()"). That TabPane is no longer
		// shown and can only have one selected tab, so with BentoFX they would stop updating whenever they are not the
		// selected one. A tab that is not in any TabPane counts as always visible, which is how QuPath treats tabs it
		// has undocked, so take the docked tabs out of it. deactivation puts them back (see bentoTeardown).
		originalAnalysisTabs = new ArrayList<>(analysisPane.getTabs());
		originalSelectedAnalysisTab = analysisPane.getSelectionModel().getSelectedItem();
		analysisPane.getTabs().removeAll(
				originalAnalysisTabs.stream().filter(tab -> tab.getContent() != null && tab.getId() == null).toList());

		// From now on, tabs that scripts add to that TabPane are shown here
		analysisTabListener = this::onAnalysisTabsChanged;
		analysisPane.getTabs().addListener(analysisTabListener);

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

			// A viewer QuPath had detached into its own window: take it out of that window, which is then closed
			releaseDetachedViewerWindow(v, qupath.getStage());

			// Keep QuPath's file drop, but let Bento tab drags through
			ViewerDragDrop.install(qupath, v);
			PanelFitter.enforceMinSize(v, PanelFitter.MIN_VIEWER_W, PanelFitter.MIN_VIEWER_H);

			dockable.setNode(v);
			menuInterceptor.trackViewer(dockable, viewer);

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
		collapseGuard = new CollapseGuard(bento);   // see CollapseGuard
		bento.events().addEventListener(collapseGuard::onDockEvent);

		// Right-click menu on tabs: tab position and minimise (see TabContextMenu)
		bento.events().addEventListener(new TabContextMenu()::onDockEvent);
		bento.events().addEventListener(this::onScriptTabEvent);
		List<DockContainerLeaf> existingLeaves = new ArrayList<>();
		collectLeaves(rootBranch, existingLeaves);
		existingLeaves.forEach(l -> l.getDockables().forEach(TabContextMenu::install));

		rootBranch.requestFocus();
		rootBranch.requestLayout();

		addToolbarButton(qupath);

		// The layout saved last time, if the preference asks for it (after QuPath has laid out what we just built)
		if (restoreLayoutProperty.get())
			Platform.runLater(() -> bentoRestoreLayout(false));
		logger.info("BentoFX layout initialised with {} viewer(s)", viewerManager.getAllViewers().size());

	}

	/**
	 * If a viewer's view is in a window of its own (QuPath's "Detach viewer from grid"), take it out of that
	 * window and close the window, so no empty window is left behind when the view is docked into BentoFX.
	 * Mirrors what QuPath itself does when it re-attaches a viewer: the window's content is emptied first, so
	 * nothing in it still refers to the view.
	 */
	private static void releaseDetachedViewerWindow(Node view, Stage mainStage) {
		var scene = view.getScene();
		if (scene == null || !(scene.getWindow() instanceof Stage stage) || stage == mainStage)
			return;
		logger.debug("Closing the window of a detached viewer ('{}')", stage.getTitle());
		if (scene.getRoot() instanceof Pane pane)
			pane.getChildren().clear();
		scene.setRoot(new BorderPane());
		stage.titleProperty().unbind();   // QuPath binds the title to the viewer's image name
		stage.hide();
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
		collapseGuard.dispose();
		menuInterceptor.dispose();        // add row/column items, active-viewer listener
		List<DockContainerLeaf> leaves = new ArrayList<>();
		collectLeaves(rootBranch, leaves);
		if (analysisToggle.hiddenHost() != null)
			collectLeaves(analysisToggle.hiddenHost(), leaves);   // analysis pane currently hidden
		// Tabs that were dragged out of the main window live in windows BentoFX opened itself, each with a root of
		// its own. Their content has to be released too: it is bound to those windows, and is taken back below.
		List<Stage> floatingStages = new ArrayList<>();
		collectFloatingLeaves(leaves, floatingStages);
		for (DockContainerLeaf leaf : leaves)
			leaf.selectDockable(null);

		// 2. Give docked dialogs back to their own windows (and show them again)
		capturedWindows.releaseAll();
		pendingSlots.clear();
		stopWatchingForDialogs();

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
		//    The tabs were taken out of the TabPane at setup: put them back, in their original order.
		if (analysisTabListener != null) {
			analysisTabPane.getTabs().removeListener(analysisTabListener);
			analysisTabListener = null;
		}
		scriptTabs.values().forEach(ScriptTab::detach);
		scriptTabs.clear();
		analysisTabPane.setSkin(new TabPaneSkin(analysisTabPane));
		List<Tab> tabsToRestore = new ArrayList<>(originalAnalysisTabs != null ? originalAnalysisTabs : List.of());
		for (Tab tab : analysisTabPane.getTabs())      // tabs scripts added while BentoFX was on stay too
			if (!tabsToRestore.contains(tab))
				tabsToRestore.add(tab);
		for (Tab tab : tabsToRestore) {
			Node content = tab.getContent();
			if (content != null) {
				tab.setContent(null);
				tab.setContent(content);
			}
		}
		analysisTabPane.getTabs().setAll(tabsToRestore);
		if (originalSelectedAnalysisTab != null)
			analysisTabPane.getSelectionModel().select(originalSelectedAnalysisTab);

		// 6. Main layout exactly as QuPathMainPaneManager.setAnalysisPaneVisible(true) builds it
		mainSplitPane.setOnDragDropped(null);
		mainSplitPane.getItems().setAll(analysisTabPane, viewerManager.getRegion());
		mainSplitPane.setDividerPosition(0, total > 0 ? Math.min(analysisWidth / total, 0.5) : 0.15);

		// 6b. Close the windows BentoFX opened for dragged-out tabs: everything they showed (viewers, captured
		//     dialogs) has been taken back above, so only an empty shell is left. hide(), not close(): a close
		//     request makes BentoFX close, and so remove, the tabs it still lists.
		for (Stage floatingStage : floatingStages)
			floatingStage.hide();

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
		collapseGuard = null;
		capturedWindows = null;
		mainSplitPane = null;
		analysisTabPane = null;
		originalAnalysisTabs = null;
		originalSelectedAnalysisTab = null;
		bentoCssUrl = null;
		removeToolbarButton(qupath);

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

	/**
	 * Collect the floating dialog windows to capture, emptying and closing each one as it is collected, so the
	 * caller must dock every window returned.
	 *
	 * @param only just this window, leaving every other window untouched; all of them if null
	 */
	private List<CapturedStage> getDialogWindowsPanes(Stage only) {
		List<CapturedStage> result = new ArrayList<>();
		Stage mainStage = QuPathGUI.getInstance().getStage();

		List<Window> windows = new ArrayList<>(Window.getWindows());

		for (Window window : windows) {
			if (window instanceof Stage stage) {
				if (only != null && stage != only)
					continue;
				if (stage != mainStage && !(stage instanceof DragDropStage) && stage.isShowing()
					&& !capturedWindows.isCapturedStage(stage) &&
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
		captureWindows(null);
	}

	/**
	 * @param only capture just this window (a dialog that has just opened and has a saved place), or all of them if null
	 */
	private void captureWindows(Stage only) {
		if (bento == null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-not-setup"));
			logger.error(resources.getString("error.bento-not-setup"));
			return;
		}

		// Tabs that were torn out of the main window, or detached viewers, come back first
		int reintegrated = only == null ? reintegrateFloatingTabs() : 0;

		List<CapturedStage> captured = getDialogWindowsPanes(only);

		for (CapturedStage cs : captured) {
			String title = cs.title();
			Parent pane = cs.root();

			double paneWidth = pane.getBoundsInLocal().getWidth();
			double paneHeight = pane.getBoundsInLocal().getHeight();
			logger.debug("Processing window '{}' - width={}", title, paneWidth);

			boolean narrow = paneWidth < NARROW_PANEL_PX;

			// A dialog the restored layout had a place for goes there rather than by its width
			LayoutSnapshot.Slot slot = pendingSlots.remove(title);
			boolean edgeSlot = slot != null && "edge".equals(slot.placement);

			Dockable dockable = builder.dockable();
			dockable.setTitle(title);
			// Peel anonymous wrappers, lift USE_PREF_SIZE caps, add grow hints.
			// Narrow form-like panels (InstanSeg) also get a ScrollPane so they can shrink.
			PanelFitter.Fitted fitted = PanelFitter.fit(pane, narrow);
			dockable.setNode(fitted.node());
			// Remember the window's own size as the starting size of any pane made for it
			paneSizing.declare(dockable, edgeSlot && slot.size > 0 ? slot.size : paneWidth, paneHeight);

			// FLEX: the width only picks the initial leaf; the panel can then be dragged anywhere
			dockable.setDragGroupMask(DragGroups.FLEX);
			dockable.setClosable(true);

			// Never use the leaf fields directly: a leaf that was emptied by dragging is pruned from the
			// tree, and adding to it would silently put the panel in a detached, invisible leaf.
			DockContainerLeaf target = slot == null ? null : slotTarget(slot);
			boolean newRail = edgeSlot && target != null && target.getDockables().isEmpty();
			if (target == null)
				target = narrow ? analysisTarget() : viewerTarget();
			if (target.addDockable(dockable)) {
				target.selectDockable(dockable);
				// Remember the original Stage so closing the tab can give the dialog back
				capturedWindows.register(dockable, cs.stage(), pane, fitted);
				logger.debug("Docked '{}' into leaf {}{}", title, target.getIdentifier(), slot == null ? "" : " (its saved place)");
				if (newRail && slot.collapsed)
					minimiseLater(target, dockable);
			} else {
				if (newRail && target.getParentContainer() != null)
					target.getParentContainer().removeContainer(target);   // do not leave an empty rail behind
				logger.warn("Could not dock '{}' into leaf {}; restoring its window", title, target.getIdentifier());
				fitted.undo().run();
				PanelFitter.detachFromParent(pane);
				cs.stage().getScene().setRoot(pane);
				cs.stage().show();
			}
		}

		if (only != null && captured.isEmpty())
			return;   // nothing to say about a window that turned out not to be capturable
		Dialogs.showInfoNotification(
			resources.getString("name"),
			captured.isEmpty() && reintegrated == 0 ? resources.getString("info.no-capture") : resources.getString("info.windows-captured")
		);
	}

	// ------------------------------------------------------------------ tabs that scripts add to the analysis pane

	private Dockable newAnalysisDockable(Tab tab) {
		Dockable dockable = builder.dockable();
		dockable.setTitle(tab.getText());
		dockable.setNode(tab.getContent());
		dockable.setTooltip(tab.getTooltip());
		dockable.setClosable(false);  // Remove close button
		dockable.setDragGroupMask(DragGroups.ANALYSIS);
		return dockable;
	}

	/**
	 * A tab a script added to QuPath's analysis TabPane, and the BentoFX tab that shows it. Scripts add their tabs
	 * to that TabPane (as they always have), which is hidden while BentoFX is on, so this carries them over:
	 * a tab added there appears in the analysis pane, one removed from there disappears, and its title and
	 * tooltip follow. The tab stays in the TabPane meanwhile, so that scripts that look for their earlier tab
	 * there before adding a new one (by id, say) keep working.
	 */
	private final class ScriptTab {
		private final Tab tab;
		private final Dockable dockable;
		private final javafx.beans.value.ChangeListener<String> textListener;
		private final javafx.beans.value.ChangeListener<Tooltip> tooltipListener;

		ScriptTab(Tab tab, Dockable dockable) {
			this.tab = tab;
			this.dockable = dockable;
			textListener = (obs, old, text) -> dockable.setTitle(text);
			tooltipListener = (obs, old, tooltip) -> dockable.setTooltip(tooltip);
			tab.textProperty().addListener(textListener);
			tab.tooltipProperty().addListener(tooltipListener);
		}

		/** Stop following the tab. */
		void detach() {
			tab.textProperty().removeListener(textListener);
			tab.tooltipProperty().removeListener(tooltipListener);
		}

		/** Take the tab's pane out of BentoFX, wherever the user has moved it to. */
		void remove() {
			detach();
			DockContainerLeaf leaf = dockable.getContainer();
			if (leaf != null)
				leaf.removeDockable(dockable);
		}
	}

	/** Dockables that are being closed (DockableClosing seen, DockableRemoved not yet), to tell a close from a move. */
	private final Set<Dockable> closingScriptTabs = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * Closing a script's tab with its close button does what closing a tab of a TabPane does: the tab's
	 * {@code onCloseRequest} may veto it, the tab leaves QuPath's TabPane (so that it is not brought back when
	 * BentoFX is switched off), and its {@code onClosed} runs.
	 */
	private void onScriptTabEvent(DockEvent event) {
		if (event instanceof DockEvent.DockableClosing closing) {
			Tab tab = scriptTabFor(closing.dockable());
			if (tab == null)
				return;
			if (tab.getOnCloseRequest() != null) {
				Event request = new Event(tab, tab, Tab.TAB_CLOSE_REQUEST_EVENT);
				tab.getOnCloseRequest().handle(request);
				if (request.isConsumed()) {
					closing.cancel();
					return;
				}
			}
			// closeDockable() removes the dockable right after this event: a move never gets here
			closingScriptTabs.add(closing.dockable());
			Platform.runLater(() -> closingScriptTabs.remove(closing.dockable()));   // in case another listener cancels
		} else if (event instanceof DockEvent.DockableRemoved removed && closingScriptTabs.remove(removed.dockable())) {
			Tab tab = scriptTabFor(removed.dockable());
			if (tab == null || analysisTabPane == null)
				return;
			analysisTabPane.getTabs().remove(tab);   // our listener sees the tab go and forgets it
			if (tab.getOnClosed() != null)
				tab.getOnClosed().handle(new Event(tab, tab, Tab.CLOSED_EVENT));
		}
	}

	private Tab scriptTabFor(Dockable dockable) {
		for (var entry : scriptTabs.entrySet())
			if (entry.getValue().dockable == dockable)
				return entry.getKey();
		return null;
	}

	private void onAnalysisTabsChanged(ListChangeListener.Change<? extends Tab> change) {
		while (change.next()) {
			for (Tab tab : change.getRemoved()) {
				ScriptTab shown = scriptTabs.remove(tab);
				if (shown != null)
					shown.remove();
			}
			for (Tab tab : change.getAddedSubList())
				showScriptTab(tab);
		}
	}

	private void showScriptTab(Tab tab) {
		if (bento == null || scriptTabs.containsKey(tab))
			return;
		if (tab.getContent() == null) {
			// Its content comes later: show it then
			javafx.beans.value.ChangeListener<Node> once = new javafx.beans.value.ChangeListener<>() {
				@Override
				public void changed(javafx.beans.value.ObservableValue<? extends Node> obs, Node old, Node content) {
					if (content == null)
						return;
					tab.contentProperty().removeListener(this);
					if (analysisTabListener != null && analysisTabPane != null && analysisTabPane.getTabs().contains(tab))
						showScriptTab(tab);
				}
			};
			tab.contentProperty().addListener(once);
			return;
		}
		Dockable dockable = newAnalysisDockable(tab);
		dockable.setClosable(true);           // the built-in tabs cannot be closed, a script's can
		analysisLeaf.addDockable(dockable);   // not analysisTarget(): that would show a hidden analysis pane
		scriptTabs.put(tab, new ScriptTab(tab, dockable));
		logger.debug("Showing the analysis tab '{}' that a script added", tab.getText());
	}

	/** Where a dialog with a saved slot goes; null if its slot cannot be honoured here (the usual rule applies). */
	private DockContainerLeaf slotTarget(LayoutSnapshot.Slot slot) {
		if (slot.placement == null)
			return null;
		return switch (slot.placement) {
			case "analysis" -> analysisTarget();
			case "viewers" -> viewerTarget();
			case "edge" -> edgeLeaf(slot);
			case "beside" -> besideLeaf(slot);
			default -> null;
		};
	}

	/**
	 * The pane along the window's left or right edge that holds captured dialogs with this tab side: the one
	 * there already, or a new one (a rail). Null if the window is not split side by side.
	 */
	private DockContainerLeaf edgeLeaf(LayoutSnapshot.Slot slot) {
		if (rootBranch.getOrientation() != Orientation.HORIZONTAL)
			return null;
		boolean right = !"LEFT".equals(slot.edge);
		Side side = Side.TOP;
		try {
			if (slot.side != null)
				side = Side.valueOf(slot.side);
		} catch (IllegalArgumentException ignored) {
			// keep the default
		}
		List<DockContainer> children = rootBranch.getChildContainers();
		if (!children.isEmpty()) {
			DockContainer atEdge = right ? children.getLast() : children.getFirst();
			if (atEdge instanceof DockContainerLeaf leaf && leaf != analysisLeaf && leaf.getSide() == side
					&& !leaf.getDockables().isEmpty()
					&& leaf.getDockables().stream().allMatch(d -> d.getDragGroupMask() == DragGroups.FLEX))
				return leaf;
		}
		DockContainerLeaf leaf = builder.leaf("slot-" + System.nanoTime());
		leaf.setSide(side);
		Branches.insert(builder, rootBranch, right ? children.size() : 0, leaf);
		return leaf;
	}

	/**
	 * The pane split off one side of the analysis or viewer pane that holds this dialog's kind, as the slot says: the
	 * one there already, or a new one made the way dropping a tab on that side of the pane makes it.
	 */
	private DockContainerLeaf besideLeaf(LayoutSnapshot.Slot slot) {
		Side where;
		try {
			where = Side.valueOf(slot.edge);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
		DockContainerLeaf anchor = "analysis".equals(slot.anchor)
				? (isAttached(analysisLeaf) ? analysisLeaf : null)
				: findViewerLeaf();
		if (anchor == null || anchor.getParentContainer() == null)
			return null;
		Side tabs = Side.TOP;
		try {
			if (slot.side != null)
				tabs = Side.valueOf(slot.side);
		} catch (IllegalArgumentException ignored) {
			// keep the default
		}

		// A neighbour from an earlier dialog with the same slot: join it
		boolean vertical = where == Side.TOP || where == Side.BOTTOM;
		DockContainerBranch parent = anchor.getParentContainer();
		if (parent.getOrientation() == (vertical ? Orientation.VERTICAL : Orientation.HORIZONTAL)) {
			List<DockContainer> kids = parent.getChildContainers();
			int i = kids.indexOf(anchor) + (where == Side.BOTTOM || where == Side.RIGHT ? 1 : -1);
			if (i >= 0 && i < kids.size() && kids.get(i) instanceof DockContainerLeaf neighbour
					&& neighbour != analysisLeaf && !neighbour.getDockables().isEmpty()
					&& neighbour.getSide() == tabs
					&& neighbour.getDockables().stream().allMatch(d -> d.getDragGroupMask() == DragGroups.FLEX))
				return neighbour;
		}
		DockContainerLeaf leaf = builder.leaf("slot-" + System.nanoTime());
		leaf.setSide(tabs);
		Branches.splitBeside(builder, anchor, leaf, where);
		return leaf;
	}

	/** Minimise a pane once it has been laid out, so that it remembers the size it opens at. */
	private void minimiseLater(DockContainerLeaf leaf, Dockable dockable) {
		Platform.runLater(() -> {
			if (bento == null || !isAttached(leaf))
				return;
			var scene = rootBranch.getScene();
			if (scene != null) {
				scene.getRoot().applyCss();
				scene.getRoot().layout();
			}
			if (!leaf.isCollapsed())
				leaf.toggleCollapse(dockable);
		});
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

	/**
	 * Move every tab that lives in a window BentoFX opened (a tab dragged out of the main window, a detached
	 * viewer) back into the main layout, where its kind decides where it goes: viewers into the viewer area,
	 * analysis tabs into the analysis pane, captured dialogs by the size of the window they came from. The
	 * emptied windows close themselves; any that do not are hidden.
	 *
	 * @return the number of tabs moved
	 */
	private int reintegrateFloatingTabs() {
		List<DockContainerLeaf> leaves = new ArrayList<>();
		List<Stage> stages = new ArrayList<>();
		collectFloatingLeaves(leaves, stages);

		int moved = 0;
		for (DockContainerLeaf leaf : leaves) {
			for (Dockable dockable : new ArrayList<>(leaf.getDockables())) {
				// Decide before removing: the target may have to reveal the analysis pane or make a leaf
				DockContainerLeaf target = reintegrationTarget(dockable);
				if (!leaf.removeDockable(dockable)) {
					logger.warn("Could not take '{}' out of its floating window", dockable.getTitle());
					continue;
				}
				if (target.addDockable(dockable)) {
					target.selectDockable(dockable);
					moved++;
					logger.debug("Reintegrated '{}' into leaf {}", dockable.getTitle(), target.getIdentifier());
				} else {
					logger.warn("Could not dock '{}' into leaf {}", dockable.getTitle(), target.getIdentifier());
				}
			}
		}
		// Normally gone already (an emptied window closes itself); hide, never close(): a close request
		// would make BentoFX close the tabs it still lists
		for (Stage stage : stages)
			if (stage.isShowing())
				stage.hide();
		return moved;
	}

	private DockContainerLeaf reintegrationTarget(Dockable dockable) {
		int mask = dockable.getDragGroupMask();
		if (mask == DragGroups.VIEWER)
			return viewerPaneTarget();
		if (mask == DragGroups.ANALYSIS)
			return analysisTarget();
		// Captured dialog (FLEX): same rule as when it was captured, by the size of its original window
		double width = paneSizing.declaredWidth(dockable);
		return width > 0 && width < NARROW_PANEL_PX ? analysisTarget() : viewerTarget();
	}

	/**
	 * Leaves (and their windows) of every window BentoFX opened itself: each has a Bento root of its own,
	 * separate from the one in QuPath's main window.
	 */
	private void collectFloatingLeaves(List<DockContainerLeaf> leaves, List<Stage> stages) {
		floatingLeavesByStage().forEach((stage, stageLeaves) -> {
			stages.add(stage);
			leaves.addAll(stageLeaves);
		});
	}

	private Map<Stage, List<DockContainerLeaf>> floatingLeavesByStage() {
		Map<Stage, List<DockContainerLeaf>> result = new LinkedHashMap<>();
		Stage mainStage = QuPathGUI.getInstance().getStage();
		for (var floatingRoot : new ArrayList<>(bento.getRootContainers())) {
			if (floatingRoot.equals(rootBranch))
				continue;
			var floatingScene = floatingRoot.getScene();
			if (floatingScene != null && floatingScene.getWindow() instanceof Stage floatingStage
					&& floatingStage != mainStage)
				collectLeaves(floatingRoot, result.computeIfAbsent(floatingStage, s -> new ArrayList<>()));
		}
		return result;
	}

	// ------------------------------------------------------------------ saving and restoring the layout

	/** The file the layout is saved in. */
	private static Path layoutFile() {
		String user = PathPrefs.userPathProperty().get();
		Path dir = user == null || user.isBlank() ? Path.of(System.getProperty("user.home"), "QuPath") : Path.of(user);
		return dir.resolve(LAYOUT_FILE_NAME);
	}

	/** Extensions > BentoFX > Save layout. */
	private void bentoSaveLayout() {
		if (bento == null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-not-setup"));
			return;
		}
		try {
			DockContainer hidden = analysisToggle.hiddenHost();
			LayoutSnapshot snapshot = LayoutSnapshot.capture(rootBranch, hidden, analysisToggle.currentWidth(), analysisLeaf);
			floatingLeavesByStage().forEach(snapshot::addFloating);
			Path file = layoutFile();
			Files.createDirectories(file.getParent());
			Files.writeString(file, snapshot.toJson(), StandardCharsets.UTF_8);
			logger.info("Layout saved to {}", file);
			Dialogs.showInfoNotification(EXTENSION_NAME, resources.getString("info.layout-saved"));
		} catch (IOException | RuntimeException e) {
			logger.error("Could not save the layout", e);
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.layout-save-failed"));
		}
	}

	/**
	 * Extensions > BentoFX > Restore layout, and the "restore on start" preference.
	 *
	 * @param interactive false for the automatic restore, which says nothing if there is no saved layout yet
	 */
	private void bentoRestoreLayout(boolean interactive) {
		if (bento == null) {
			if (interactive)
				Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.bento-not-setup"));
			return;
		}
		Path file = layoutFile();
		if (!Files.isRegularFile(file)) {
			if (interactive)
				Dialogs.showInfoNotification(EXTENSION_NAME, resources.getString("info.layout-none"));
			return;
		}
		LayoutSnapshot snapshot;
		try {
			snapshot = LayoutSnapshot.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			logger.error("Could not read the saved layout {}", file, e);
			snapshot = null;
		}
		if (snapshot == null) {
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.layout-unreadable"));
			return;
		}

		// Everything must be in the main layout, and the analysis pane showing, before it is rebuilt
		reintegrateFloatingTabs();
		boolean wasHidden = analysisToggle.hiddenHost() != null;
		analysisToggle.ensureVisible();
		LayoutSnapshot toRestore = snapshot;
		// Showing the pane queues a width correction of its own: let that run before the saved sizes go in
		if (wasHidden)
			Platform.runLater(() -> restoreLayout(toRestore));
		else
			restoreLayout(toRestore);
	}

	private void restoreLayout(LayoutSnapshot snapshot) {
		if (bento == null)
			return;
		var host = new LayoutSnapshot.Host() {
			@Override public DockBuilding builder() { return builder; }
			@Override public DockContainerBranch root() { return rootBranch; }
			@Override public DockContainerLeaf anchor() { return analysisLeaf; }
			@Override public Dockable newViewer() { return menuInterceptor.newViewerPane(); }
			@Override public PaneSizing sizing() { return paneSizing; }
			@Override public void setAnalysisVisible(boolean visible) { analysisToggle.setVisible(visible); }
		};
		LayoutSnapshot.Result result = snapshot.apply(host);
		pendingSlots.clear();
		for (LayoutSnapshot.Slot slot : result.pendingSlots())
			pendingSlots.put(slot.title, slot);
		if (!pendingSlots.isEmpty())
			logger.info("Layout restored; dialogs waiting to dock into their saved places when opened: {}", pendingSlots.keySet());

		// Tabs the saved layout does not mention (new dialogs, say) go where capturing would have put them
		for (Dockable d : result.unplaced()) {
			DockContainerLeaf target = reintegrationTarget(d);
			if (target.addDockable(d))
				target.selectDockable(d);
		}
		result.finish().run();

		if (result.failed())
			Dialogs.showErrorNotification(resources.getString("error"), resources.getString("error.layout-restore-failed"));
		else if (pendingSlots.isEmpty())
			Dialogs.showInfoNotification(EXTENSION_NAME, resources.getString("info.layout-restored"));
		else
			Dialogs.showInfoNotification(EXTENSION_NAME, resources.getString("info.layout-restored-waiting")
					+ " " + String.join(", ", pendingSlots.keySet()));
		watchForDialogs();
	}

	// ------------------------------------------------------------------ docking dialogs into their saved places

	private ListChangeListener<Window> dialogWatcher;

	/**
	 * Dock the dialogs the restored layout is waiting for as soon as their windows are open: those open now, and
	 * any that open later (QuPath makes these windows on demand, so they cannot be opened for the layout).
	 */
	private void watchForDialogs() {
		if (pendingSlots.isEmpty())
			return;
		if (dialogWatcher == null) {
			dialogWatcher = change -> {
				while (change.next())
					for (Window w : change.getAddedSubList())
						dockWhenReady(w);
			};
			Window.getWindows().addListener(dialogWatcher);
		}
		for (Window w : new ArrayList<>(Window.getWindows()))
			dockWhenReady(w);
	}

	private void stopWatchingForDialogs() {
		if (dialogWatcher != null) {
			Window.getWindows().removeListener(dialogWatcher);
			dialogWatcher = null;
		}
	}

	private void dockWhenReady(Window window) {
		if (!(window instanceof Stage stage) || stage instanceof DragDropStage || stage.getModality() != Modality.NONE)
			return;
		if (!pendingSlots.containsKey(stage.getTitle()))
			return;
		// Give the window a moment to be laid out: its size is part of how it is fitted into its pane
		PauseTransition wait = new PauseTransition(Duration.millis(250));
		wait.setOnFinished(e -> {
			if (bento != null && stage.isShowing() && pendingSlots.containsKey(stage.getTitle()))
				captureWindows(stage);
			if (pendingSlots.isEmpty())
				stopWatchingForDialogs();
		});
		wait.play();
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

	static void collectLeaves(DockContainer c, List<DockContainerLeaf> out) {
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

	/** Leaf of the active viewer, else any attached leaf holding a viewer, else any leaf. */
	private DockContainerLeaf viewerTarget() {
		DockContainerLeaf leaf = findViewerLeaf();
		if (leaf != null)
			return leaf;
		if (!isAttached(viewerLeaf))
			logger.warn("The original viewer leaf is no longer in the layout");
		return anyLeafOrNew();
	}

	/**
	 * Where a viewer pane goes: the viewer area, or a new leaf of its own if the main window has none (all
	 * its viewers are detached). Never the analysis leaf.
	 */
	private DockContainerLeaf viewerPaneTarget() {
		DockContainerLeaf leaf = findViewerLeaf();
		if (leaf != null)
			return leaf;
		leaf = builder.leaf("viewer-leaf-" + System.nanoTime());
		leaf.setSide(Side.TOP);
		rootBranch.addContainer(leaf);
		return leaf;
	}

	/** Leaf of the active viewer, else any leaf of the main layout holding a viewer; null if there is none. */
	private DockContainerLeaf findViewerLeaf() {
		QuPathViewer active = QuPathGUI.getInstance().getViewerManager().getActiveViewer();
		if (active != null && active.getView() != null) {
			Node view = active.getView();
			DockContainerLeaf leaf = findLeaf(rootBranch,
					l -> l.getDockables().stream().anyMatch(d -> d.getNode() == view));
			if (leaf != null)
				return leaf;
		}
		return findLeaf(rootBranch,
				l -> l.getDockables().stream().anyMatch(d -> d.getDragGroupMask() == DragGroups.VIEWER));
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