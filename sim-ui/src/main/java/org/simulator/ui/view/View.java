package org.simulator.ui.view;

import javafx.scene.Node;

/** A navigable screen of the main window. */
public interface View {

    String title();

    Node node();

    /** Called every time the view becomes visible. */
    default void onShow() {
    }

    /** Called on application shutdown. */
    default void dispose() {
    }
}
