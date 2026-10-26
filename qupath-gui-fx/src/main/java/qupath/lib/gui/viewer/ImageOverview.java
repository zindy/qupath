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

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A small preview panel to be associated with a viewer, which shows the currently-visible
 * region &amp; can be clicked on to navigate to other regions.
 * <p>
 * Additional content can be painted using {@link OverviewLayer}s, and the size can be changed
 * using the context menu or by Alt + scrolling.
 */
class ImageOverview implements QuPathViewerListener, OverviewLayer.Context {

	private static final Logger logger = LoggerFactory.getLogger(ImageOverview.class);

	private QuPathViewer viewer;
	
	private final Canvas canvas = new Canvas();
		
	private boolean repaintRequested = false;

	/*
	 * There are two reasons for the following variables...
	 * 	1 - The viewer's thumbnail is not guaranteed to remain constant, even if the image server does -
	 * 		this is because it can be modified by color transforms, brightness/contrast settings
	 * 	2 - Simply drawing the image directly & rescaling on-the-fly produces a very low-quality image -
	 * 		getScaledInstance() provides something smoother
	 * Therefore to assist repainting, we need to have:
	 * 	- a nicely-rescaled thumbnail to draw
	 * 	- the original thumbnail image used to produce the scaled version
	 * The latter means we can compare it with the viewer's thumbnail & we know if it needs to be updated
	 */
	private BufferedImage imgLastThumbnail; // The last thumbnail the viewer gave us
	private WritableImage imgPreview;       // The (probably downsampled) preview version

	private static final int MIN_LENGTH = 100;
	private static final int SMALL_LENGTH = 150;
	private static final int MEDIUM_LENGTH = 300;
	private static final int LARGE_LENGTH = 450;
	private static final int MAX_LENGTH = 600;
	private static final int RESIZE_INCREMENT = 50;

	private int maxLength = SMALL_LENGTH; // Maximum component width or height - used for thumbnail scaling

	private final ObservableList<OverviewLayer> layers = FXCollections.observableArrayList();
	private final Set<OverviewLayer> failedLayers = Collections.newSetFromMap(new IdentityHashMap<>());
	private final ContextMenu contextMenu = new ContextMenu();

	private Shape shapeVisible = null; // The visible shape (transformed already)
	private AffineTransform transform;
	
	private static final Color color = Color.rgb(200, 0, 0, .8);
	private static final Color colorBorder = Color.rgb(64, 64, 64);

	protected void mouseViewerToLocation(double x, double y) {
		ImageServer<BufferedImage> server = viewer.getServer();
		if (server == null)
			return;
		double cx = x / getWidth() * server.getWidth();
		double cy = y / getHeight() * server.getHeight();
		viewer.setCenterPixelLocation(cx, cy);
	}

	public ImageOverview(final QuPathViewer viewer) {
		this.viewer = viewer;
		setImage(viewer.getRGBThumbnail());
		
		canvas.setOnMouseClicked(e -> {
			if (e.getButton() == MouseButton.PRIMARY)
				mouseViewerToLocation(e.getX(), e.getY());
		});

		canvas.setOnMousePressed(e -> {
			// Consume right-click to prevent the viewer's own context menu from showing
			if (e.isSecondaryButtonDown())
				e.consume();
		});

		canvas.setOnMouseDragged(e -> {
			if (e.isPrimaryButtonDown()) {
				mouseViewerToLocation(e.getX(), e.getY());
				e.consume();
			}
		});

		canvas.setOnScroll(e -> {
			if (e.isAltDown()) {
				// Alt + mousewheel to resize the overview
				double delta = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
				if (delta != 0) {
					int increment = delta > 0 ? RESIZE_INCREMENT : -RESIZE_INCREMENT;
					setMaxLength(Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, maxLength + increment)));
				}
				e.consume();
			}
		});

		canvas.setOnContextMenuRequested(e -> {
			showContextMenu(e.getScreenX(), e.getScreenY());
			e.consume();
		});

		layers.addListener((javafx.collections.ListChangeListener.Change<? extends OverviewLayer> c) -> {
			while (c.next()) {
				if (c.wasRemoved())
					failedLayers.removeAll(c.getRemoved());
			}
			repaint();
		});

		viewer.rgbThumbnailProperty().subscribe(this::repaint);
		viewer.addViewerListener(this);
	}

	/**
	 * Get the (modifiable) list of layers painted on top of the thumbnail.
	 * Should only be modified on the JavaFX Application Thread.
	 */
	ObservableList<OverviewLayer> getLayers() {
		return layers;
	}

	private void showContextMenu(double screenX, double screenY) {
		var items = new ArrayList<MenuItem>();
		for (var layer : layers) {
			try {
				var layerItems = layer.getContextMenuItems(this);
				if (layerItems != null && !layerItems.isEmpty()) {
					if (!items.isEmpty())
						items.add(new SeparatorMenuItem());
					items.addAll(layerItems);
				}
			} catch (Exception ex) {
				logger.warn("Error requesting overview menu items from {}: {}", layer, ex.getMessage(), ex);
			}
		}
		if (!items.isEmpty())
			items.add(new SeparatorMenuItem());
		items.addAll(createSizeMenuItems());
		contextMenu.getItems().setAll(items);
		contextMenu.show(canvas, screenX, screenY);
	}

	private java.util.List<MenuItem> createSizeMenuItems() {
		var group = new ToggleGroup();
		var items = new ArrayList<MenuItem>();
		items.add(createSizeItem("Small overview", SMALL_LENGTH, group));
		items.add(createSizeItem("Medium overview", MEDIUM_LENGTH, group));
		items.add(createSizeItem("Large overview", LARGE_LENGTH, group));
		return items;
	}

	private MenuItem createSizeItem(String text, int length, ToggleGroup group) {
		var item = new RadioMenuItem(text);
		item.setToggleGroup(group);
		item.setSelected(maxLength == length);
		item.setOnAction(e -> setMaxLength(length));
		return item;
	}

	@Override
	public QuPathViewer getViewer() {
		return viewer;
	}

	@Override
	public int getMaxLength() {
		return maxLength;
	}

	@Override
	public void setMaxLength(int length) {
		if (length <= 0 || length == maxLength)
			return;
		maxLength = length;
		// Force the thumbnail to be rescaled
		imgLastThumbnail = null;
		if (viewer != null && viewer.hasServer()) {
			setImage(viewer.getRGBThumbnail());
			// The scaling has changed, so the visible shape needs to be transformed again
			var shape = viewer.getDisplayedRegionShape();
			if (shape != null && transform != null)
				shapeVisible = transform.createTransformedShape(shape);
		}
		repaint();
	}

	@Override
	public AffineTransform getImageToOverviewTransform() {
		return transform == null ? null : new AffineTransform(transform);
	}

	@Override
	public Shape getVisibleShape() {
		return shapeVisible;
	}

	@Override
	public void requestRepaint() {
		repaint();
	}

	private void updateTransform() {
		if (imgPreview != null && viewer != null && viewer.getServer() != null) {
			double scale = imgPreview.getWidth() / viewer.getServer().getWidth();
			if (scale > 0) {
				// Reuse an existing transform if we have one
				if (transform == null)
					transform = AffineTransform.getScaleInstance(scale, scale);
				else
					transform.setToScale(scale, scale);
			}
			else
				transform = null;
		}
	}

	void paintCanvas() {
		
		GraphicsContext g = canvas.getGraphicsContext2D();
		double w = getWidth();
		double h = getHeight();
		g.clearRect(0, 0, w, h);
		
		if (viewer == null || !viewer.hasServer()) {
			return;
		}
		
		// Ensure the image has been set
		setImage(viewer.getRGBThumbnail());

		g.drawImage(imgPreview, 0, 0);

		// Draw any additional layers (between the thumbnail and the visible region)
		for (var layer : layers.toArray(OverviewLayer[]::new)) {
			g.save();
			try {
				layer.paint(g, this);
			} catch (Exception e) {
				// Only warn once per layer, to avoid flooding the log
				if (failedLayers.add(layer))
					logger.warn("Error painting overview layer {}: {}", layer, e.getMessage(), e);
				else
					logger.debug("Error painting overview layer {}: {}", layer, e.getMessage(), e);
			} finally {
				g.restore();
			}
		}

		// Draw the currently-visible region, if we have a viewer and it isn't 'zoom to fit' (in which case everything is visible)
		if (shapeVisible != null) {
			g.setStroke(color);
			g.setLineWidth(1);
			
			// TODO: Try to avoid PathIterator, and do something more JavaFX-like
			PathIterator iterator = shapeVisible.getPathIterator(null);
			double[] coords = new double[6];
			g.beginPath();
			while (!iterator.isDone()) {
				int type = iterator.currentSegment(coords);
				if (type == PathIterator.SEG_MOVETO)
					g.moveTo(coords[0], coords[1]);
				else if (type == PathIterator.SEG_LINETO)
					g.lineTo(coords[0], coords[1]);
				else if (type == PathIterator.SEG_CLOSE) {
					g.closePath();
					g.stroke();
				}
				else
					logger.debug("Unknown PathIterator type: {}", type);
				iterator.next();
			}
		}
		
		// Draw border
		g.setLineWidth(2);
		g.setStroke(colorBorder);
		g.strokeRect(0, 0, w, h);
		
		repaintRequested = false;
	}

	
	public boolean isVisible() {
		return canvas.isVisible();
	}
	
	public void setVisible(final boolean visible) {
		canvas.setVisible(visible);
	}
	
	@Override
	public double getWidth() {
		return canvas.getWidth();
	}

	@Override
	public double getHeight() {
		return canvas.getHeight();
	}


	private void setImage(BufferedImage img) {
		if (img == imgLastThumbnail)
			return;
		if (img == null) {
			imgLastThumbnail = null;
		} else {
			double scale = (double)maxLength / Math.max(img.getWidth(), img.getHeight());
			int preferredWidth = (int)Math.round(img.getWidth() * scale);
			int preferredHeight = (int)Math.round(img.getHeight() * scale);
			
			imgPreview = GuiTools.getScaledRGBInstance(img, preferredWidth, preferredHeight);

			canvas.setWidth(imgPreview.getWidth());
			canvas.setHeight(imgPreview.getHeight());

			imgLastThumbnail = img;
		}
		updateTransform();
	}


	@Override
	public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {
		setImage(viewer.getRGBThumbnail());
		repaint();
	}

	@Override
	public void visibleRegionChanged(QuPathViewer viewer, Shape shape) {
		// Get the shape & apply a transform, if we have one
		if (shape != null) {
			if (transform == null)
				updateTransform();
			if (transform != null)
				shapeVisible = transform.createTransformedShape(shape);
			else
				shapeVisible = shape;
		} else
			shapeVisible = null;
		// Repaint
		repaint();
	}


	
	void repaint() {
		if (Platform.isFxApplicationThread()) {
			repaintRequested = true;
			paintCanvas();
			return;
		}
		if (repaintRequested)
			return;
		logger.trace("Overview repaint requested!");
		repaintRequested = true;
		Platform.runLater(this::repaint);
	}
	

	public Node getNode() {
		return canvas;
	}
	


	@Override
	public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {}

	@Override
	public void viewerClosed(QuPathViewer viewer) {
		this.viewer = null;
	}

}