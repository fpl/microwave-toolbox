/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import Jama.Matrix;
import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ETAD corrector for S-1 Stripmap SLC products.
 */

 public class SMCorrector extends BaseCorrector implements Corrector {

    private double firstLineTime = 0.0;
    private double lastLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double slantRangeToFirstPixel = 0.0;
    private double rangeSpacing = 0.0;
    private double radarFrequency = 0.0;
    private static final String ETAD = "ETAD";

    private String[] swathID = null;
    private String[] polarizations = null;


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SMCorrector(final Product sourceProduct, final ETADUtils etadUtils, final Resampling selectedResampling) {
        super(sourceProduct, etadUtils, selectedResampling);
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            getSourceProductMetadata();
        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void getSourceProductMetadata() {

        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if(absRoot == null) {
                throw new OperatorException("Abstracted Metadata not found");
            }

            final String acqMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE).toLowerCase();
            if (!acqMode.equals("sm")) {
                throw new OperatorException("StripMap product is expected.");
            }

            firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * Constants.secondsInDay;
            lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * Constants.secondsInDay;
            lineTimeInterval = (lastLineTime - firstLineTime) / (sourceImageHeight - 1);
            slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
            rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
            radarFrequency = absRoot.getAttributeDouble(AbstractMetadata.radar_frequency) * 1E6; // MHz to Hz

            final MetadataElement srcOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement srcAnnotation = srcOrigProdRoot.getElement("annotation");
            if (srcAnnotation == null) {
                throw new IOException("Annotation Metadata not found for product: " + sourceProduct.getName());
            }
            final MetadataElement[] elements = srcAnnotation.getElements();
            final int numOfPolarizations = elements.length;
            swathID = new String[numOfPolarizations];
            polarizations = new String[numOfPolarizations];
            for (int i = 0; i < numOfPolarizations; ++i) {
                final MetadataElement productElem = elements[i].getElement("product");
                final MetadataElement adsHeaderElem = productElem.getElement("adsHeader");
                swathID[i] = adsHeaderElem.getAttributeString("swath");
                polarizations[i] = adsHeaderElem.getAttributeString("polarisation");
            }

        } catch(Throwable e) {
            throw new OperatorException(e);
        }
    }

    @Override
    public Product createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX, sourceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);

        if (outputPhaseCorrections) {

            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, true);

                if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
                }
            }

            final Band phaseBand = new Band(ETAD_PHASE_CORRECTION, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
            phaseBand.setUnit(Unit.RADIANS);
            targetProduct.addBand(phaseBand);

            final Band heightBand = new Band(ETAD_HEIGHT, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
            heightBand.setUnit(Unit.METERS);
            targetProduct.addBand(heightBand);

        } else { // resampling image

            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = new Band(srcBand.getName(), ProductData.TYPE_FLOAT32,
                        srcBand.getRasterWidth(), srcBand.getRasterHeight());

                targetBand.setUnit(srcBand.getUnit());
                targetBand.setDescription(srcBand.getDescription());
                targetProduct.addBand(targetBand);

                if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
                }
            }
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        return targetProduct;
    }


    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm,
                                 final Operator op) throws OperatorException {

        if (resamplingImage) {
            computeTileStackResampleImage(targetTileMap, targetRectangle, pm, op);
        } else {
            computeTileStackOutputCorrections(targetTileMap, targetRectangle, pm, op);
        }
    }

    private void computeTileStackResampleImage(Map<Band, Tile> targetTileMap, Rectangle targetRectangle,
                                               ProgressMonitor pm, final Operator op) throws OperatorException {
        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int yMax = y0 + h - 1;
            final int xMax = x0 + w - 1;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle srcRectangle = getSourceRectangle(x0, y0, w, h, margin);

            for (Band tgtBand : targetProduct.getBands()) {
                if (tgtBand instanceof VirtualBand) {
                    continue;
                }

                final Band srcBand = sourceProduct.getBand(tgtBand.getName());
                final Tile srcTile = op.getSourceTile(srcBand, srcRectangle);
                final ProductData srcData = srcTile.getDataBuffer();

                final Tile tgtTile = targetTileMap.get(tgtBand);
                final ProductData tgtData = tgtTile.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(srcTile);
                final TileIndex tgtIndex = new TileIndex(tgtTile);

                final ResamplingRaster slvResamplingRaster = new ResamplingRaster(srcTile, srcData);
                final Resampling.Index resamplingIndex = selectedResampling.createIndex();

                for (int y = y0; y <= yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    final int yy = y - y0;

                    for (int x = x0; x <= xMax; ++ x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int xx = x - x0;
                        final PixelPos slavePixelPos = slavePixPos[yy][xx];

                        selectedResampling.computeCornerBasedIndex(slavePixelPos.x, slavePixelPos.y,
                                sourceImageWidth, sourceImageHeight, resamplingIndex);

                        final double v = selectedResampling.resample(slvResamplingRaster, resamplingIndex);
                        tgtData.setElemDoubleAt(tgtIdx, v);
                    }
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void computeTileStackOutputCorrections(Map<Band, Tile> targetTileMap, Rectangle targetRectangle,
                                                   ProgressMonitor pm, final Operator op) throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int yMax = y0 + h - 1;
            final int xMax = x0 + w - 1;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            if (!tropToHeightGradientComputed) {
                computeTroposphericToHeightGradient();
            }

            double[][] correction = new double[h][w];
            getInSARRangeTimeCorrectionForCurrentTile(x0, y0, w, h, 1, correction);
            final double rangeTimeCalibration = getInstrumentRangeTimeCalibration(swathID[0]);

            double[][] height = new double[h][w];
            getCorrectionForCurrentTile(HEIGHT, x0, y0, w, h, 1, height);

            final Band phaseBand = targetProduct.getBand(ETAD_PHASE_CORRECTION);
            final Tile phaseTile = targetTileMap.get(phaseBand);
            final ProductData phaseData = phaseTile.getDataBuffer();
            final TileIndex phaseIndex = new TileIndex(phaseTile);

            final Band heightBand = targetProduct.getBand(ETAD_HEIGHT);
            final Tile heightTile = targetTileMap.get(heightBand);
            final ProductData heightData = heightTile.getDataBuffer();
            final TileIndex heightIndex = new TileIndex(phaseTile);

            for (int y = y0; y <= yMax; ++y) {
                phaseIndex.calculateStride(y);
                heightIndex.calculateStride(y);
                int yy = y - y0;

                for (int x = x0; x <= xMax; ++x) {
                    final int phaseIdx = phaseIndex.getIndex(x);
                    final int heightIdx = heightIndex.getIndex(x);
                    final int xx = x - x0;

                    final double delay = correction[yy][xx] + rangeTimeCalibration;
                    final double phase = -2.0 * Constants.PI * radarFrequency * delay; // delay time (s) to phase (radian)
                    phaseData.setElemDoubleAt(phaseIdx, phase);
                    heightData.setElemDoubleAt(heightIdx, height[yy][xx]);
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private synchronized void computeTroposphericToHeightGradient() {

        if (tropToHeightGradientComputed) return;

        final double[] gradientArray = new double[1];
        final ETADUtils.Burst burst = etadUtils.getBurst(1, 1, 1);
        final double[][] tropCorr = getBurstCorrection(TROPOSPHERIC_CORRECTION_RG, burst);
        final double[][] height = getBurstCorrection(HEIGHT, burst);
        final double gradient = computeGradientForCurrentBurst(tropCorr, height);
        gradientArray[0] = -2.0 * Constants.PI * radarFrequency * gradient;

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        MetadataElement etadElem = absTgt.getElement(ETAD);
        if (etadElem == null) {
            etadElem = new MetadataElement(ETAD);
            absTgt.addElement(etadElem);
        }
        final MetadataAttribute attrib = new MetadataAttribute("gradient", ProductData.TYPE_FLOAT64, gradientArray.length);
        attrib.getData().setElems(gradientArray);
        etadElem.addAttribute(attrib);

        tropToHeightGradientComputed = true;
    }

    private double computeGradientForCurrentBurst(final double[][] tropCorr, final double[][] height) {

        final int rows = tropCorr.length;
        final int cols = tropCorr[0].length;

        double sumX = 0.0, sumX2 = 0.0, sumY = 0.0, sumXY = 0.0;
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols - 1; ++c) {
                final double dh = height[r][c + 1] - height[r][c];
                final double dt = tropCorr[r][c + 1] - tropCorr[r][c];
                sumX += dh;
                sumX2 += dh * dh;
                sumY += dt;
                sumXY += dh * dt;
            }
        }

        final Matrix A = new Matrix(new double[][]{{sumX2, sumX}, {sumX, rows * (cols - 1)}});
        final Matrix b = new Matrix(new double[]{sumXY, sumY}, 2);
        final Matrix c = A.solve(b);
        return c.get(0,0);
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h,
                                       final PixelPos[][] slavePixPos) throws Exception {

        final double[][] azCorr = new double[h][w];
        getAzimuthTimeCorrectionForCurrentTile(x0, y0, w, h, azCorr);

        final double[][] rgCorr = new double[h][w];
        getRangeTimeCorrectionForCurrentTile(x0, y0, w, h, rgCorr);

        double azimuthTimeCalibration = 0.0;
        if (!sumOfAzimuthCorrections) {
            azimuthTimeCalibration = getInstrumentAzimuthTimeCalibration(swathID[0]);
        }

        double rangeTimeCalibration = 0.0;
        if (!sumOfRangeCorrections) {
            rangeTimeCalibration = getInstrumentRangeTimeCalibration(swathID[0]);
        }

        for (int y = y0; y < y0 + h; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;

            for (int x = x0; x < x0 + w; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;

                final double azCorrTime = azTime + azCorr[yy][xx] + azimuthTimeCalibration;
                final double rgCorrTime = rgTime + rgCorr[yy][xx] + rangeTimeCalibration;

                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = (rgCorrTime * Constants.halfLightSpeed - slantRangeToFirstPixel) / rangeSpacing;

                slavePixPos[yy][xx] = new PixelPos(xCorr, yCorr);
            }
        }
    }

	@Override
    protected void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                               final int burstIndex, final double[][] correction, final double scale) {

        Map<String, double[][]> correctionMap = new HashMap<>(10);
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;
                final ETADUtils.Burst burst = etadUtils.getBurst(azTime, rgTime);
                correction[yy][xx] += scale * getCorrection(layer, azTime, rgTime, burst, correctionMap);
            }
        }
    }


    private static class ResamplingRaster implements Resampling.Raster {

        private final Tile tile;
        private final ProductData dataBuffer;

        private ResamplingRaster(final Tile tile, final ProductData dataBuffer) {
            this.tile = tile;
            this.dataBuffer = dataBuffer;
        }

        public final int getWidth() {
            return tile.getWidth();
        }

        public final int getHeight() {
            return tile.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            try {
                final TileIndex index = new TileIndex(tile);
                final int maxX = x.length;
                for (int i = 0; i < y.length; i++) {
                    index.calculateStride(y[i]);
                    for (int j = 0; j < maxX; j++) {
                        double v = dataBuffer.getElemDoubleAt(index.getIndex(x[j]));
                        samples[i][j] = v;
                    }
                }

            } catch (Exception e) {
                SystemUtils.LOG.severe(e.getMessage());
                allValid = false;
            }

            return allValid;
        }
    }
}
