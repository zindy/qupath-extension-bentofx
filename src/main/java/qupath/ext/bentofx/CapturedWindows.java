package qupath.ext.bentofx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.event.DockEvent;

import java.util.ArrayList;
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

    /**
     * Once docked, a dialog lives in the main scene, whose accelerators (e.g. "-" = Zoom out, "m"/"o" = tools)
     * fire for any KEY_PRESSED that reaches the scene unconsumed. Text editors insert characters from KEY_TYPED
     * and do not always consume the KEY_PRESSED (typing "-" in the docked script editor zoomed the viewer), so a
     * docked panel swallows the KEY_PRESSED of any plain key typed into a text editor.
     * <p>
     * Deliberately a bubbling-phase handler, not a capture-phase filter: the editor's own filters and handlers have
     * already run by then (QuPath's code area refreshes its auto-complete popup from KEY_PRESSED), and only the
     * accelerators above the panel are skipped.
     * Ctrl/Alt/Meta combinations and function, navigation, modifier and media keys, Escape, Tab and Enter are
     * left alone. Decided by key code, not by KeyEvent.getText().
     */
    private static final EventHandler<KeyEvent> KEY_GUARD = e -> {
        if (e.isConsumed() || e.getEventType() != KeyEvent.KEY_PRESSED)
            return;
        if (!isTextEditor(e.getTarget())) {
            logger.trace("key guard: target {} is not a text editor", e.getTarget().getClass().getSimpleName());
            return;
        }
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown())
            return;
        KeyCode code = e.getCode();
        if (code.isFunctionKey() || code.isNavigationKey() || code.isArrowKey() || code.isModifierKey()
                || code.isMediaKey() || code == KeyCode.ESCAPE || code == KeyCode.TAB || code == KeyCode.ENTER)
            return;
        logger.debug("key guard consumed {} (text='{}')", code, e.getText());
        e.consume();
    };

    /**
     * A JavaFX text input, or a RichTextFX area (QuPath's "Rich script editor" is a RichTextFX CodeArea, which is
     * not a TextInputControl). RichTextFX is recognised by class name so that this extension needs no dependency on it.
     */
    static boolean isTextEditor(Object target) {
        if (target instanceof TextInputControl)
            return true;
        if (target == null)
            return false;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("org.fxmisc.richtext."))
                return true;
        }
        return false;
    }

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
        Node dockedNode = fitted.node();
        dockedNode.addEventHandler(KeyEvent.KEY_PRESSED, KEY_GUARD);
        KeyTrace.install(QuPathGUI.getInstance().getStage().getScene());
        e.showListener = (obs, was, now) -> {
            if (!now)
                return;
            Platform.runLater(() -> {
                if (entries.get(dockable) != e)
                    return;
                reveal.accept(dockable);
                // QuPath may have shown this window only to put a dialog on top of it: the script editor does
                // that for its "Save before closing?" prompt when QuPath quits. Hiding the owner would close the
                // dialog unanswered (= cancel the quit), so the window is only hidden once nothing it owns is open.
                PauseTransition pause = new PauseTransition(Duration.millis(250));
                pause.setOnFinished(ev -> hideWhenIdle(e));
                pause.play();
            });
        };
        stage.showingProperty().addListener(e.showListener);
        entries.put(dockable, e);
    }

    private void hideWhenIdle(Entry e) {
        if (entries.get(e.dockable) != e || !e.stage.isShowing())
            return;
        if (!hasOwnedWindowShowing(e.stage)) {
            e.stage.hide();
            return;
        }
        ListChangeListener<Window> listener = new ListChangeListener<>() {
            @Override
            public void onChanged(Change<? extends Window> change) {
                if (entries.get(e.dockable) != e || !e.stage.isShowing()) {
                    Window.getWindows().removeListener(this);
                } else if (!hasOwnedWindowShowing(e.stage)) {
                    Window.getWindows().removeListener(this);
                    // Not directly: this runs inside the window list's own change notification (the owned dialog
                    // is being hidden), and hiding another window now modifies that list re-entrantly.
                    Platform.runLater(() -> {
                        if (entries.get(e.dockable) == e && e.stage.isShowing())
                            e.stage.hide();
                    });
                }
            }
        };
        Window.getWindows().addListener(listener);
    }

    private static boolean hasOwnedWindowShowing(Window owner) {
        for (Window w : Window.getWindows()) {
            if (w != owner && w.isShowing()) {
                for (Window o = ownerOf(w); o != null; o = ownerOf(o)) {
                    if (o == owner)
                        return true;
                }
            }
        }
        return false;
    }

    private static Window ownerOf(Window w) {
        if (w instanceof Stage s)
            return s.getOwner();
        if (w instanceof PopupWindow p)
            return p.getOwnerWindow();
        return null;
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

    /**
     * Give every docked dialog back to its own window and show it again (used when BentoFX is deactivated).
     */
    void releaseAll() {
        for (Dockable d : new ArrayList<>(entries.keySet())) {
            Entry e = entries.get(d);
            restore(d);
            if (e != null)
                e.stage.show();
        }
        closing.clear();
    }

    private void restore(Dockable dockable) {
        Entry e = entries.remove(dockable);
        if (e == null)
            return;
        e.stage.showingProperty().removeListener(e.showListener);
        e.fitted.node().removeEventHandler(KeyEvent.KEY_PRESSED, KEY_GUARD);
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
