/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.sentinel1;

import eu.esa.sar.commons.io.JSONProductDirectory;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.io.XMLProductDirectory;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.core.util.math.MathUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import static org.esa.snap.engine_utilities.datamodel.AbstractMetadata.NO_METADATA_STRING;

/**
 * This class represents a product directory.
 */
public class Sentinel1GDALProductDirectory extends XMLProductDirectory {

    private static final GTiffDriverProductReaderPlugIn readerPlugin = new GTiffDriverProductReaderPlugIn();
    private final Map<String, ReaderData> bandProductMap = new HashMap<>();
    private final Map<String, ReaderData> bandNameReaderDataMap = new HashMap<>();

    private final Map<Band, TiePointGeoCoding> bandGeocodingMap = new HashMap<>(5);
    private final transient Map<String, String> imgBandMetadataMap = new HashMap<>(4);
    private String acqMode = "";
    DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    private final static Double NoDataValue = 0.0;//-9999.0;

    public static class ReaderData {
        ProductReader reader;
        Product bandProduct;
        Dimension bandDimensions;
    }
    
    @Override
    public void close() throws IOException {
        super.close();
        for (ReaderData data : bandProductMap.values()) {
            if (data.bandProduct != null) {
                data.bandProduct.dispose();
            }
            data.reader.close();
        }
    }

    public Sentinel1GDALProductDirectory(final File inputFile) {
        super(inputFile);
    }

    protected String getHeaderFileName() {
        return Sentinel1GDALProductReaderPlugIn.PRODUCT_HEADER_NAME;
    }

    protected String getRelativePathToImageFolder() {
        return getRootFolder() + "measurement" + '/';
    }

    protected void addImageFile(final String imgPath, final MetadataElement newRoot) throws IOException {
        final String name = getBandFileNameFromImage(imgPath);
        if ((name.endsWith("tiff"))) {
            try {
                final InputStream inStream = getInputStream(imgPath);
                if(inStream.available() > 0) {
                    ReaderData data = new ReaderData();
                    data.reader = readerPlugin.createReaderInstance();
                    data.bandProduct = data.reader.readProductNodes(productDir.getFile(imgPath), null);
                    data.bandDimensions = getBandDimensions(newRoot, imgBandMetadataMap.get(name));
                    bandProductMap.put(name, data);

                } else {
                    inStream.close();
                }
            } catch (Exception e) {
                SystemUtils.LOG.severe(imgPath +" not found");
            }
        }
    }

    @Override
    protected void addBands(final Product product) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        int cnt = 1;
        for (String imgName : bandProductMap.keySet()) {
            final ReaderData img = bandProductMap.get(imgName);
            final MetadataElement bandMetadata = absRoot.getElement(imgBandMetadataMap.get(imgName));
            final String swath = bandMetadata.getAttributeString(AbstractMetadata.swath);
            final String pol = bandMetadata.getAttributeString(AbstractMetadata.polarization);
            final int width = bandMetadata.getAttributeInt(AbstractMetadata.num_samples_per_line);
            final int height = bandMetadata.getAttributeInt(AbstractMetadata.num_output_lines);
            int numImages = 1;

            String tpgPrefix = "";
            String suffix = pol;
            if (isSLC()) {
                numImages *= 2; // real + imaginary
                if(isTOPSAR()) {
                    suffix = swath + '_' + pol;
                    tpgPrefix = swath;
                } else if(acqMode.equals("WV")) {
                    suffix = suffix + '_' + cnt;
                    ++cnt;
                }
            }

            String bandName;
            boolean real = true;
            Band lastRealBand = null;
            for (int i = 0; i < numImages; ++i) {

                if (isSLC()) {
                    String unit;

                    for(Band band : img.bandProduct.getBands()) {
                        if (real) {
                            bandName = "i" + '_' + suffix;
                            unit = Unit.REAL;
                        } else {
                            bandName = "q" + '_' + suffix;
                            unit = Unit.IMAGINARY;
                        }

                        final Band newBand = new Band(bandName, ProductData.TYPE_INT16, width, height);
                        newBand.setUnit(unit);
                        newBand.setNoDataValueUsed(true);
                        newBand.setNoDataValue(NoDataValue);
                        //newBand.setSourceImage(band.getSourceImage());

                        bandNameReaderDataMap.put(bandName, img);

                        product.addBand(newBand);
                        AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                        if (real) {
                            lastRealBand = newBand;
                        } else {
                            ReaderUtils.createVirtualIntensityBand(product, lastRealBand, newBand, '_' + suffix);
                        }
                        real = !real;

                        // add tiepointgrids and geocoding for band
                        addTiePointGrids(product, newBand, imgName, tpgPrefix);

                        // reset to null so it doesn't adopt a geocoding from the bands
                        product.setSceneGeoCoding(null);
                    }
                } else {
                    for(Band band : img.bandProduct.getBands()) {
                        bandName = "Amplitude" + '_' + suffix;
                        final Band newBand = new Band(bandName, band.getDataType(), width, height);
                        newBand.setUnit(Unit.AMPLITUDE);
                        newBand.setNoDataValueUsed(true);
                        newBand.setNoDataValue(NoDataValue);
                        newBand.setSourceImage(band.getSourceImage());

                        product.addBand(newBand);
                        AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                        SARReader.createVirtualIntensityBand(product, newBand, '_' + suffix);

                        // add tiepointgrids and geocoding for band
                        addTiePointGrids(product, newBand, imgName, tpgPrefix);
                    }
                }
            }
        }
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        final SafeManifest safeManifest = new SafeManifest();
        safeManifest.addManifestMetadata(getProductName(), absRoot, origProdRoot, false);

        acqMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        setSLC(absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE).equals("COMPLEX"));

        addProductInfoJSON(origProdRoot);

        // get metadata for each band
        addBandAbstractedMetadata(absRoot, origProdRoot);
        addCalibrationAbstractedMetadata(origProdRoot);
        addNoiseAbstractedMetadata(origProdRoot);
        addRFIAbstractedMetadata(origProdRoot);
    }

    private void addProductInfoJSON(final MetadataElement origProdRoot) {
        if(productDir.exists("productInfo.json")) {
            try {
                final File productInfoFile = productDir.getFile("productInfo.json");
                if(productInfoFile.length() > 0) {
                    final BufferedReader streamReader = new BufferedReader(new FileReader(productInfoFile.getPath()));
                    final JSONParser parser = new JSONParser();
                    final JSONObject json = (JSONObject) parser.parse(streamReader);
                    json.remove("filenameMap");
                    AbstractMetadataIO.AddXMLMetadata(JSONProductDirectory.jsonToXML("ProductInfo", json), origProdRoot);
                }
            } catch(Exception e) {
               //throw new IOException("Unable to read productInfo " + e.getMessage(), e);
            }
        }
    }

    private void determineProductDimensions(final MetadataElement absRoot) throws IOException {
        int totalWidth = 0, maxHeight = 0, k = 0;
        String pol = null;
        for (String imgName : bandProductMap.keySet()) {
            final String bandMetadataName = imgBandMetadataMap.get(imgName);
            if (bandMetadataName == null) {
                throw new IOException("Metadata for measurement dataset " + imgName + " not found");
            }

            if (k == 0) {
                pol = bandMetadataName.substring(bandMetadataName.lastIndexOf("_") + 1);
            } else if (!bandMetadataName.substring(bandMetadataName.lastIndexOf("_") + 1).equals(pol)) {
                continue;
            }
            k++;

            final MetadataElement bandMetadata = absRoot.getElement(bandMetadataName);
            int width = bandMetadata.getAttributeInt(AbstractMetadata.num_samples_per_line);
            int height = bandMetadata.getAttributeInt(AbstractMetadata.num_output_lines);
            totalWidth += width;
            if (height > maxHeight) {
                maxHeight = height;
            }
        }

        if (isSLC() && isTOPSAR()) {  // approximate does not account for overlap
            absRoot.setAttributeInt(AbstractMetadata.num_samples_per_line, totalWidth);
            absRoot.setAttributeInt(AbstractMetadata.num_output_lines, maxHeight);
        }
    }

    private void addBandAbstractedMetadata(final MetadataElement absRoot,
                                           final MetadataElement origProdRoot) throws IOException {

        MetadataElement annotationElement = origProdRoot.getElement("annotation");
        if (annotationElement == null) {
            annotationElement = new MetadataElement("annotation");
            origProdRoot.addElement(annotationElement);
        }

        // collect range and azimuth spacing
        double rangeSpacingTotal = 0;
        double azimuthSpacingTotal = 0;
        boolean commonMetadataRetrieved = false;
        final DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd_HH:mm:ss");

        double heightSum = 0.0;

        int numBands = 0;
        final String annotFolder = getRootFolder() + "annotation";
        final String[] filenames = listFiles(annotFolder);
        if (filenames != null) {
            for (String metadataFile : filenames) {

                final Document xmlDoc;
                try (final InputStream is = getInputStream(annotFolder + '/' + metadataFile)) {
                    xmlDoc = XMLSupport.LoadXML(is);
                }
                final Element rootElement = xmlDoc.getRootElement();
                final MetadataElement nameElem = new MetadataElement(metadataFile);
                annotationElement.addElement(nameElem);
                AbstractMetadataIO.AddXMLMetadata(rootElement, nameElem);

                final MetadataElement prodElem = nameElem.getElement("product");
                final MetadataElement adsHeader = prodElem.getElement("adsHeader");

                final String swath = adsHeader.getAttributeString("swath");
                final String pol = adsHeader.getAttributeString("polarisation");

                final ProductData.UTC startTime = getTime(adsHeader, "startTime", sentinelDateFormat);
                final ProductData.UTC stopTime = getTime(adsHeader, "stopTime", sentinelDateFormat);

                final String bandRootName = AbstractMetadata.BAND_PREFIX + swath + '_' + pol;
                final MetadataElement bandAbsRoot = AbstractMetadata.addBandAbstractedMetadata(absRoot, bandRootName);
                final String imgName = FileUtils.exchangeExtension(metadataFile, ".tiff");
                imgBandMetadataMap.put(imgName, bandRootName);

                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.SWATH, swath);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.polarization, pol);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.annotation, metadataFile);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.first_line_time, startTime);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.last_line_time, stopTime);

                if (AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds1_tx_rx_polar)) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, pol);
                } else if(!absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar, NO_METADATA_STRING).equals(pol)){
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds2_tx_rx_polar, pol);
                }

                final MetadataElement imageAnnotation = prodElem.getElement("imageAnnotation");
                final MetadataElement imageInformation = imageAnnotation.getElement("imageInformation");

                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                                              Integer.parseInt(adsHeader.getAttributeString("missionDataTakeId")));
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slice_num,
                                              Integer.parseInt(imageInformation.getAttributeString("sliceNumber")));

                rangeSpacingTotal += imageInformation.getAttributeDouble("rangePixelSpacing");
                azimuthSpacingTotal += imageInformation.getAttributeDouble("azimuthPixelSpacing");

                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.line_time_interval,
                                              imageInformation.getAttributeDouble("azimuthTimeInterval"));
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_samples_per_line,
                                              imageInformation.getAttributeInt("numberOfSamples"));
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_output_lines,
                                              imageInformation.getAttributeInt("numberOfLines"));
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.sample_type,
                                              imageInformation.getAttributeString("pixelValue").toUpperCase());

                heightSum += getBandTerrainHeight(prodElem);

                if (!commonMetadataRetrieved) {
                    // these should be the same for all swaths
                    // set to absRoot

                    final MetadataElement generalAnnotation = prodElem.getElement("generalAnnotation");
                    final MetadataElement productInformation = generalAnnotation.getElement("productInformation");
                    final MetadataElement processingInformation = imageAnnotation.getElement("processingInformation");
                    final MetadataElement swathProcParamsList = processingInformation.getElement("swathProcParamsList");
                    final MetadataElement swathProcParams = swathProcParamsList.getElement("swathProcParams");
                    final MetadataElement rangeProcessing = swathProcParams.getElement("rangeProcessing");
                    final MetadataElement azimuthProcessing = swathProcParams.getElement("azimuthProcessing");

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
                                                  productInformation.getAttributeDouble("rangeSamplingRate") / Constants.oneMillion);
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                                                  productInformation.getAttributeDouble("radarFrequency") / Constants.oneMillion);
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
                                                  imageInformation.getAttributeDouble("azimuthTimeInterval"));

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
                                                  imageInformation.getAttributeDouble("slantRangeTime") * Constants.halfLightSpeed);

                    final MetadataElement downlinkInformationList = generalAnnotation.getElement("downlinkInformationList");
                    final MetadataElement downlinkInformation = downlinkInformationList.getElement("downlinkInformation");

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
                                                  downlinkInformation.getAttributeDouble("prf"));

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth,
                                                  rangeProcessing.getAttributeDouble("processingBandwidth") / Constants.oneMillion);
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth,
                                                  azimuthProcessing.getAttributeDouble("processingBandwidth"));

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                                                  rangeProcessing.getAttributeDouble("numberOfLooks"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                                                  azimuthProcessing.getAttributeDouble("numberOfLooks"));

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_window_type,
                                                  rangeProcessing.getAttributeString("windowType"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_window_coefficient,
                                                  rangeProcessing.getAttributeDouble("windowCoefficient"));

                    if (!isTOPSAR() || !isSLC()) {
                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                                                      imageInformation.getAttributeInt("numberOfLines"));
                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                                                      imageInformation.getAttributeInt("numberOfSamples"));
                    }

                    addOrbitStateVectors(absRoot, generalAnnotation.getElement("orbitList"));
                    addSRGRCoefficients(absRoot, prodElem.getElement("coordinateConversion"));
                    addDopplerCentroidCoefficients(absRoot, prodElem.getElement("dopplerCentroid"));

                    commonMetadataRetrieved = true;
                }

                ++numBands;
            }
        }

        // set average to absRoot
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                                      rangeSpacingTotal / (double) numBands);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                                      azimuthSpacingTotal / (double) numBands);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height, heightSum / filenames.length);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied, 1);
    }

    private double getBandTerrainHeight(final MetadataElement prodElem) {
        final MetadataElement generalAnnotation = prodElem.getElement("generalAnnotation");
        final MetadataElement terrainHeightList = generalAnnotation.getElement("terrainHeightList");

        double heightSum = 0.0;

        final MetadataElement[] heightList = terrainHeightList.getElements();
        int cnt = 0;
        for (MetadataElement terrainHeight : heightList) {
            heightSum += terrainHeight.getAttributeDouble("value");
            ++cnt;
        }
        return heightSum / cnt;
    }

    private void addCalibrationAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "calibration";
        addMetadataFiles(origProdRoot, annotfolder, "calibration");
    }

    private void addNoiseAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "calibration";
        addMetadataFiles(origProdRoot, annotfolder, "noise");
    }

    private void addRFIAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "rfi";
        addMetadataFiles(origProdRoot, annotfolder, "rfi");
    }

    private void addMetadataFiles(final MetadataElement origProdRoot, final String folder, final String name) throws IOException {

        final String[] filenames = listFiles(folder);

        if (filenames != null && filenames.length > 0) {
            MetadataElement metaElement = origProdRoot.getElement(name);
            if (metaElement == null) {
                metaElement = new MetadataElement(name);
                origProdRoot.addElement(metaElement);
            }

            for (String metadataFile : filenames) {
                if (metadataFile.startsWith(name)) {

                    final Document xmlDoc;
                    try (final InputStream is = getInputStream(folder + '/' + metadataFile)) {
                        xmlDoc = XMLSupport.LoadXML(is);
                    }
                    final Element rootElement = xmlDoc.getRootElement();
                    final String newName = metadataFile.replace(name+"-", "");
                    final MetadataElement nameElem = new MetadataElement(newName);
                    metaElement.addElement(nameElem);
                    AbstractMetadataIO.AddXMLMetadata(rootElement, nameElem);
                }
            }
        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot, final MetadataElement orbitList) {
        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

        final MetadataElement[] stateVectorElems = orbitList.getElements();
        for (int i = 1; i <= stateVectorElems.length; ++i) {
            addVector(AbstractMetadata.orbit_vector, orbitVectorListElem, stateVectorElems[i - 1], i);
        }

        // set state vector time
        if (absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, AbstractMetadata.NO_METADATA_UTC).
                equalElems(AbstractMetadata.NO_METADATA_UTC)) {

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME,
                                          ReaderUtils.getTime(stateVectorElems[0], "time", sentinelDateFormat));
        }
    }

    private void addVector(final String name, final MetadataElement orbitVectorListElem,
                                  final MetadataElement orbitElem, final int num) {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        final MetadataElement positionElem = orbitElem.getElement("position");
        final MetadataElement velocityElem = orbitElem.getElement("velocity");

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,
                                        ReaderUtils.getTime(orbitElem, "time", sentinelDateFormat));

        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,
                                           positionElem.getAttributeDouble("x", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,
                                           positionElem.getAttributeDouble("y", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,
                                           positionElem.getAttributeDouble("z", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,
                                           velocityElem.getAttributeDouble("x", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,
                                           velocityElem.getAttributeDouble("y", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,
                                           velocityElem.getAttributeDouble("z", 0));

        orbitVectorListElem.addElement(orbitVectorElem);
    }

    private void addSRGRCoefficients(final MetadataElement absRoot, final MetadataElement coordinateConversion) {
        if (coordinateConversion == null) return;
        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null) return;

        final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + '.' + listCnt);
            srgrCoefficientsElem.addElement(srgrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, utcTime);

            final double grOrigin = elem.getAttributeDouble("gr0", 0);
            AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                                                    ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
            AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin, grOrigin);

            final String coeffStr = elem.getElement("grsrCoefficients").getAttributeString("grsrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                    srgrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,
                                                            ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, coefValue);
                }
            }
        }
    }

    private void addDopplerCentroidCoefficients(
            final MetadataElement absRoot, final MetadataElement dopplerCentroid) {
        if (dopplerCentroid == null) return;
        final MetadataElement dcEstimateList = dopplerCentroid.getElement("dcEstimateList");
        if (dcEstimateList == null) return;

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : dcEstimateList.getElements()) {
            final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
            dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

            final double refTime = elem.getAttributeDouble("t0", 0) * 1e9; // s to ns
            AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                                                    ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
            AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, refTime);

            final String coeffStr = elem.getElement("geometryDcPolynomial").getAttributeString("geometryDcPolynomial", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                    dopplerListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                                                            ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                    AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
                }
            }
        }
    }

    @Override
    protected void addGeoCoding(final Product product) {

        TiePointGrid latGrid = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
        TiePointGrid lonGrid = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
        if (latGrid != null && lonGrid != null) {
            setLatLongMetadata(product, latGrid, lonGrid);

            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
            product.setSceneGeoCoding(tpGeoCoding);
            return;
        }

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        int numOfSubSwath;
        switch (acquisitionMode) {
            case "IW":
                numOfSubSwath = 3;
                break;
            case "EW":
                numOfSubSwath = 5;
                break;
            default:
                numOfSubSwath = 1;
        }

        String[] bandNames = product.getBandNames();
        Band firstSWBand = null, lastSWBand = null;
        boolean firstSWBandFound = false, lastSWBandFound = false;
        for (String bandName : bandNames) {
            if (!firstSWBandFound && bandName.contains(acquisitionMode + 1)) {
                firstSWBand = product.getBand(bandName);
                firstSWBandFound = true;
            }

            if (!lastSWBandFound && bandName.contains(acquisitionMode + numOfSubSwath)) {
                lastSWBand = product.getBand(bandName);
                lastSWBandFound = true;
            }
        }
        if (firstSWBand != null && lastSWBand != null) {

            final GeoCoding firstSWBandGeoCoding = bandGeocodingMap.get(firstSWBand);
            final int firstSWBandHeight = firstSWBand.getRasterHeight();

            final GeoCoding lastSWBandGeoCoding = bandGeocodingMap.get(lastSWBand);
            final int lastSWBandWidth = lastSWBand.getRasterWidth();
            final int lastSWBandHeight = lastSWBand.getRasterHeight();

            final PixelPos ulPix = new PixelPos(0, 0);
            final PixelPos llPix = new PixelPos(0, firstSWBandHeight - 1);
            final GeoPos ulGeo = new GeoPos();
            final GeoPos llGeo = new GeoPos();
            firstSWBandGeoCoding.getGeoPos(ulPix, ulGeo);
            firstSWBandGeoCoding.getGeoPos(llPix, llGeo);

            final PixelPos urPix = new PixelPos(lastSWBandWidth - 1, 0);
            final PixelPos lrPix = new PixelPos(lastSWBandWidth - 1, lastSWBandHeight - 1);
            final GeoPos urGeo = new GeoPos();
            final GeoPos lrGeo = new GeoPos();
            lastSWBandGeoCoding.getGeoPos(urPix, urGeo);
            lastSWBandGeoCoding.getGeoPos(lrPix, lrGeo);

            final float[] latCorners = {(float) ulGeo.getLat(), (float) urGeo.getLat(), (float) llGeo.getLat(), (float) lrGeo.getLat()};
            final float[] lonCorners = {(float) ulGeo.getLon(), (float) urGeo.getLon(), (float) llGeo.getLon(), (float) lrGeo.getLon()};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, ulGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, ulGeo.getLon());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, urGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, urGeo.getLon());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, llGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, llGeo.getLon());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lrGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lrGeo.getLon());

            // add band geocoding
            final Band[] bands = product.getBands();
            for (Band band : bands) {
                band.setGeoCoding(bandGeocodingMap.get(band));
            }
        } else {
            try {
                final String annotFolder = getRootFolder() + "annotation";
                final String[] filenames = listFiles(annotFolder);

                //addTiePointGrids(product, null, filenames[0], "");

                latGrid = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
                lonGrid = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
                if (latGrid != null && lonGrid != null) {
                    setLatLongMetadata(product, latGrid, lonGrid);

                    final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
                    product.setSceneGeoCoding(tpGeoCoding);
                }
            } catch (IOException e) {
                SystemUtils.LOG.severe("Unable to add tpg geocoding " + e.getMessage());
            }
        }
    }

    @Override
    protected void addTiePointGrids(final Product product) {
        // replaced by call to addTiePointGrids(band)
    }

    private void addTiePointGrids(final Product product, final Band band, final String imgXMLName, final String tpgPrefix) {

        //System.out.println("S1L1Dir.addTiePointGrids: band = " + band.getName() + " imgXMLName = " + imgXMLName + " tpgPrefix = " + tpgPrefix);

        String pre = "";
        if (!tpgPrefix.isEmpty())
            pre = tpgPrefix + '_';

        final TiePointGrid existingLatTPG = product.getTiePointGrid(pre + OperatorUtils.TPG_LATITUDE);
        final TiePointGrid existingLonTPG = product.getTiePointGrid(pre + OperatorUtils.TPG_LONGITUDE);
        if (existingLatTPG != null && existingLonTPG != null) {
            if(band != null) {
                // reuse geocoding
                final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(existingLatTPG, existingLonTPG);
                band.setGeoCoding(tpGeoCoding);
            }
            return;
        }
        //System.out.println("add new TPG for band = " + band.getName());
        final String annotation = FileUtils.exchangeExtension(imgXMLName, ".xml");
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement imgElem = annotationElem.getElement(annotation);
        final MetadataElement productElem = imgElem.getElement("product");
        final MetadataElement geolocationGrid = productElem.getElement("geolocationGrid");
        final MetadataElement geolocationGridPointList = geolocationGrid.getElement("geolocationGridPointList");

        final MetadataElement[] geoGrid = geolocationGridPointList.getElements();

        //System.out.println("geoGrid.length = " + geoGrid.length);

        final double[] latList = new double[geoGrid.length];
        final double[] lonList = new double[geoGrid.length];
        final double[] incidenceAngleList = new double[geoGrid.length];
        final double[] elevAngleList = new double[geoGrid.length];
        final double[] rangeTimeList = new double[geoGrid.length];
        final int[] x = new int[geoGrid.length];
        final int[] y = new int[geoGrid.length];

        // Loop through the list of geolocation grid points, assuming that it represents a row-major rectangular grid.
        int gridWidth = 0, gridHeight = 0;
        int i = 0;
        for (MetadataElement ggPoint : geoGrid) {
            latList[i] = ggPoint.getAttributeDouble("latitude", 0);
            lonList[i] = ggPoint.getAttributeDouble("longitude", 0);
            incidenceAngleList[i] = ggPoint.getAttributeDouble("incidenceAngle", 0);
            elevAngleList[i] = ggPoint.getAttributeDouble("elevationAngle", 0);
            rangeTimeList[i] = ggPoint.getAttributeDouble("slantRangeTime", 0) * Constants.oneBillion; // s to ns

            x[i] = (int) ggPoint.getAttributeDouble("pixel", 0);
            y[i] = (int) ggPoint.getAttributeDouble("line", 0);
            if (x[i] == 0) {
                // This means we are at the start of a new line
                if (gridWidth == 0) // Here we are implicitly assuming that the pixel horizontal spacing is assumed to be the same from line to line.
                    gridWidth = i;
                ++gridHeight;
            }
            ++i;
        }

        if (crossAntimeridian(lonList)) {
            for (int j = 0; j < lonList.length; ++j) {
                if (lonList[j] < 0.0) lonList[j] += 360.0;
            }
        }

        //System.out.println("geoGrid w = " + gridWidth + "; h = " + gridHeight);

        final int newGridWidth = gridWidth;
        final int newGridHeight = gridHeight;
        final float[] newLatList = new float[newGridWidth * newGridHeight];
        final float[] newLonList = new float[newGridWidth * newGridHeight];
        final float[] newIncList = new float[newGridWidth * newGridHeight];
        final float[] newElevList = new float[newGridWidth * newGridHeight];
        final float[] newslrtList = new float[newGridWidth * newGridHeight];
        int sceneRasterWidth = product.getSceneRasterWidth();
        int sceneRasterHeight = product.getSceneRasterHeight();
        if(band != null) {
            sceneRasterWidth = band.getRasterWidth();
            sceneRasterHeight = band.getRasterHeight();
        }

        final double subSamplingX = (double) sceneRasterWidth / (newGridWidth - 1);
        final double subSamplingY = (double) sceneRasterHeight / (newGridHeight - 1);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, latList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLatList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, lonList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLonList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, incidenceAngleList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newIncList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, elevAngleList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newElevList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, rangeTimeList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newslrtList);

        TiePointGrid latGrid = product.getTiePointGrid(pre + OperatorUtils.TPG_LATITUDE);
        if (latGrid == null) {
            latGrid = new TiePointGrid(pre + OperatorUtils.TPG_LATITUDE,
                                       newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLatList);
            latGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(latGrid);
        }

        TiePointGrid lonGrid = product.getTiePointGrid(pre + OperatorUtils.TPG_LONGITUDE);
        if (lonGrid == null) {
            lonGrid = new TiePointGrid(pre + OperatorUtils.TPG_LONGITUDE,
                                       newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLonList, TiePointGrid.DISCONT_AT_180);
            lonGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(lonGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_INCIDENT_ANGLE) == null) {
            final TiePointGrid incidentAngleGrid = new TiePointGrid(pre + OperatorUtils.TPG_INCIDENT_ANGLE,
                                                                    newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newIncList);
            incidentAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(incidentAngleGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_ELEVATION_ANGLE) == null) {
            final TiePointGrid elevAngleGrid = new TiePointGrid(pre + OperatorUtils.TPG_ELEVATION_ANGLE,
                                                                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newElevList);
            elevAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(elevAngleGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_SLANT_RANGE_TIME) == null) {
            final TiePointGrid slantRangeGrid = new TiePointGrid(pre + OperatorUtils.TPG_SLANT_RANGE_TIME,
                                                                 newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newslrtList);
            slantRangeGrid.setUnit(Unit.NANOSECONDS);
            product.addTiePointGrid(slantRangeGrid);
        }

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);

        if(band != null) {
            bandGeocodingMap.put(band, tpGeoCoding);
        }
    }

    private boolean crossAntimeridian(final double[] lonList) {
        for (int i = 1; i < lonList.length; ++i) {
            if (Math.abs(lonList[i] - lonList[i - 1]) > 350.0 ) {
                return true;
            }
        }
        return false;
    }

    private static void setLatLongMetadata(Product product, TiePointGrid latGrid, TiePointGrid lonGrid) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, latGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, lonGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, latGrid.getPixelDouble(w, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, lonGrid.getPixelDouble(w, 0));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, latGrid.getPixelDouble(0, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lonGrid.getPixelDouble(0, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, latGrid.getPixelDouble(w, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lonGrid.getPixelDouble(w, h));
    }

    private boolean isTOPSAR() {
        return acqMode.equals("IW") || acqMode.equals("EW");
    }

    @Override
    protected String getProductName() {
        String name = getBaseName();
        if (name.toUpperCase().endsWith(".SAFE"))
            return name.substring(0, name.length() - 5);
        else if (name.toUpperCase().endsWith(".ZIP"))
            return name.substring(0, name.length() - 4);
        return name;
    }

    protected String getProductType() {
        return "Level-1";
    }

    public static void getListInEvenlySpacedGrid(
            final int sceneRasterWidth, final int sceneRasterHeight, final int sourceGridWidth,
            final int sourceGridHeight, final int[] x, final int[] y, final double[] sourcePointList,
            final int targetGridWidth, final int targetGridHeight, final double subSamplingX, final double subSamplingY,
            final float[] targetPointList) {

        if (sourcePointList.length != sourceGridWidth * sourceGridHeight) {
            throw new IllegalArgumentException(
                    "Original tie point array size does not match 'sourceGridWidth' x 'sourceGridHeight'");
        }

        if (targetPointList.length != targetGridWidth * targetGridHeight) {
            throw new IllegalArgumentException(
                    "Target tie point array size does not match 'targetGridWidth' x 'targetGridHeight'");
        }

        int k = 0;
        for (int r = 0; r < targetGridHeight; r++) {

            double newY = r * subSamplingY;
            if (newY > sceneRasterHeight - 1) {
                newY = sceneRasterHeight - 1;
            }
            double oldY0 = 0, oldY1 = 0;
            int j0 = 0, j1 = 0;
            for (int rr = 1; rr < sourceGridHeight; rr++) {
                j0 = rr - 1;
                j1 = rr;
                oldY0 = y[j0 * sourceGridWidth];
                oldY1 = y[j1 * sourceGridWidth];
                if (oldY1 > newY) {
                    break;
                }
            }

            final double wj = (newY - oldY0) / (oldY1 - oldY0);

            for (int c = 0; c < targetGridWidth; c++) {

                double newX = c * subSamplingX;
                if (newX > sceneRasterWidth - 1) {
                    newX = sceneRasterWidth - 1;
                }
                double oldX0 = 0, oldX1 = 0;
                int i0 = 0, i1 = 0;
                for (int cc = 1; cc < sourceGridWidth; cc++) {
                    i0 = cc - 1;
                    i1 = cc;
                    oldX0 = x[i0];
                    oldX1 = x[i1];
                    if (oldX1 > newX) {
                        break;
                    }
                }
                final double wi = (newX - oldX0) / (oldX1 - oldX0);

                targetPointList[k++] = (float) (MathUtils.interpolate2D(wi, wj,
                                                                        sourcePointList[i0 + j0 * sourceGridWidth],
                                                                        sourcePointList[i1 + j0 * sourceGridWidth],
                                                                        sourcePointList[i0 + j1 * sourceGridWidth],
                                                                        sourcePointList[i1 + j1 * sourceGridWidth]));
            }
        }
    }

    public static ProductData.UTC getTime(final MetadataElement elem, final String tag, final DateFormat sentinelDateFormat) {

        String start = elem.getAttributeString(tag, NO_METADATA_STRING);
        start = start.replace("T", "_");

        return AbstractMetadata.parseUTC(start, sentinelDateFormat);
    }

    @Override
    public Product createProduct() throws IOException {

        final MetadataElement newRoot = addMetaData();
        findImages(newRoot);

        final MetadataElement absRoot = newRoot.getElement(AbstractMetadata.ABSTRACT_METADATA_ROOT);
        determineProductDimensions(absRoot);

        final int sceneWidth = absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line);
        final int sceneHeight = absRoot.getAttributeInt(AbstractMetadata.num_output_lines);

        final Product product = new Product(getProductName(), getProductType(), sceneWidth, sceneHeight);
        updateProduct(product, newRoot);

        addBands(product);
        addGeoCoding(product);

        ReaderUtils.addMetadataIncidenceAngles(product);
        ReaderUtils.addMetadataProductSize(product);

        return product;
    }
}
