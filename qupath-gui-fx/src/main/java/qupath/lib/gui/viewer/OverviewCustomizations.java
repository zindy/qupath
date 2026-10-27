/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2026 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.viewer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Registry of {@link OverviewCustomization}s that users can choose from in the overview's context menu.
 * <p>
 * An extension would typically register its customization(s) in
 * {@link qupath.lib.gui.extensions.QuPathExtension#installExtension(qupath.lib.gui.QuPathGUI)}:
 * <pre>
 * OverviewCustomizations.register(new MyCustomization());
 * </pre>
 * The list should only be modified on the JavaFX Application Thread.
 *
 * @since v0.8.0
 */
public final class OverviewCustomizations {

    private static final ObservableList<OverviewCustomization> customizations = FXCollections.observableArrayList();

    private OverviewCustomizations() {}

    /**
     * Get the (modifiable) list of registered customizations.
     * @return the registered customizations
     */
    public static ObservableList<OverviewCustomization> getCustomizations() {
        return customizations;
    }

    /**
     * Register a customization, if it is not already registered.
     * @param customization
     */
    public static void register(OverviewCustomization customization) {
        if (customization != null && !customizations.contains(customization))
            customizations.add(customization);
    }

    /**
     * Unregister a customization. Any viewer currently using it reverts to the standard overview.
     * @param customization
     */
    public static void unregister(OverviewCustomization customization) {
        customizations.remove(customization);
    }

}
