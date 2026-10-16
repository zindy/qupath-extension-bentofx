package qupath.ext.bentofx;

import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.List;

/**
 * Safe ways to put a container into a BentoFX branch at a given place.
 * <p>
 * {@code DockContainerBranch.addContainer(index, container)} must not be used: it appends to the branch's list of
 * children but inserts the visual item at {@code index}, so the two lists disagree afterwards, and everything that
 * works from either of them (sizing, removing, replacing a pane) then acts on the wrong container. The result is
 * empty bands, panes of the wrong size and a squeezed viewer.
 */
final class Branches {

    private Branches() {}

    /**
     * Put {@code container} at {@code index} among the children of {@code branch}. At the end that is a plain add;
     * anywhere else the container is paired with the child it displaces, in a branch of the same orientation that
     * takes that child's place, which is what dropping a tab on the edge of a pane does.
     */
    static void insert(DockBuilding builder, DockContainerBranch branch, int index, DockContainer container) {
        List<DockContainer> children = branch.getChildContainers();
        if (index >= children.size()) {
            branch.addContainer(container);
            return;
        }
        DockContainer displaced = children.get(Math.max(0, index));
        DockContainerBranch pair = builder.branch("pair-" + System.nanoTime());
        pair.setOrientation(branch.getOrientation());
        pair.addContainer(container);
        pair.addContainer(displaced);
        branch.replaceContainer(displaced, pair);
    }

    /**
     * Split a pane: a new branch takes its place and holds it together with {@code added}, on the given side of it.
     * The same as dropping a tab on that side of the pane.
     */
    static void splitBeside(DockBuilding builder, DockContainerLeaf pane, DockContainerLeaf added, javafx.geometry.Side side) {
        DockContainerBranch parent = pane.getParentContainer();
        if (parent == null)
            throw new IllegalStateException("The pane is not in a layout");
        DockContainerBranch split = builder.branch("split-" + System.nanoTime());
        if (side == javafx.geometry.Side.TOP || side == javafx.geometry.Side.BOTTOM)
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        if (side == javafx.geometry.Side.TOP || side == javafx.geometry.Side.LEFT) {
            split.addContainer(added);
            split.addContainer(pane);
        } else {
            split.addContainer(pane);
            split.addContainer(added);
        }
        parent.replaceContainer(pane, split);
    }
}