package qupath.ext.bentofx;

import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import software.coley.bentofx.control.Header;
import software.coley.bentofx.control.HeaderPane;
import software.coley.bentofx.dockable.Dockable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A {@link Header} (tab) with two fixes that cannot be done from a stylesheet alone.
 * <ol>
 *   <li><b>Title colour.</b> The title is a bare {@link Text} node, and unlike the text inside a JavaFX
 *       control it has no style class, so no stylesheet rule can reach it (and a {@code Text} is black unless
 *       told otherwise). It gets the class {@value #TITLE_CLASS}, which {@code bento.css} colours like
 *       Modena's own tabs, so it follows the theme.</li>
 *   <li><b>Horizontal balance.</b> A horizontal tab's content is a grid of three cells: graphic, title and
 *       close button. A cell that is empty (no graphic; not closable) still takes up a column, and the
 *       grid's gap goes with it, so the title sat further from the left edge than from the right. Empty cells
 *       are taken out here, so the gaps left and right of the title are equal.</li>
 * </ol>
 * It works on BentoFX's own nodes as they are (the grid is found, not replaced), and tidies again whenever the
 * grid is rebuilt or a cell gains or loses content (BentoFX adds the close button after construction).
 */
final class ThemedHeader extends Header {

    /** Style class of the title; see bento.css. */
    static final String TITLE_CLASS = "header-title";

    private static final String WATCHED = "qupath.ext.bentofx.themedHeader.watched";

    ThemedHeader(Dockable dockable, HeaderPane parentPane) {
        super(dockable, parentPane);
        GridPane grid = findGrid();
        if (grid != null) {
            grid.getChildren().addListener((InvalidationListener) o -> tidy(grid));
            tidy(grid);
        }
    }

    /** Header's only child is a StackPane wrapper, whose first child is the grid. */
    private GridPane findGrid() {
        if (!getChildren().isEmpty() && getChildren().get(0) instanceof Pane wrapper)
            for (Node node : wrapper.getChildren())
                if (node instanceof GridPane grid)
                    return grid;
        return null;
    }

    private static void tidy(GridPane grid) {
        List<Node> nodes = new ArrayList<>(grid.getChildren());

        for (Node node : nodes) {
            Text title = findText(node);
            if (title != null && !title.getStyleClass().contains(TITLE_CLASS))
                title.getStyleClass().add(TITLE_CLASS);
        }

        // Only a horizontal tab (everything in row 0). A vertical one stacks its cells in a column.
        for (Node node : nodes) {
            Integer row = GridPane.getRowIndex(node);
            if (row != null && row != 0)
                return;
        }

        nodes.sort(Comparator.comparingInt(node -> {
            Integer column = GridPane.getColumnIndex(node);
            return column == null ? 0 : column;
        }));
        int column = 0;
        for (Node node : nodes) {
            boolean empty = false;
            if (node instanceof Pane pane && !(node instanceof GridPane)) {
                watch(pane, grid);
                empty = pane.getChildren().stream().noneMatch(Node::isManaged);
            }
            node.setManaged(!empty);
            if (!empty)
                GridPane.setColumnIndex(node, column++);
        }
    }

    /** Tidy again when a cell gets or loses content, or its content starts or stops taking up room. */
    private static void watch(Pane pane, GridPane grid) {
        if (pane.getProperties().putIfAbsent(WATCHED, Boolean.TRUE) != null)
            return;
        InvalidationListener again = o -> tidy(grid);
        pane.getChildren().addListener((ListChangeListener<Node>) change -> {
            for (Node child : pane.getChildren())
                watchManaged(child, again);
            tidy(grid);
        });
        for (Node child : pane.getChildren())
            watchManaged(child, again);
    }

    private static void watchManaged(Node child, InvalidationListener again) {
        if (child.getProperties().putIfAbsent(WATCHED, Boolean.TRUE) == null)
            child.managedProperty().addListener(again);
    }

    private static Text findText(Node node) {
        if (node instanceof Text text)
            return text;
        if (node instanceof Group group)
            for (Node child : group.getChildren()) {
                Text text = findText(child);
                if (text != null)
                    return text;
            }
        return null;
    }
}
