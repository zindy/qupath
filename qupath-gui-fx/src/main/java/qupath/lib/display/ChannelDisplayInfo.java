/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2022 QuPath developers, The University of Edinburgh
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

package qupath.lib.display;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.SampleModel;

import qupath.lib.color.ColorTransformer;
import qupath.lib.color.ColorTransformer.ColorTransformMethod;

/**
 * Interface used to control the display of single channels of image data, where
 * 'single channel' means one value per pixel (in Java's parlance, one band for the
 * SampleModel).  This applies not only to the 'default' channels in an image -
 * e.g. red, green and blue for an RGB image - but also to 'derived' channels computed
 * by a transformation, e.g. color deconvolution.
 * <p>
 * The primary uses are:
 * <ul>
 * 	<li> to extract floating point pixel values for the channel from a {@link BufferedImage}
 * 		(either directly, or via some color transformation that may involve
 * 		 more than one channel/band from the BufferedImage)
 * 	<li> to generate RGB pixel values suitable for visualizing the raw channel values
 * 		extracted above, including the storage of any lookup tables required
 * 	<li> to store min/max display values, which influence the lookup table mapping to RGB
 * 		(i.e. to store brightness/contrast values)
 * 	<li> to update an existing RGB value, to facilitate creating composite images that depict
 *      the values of multiple channels in a single, merged visualization
 * </ul>
 * 
 * <p>
 * As such, its uses lie somewhere between Java's {@link SampleModel} and {@link ColorModel} classes.
 * <p>
 * Its reason for existing is that sometimes we need to be able to adjust the display of channels
 * individually and to create merges - particularly in the case of fluorescence images - but
 * to simplify whole slide image support we need to be able to do this on-the-fly.
 * Switching the ColorModel for an existing BufferedImage is somewhat awkward, and when caching
 * image tiles we want to be able to keep the original ColorModels intact - otherwise ColorModels
 * for recently-seen tiles might be different from the ColorModels of tiles that have been in the cache
 * for longer, even though they were read from the same image servers.  Furthermore, 'unknown' image types
 * (i.e. not standard RGB/BGR/single-channel images) don't always behave nicely with Graphics objects
 * if we want to paint or scale them.
 * <p>
 * Using the {@link ChannelDisplayInfo} approach means that during repainting an (A)RGB image can be produced on-the-fly
 * without needing to create a new image with the desired ColorModel for painting.
 * This potentially ends up requiring a bit more computation that is really necessary - and it may be optimized
 * better in the future - but it was the simplest method I could come up with to provide the features I wanted...
 * <p>
 * Before v0.4.0, some methods supported a boolean parameter to specify whether or not to use color LUTs.
 * Since v0.4.0, this has been replaced by {@link ChannelDisplayMode} to support different ways of visualizing channels.
 * In particular, in some cases an inverted display is requested - where 'inverted' refers to the background 
 * (i.e. switching from black is zero to white is zero, or vice versa depending upon the default display). 
 * Because only additive combinations of RGB colors are permitted, requesting inversion means that the color used for the 
 * LUT should be inverted but everything else proceeds are normal. 
 * The calling code then has the job of inverting the resulting image, as done by 
 * {@link ImageDisplay#applyTransforms(BufferedImage, BufferedImage, java.util.List, ChannelDisplayMode)}.
 * This approach is also used by the HiLo Range indicator.
 * 
 * @author Pete Bankhead
 * @see ImageDisplay
 *
 */
public interface ChannelDisplayInfo {
	
	/**
	 * Get the channel name.  This may also be returned by the {@code toString()} method.
	 * 
	 * @return
	 */
	public abstract String getName();

	/**
	 * Get the min display value.
	 * This is used to control the brightness/contrast when painting.
	 * 
	 * @return
	 */
	public abstract float getMinDisplay();

	/**
	 * Get the max display value.
	 * This is used to control the brightness/contrast when painting.
	 * 
	 * @return
	 */
	public abstract float getMaxDisplay();

	/**
	 * Get the min allowed display value.
	 * This is only a hint.
	 * 
	 * @return
	 */
	public abstract float getMinAllowed();

	/**
	 * Get the max allowed display value.
	 * This is only a hint.
	 * 
	 * @return
	 */
	public abstract float getMaxAllowed();

	/**
	 * Returns true if this channel can be used additively to create a composite image display;
	 * returns false if this channel wants all the color information to itself, so can't be displayed with others.
	 * 
	 * @return
	 */
	public abstract boolean isAdditive();

	/**
	 * Returns true if rescaling according to min &amp; max display levels is applied, false if the full display range is used.
	 * 
	 * @return
	 */
	public boolean isBrightnessContrastRescaled();
	
	/**
	 * Get a string representation of a pixel's value.
	 * This might be a single number, or 3 numbers for an RGB image where the channel includes all values.
	 * 
	 * @param img
	 * @param x
	 * @param y
	 * @return
	 */
	public String getValueAsString(BufferedImage img, int x, int y);

	/**
	 * Get the RGB value that would be used to display a particular pixel
	 * 
	 * @param img
	 * @param x
	 * @param y
	 * @param mode
	 * @return
	 */
	public abstract int getRGB(BufferedImage img, int x, int y, ChannelDisplayMode mode);
	
	/**
	 * Get the RGB values that would be used to display all the pixels of an image
	 * 
	 * @param img
	 * @param rgb
	 * @param mode
	 * @return
	 */
	public abstract int[] getRGB(BufferedImage img, int[] rgb, ChannelDisplayMode mode);
	
	/**
	 * Update an existing pixel (packed RGB) additively using the color used to display a specified one
	 * 
	 * @param img
	 * @param x
	 * @param y
	 * @param rgb
	 * @param mode 
	 * @return
	 */
	public abstract int updateRGBAdditive(BufferedImage img, int x, int y, int rgb, ChannelDisplayMode mode);
	
	/**
	 * Update an array of existing pixels (packed RGB) additively using the colors to display a specified image.
	 * May throw an UnsupportedOperationException if isAdditive() returns false;
	 * 
	 * @param img
	 * @param rgb
	 * @param mode
	 */
	public void updateRGBAdditive(BufferedImage img, int[] rgb, ChannelDisplayMode mode);


	/**
	 * Returns true if this does something - anything - and false otherwise.
	 * For example, this will return false if we have an RGB image with no transformations of any kind applied (e.g. brightness/contrast)
	 * @return
	 */
	public boolean doesSomething();
	
	
	/**
	 * Predominate color used when this ChannelDisplayInfo uses a Color LUT (e.g. Color.RED for a red channel).
	 * Returns null if there is no appropriate color choice, or the image is RGB.
	 * @return
	 */
	public Integer getColor();
	
	/**
	 * Get the {@link ColorTransformMethod} associated with this channel, or null
	 * if no method is relevant.
	 * 
	 * @return
	 */
	public default ColorTransformer.ColorTransformMethod getMethod() {
		return null;
	}
	

	/**
	 * Helper interface to indicate that the display ranges can be modified.
	 */
	static interface ModifiableChannelDisplayInfo extends ChannelDisplayInfo {
		
		/**
		 * Set the maximum permissible range for the image display.
		 * <p>
		 * For an 8-bit image, that should be 0 and 255.
		 * <p>
		 * For a 16-bit image, fewer bits might actually be used... therefore the full range of 0-2^16-1 may be too much.
		 * <p>
		 * Also, for a 32-bit floating point image the limits are rather harder to define in a general way.
		 * This method makes it possible to restrict the permissible range to something sensible.
		 * Brightness/contrast/min/max sliders may make use of this.
		 * 
		 * @param minAllowed
		 * @param maxAllowed
		 */
		public abstract void setMinMaxAllowed(float minAllowed, float maxAllowed);
		
		/**
		 * Set the min display value for this channel.
		 * Note that it is *strongly* advised to use <code>ImageDisplay.setMinMaxDisplay</code> instead 
		 * since this helps ensure that the <code>ImageDisplay</code> fires appropriate events etc.
		 * 
		 * @see ImageDisplay
		 * 
		 * @param minDisplay
		 */
		public abstract void setMinDisplay(float minDisplay);

		/**
		 * Set the max display value for this channel.
		 * Note that it is *strongly* advised to use <code>ImageDisplay.setMinMaxDisplay</code> instead 
		 * since this helps ensure that the <code>ImageDisplay</code> fires appropriate events etc.
		 * 
		 * @see ImageDisplay
		 * 
		 * @param maxDisplay
		 */
		public abstract void setMaxDisplay(float maxDisplay);
		

	}

}