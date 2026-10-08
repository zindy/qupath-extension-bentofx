package qupath.ext.bentofx;

import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Diagnostic: with DEBUG logging for {@code qupath.ext.bentofx.KeyTrace}, logs every KEY_PRESSED that goes to a
 * text editor (JavaFX text input or RichTextFX area) in the main scene, at the very start of dispatch (scene filter) and at the very end (scene handler),
 * together with whether it had been consumed by then. If a key still triggers an accelerator, the second line
 * shows consumed=false.
 */
final class KeyTrace {

    private static final Logger logger = LoggerFactory.getLogger(KeyTrace.class);
    private static final Set<Scene> installed = Collections.newSetFromMap(new WeakHashMap<>());

    private KeyTrace() {}

    static void install(Scene scene) {
        if (scene == null || !installed.add(scene))
            return;
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> log("start  ", scene, e));
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> log("end    ", scene, e));
    }

    static void log(String where, Scene scene, KeyEvent e) {
        if (!logger.isDebugEnabled() || !CapturedWindows.isTextEditor(scene.getFocusOwner()))
            return;
        logger.debug("KEY_PRESSED {} code={} text='{}' target={} consumed={} ctrl={} alt={} meta={} shift={}",
                where, e.getCode(), e.getText(), e.getTarget().getClass().getSimpleName(), e.isConsumed(),
                e.isControlDown(), e.isAltDown(), e.isMetaDown(), e.isShiftDown());
    }
}
