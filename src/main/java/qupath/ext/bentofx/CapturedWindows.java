package qupath.ext.bentofx;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Keeps captured dialogs usable after they were docked.
 * <p>
 * QuPath creates many dialogs once and re-shows the same Stage (e.g. LogViewerCommand.show() calls
 * {@code dialog.show()} on a kept Stage). Capturing swaps that Stage's scene root for an empty Group, so
 * closing the docked tab used to leave a Stage that re-opens blank. This class keeps the Stage and the
 * original root for every captured dockable and:
 * <ul>
 *   <li><b>Tab closed</b> (DockableClosing followed by DockableRemoved): undo the fitting, put the original
 *       root back in the Stage's scene and leave the Stage hidden. The next "Show log" etc. opens a normal
 *       dialog again.</li>
 *   <li><b>Dialog re-opened while docked</b> (e.g. View &gt; Show log): the Stage is kept at opacity 0 while
 *       docked, so a re-show is invisible; it is hidden again at once and the docked tab is brought forward.</li>
 * </ul>
 * Moving a tab between leaves also fires DockableRemoved, but never DockableClosing, so only real closes
 * restore.
 */
final class CapturedWindows {

    private static final Logger logger = LoggerFactory.getLogger(CapturedWindows.class);

    private static final class Entry {
        final Dockable dockable;
        final Stage stage;
        final Parent originalRoot;
        final PanelFitter.Fitted fitted;
        final double originalOpacity;
        ChangeListener<Boolean> showListener;

        Entry(Dockable dockable, Stage stage, Parent originalRoot, PanelFitter.Fitted fitted) {
            this.dockable = dockable;
            this.stage = stage;
            this.originalRoot = originalRoot;
            this.fitted = fitted;
            this.originalOpacity = stage.getOpacity();
        }
    }

    private final Map<Dockable, Entry> entries = new IdentityHashMap<>();
    private final Set<Dockable> closing = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<Dockable> reveal;

    /** @param reveal brings a docked tab to the front (selects it, shows its pane, focuses the window) */
    CapturedWindows(Consumer<Dockable> reveal) {
        this.reveal = reveal;
    }

    /** True for a Stage we emptied; "Capture floating windows" must not capture it again. */
    boolean isCapturedStage(Window window) {
        return entries.values().stream().anyMatch(e -> e.stage == window);
    }

    void register(Dockable dockable, Stage stage, Parent originalRoot, PanelFitter.Fitted fitted) {
        Entry e = new Entry(dockable, stage, originalRoot, fitted);
        stage.setOpacity(0);   // a re-show while docked must not flash an empty dialog
        e.showListener = (obs, was, now) -> {
            if (now) {
                Platform.runLater(() -> {
                    if (entries.get(dockable) == e) {
                        stage.hide();
                        reveal.accept(dockable);
                    }
                });
            }
        };
        stage.showingProperty().addListener(e.showListener);
        entries.put(dockable, e);
    }

    /** Register with {@code bento.events().addEventListener(...)}. */
    void onDockEvent(DockEvent event) {
        if (event instanceof DockEvent.DockableClosing closingEvent) {
            Dockable d = closingEvent.dockable();
            if (!closingEvent.isCancelled() && entries.containsKey(d)) {
                closing.add(d);
                // closeDockable() calls removeDockable() synchronously right after this event.
                // If another listener cancels the close, no removal follows: drop the mark afterwards.
                Platform.runLater(() -> closing.remove(d));
            }
        } else if (event instanceof DockEvent.DockableRemoved removed) {
            if (closing.remove(removed.dockable()))
                restore(removed.dockable());
        }
    }

    private void restore(Dockable dockable) {
        Entry e = entries.remove(dockable);
        if (e == null)
            return;
        e.stage.showingProperty().removeListener(e.showListener);
        try {
            e.fitted.undo().run();                       // re-create the original node hierarchy
            PanelFitter.detachFromParent(e.originalRoot); // release it from the Bento leaf if still held
            e.stage.getScene().setRoot(e.originalRoot);
            logger.debug("Restored '{}' to its original window", dockable.getTitle());
        } catch (RuntimeException ex) {
            logger.error("Could not restore window '{}'", dockable.getTitle(), ex);
        }
        e.stage.setOpacity(e.originalOpacity);
    }
}
