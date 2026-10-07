package qupath.ext.bentofx;

import javafx.geometry.Side;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.dockable.DockableDragDropBehavior;
import software.coley.bentofx.layout.container.DockContainerLeaf;

/**
 * Drag-group model for the QuPath layout.
 * <p>
 * BentoFX's default rule is "a leaf accepts a dockable only if it already holds a dockable with the
 * <i>same</i> mask", which makes every group exclusive. Here the mask is a set of flags instead:
 * <ul>
 *   <li>{@link #ANALYSIS}: the built-in analysis tabs (Project, Image, ...) - stay in analysis leaves</li>
 *   <li>{@link #VIEWER}: viewers - stay in viewer leaves</li>
 *   <li>{@link #FLEX}: captured dialogs - may be dropped into any leaf</li>
 * </ul>
 * A leaf's "kind" is decided by the non-FLEX dockables it holds, so a captured panel sitting in the
 * analysis leaf does not make that leaf accept viewers.
 */
final class DragGroups {

    static final int ANALYSIS = 0b01;
    static final int VIEWER   = 0b10;
    static final int FLEX     = ANALYSIS | VIEWER;

    private DragGroups() {}

    static boolean canReceive(DockContainerLeaf target, Dockable dockable) {
        int mask = dockable.getDragGroupMask();
        if (mask == FLEX)
            return true;
        // Note: BentoFX already accepts anything into an EMPTY leaf before asking this question.
        return target.getDockables().stream()
                .anyMatch(d -> d.getDragGroupMask() != FLEX && d.getDragGroupMask() == mask);
    }

    /**
     * Bento exposes no setter for the drag-drop behaviour, only the protected factory
     * {@code newDragDropBehavior()}, so we subclass. That factory is called from a field initialiser
     * of the Bento constructor: the override must not use any instance state of the subclass.
     */
    static Bento newBento() {
        return new Bento() {
            @Override
            protected DockableDragDropBehavior newDragDropBehavior() {
                return new DockableDragDropBehavior() {
                    @Override
                    public boolean canReceiveDockable(DockContainerLeaf targetContainer, Side targetSide, Dockable dockable) {
                        return canReceive(targetContainer, dockable);
                    }
                };
            }
        };
    }
}
