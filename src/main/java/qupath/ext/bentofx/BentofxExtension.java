package qupath.ext.bentofx;

import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.QuPathViewer;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
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

	private Bento bento;
	private DockBuilding builder;
	private DockContainerBranch rootBranch;
	private DockContainerLeaf viewerLeaf;
	private DockContainerLeaf analysisLeaf;

	private BentoMenuInterceptor menuInterceptor;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addMenuItem(qupath);
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		
		MenuItem initItem = new MenuItem("Initialisation");
		initItem.setOnAction(e -> bentoSetup());
		menu.getItems().add(initItem);

		MenuItem captureItem = new MenuItem("Capture floating windows");
		captureItem.setOnAction(e -> bentoCapture());
		menu.getItems().add(captureItem);
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
		bento = new Bento();
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
		var viewerManager = qupath.getViewerManager();
		var analysisPane = qupath.getAnalysisTabPane();

		logger.debug("Getting the parent split pane...");
		Node node = analysisPane;
		while (node != null && !(node instanceof SplitPane)) {
			node = node.getParent();
		}

		if (node instanceof SplitPane splitPane) {
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
				scene.getStylesheets().add(cssUrl.toExternalForm());
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
				dockable.setDragGroupMask(0); // Separate group from viewers
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
			dockable.setTitle("Viewer " + (i + 1));
			var v = viewer.getView();

			v.setOnDragOver(null);
			v.setOnDragDropped(null);
			v.setOnDragDone(null);

			dockable.setNode(v);
			menuInterceptor.trackViewer(dockable, viewer);

			if (i == 0) {
				dockable.setClosable(false);
			}

			dockable.setDragGroupMask(1);
			viewerLeaf.addDockables(dockable);
		}

		// Register current viewers and intercept context menu actions
		viewers = viewerManager.getAllViewers();
		for (QuPathViewer viewer : viewers) {
			menuInterceptor.registerViewer(viewer);
		}

		rootBranch.requestFocus();
		rootBranch.requestLayout();

		logger.info("BentoFX layout initialised with {} viewer(s)", viewerManager.getAllViewers().size());

	}

	/**
	 * Find all non-main QuPath windows and return their root panes.
	 */
	private Map<String, Parent> getDialogWindowsPanes() {
		Map<String, Parent> paneMap = new HashMap<>();
		Stage mainStage = QuPathGUI.getInstance().getStage();

		List<Window> windows = new ArrayList<>(Window.getWindows());

		for (Window window : windows) {
			if (window instanceof Stage stage) {
				if (stage != mainStage && stage.isShowing() && 
					stage.getTitle() != null && !stage.getTitle().isEmpty()) {

					String windowTitle = stage.getTitle();
					if (stage.getScene() != null) {
						Parent pane = stage.getScene().getRoot();
						// Decouple root before closing stage
						stage.getScene().setRoot(new Group()); 
						paneMap.put(windowTitle, pane);
						logger.debug("Found floating window: {}", windowTitle);
					}
					stage.close();
				}
			}
		}
		return paneMap;
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

		Map<String, Parent> paneMap = getDialogWindowsPanes();

		for (Map.Entry<String, Parent> entry : paneMap.entrySet()) {
			String title = entry.getKey();
			Parent pane = entry.getValue();

			double paneWidth = pane.getBoundsInLocal().getWidth();
			logger.debug("Processing window '{}' - width={}", title, paneWidth);

			boolean narrow = paneWidth < 400;

			Dockable dockable = builder.dockable();
			dockable.setTitle(title);
			// Peel anonymous wrappers, lift USE_PREF_SIZE caps, add grow hints.
			// Narrow form-like panels (InstanSeg) also get a ScrollPane so they can shrink.
			dockable.setNode(PanelFitter.fit(pane, narrow));

			if (narrow) {
				dockable.setDragGroupMask(0);
				dockable.setClosable(true);
				analysisLeaf.addDockables(dockable);
			} else {
				dockable.setDragGroupMask(1);
				dockable.setClosable(true);
				viewerLeaf.addDockables(dockable);
			}
		}

		Dialogs.showInfoNotification(
			resources.getString("name"),
			paneMap.isEmpty() ? resources.getString("info.no-capture") : resources.getString("info.windows-captured")
		);
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