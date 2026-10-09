package qupath.ext.bentofx;

import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import software.coley.bentofx.Bento;
import software.coley.bentofx.control.ContentWrapper;
import software.coley.bentofx.control.canvas.PixelCanvas;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.path.DockablePath;
import software.coley.bentofx.util.BentoUtils;
import software.coley.bentofx.util.DragUtils;

/**
 * A {@link ContentWrapper} whose drop hint shows the size a docked panel will really get.
 * <p>
 * BentoFX always draws the hint for a drop on the left, right, top or bottom as half of the target. But
 * here a panel keeps its own pixel size once docked (see {@link PaneSizing}), so for a narrow panel the
 * hint promised far more than it got. The hint is now as large as the panel will be, but never larger than
 * half of the target (a panel wider than that is shrunk to fit anyway). Viewers still show half.
 * <p>
 * Only the drag-over handler is replaced; dropping is BentoFX's own. The size comes in through the leaf's
 * {@link javafx.scene.Node#getProperties() properties} under {@link #DROP_EXTENT}, because
 * {@link ContentWrapper}'s constructor calls {@link #setupDragDrop} before this class's fields exist.
 */
final class SizedHintContentWrapper extends ContentWrapper {

    /** Key in the leaf's properties map holding a {@link DropExtent}. */
    static final String DROP_EXTENT = "qupath.ext.bentofx.dropExtent";

    /** Size in pixels a dockable will have along an axis once docked, or -1 if it shares the space. */
    @FunctionalInterface
    interface DropExtent {
        double of(Dockable dockable, boolean horizontal);
    }

    private static final int FILL = 0x44FF0000;
    private static final int BORDER = 0x88FF0000;
    private static final int BORDER_WIDTH = 2;

    SizedHintContentWrapper(DockContainerLeaf container) {
        super(container);
    }

    @Override
    protected void setupDragDrop(DockContainerLeaf container) {
        super.setupDragDrop(container);     // dropping, and clearing the hint when the drag leaves
        Bento bento = container.getBento();
        // Same as ContentWrapper's drag-over, except for how the hint is drawn
        setOnDragOver(e -> {
            Dragboard dragboard = e.getDragboard();
            String dockableIdentifier = DragUtils.extractIdentifier(dragboard);
            if (dockableIdentifier != null) {
                DockablePath dragSourcePath = bento.search().dockable(dockableIdentifier);
                if (dragSourcePath != null) {
                    Dockable dragged = dragSourcePath.dockable();
                    Side side = container.isCanSplit() ? BentoUtils.computeClosestSide(this, e.getX(), e.getY()) : null;
                    if (container.canReceiveDockable(dragged, side))
                        drawHint(container, side, dragged);
                    else
                        container.clearCanvas();
                }
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
    }

    @SuppressWarnings("unchecked")
    private void drawHint(DockContainerLeaf leaf, Side side, Dockable dragged) {
        // Offset when this region is not a direct child of the leaf
        double ox = 0, oy = 0;
        for (Parent p = getParent(); p != null && p != leaf; p = p.getParent()) {
            ox += p.getLayoutX();
            oy += p.getLayoutY();
        }
        double x = ox + getLayoutX();
        double y = oy + getLayoutY();
        double w = getWidth();
        double h = getHeight();

        // Half by default; a panel only takes its own size, if that is less
        double hintW = w / 2;
        double hintH = h / 2;
        if (side != null && leaf.getProperties().get(DROP_EXTENT) instanceof DropExtent sizer) {
            boolean horizontal = side == Side.LEFT || side == Side.RIGHT;
            double px = sizer.of(dragged, horizontal);
            if (px > 0) {
                if (horizontal)
                    hintW = Math.min(px, w / 2);
                else
                    hintH = Math.min(px, h / 2);
            }
        }

        PixelCanvas canvas = leaf.getCanvas();
        canvas.clear();
        if (side == null)
            canvas.fillBorderedRect(x, y, w, h, BORDER_WIDTH, FILL, BORDER);
        else switch (side) {
            case TOP -> canvas.fillBorderedRect(x, y, w, hintH, BORDER_WIDTH, FILL, BORDER);
            case BOTTOM -> canvas.fillBorderedRect(x, y + h - hintH, w, hintH, BORDER_WIDTH, FILL, BORDER);
            case LEFT -> canvas.fillBorderedRect(x, y, hintW, h, BORDER_WIDTH, FILL, BORDER);
            case RIGHT -> canvas.fillBorderedRect(x + w - hintW, y, hintW, h, BORDER_WIDTH, FILL, BORDER);
        }
        canvas.commit();
    }
}
