package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TabPaneSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves the tabs of QuPath's analysis TabPane (Project, Image, Annotations, ...) into the BentoFX analysis pane
 * and gives them back when BentoFX is switched off.
 * <p>
 * Tabs that scripts add to that TabPane while BentoFX is on are followed too: QuPath's TabPane is hidden then, so
 * a tab added there appears in the analysis pane, one removed from there disappears, and its title and tooltip
 * follow. Such a tab stays in the TabPane, so that scripts that look for their earlier tab there before adding a
 * new one (by id, say) keep working.
 */
final class AnalysisTabs {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisTabs.class);

    private final DockBuilding builder;
    private final DockContainerLeaf analysisLeaf;
    private final TabPane tabPane;

    /** QuPath's tabs as they were before BentoFX took their contents, in order, and the selected one. */
    private List<Tab> originalTabs = List.of();
    private Tab originalSelectedTab;

    /** The tabs that scripts added to the TabPane, and the BentoFX tabs showing them. */
    private final Map<Tab, ScriptTab> scriptTabs = new IdentityHashMap<>();

    /** Dockables that are being closed (DockableClosing seen, DockableRemoved not yet), to tell a close from a move. */
    private final Set<Dockable> closingScriptTabs = Collections.newSetFromMap(new IdentityHashMap<>());

    private ListChangeListener<Tab> tabListener;

    AnalysisTabs(DockBuilding builder, DockContainerLeaf analysisLeaf, TabPane tabPane) {
        this.builder = builder;
        this.analysisLeaf = analysisLeaf;
        this.tabPane = tabPane;
    }

    TabPane tabPane() {
        return tabPane;
    }

    /** Put QuPath's analysis tabs into the BentoFX analysis pane and start following the tabs that scripts add. */
    void dock() {
        // QuPath's own TabPane is not shown while BentoFX is on, and must not keep hold of tab contents: a skin lays
        // out the content of every tab it holds, which takes it away from the pane showing it. restore() gives
        // it a skin again.
        tabPane.setSkin(null);
        tabPane.getTabs().stream()
                .filter(tab -> tab.getContent() != null)
                .forEach(tab -> {
                    Dockable dockable = newDockable(tab);
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
        // has undocked, so take the docked tabs out of it. restore() puts them back.
        originalTabs = new ArrayList<>(tabPane.getTabs());
        originalSelectedTab = tabPane.getSelectionModel().getSelectedItem();
        tabPane.getTabs().removeAll(
                originalTabs.stream().filter(tab -> tab.getContent() != null && tab.getId() == null).toList());

        // From now on, tabs that scripts add to that TabPane are shown here
        tabListener = this::onTabsChanged;
        tabPane.getTabs().addListener(tabListener);
    }

    /**
     * Give QuPath's TabPane its tabs (and its skin) back, in their original order. Their content nodes were moved
     * into BentoFX, and setting the content again re-attaches it to the TabPane's own content region. Tabs that
     * scripts added while BentoFX was on stay too.
     */
    void restore() {
        if (tabListener != null) {
            tabPane.getTabs().removeListener(tabListener);
            tabListener = null;
        }
        scriptTabs.values().forEach(ScriptTab::detach);
        scriptTabs.clear();
        tabPane.setSkin(new TabPaneSkin(tabPane));
        List<Tab> tabsToRestore = new ArrayList<>(originalTabs);
        for (Tab tab : tabPane.getTabs())
            if (!tabsToRestore.contains(tab))
                tabsToRestore.add(tab);
        for (Tab tab : tabsToRestore) {
            Node content = tab.getContent();
            if (content != null) {
                tab.setContent(null);
                tab.setContent(content);
            }
        }
        tabPane.getTabs().setAll(tabsToRestore);
        if (originalSelectedTab != null)
            tabPane.getSelectionModel().select(originalSelectedTab);
    }

    private Dockable newDockable(Tab tab) {
        Dockable dockable = builder.dockable();
        dockable.setTitle(tab.getText());
        dockable.setNode(tab.getContent());
        dockable.setTooltip(tab.getTooltip());
        dockable.setClosable(false);
        dockable.setDragGroupMask(DragGroups.ANALYSIS);
        return dockable;
    }

    /** A tab a script added to QuPath's analysis TabPane, and the BentoFX tab that shows it. */
    private static final class ScriptTab {
        private final Tab tab;
        private final Dockable dockable;
        private final ChangeListener<String> textListener;
        private final ChangeListener<Tooltip> tooltipListener;

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

    /**
     * Closing a script's tab with its close button does what closing a tab of a TabPane does: the tab's
     * {@code onCloseRequest} may veto it, the tab leaves QuPath's TabPane (so that it is not brought back when
     * BentoFX is switched off), and its {@code onClosed} runs. Register with {@code bento.events().addEventListener(...)}.
     */
    void onDockEvent(DockEvent event) {
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
            if (tab == null)
                return;
            tabPane.getTabs().remove(tab);   // our listener sees the tab go and forgets it
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

    private void onTabsChanged(ListChangeListener.Change<? extends Tab> change) {
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
        if (tabListener == null || scriptTabs.containsKey(tab))
            return;
        if (tab.getContent() == null) {
            // Its content comes later: show it then
            tab.contentProperty().addListener(new ChangeListener<Node>() {
                @Override
                public void changed(ObservableValue<? extends Node> obs, Node old, Node content) {
                    if (content == null)
                        return;
                    tab.contentProperty().removeListener(this);
                    if (tabListener != null && tabPane.getTabs().contains(tab))
                        showScriptTab(tab);
                }
            });
            return;
        }
        Dockable dockable = newDockable(tab);
        dockable.setClosable(true);           // the built-in tabs cannot be closed, a script's can
        analysisLeaf.addDockable(dockable);   // straight into the leaf: looking for a target would reveal a hidden analysis pane
        scriptTabs.put(tab, new ScriptTab(tab, dockable));
        logger.debug("Showing the analysis tab '{}' that a script added", tab.getText());
    }
}
