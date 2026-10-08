package qupath.ext.bentofx;

import javafx.scene.Node;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.DragDropImportListener;

/**
 * Restores QuPath's file/URL drop on viewer nodes without breaking BentoFX tab drags.
 * <p>
 * QuPath's {@code DragDropImportListener.handle} consumes every drop it receives: if the dragboard
 * holds no files/URL/string it still calls {@code setDropCompleted(false)} and {@code consume()}.
 * A dragged Bento header therefore got swallowed when dropped over a viewer, which is why the
 * handlers were cleared. Clearing them also removes the file drop, hence the "no entry" cursor.
 * <p>
 * Fix: forward everything to QuPath's listener except Bento header drags, which are left
 * unconsumed so they bubble up to the Bento leaf as before.
 * <p>
 * Bento drags are recognised without using any BentoFX API (the helper in DragUtils is not
 * present in all releases), using three independent signals.
 */
final class ViewerDragDrop {

    private static final String BENTO_PACKAGE = "software.coley.bentofx";
    private static final String BENTO_MIME = "application/x-bentofx-dockable";
    private static final String BENTO_STRING_PREFIX = "dnd-bento;";

    private ViewerDragDrop() {}

    static void install(QuPathGUI qupath, Node viewerView) {
        DragDropImportListener listener = qupath.getDefaultDragDropListener();
        viewerView.setOnDragOver(e -> forward(listener, e));
        viewerView.setOnDragDropped(e -> forward(listener, e));
        viewerView.setOnDragDone(e -> forward(listener, e));
    }

    /** Put QuPath's own listener back, exactly as ViewerManager installs it. */
    static void uninstall(QuPathGUI qupath, Node viewerView) {
        qupath.getDefaultDragDropListener().setupTarget(viewerView);
    }

    private static void forward(DragDropImportListener listener, DragEvent e) {
        if (isBentoDrag(e))
            return;                 // not consumed: Bento's own handlers further up deal with it
        listener.handle(e);
    }

    private static boolean isBentoDrag(DragEvent e) {
        // 1. Same-window drags: the gesture source is a Bento control (Header etc.)
        Object source = e.getGestureSource();
        if (source != null && source.getClass().getName().startsWith(BENTO_PACKAGE))
            return true;

        Dragboard db = e.getDragboard();
        if (db == null)
            return false;

        // 2. Newer builds: custom data format (gesture source is null across stages)
        DataFormat format = DataFormat.lookupMimeType(BENTO_MIME);
        if (format != null && db.hasContent(format))
            return true;

        // 3. Older builds that put the payload in a plain string
        return db.hasString() && db.getString() != null && db.getString().startsWith(BENTO_STRING_PREFIX);
    }
}
