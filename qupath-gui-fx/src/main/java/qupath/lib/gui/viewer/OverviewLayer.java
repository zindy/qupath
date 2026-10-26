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

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.MenuItem;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.List;

/**
 * A layer that can be painted onto a viewer's overview, between the thumbnail and the
 * outline of the visible region.
 * <p>
 * Layers are added to (or removed from) the list returned by
 * {@link QuPathViewerPlus#getOverviewLayers()}. Everything here is called on the JavaFX
 * Application Thread; expensive computation should be done elsewhere and only the
 * finished result painted.
 * <p>
 * A layer instance should normally be used with a single viewer, because it is
 * free to hold viewer-specific state and menu items.
 *
 * @since v0.8.0
 */
public interface OverviewLayer {

	/**
	 * Paint the layer.
	 * The graphics context is already in overview pixel coordinates (0,0 is the top left,
	 * {@link Context#getWidth()} x {@link Context#getHeight()} is the extent of the thumbnail).
	 * Its state is saved before, and restored after, this call, so the layer may freely change it.
	 * @param g the graphics context of the overview canvas
	 * @param context information about the overview being painted
	 */
	void paint(GraphicsContext g, Context context);

	/**
	 * Get menu items to add to the overview's context menu.
	 * This is called each time the menu is requested, so check states can be updated here.
	 * @param context information about the overview
	 * @return menu items to show (never null, empty by default)
	 */
	default List<? extends MenuItem> getContextMenuItems(Context context) {
		return List.of();
	}

	/**
	 * Information about, and limited control over, the overview that is painting a layer.
	 */
	interface Context {

		/**
		 * Get the viewer that owns the overview.
		 * @return the viewer, or null if the viewer has been closed
		 */
		QuPathViewer getViewer();

		/**
		 * Get the current width of the overview thumbnail, in pixels.
		 * @return
		 */
		double getWidth();

		/**
		 * Get the current height of the overview thumbnail, in pixels.
		 * @return
		 */
		double getHeight();

		/**
		 * Get a transform from full-resolution image pixel coordinates to overview coordinates.
		 * @return a copy of the transform, or null if no image is available
		 */
		AffineTransform getImageToOverviewTransform();

		/**
		 * Get the currently visible region of the viewer, already transformed to overview coordinates.
		 * @return the shape, or null if it is not available
		 */
		Shape getVisibleShape();

		/**
		 * Get the maximum width or height of the overview, in pixels.
		 * @return
		 * @see #setMaxLength(int)
		 */
		int getMaxLength();

		/**
		 * Set the maximum width or height of the overview, in pixels.
		 * @param maxLength
		 */
		void setMaxLength(int maxLength);

		/**
		 * Request that the overview is repainted.
		 * This is safe to call from any thread.
		 */
		void requestRepaint();
	}

}
