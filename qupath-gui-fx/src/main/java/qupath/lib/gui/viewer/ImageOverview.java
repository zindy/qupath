/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
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
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * A small preview panel to be associated with a viewer, which shows the currently-visible
 * region &amp; can be clicked on to navigate to other regions.
 * Enhanced with tracker functionality using bitwise alpha channel operations.
 * 
 * @author Pete Bankhead
 *
 */
class ImageOverview implements QuPathViewerListener {

	private static final Logger logger = LoggerFactory.getLogger(ImageOverview.class);

	private QuPathViewer viewer;
	
	private Canvas canvas = new Canvas();
		
	private boolean repaintRequested = false;

	private BufferedImage imgLastThumbnail;
	private WritableImage imgPreview;

	private int preferredWidth = 150;

	private Shape shapeVisible = null;
	private AffineTransform transform;
	
	private static Color color = Color.rgb(200, 0, 0, .8);
	private static Color colorBorder = Color.rgb(64, 64, 64);
	
	/**
	 * Magnification layer definition
	 */
	private static class MagnificationLayer {
		final double minMag;
		final int bitIndex;  // Which bit in alpha channel (0-3)
		final int argbColor;  // Color for the range...
		
		MagnificationLayer(double minMag, int bitIndex, int argbColor) {
			this.minMag = minMag;
			this.bitIndex = bitIndex;
			this.argbColor = argbColor;
		}
	}
	
	// Define the 4 magnification layers
	private static final MagnificationLayer[] MAG_LAYERS = {
		new MagnificationLayer(12.0,  6, 0xFF71FF9E), 
		new MagnificationLayer(5.12,  5, 0xFFFFFF88), 
		new MagnificationLayer(2.8,   4, 0xFFFF7171),
		new MagnificationLayer(1.38,  3, 0xFFFFC071),
	};
	
	private boolean trackingEnabled = false;
	private boolean trackingVisible = false;
	
	// Single RGBA overlay image
	// RGB = base color (0,0,0 for brightfield image or 255,255,255 for fluorescent)
	// Alpha = 4-bit mask (each representing a different magnification layer)
	private WritableImage trackingOverlay = null;
	private WritableImage contouredOverlay = null;
	
	// Pre-computed initial alpha mask based on magnification layer bits
	private int initialMaskARGB = 0;
	
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
			if (e.getButton() == MouseButton.PRIMARY) {
				mouseViewerToLocation(e.getX(), e.getY());
			}
		});
		
		canvas.setOnMousePressed(e -> {
			// Consume right-click to prevent viewer's context menu from showing
			if (e.isSecondaryButtonDown()) {
				e.consume();
			}
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
				double delta = e.getDeltaY();
				int increment = delta > 0 ? 50 : -50;
				int newWidth = Math.max(50, Math.min(800, preferredWidth + increment));
				setPreferredWidth(newWidth);
				e.consume(); // Stop event propagation
			}
		});
		
		viewer.zPositionProperty().addListener(v -> repaint());
		viewer.tPositionProperty().addListener(v -> repaint());
		
		// Create context menu
		ContextMenu contextMenu = new ContextMenu();
		
		// Size options
		MenuItem smallMapItem = new MenuItem("Small Map");
		smallMapItem.setOnAction(e -> setPreferredWidth(400));
		
		MenuItem mediumMapItem = new MenuItem("Medium Map");
		mediumMapItem.setOnAction(e -> setPreferredWidth(600));
		
		MenuItem largeMapItem = new MenuItem("Large Map");
		largeMapItem.setOnAction(e -> setPreferredWidth(800));
		
		// Tracking options
		CheckMenuItem enableTrackingItem = new CheckMenuItem("Enable Tracking");
		enableTrackingItem.setOnAction(e -> {
			if (enableTrackingItem.isSelected()) {
				enableTracking();
			} else {
				disableTracking();
			}
		});
		
		CheckMenuItem showTrackMapItem = new CheckMenuItem("Show Track Map");
		showTrackMapItem.setOnAction(e -> showTracking(showTrackMapItem.isSelected()));
		
		MenuItem resetTrackMapItem = new MenuItem("Reset Track Map");
		resetTrackMapItem.setOnAction(e -> resetTracking());
		
		// Hide map option
		MenuItem hideMapItem = new MenuItem("Hide Map");
		hideMapItem.setOnAction(e -> setVisible(false));
		
		// Build menu
		contextMenu.getItems().addAll(
			smallMapItem,
			mediumMapItem,
			largeMapItem,
			new SeparatorMenuItem(),
			enableTrackingItem,
			showTrackMapItem,
			resetTrackMapItem,
			new SeparatorMenuItem(),
			hideMapItem
		);
		
		// Add context menu handler
		canvas.setOnContextMenuRequested(e -> {
			// Update checkbox states
			enableTrackingItem.setSelected(trackingEnabled);
			showTrackMapItem.setSelected(trackingVisible);
			showTrackMapItem.setDisable(!trackingEnabled);
			resetTrackMapItem.setDisable(!trackingEnabled);
			
			contextMenu.show(canvas, e.getScreenX(), e.getScreenY());
			e.consume(); // Prevent event propagation to viewer
		});
			
		viewer.addViewerListener(this);
	}

	public int getPreferredWidth() {
		return preferredWidth;
	}

	/**
	 * Set the preferred width of the overview
	 * @param width the preferred width
	 */
	public void setPreferredWidth(int width) {
		if (width > 0 && this.preferredWidth != width) {
			// Capture current shape in image coordinates before resizing
			Shape currentShapeInImageCoords = null;
			if (viewer != null && viewer.hasServer()) {
				currentShapeInImageCoords = viewer.getDisplayedRegionShape();
			}
			
			this.preferredWidth = width;
			this.imgLastThumbnail = null;
			if (viewer != null && viewer.hasServer()) {
				setImage(viewer.getRGBThumbnail());
				// Re-transform using the SAME shape we captured before resizing
				if (currentShapeInImageCoords != null && transform != null) {
					shapeVisible = transform.createTransformedShape(currentShapeInImageCoords);
				}
			}
			repaint();
		}
	}

	private void updateTransform() {
		if (imgPreview != null && viewer != null && viewer.getServer() != null) {
			double scale = imgPreview.getWidth() / viewer.getServer().getWidth();
			if (scale > 0) {
				if (transform == null)
					transform = AffineTransform.getScaleInstance(scale, scale);
				else
					transform.setToScale(scale, scale);
			}
			else
				transform = null;
		}
	}
	
	/**
	 * Enable tracking functionality
	 * @param fluorescent true for fluorescent images (white base), false for transmitted (black base)
	 */
	public void enableTracking() {
		if (!trackingEnabled) {
			this.trackingEnabled = true;
			this.trackingVisible = true; // Automatically show when enabling
			initializeTrackingOverlay();
			logger.info("Tracking enabled and visible");
			repaint();
		}
	}
	
	/**
	 * Disable tracking functionality
	 */
	public void disableTracking() {
		this.trackingEnabled = false;
		this.trackingVisible = false;
		logger.info("Tracking disabled");
		repaint();
	}
	
	/**
	 * Show/hide tracking overlay
	 */
	public void showTracking(boolean visible) {
		if (!trackingEnabled) {
			logger.warn("Cannot show tracking - tracking is not enabled. Call enableTracking() first.");
			return;
		}
		this.trackingVisible = visible;
		logger.info("Tracking visibility: {}", visible);
		repaint();
	}
	
	/**
	 * Toggle tracking visibility
	 */
	public void toggleTracking() {
		showTracking(!trackingVisible);
	}
	
	/**
	 * Reset tracking to initial state
	 */
	public void resetTracking() {
		if (trackingEnabled) {
			initializeTrackingOverlay();
			logger.info("Tracking reset");
			repaint();
		} else {
			this.trackingEnabled = false;
			this.trackingVisible = false;
			this.trackingOverlay = null;
			this.contouredOverlay= null;
			logger.info("Tracking cleared");
		}
	}
	
	/**
	 * Initialize the tracking overlay with all bits set
	 */
	private void initializeTrackingOverlay() {
		if (imgPreview == null)
			return;
		
		int width = (int) imgPreview.getWidth();
		int height = (int) imgPreview.getHeight();

		
		trackingOverlay = new WritableImage(width, height);
		contouredOverlay = new WritableImage(width, height);

		PixelWriter writer = trackingOverlay.getPixelWriter();
		
		// initial base RGB color (black for brightfield and white for fluorescence)
		int baseRGB = viewer.getImageData().isBrightfield()? 0xFF000000 : 0xFFFFFFFF;  // White or black
		
		// All pixels start with alpha = INITIAL_ALPHA (all 4 bits set)
		initialMaskARGB = 0x00FFFFFF;
		
		for (int i = 0; i < MAG_LAYERS.length; i++) {
			initialMaskARGB |= 1 << (24 + MAG_LAYERS[i].bitIndex);
		}

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				writer.setArgb(x, y, baseRGB & initialMaskARGB);
			}
		}
		contouredOverlay.getPixelWriter().setPixels(0, 0, width, height, trackingOverlay.getPixelReader(), 0, 0);
		
		logger.debug("Initialized tracking overlay: {}x{}, base RGB: 0x{}, initial alpha: 0b{}", 
			width, height, Integer.toHexString(baseRGB), Integer.toBinaryString(initialMaskARGB));
	}
	
	/*
	 * Detect the edge of a bitplane in the input array and apply a contour on the output pixel array
	 */
	private void applyMorphologicalEdge(int[] alphas, int[] outputPixels, int w, int h) {
		for (int y = 1; y < h - 1; y++) {
			for (int x = 1; x < w - 1; x++) {
				int idx = y * w + x;
				for (int i = 0; i < MAG_LAYERS.length; i++) {
					int bit = 1 << MAG_LAYERS[i].bitIndex;
					if ((alphas[idx] & bit) == 0) {  // visited
						boolean isEdge =
							(alphas[idx - w]     & bit) != 0 ||
							(alphas[idx + w]     & bit) != 0 ||
							(alphas[idx - 1]     & bit) != 0 ||
							(alphas[idx + 1]     & bit) != 0 ||
							(alphas[idx - w - 1] & bit) != 0 ||
							(alphas[idx - w + 1] & bit) != 0 ||
							(alphas[idx + w - 1] & bit) != 0 ||
							(alphas[idx + w + 1] & bit) != 0;
						if (isEdge) {
							outputPixels[idx] = MAG_LAYERS[i].argbColor;
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * Update tracking overlay based on current visible region and magnification
	 */
	private void updateTrackingOverlay() {
		if (!trackingEnabled || trackingOverlay == null || shapeVisible == null)
			return;
		
		// Get bounding box of visible region
		Rectangle2D bounds = shapeVisible.getBounds2D();
		int minX = Math.max(0, (int) Math.floor(bounds.getMinX()));
		int minY = Math.max(0, (int) Math.floor(bounds.getMinY()));
		int maxX = Math.min((int) trackingOverlay.getWidth() - 1, (int) Math.ceil(bounds.getMaxX()));
		int maxY = Math.min((int) trackingOverlay.getHeight() - 1, (int) Math.ceil(bounds.getMaxY()));
		
		if (minX >= maxX || minY >= maxY)
			return;

		double currentMag = viewer.getMagnification();
		int currentMaskARGB = initialMaskARGB;
		
		// Determine which alpha mask to use based on current magnification
		int bitMask = 0;
		for (int i = 0; i < MAG_LAYERS.length; i++) {
			if (currentMag >= MAG_LAYERS[i].minMag) {
				bitMask = 1<<(24 + MAG_LAYERS[i].bitIndex);
				currentMaskARGB ^= bitMask;
			}
		}

		PixelReader reader = trackingOverlay.getPixelReader();
		PixelWriter writer = trackingOverlay.getPixelWriter();
		
		// Nibble pixels in the visible region using bitwise AND
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				if (shapeVisible.contains(x, y)) {
					int argb = reader.getArgb(x, y);
					writer.setArgb(x, y, argb & currentMaskARGB);
				}
			}
		}

		// Expand crop by 1 pixel for edge detection, clamped to image bounds
		int padMinX = Math.max(0, minX - 1);
		int padMinY = Math.max(0, minY - 1);
		int padMaxX = Math.min((int) trackingOverlay.getWidth() - 1, maxX + 1);
		int padMaxY = Math.min((int) trackingOverlay.getHeight() - 1, maxY + 1);

		int padWidth  = padMaxX + 1 - padMinX;
		int padHeight = padMaxY + 1 - padMinY;

		int[] maskPixels = new int[padWidth * padHeight];
		trackingOverlay.getPixelReader().getPixels(padMinX, padMinY, padWidth, padHeight,
			PixelFormat.getIntArgbInstance(), maskPixels, 0, padWidth);

		int[] outputPixels = maskPixels.clone();
		for (int i = 0; i < maskPixels.length; i++) {
			maskPixels[i] >>>= 24;  // then crush maskPixels down to alpha-only in place
		}

		applyMorphologicalEdge(maskPixels, outputPixels, padWidth, padHeight);

		// Write back only the original (unpadded) crop region
		int innerOffsetX = minX - padMinX;  // 0 or 1
		int innerOffsetY = minY - padMinY;  // 0 or 1
		int cropWidth = (int) (maxX+1-minX);
		int cropHeight = (int) (maxY+1 - minY);
		contouredOverlay.getPixelWriter().setPixels(minX, minY, cropWidth, cropHeight,
			PixelFormat.getIntArgbInstance(), outputPixels, innerOffsetY * padWidth + innerOffsetX, padWidth);
	}
	
	/**
	 * Draw the tracking overlay on the canvas
	 */
	private void drawTrackingOverlay(GraphicsContext g) {
		if (!trackingEnabled || !trackingVisible || trackingOverlay == null)
			return;
		
		// Simply draw the overlay - alpha channel handles transparency automatically
		g.drawImage(contouredOverlay, 0, 0);
	}
	
	/**
	 * Get exploration statistics
	 */
	public void printExplorationStats() {
		if (trackingOverlay == null) {
			logger.info("No tracking overlay initialized");
			return;
		}
		
		int width = (int) trackingOverlay.getWidth();
		int height = (int) trackingOverlay.getHeight();
		int totalPixels = width * height;
		
		PixelReader reader = trackingOverlay.getPixelReader();
		
		// Count pixels at each exploration level
		int[] bitCounts = new int[5];  // 0 bits set, 1 bit set, ..., 4 bits set
		
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int alpha = (reader.getArgb(x, y) >>> 24) & 0xFF;
				int bitsSet = Integer.bitCount(alpha);
				bitCounts[bitsSet]++;
			}
		}
		
		logger.info("Exploration Statistics:");
		logger.info("  Fully explored (0 bits):  {}/{} ({:.1f}%)", 
			bitCounts[0], totalPixels, bitCounts[0] * 100.0 / totalPixels);
		logger.info("  3 layers visited (1 bit):  {}/{} ({:.1f}%)", 
			bitCounts[1], totalPixels, bitCounts[1] * 100.0 / totalPixels);
		logger.info("  2 layers visited (2 bits): {}/{} ({:.1f}%)", 
			bitCounts[2], totalPixels, bitCounts[2] * 100.0 / totalPixels);
		logger.info("  1 layer visited (3 bits):  {}/{} ({:.1f}%)", 
			bitCounts[3], totalPixels, bitCounts[3] * 100.0 / totalPixels);
		logger.info("  Unvisited (4 bits):        {}/{} ({:.1f}%)", 
			bitCounts[4], totalPixels, bitCounts[4] * 100.0 / totalPixels);
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

		// Draw base thumbnail
		g.drawImage(imgPreview, 0, 0);
		
		// Draw tracking overlay
		drawTrackingOverlay(g);
		
		// Draw the currently-visible region (thick red box)
		if (shapeVisible != null) {
			g.setStroke(color);
			g.setLineWidth(2);
			
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
	
	private double getWidth() {
		return canvas.getWidth();
	}

	private double getHeight() {
		return canvas.getHeight();
	}

	private void setImage(BufferedImage img) {
		if (img == imgLastThumbnail)
			return;
		if (img == null) {
			imgLastThumbnail = null;
		} else {
			int preferredHeight = (int)(img.getHeight() * (double)(preferredWidth / (double)img.getWidth()));
			
			imgPreview = GuiTools.getScaledRGBInstance(img, preferredWidth, preferredHeight);

			canvas.setWidth(imgPreview.getWidth());
			canvas.setHeight(imgPreview.getHeight());

			imgLastThumbnail = img;
			
			// Reinitialize tracking overlay if size changed
			if (trackingEnabled && trackingOverlay != null && 
				((int)trackingOverlay.getWidth() != (int)imgPreview.getWidth() || 
				 (int)trackingOverlay.getHeight() != (int)imgPreview.getHeight())) {
				initializeTrackingOverlay();
			}
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
		
		// Update tracking overlay
		updateTrackingOverlay();
		
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