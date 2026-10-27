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

/**
 * A named customization of the viewer overview, typically provided by an extension.
 * <p>
 * Registered customizations (see {@link OverviewCustomizations}) appear in a
 * <i>Custom overview</i> submenu of the overview's context menu, with one entry per customization
 * plus a <i>None</i> entry. The submenu is hidden while no customizations are registered.
 * <p>
 * When the user selects a customization, {@link #createLayer(QuPathViewer)} is called for the viewer
 * that owns the overview, and the result is added to that viewer's overview layers
 * (replacing the layer of any previously-selected customization).
 * The layer can hold its own state, paint whatever it needs, and contribute its own
 * context menu items through {@link OverviewLayer#getContextMenuItems(OverviewLayer.Context)}.
 * Because each viewer gets its own layer, per-viewer initialisation belongs in {@link #createLayer(QuPathViewer)}.
 *
 * @see OverviewCustomizations#register(OverviewCustomization)
 * @since v0.8.0
 */
public interface OverviewCustomization {

    /**
     * Get the name shown in the <i>Custom overview</i> submenu.
     * @return a short, user-readable name
     */
    String getName();

    /**
     * Create a layer to apply to the overview of the specified viewer.
     * This is called on the JavaFX Application Thread each time the customization is selected.
     * @param viewer the viewer that owns the overview
     * @return the layer to add; if null, nothing is applied
     */
    OverviewLayer createLayer(QuPathViewer viewer);

    /**
     * Called when the layer created for a viewer is no longer in use, either because the user chose
     * something else or because the customization was unregistered. The default implementation does nothing.
     * @param viewer the viewer that owns the overview
     * @param layer the layer previously returned by {@link #createLayer(QuPathViewer)}
     */
    default void disposeLayer(QuPathViewer viewer, OverviewLayer layer) {}

}
