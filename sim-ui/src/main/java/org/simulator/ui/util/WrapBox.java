package org.simulator.ui.util;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal toolbar that behaves like an {@link HBox} (children with {@code HBox.hgrow=ALWAYS} absorb the
 * free space, e.g. {@link Ui#spacer()}) but wraps onto a new row instead of truncating the controls when the
 * window is too narrow.
 */
public class WrapBox extends Pane {

    private final double hgap;
    private final double vgap;

    public WrapBox(double hgap, double vgap, Node... children) {
        super(children);
        this.hgap = hgap;
        this.vgap = vgap;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private List<List<Node>> rows(double width) {
        List<List<Node>> rows = new ArrayList<>();
        List<Node> row = new ArrayList<>();
        double x = 0;
        for (Node n : getManagedChildren()) {
            double w = n.prefWidth(-1);
            if (!row.isEmpty() && x + hgap + w > width) {
                rows.add(row);
                row = new ArrayList<>();
                x = 0;
            }
            if (row.isEmpty() && grows(n) && w == 0) {
                continue; // a spacer starting a new row is useless
            }
            x += (row.isEmpty() ? 0 : hgap) + w;
            row.add(n);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private static boolean grows(Node n) {
        return HBox.getHgrow(n) == Priority.ALWAYS;
    }

    private static double rowHeight(List<Node> row) {
        double h = 0;
        for (Node n : row) {
            h = Math.max(h, n.prefHeight(-1));
        }
        return h;
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        double w = 0;
        int count = 0;
        for (Node n : getManagedChildren()) {
            w += n.prefWidth(-1);
            count++;
        }
        return in.getLeft() + in.getRight() + w + Math.max(0, count - 1) * hgap;
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets in = getInsets();
        double w = 0;
        for (Node n : getManagedChildren()) {
            w = Math.max(w, Math.min(n.prefWidth(-1), 240));
        }
        return in.getLeft() + in.getRight() + w;
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double avail = (width < 0 ? computePrefWidth(-1) : width) - in.getLeft() - in.getRight();
        List<List<Node>> rows = rows(avail);
        double h = 0;
        for (List<Node> r : rows) {
            h += rowHeight(r);
        }
        return in.getTop() + in.getBottom() + h + Math.max(0, rows.size() - 1) * vgap;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double avail = getWidth() - in.getLeft() - in.getRight();
        double y = in.getTop();
        for (Node n : getManagedChildren()) {
            n.resize(0, 0);
            n.relocate(in.getLeft(), y);
        }
        for (List<Node> row : rows(avail)) {
            double used = 0;
            int growers = 0;
            for (Node n : row) {
                used += n.prefWidth(-1);
                if (grows(n)) {
                    growers++;
                }
            }
            used += (row.size() - 1) * hgap;
            double extra = avail - used;
            double h = rowHeight(row);
            double x = in.getLeft();
            for (Node n : row) {
                double w = n.prefWidth(-1);
                if (growers > 0 && grows(n) && extra > 0) {
                    w += extra / growers;
                } else if (row.size() == 1 && w > avail) {
                    w = Math.max(n.minWidth(-1), avail);
                }
                double nh = Math.min(n.prefHeight(w), h);
                n.resizeRelocate(snapPositionX(x), snapPositionY(y + (h - nh) / 2), snapSizeX(w), snapSizeY(nh));
                x += w + hgap;
            }
            y += h + vgap;
        }
    }
}
