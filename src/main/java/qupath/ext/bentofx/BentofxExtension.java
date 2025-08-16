package qupath.ext.bentofx;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.geometry.Side;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.stage.Window;
import javafx.scene.control.SplitPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;			

import java.io.IOException;
import java.util.ResourceBundle;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

/**
 * This is a demo to provide a template for creating a new QuPath extension.
 * <p>
 * It doesn't do much - it just shows how to add a menu item and a preference.
 * See the code and comments below for more info.
 * <p>
 * <b>Important!</b> For your extension to work in QuPath, you need to make sure the name &amp; package
 * of this class is consistent with the file
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
public class BentofxExtension implements QuPathExtension {
	// TODO: add and modify strings to this resource bundle as needed
	/**
	 * A resource bundle containing all the text used by the extension. This may be useful for translation to other languages.
	 * Note that this is optional and you can define the text within the code and FXML files that you use.
	 */
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bentofx.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(BentofxExtension.class);

	/**
	 * Display name for your extension
	 * TODO: define this
	 */
	private static final String EXTENSION_NAME = resources.getString("name");

	/**
	 * Short description, used under 'Extensions > Installed extensions'
	 * TODO: define this
	 */
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");

	/**
	 * QuPath version that the extension is designed to work with.
	 * This allows QuPath to inform the user if it seems to be incompatible.
	 * TODO: define this
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	/**
	 * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
	 */
	private boolean isInstalled = false;

    private Bento bento;
    private DockBuilding builder;
    private DockContainerBranch rootBranch;
    private DockContainerLeaf viewerLeaf;
    private DockContainerLeaf analysisLeaf;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addMenuItem(qupath);
	}

	/**
	 * Bentofx showing how a new command can be added to a QuPath menu.
	 * @param qupath The QuPath GUI
	 */
	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("Initialisation");
		menuItem.setOnAction(e -> bentoSetup());
		menu.getItems().add(menuItem);
		menuItem = new MenuItem("Capture floating windows");
		menuItem.setOnAction(e -> bentoCapture());
		menu.getItems().add(menuItem);
	}

   	/**
	 * First convert the QuPath layout into BentoFX panels
	 */
	private void bentoSetup() {
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
		// These leaves shouldn't auto-expand. They are intended to be a set size.
        DockContainerBranch.setResizableWithParent(analysisLeaf, false);

		var qupath = QuPathGUI.getInstance();
		//var viewerPane = qupath.getViewer().getView();
		var viewerManager = qupath.getViewerManager();
		var analysisPane = qupath.getAnalysisTabPane();

		// Here's the parent
		//var parent = qupath.mainPaneManager.splitPane;
		logger.debug("Getting the parent...");

		Node node = analysisPane;
		while (node != null && !(node instanceof SplitPane)) {
			node = node.getParent();
		}

		if (node instanceof SplitPane splitPane) {
			splitPane.getItems().clear();
			splitPane.getItems().add(rootBranch);

			logger.debug("Intercepting bento events in parent...");
			splitPane.setOnDragDropped(event -> {
				if (event.getGestureSource() != null) {
					Object source = event.getGestureSource();
					String nodeClass = source.getClass().getName();
					if (nodeClass.contains("bentofx") || nodeClass.contains("bento")) {
						// This is a Bento event - intercept it
						logger.info("Caught Bento drop event: {}",nodeClass);
						event.setDropCompleted(true);
						event.consume();
					}
				}
			});
		} else {
			logger.warn("No SplitPane found in parent chain!");
		}

		// Load CSS from classpath resources
		String cssPath = getClass().getResource("/bento.css").toExternalForm();
		logger.debug("Applying CSS from {}", cssPath);
		rootBranch.getScene().getStylesheets().add(cssPath);

		// Add The analysis pane tools
		logger.debug("doing the analysis pane...");
		analysisPane.getTabs().stream()
			.filter(tab -> tab.getContent() != null)
			.forEach(tab -> {
				Dockable dockable = builder.dockable();
				dockable.setTitle(tab.getText());
				dockable.setNode(tab.getContent());
				dockable.setClosable(false);  // Remove close button
				dockable.setDragGroup(0); // separate group from analysis pane
				analysisLeaf.addDockables(dockable);
			});

		logger.debug("doing the viewers...");

		List<QuPathViewer> viewers = viewerManager.getAllViewers();
		for (int i = 0; i < viewers.size(); i++) {
			QuPathViewer viewer = viewers.get(i);
			
			logger.debug("{}/{} viewers added...", i + 1, viewers.size());
			
			// Create dockable for this viewer
			Dockable dockable = builder.dockable();
			dockable.setTitle("Viewer " + (i + 1));
			var v = viewer.getView();
				
			// Undoing qupath.getDefaultDragDropListener().setupTarget(viewer.getView());
			v.setOnDragOver(null);
			v.setOnDragDropped(null);
			v.setOnDragDone(null);
				
			dockable.setNode(v);
			if (i == 0) {
				dockable.setClosable(false);
			}
				
			dockable.setDragGroup(1);
			viewerLeaf.addDockables(dockable);
		}
		      
	}
    /**
     * Find all non-main QuPath windows and return them as a map
     * @return Map of window titles to their root panes
     */
    private Map<String, Parent> getDialogWindowsPanes() {
        Map<String, Parent> paneMap = new HashMap<>();
        
        Stage mainStage = QuPathGUI.getInstance().getStage();
        
        // Get a copy of the windows list to avoid concurrent modification
        List<Window> windows = new ArrayList<>(Window.getWindows());
        
        for (Window window : windows) {
            if (window instanceof Stage) {
                Stage stage = (Stage) window;
                // Skip the main QuPath window and any windows that are already closed/hidden
                if (stage != mainStage && stage.isShowing() && 
                    stage.getTitle() != null && !stage.getTitle().isEmpty()) {
                    
                    String windowTitle = stage.getTitle();
                    Parent pane = stage.getScene().getRoot();
                    paneMap.put(windowTitle, pane);
                    System.out.println("Found: " + windowTitle);
                    // Close original window
                    stage.close();
                }
            }
        }
        return paneMap;
    }

   	/**
	 * First step, convert the split layout
	 */
	private void bentoCapture() {
		Map<String, Parent> paneMap = getDialogWindowsPanes();
		
		for (Map.Entry<String, Parent> entry : paneMap.entrySet()) {
			String title = entry.getKey();
			Parent pane = entry.getValue();

			// Check pane width and decide where to dock
			double paneWidth = pane.getBoundsInLocal().getWidth();
			logger.debug("Processing {} - width={}", title, paneWidth);
			
			Dockable dockable = builder.dockable();
			dockable.setTitle(title);
			dockable.setNode(pane);
			
			if (paneWidth < 400) {
				dockable.setDragGroup(0);
				dockable.setClosable(true);
				analysisLeaf.addDockables(dockable);
			} else {
				dockable.setDragGroup(1);
				dockable.setClosable(true);
				viewerLeaf.addDockables(dockable);
			}
		}
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
