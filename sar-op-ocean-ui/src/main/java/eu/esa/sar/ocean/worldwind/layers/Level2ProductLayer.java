/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.ocean.worldwind.layers;

import com.bc.ceres.core.ProgressMonitor;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.layers.Layer;
import gov.nasa.worldwind.layers.LayerList;
import gov.nasa.worldwind.render.*;
import gov.nasa.worldwind.util.BufferFactory;
import gov.nasa.worldwind.util.BufferWrapper;
import gov.nasa.worldwind.util.WWMath;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurfaceAttributes;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.ocean.toolviews.polarview.OceanSwellTopComponent;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.worldwind.ArrowInfo;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.esa.snap.worldwind.layers.BaseLayer;
import org.esa.snap.worldwind.layers.WWLayer;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DecimalFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;

/**
 * S-1 L2 OCN visualization
 */
public class Level2ProductLayer extends BaseLayer implements WWLayer {

    private static double HUE_BLUE = 240d / 360d;
    private static final double HUE_RED = 0d / 360d;
    private static final double HUE_MAX_RED = 1.0;

    private JPanel theControlLevel2Panel;
    private boolean theOWILimitChanged = false;

    private boolean theRVLLimitChanged = false;

    private boolean theOWIArrowsDisplayed = false;

    private static final double GLOBE_RADIUS = 6371000;

    // this is the dimension of the cell in which to draw an arrow
    // at the highest resolution
    private static final int theOWIArrowCellSize = 4;

    // the number of resolutions for OWI arrows
    private static final int theOWIArrowNumLevels = 5;

    private JCheckBox theArrowsCB;

    //public double theCurrMinHue;
    //public double theCurrMaxHue;

    //private AnalyticSurface analyticSurface = null;
    //private BufferWrapper analyticSurfaceValueBuffer = null;

    private final HashMap<String, ColorBarLegend> theColorBarLegendHash = new HashMap<>();

    // product associated with the current colorBar legend
    //private Product theColorBarLegendProduct = null;
    private String theSelectedComp = null;

    private final HashMap<Object, String> theObjectInfoHash = new HashMap<>();
    private final HashMap<Object, Product> theSurfaceProductHash = new HashMap<>();
    private final HashMap<Object, Integer> theSurfaceSequenceHash = new HashMap<>();

    private final HashMap<Product, ProductRenderablesInfo> theProductRenderablesInfoHash = new HashMap<>();

    private final ScreenAnnotation theInfoAnnotation;

    private DirectedPath theLastSelectedDP = null;

    // this is set every time a product is added
    // because we can't added it in constructor as it is not called explicitly
    // and removeProduct needs it for redrawNow
    // (removeProduct can't be modified either to accept a wwd parameter)
    private WorldWindowGLCanvas theWWD;

    public Level2ProductLayer() {
        this.setName("S-1 Level-2 OCN");
        theWWD = null;
        //dpHighlightAttrs = new BasicShapeAttributes();
        //dpHighlightAttrs.setOutlineMaterial(Material.WHITE);
        //dpHighlightAttrs.setOutlineWidth(2d);

        // this is copied from gov.nasa.worldwindx.examples.util.LayerManagerLayer
        theInfoAnnotation = new ScreenAnnotation("", new Point(120, 520));

        // Set annotation so that it will not force text to wrap (large width) and will adjust it's width to
        // that of the text. A height of zero will have the annotation height follow that of the text too.
        theInfoAnnotation.getAttributes().setSize(new Dimension(Integer.MAX_VALUE, 0));
        theInfoAnnotation.getAttributes().setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);

        // Set appearance attributes
        theInfoAnnotation.getAttributes().setCornerRadius(0);
        //theInfoAnnotation.getAttributes().setFont(this.font);
        theInfoAnnotation.getAttributes().setHighlightScale(1);
        theInfoAnnotation.getAttributes().setTextColor(Color.WHITE);
        theInfoAnnotation.getAttributes().setBackgroundColor(new Color(0f, 0f, 0f, .5f));
        theInfoAnnotation.getAttributes().setInsets(new Insets(6, 6, 6, 6));
        theInfoAnnotation.getAttributes().setBorderWidth(1);

        theInfoAnnotation.getAttributes().setVisible(false);
    }

    @Override
    public void updateInfoAnnotation(final SelectEvent event) {
        //SystemUtils.LOG.info("updateInfoAnnotation " + event.getTopObject() + " " + theObjectInfoHash.get(event.getTopObject()));
        if (event.getEventAction().equals(SelectEvent.ROLLOVER) && theObjectInfoHash.get(event.getTopObject()) != null) {

            String info = theObjectInfoHash.get(event.getTopObject());
            if (event.getTopObject() instanceof DirectedPath) {
                //SystemUtils.LOG.info("DirectedPath:::");
                DirectedPath dp = (DirectedPath) event.getTopObject();
                //dp.getAttributes().setOutlineMaterial(Material.WHITE);
                dp.setHighlighted(true);
                //dp.setAttributes(productLayer.dpHighlightAttrs);
                //theSelectedObjectLabel.setText("" + productLayer.theObjectInfoHash.get(dp));
                theLastSelectedDP = dp;
            }


            theInfoAnnotation.setText(info);
            theInfoAnnotation.getAttributes().setVisible(true);

            //SystemUtils.LOG.info("selectedProduct " + getSelectedProduct());
            //final ExecCommand command = datApp.getCommandManager().getExecCommand("showPolarWaveView");
            //command.execute(2);
        } else if (event.getEventAction().equals(SelectEvent.LEFT_CLICK) && theSurfaceProductHash.get(event.getTopObject()) != null && theSurfaceSequenceHash.get(event.getTopObject()) != null) {
            //SystemUtils.LOG.info("click " + event.getTopObject());
            OceanSwellTopComponent.setOSWRecord(theSurfaceProductHash.get(event.getTopObject()), theSurfaceSequenceHash.get(event.getTopObject()));

        } else {

            if (theLastSelectedDP != null) {
                theLastSelectedDP.setHighlighted(false);
            }
            theInfoAnnotation.getAttributes().setVisible(false);
            //theSelectedObjectLabel.setText("");
        }
    }

    @Override
    public Suitability getSuitability(Product product) {
        if (product.getProductType().equalsIgnoreCase("OCN")) {
            return Suitability.INTENDED;
        }
        return Suitability.UNSUITABLE;
    }

    @Override
    public void addProduct(final Product product, final WorldWindowGLCanvas wwd) {

        if (!product.getProductType().equalsIgnoreCase("OCN")) {
            return;
        }

        // if the product has already been added, just return
        if (theProductRenderablesInfoHash.get(product) != null) {
            return;
        }

        this.theWWD = wwd;
        addRenderable(theInfoAnnotation);

        LayerList layerList = theWWD.getModel().getLayers();
        Layer layer = layerList.getLayerByName("Stars");
        if(layer != null) {
            layerList.remove(layer);
        }
        layer = layerList.getLayerByName("Products");
        if(layer != null) {
            layerList.remove(layer);
        }

        final String text = "First line<br />Second line";
        theInfoAnnotation.setText(text);
        theInfoAnnotation.getAttributes().setVisible(false);

        //theColorBarLegendProduct = product;
        final ProductRenderablesInfo productRenderablesInfo = new ProductRenderablesInfo();
        // There is code in LayerManagerLayer that updates the size
        //  it's re-rendered
        // Update current size and adjust annotation draw offset according to it's width
        //this.size = theInfoAnnotation.getPreferredSize(dc);
        //this.annotation.getAttributes().setDrawOffset(new Point(this.size.width / 2, 0));

        //SystemUtils.LOG.info("product " + product.getName());

        final Band firstBand = product.getBandAt(0);
        final String firstBandName = firstBand.getName().toLowerCase();
        final String prefix = firstBandName.startsWith("vv") ? "vv" : "hh";

        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        final Band owiLonBand = product.getBand(prefix + "_001_owiLon");
        final Band owiLatBand = product.getBand(prefix + "_001_owiLat");
        final Band owiIncAngleBand = product.getBand(prefix + "_001_owiIncidenceAngle");
        final Band owiWindSpeedBand = product.getBand(prefix + "_001_owiWindSpeed");
        final Band owiWindDirBand = product.getBand(prefix + "_001_owiWindDirection");

        int numRVLElements = 0;
        int rvlSwathWidth = 0;
        int rvlSwathHeight = 0;
        int numSwaths = 0;

        if (acquisitionMode.equalsIgnoreCase("IW")) {
            numSwaths = 3;
        } else if (acquisitionMode.equalsIgnoreCase("EW")) {
            numSwaths = 5;
        }
        for (int i = 0; i < numSwaths; i++) {
            Band rvlLonBand = product.getBand(prefix + "_001_" + acquisitionMode.toUpperCase() + (i + 1) + "_rvlLon");

            numRVLElements += (rvlLonBand.getRasterWidth() * rvlLonBand.getRasterHeight());
            rvlSwathWidth = rvlLonBand.getRasterWidth();
            rvlSwathHeight = rvlLonBand.getRasterHeight();
        }

        final GeoPos geoPos1 = product.getSceneGeoCoding().getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPos2 = product.getSceneGeoCoding().getGeoPos(new PixelPos(product.getSceneRasterWidth() - 1,
                product.getSceneRasterHeight() - 1), null);

        try {
            if (owiLonBand != null) {
                final double[] lonValues = new double[owiLonBand.getRasterWidth() * owiLonBand.getRasterHeight()];
                owiLonBand.readPixels(0, 0, owiLonBand.getRasterWidth(), owiLonBand.getRasterHeight(), lonValues, ProgressMonitor.NULL);

                final double[] latValues = new double[owiLatBand.getRasterWidth() * owiLatBand.getRasterHeight()];
                owiLatBand.readPixels(0, 0, owiLatBand.getRasterWidth(), owiLatBand.getRasterHeight(), latValues, ProgressMonitor.NULL);

                final double[] incAngleValues = new double[owiIncAngleBand.getRasterWidth() * owiIncAngleBand.getRasterHeight()];
                owiIncAngleBand.readPixels(0, 0, owiIncAngleBand.getRasterWidth(), owiIncAngleBand.getRasterHeight(), incAngleValues, ProgressMonitor.NULL);

                final double[] windSpeedValues = new double[owiWindSpeedBand.getRasterWidth() * owiWindSpeedBand.getRasterHeight()];
                owiWindSpeedBand.readPixels(0, 0, owiWindSpeedBand.getRasterWidth(), owiWindSpeedBand.getRasterHeight(), windSpeedValues, ProgressMonitor.NULL);

                final double[] windDirValues = new double[owiWindDirBand.getRasterWidth() * owiWindDirBand.getRasterHeight()];
                owiWindDirBand.readPixels(0, 0, owiWindDirBand.getRasterWidth(), owiWindDirBand.getRasterHeight(), windDirValues, ProgressMonitor.NULL);

                addWindSpeedArrows(latValues, lonValues, incAngleValues, windSpeedValues, windDirValues,
                        owiLonBand.getRasterWidth(), owiLonBand.getRasterHeight(), productRenderablesInfo.theRenderableListHash.get("owi"));

                createColorSurfaceWithGradient(geoPos1, geoPos2, latValues, lonValues, windSpeedValues,
                        owiWindSpeedBand.getRasterWidth(), owiWindSpeedBand.getRasterHeight(), 0, 10, false, productRenderablesInfo.theRenderableListHash.get("owi"), productRenderablesInfo, "owi");
            }

            // right now, this happens: InvalidRangeException when reading variable rvlLon
            if (numRVLElements > 0) {

                boolean displayAsOne = false;

                // with numRVLElements we get BufferOverflow exception
                // it works with 2 swaths: 2*125 *233
                double[] rvlLonValues = null;
                double[] rvlLatValues = null;
                double[] rvlRadVelValues = null;

                if (displayAsOne) {
                    rvlLonValues = new double[numRVLElements];
                    rvlLatValues = new double[numRVLElements];
                    rvlRadVelValues = new double[numRVLElements];
                }

                for (int i = 0; i < numSwaths; i++) {

                    Band currRVLLonBand = product.getBand(prefix + "_001_" + acquisitionMode.toUpperCase() + (i + 1) + "_rvlLon");
                    Band currRVLLatBand = product.getBand(prefix + "_001_" + acquisitionMode.toUpperCase() + (i + 1) + "_rvlLat");
                    Band currRVLRadVelBand = product.getBand(prefix + "_001_" + acquisitionMode.toUpperCase() + (i + 1) + "_rvlRadVel");

                    int w = rvlSwathWidth;
                    int h = rvlSwathHeight;

                    double[] currRVLLonValues = new double[w];
                    currRVLLonBand.readPixels(0, 0, w, 1, currRVLLonValues, ProgressMonitor.NULL);

                    // find no data value edge
                    for(int cnt=w-1; cnt>0; --cnt) {
                        if(currRVLLonValues[cnt] != currRVLLatBand.getNoDataValue()) {
                            w = cnt;
                            break;
                        }
                    }

                    currRVLLonValues = new double[h];
                    currRVLLonBand.readPixels(0, 0, 1, h, currRVLLonValues, ProgressMonitor.NULL);

                    // find no data value edge
                    for(int cnt=h-1; cnt>0; --cnt) {
                        if(currRVLLonValues[cnt] != currRVLLatBand.getNoDataValue()) {
                            h = cnt;
                            break;
                        }
                    }

                    currRVLLonValues = new double[w*h];
                    currRVLLonBand.readPixels(0, 0, w, h, currRVLLonValues, ProgressMonitor.NULL);

                    double[] currRVLLatValues = new double[w*h];
                    currRVLLatBand.readPixels(0, 0, w, h, currRVLLatValues, ProgressMonitor.NULL);

                    double[] currRVLRadVelValues = new double[w*h];
                    currRVLRadVelBand.readPixels(0, 0, w, h, currRVLRadVelValues, ProgressMonitor.NULL);

                    if (displayAsOne) {
                        System.arraycopy(currRVLLonValues, 0, rvlLonValues, i * w*h, w*h);
                        System.arraycopy(currRVLLatValues, 0, rvlLatValues, i * w*h, w*h);
                        System.arraycopy(currRVLRadVelValues, 0, rvlRadVelValues, i * w*h, w*h);
                    } else {
                        createColorSurfaceWithGradient(geoPos1, geoPos2, currRVLLatValues, currRVLLonValues, currRVLRadVelValues, w, h,
                                -6, 5, true, productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
                    }
                }

                if (displayAsOne) {
                    createColorSurfaceWithGradient(geoPos1, geoPos2, rvlLatValues, rvlLonValues, rvlRadVelValues, numSwaths * rvlSwathWidth, rvlSwathHeight, -6, 5, true, productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
                }
            }


            double[][] oswData = null;
            double[][] owiData = null;
            double[][] rvlData = null;

            if (acquisitionMode.equalsIgnoreCase("WV") && metadataRoot.getElement("Original_Product_Metadata") != null && metadataRoot.getElement("Original_Product_Metadata").getElement("annotation") != null) {
                int numElements = metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getNumElements();
                if (numElements > 0) {
                    oswData = new double[5][numElements];
                    owiData = new double[3][numElements];
                    rvlData = new double[3][numElements];

                    int i = 0;
                    for (MetadataElement element : metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getElements()) {
                        oswData[0][i] = getData(element.getElement("oswLat"));
                        oswData[1][i] = getData(element.getElement("oswLon"));
                        oswData[2][i] = getData(element.getElement("oswHs"));
                        oswData[3][i] = getData(element.getElement("oswWl"));
                        oswData[4][i] = getData(element.getElement("oswDirmet"));

                        owiData[0][i] = getData(element.getElement("owiLat"));
                        owiData[1][i] = getData(element.getElement("owiLon"));
                        owiData[2][i] = getData(element.getElement("owiWindSpeed"));

                        rvlData[0][i] = getData(element.getElement("rvlLat"));
                        rvlData[1][i] = getData(element.getElement("rvlLon"));
                        rvlData[2][i] = getData(element.getElement("rvlRadVel"));

                        i++;
                    }
                }
            }

            if (oswData != null) {
                addWaveLengthArrows(oswData[0], oswData[1], oswData[3], oswData[4], productRenderablesInfo.theRenderableListHash.get("osw"));
                createWVColorSurfaceWithGradient(product, oswData[0], oswData[1], oswData[2], productRenderablesInfo.theRenderableListHash.get("osw"), "osw");
            }

            //owiLonBand
            if (owiData != null && owiLonBand == null) {
                //addWaveLengthArrows(owiData[0], owiData[1], owiData[3], owiData[4], productRenderablesInfo.theRenderableListHash.get("owi"));
                createWVColorSurfaceWithGradient(product, owiData[0], owiData[1], owiData[2], productRenderablesInfo.theRenderableListHash.get("owi"), "owi");
            }

            if (rvlData != null && numRVLElements == 0) {
                createWVColorSurfaceWithGradient(product, rvlData[0], rvlData[1], rvlData[2], productRenderablesInfo.theRenderableListHash.get("rvl"), "rvl");
            }

            theProductRenderablesInfoHash.put(product, productRenderablesInfo);
            if (theControlLevel2Panel != null) {
                theControlLevel2Panel.setVisible(true);
            }
            setComponentVisible(theSelectedComp, wwd);

        } catch (Exception e) {
            SnapApp.getDefault().handleError("L2ProductLayer unable to add product " + product.getName(), e);
        }
    }

    private double getData(MetadataElement element) {
        if (element.getElement("Values") != null) {
            return element.getElement("Values").getAttribute("data").getData().getElemDouble();
        }
        return 0;
    }

    private void createColorSurfaceWithGradient(GeoPos geoPos1, GeoPos geoPos2, double[] latValues,
                                                double[] lonValues, double[] values, int width, int height,
                                                double minValue, double maxValue, boolean whiteZero, ArrayList<Renderable> renderableList, ProductRenderablesInfo prodRenderInfo, String comp) {
        createColorSurface(geoPos1, geoPos2, latValues, lonValues, values, width, height,
                renderableList, prodRenderInfo, comp);

        // don't create color legend if one already exists
        if (theColorBarLegendHash.get(comp) != null) {
            // use the existing limits
            minValue = theColorBarLegendHash.get(comp).getMinValue();
            maxValue = theColorBarLegendHash.get(comp).getMaxValue();
        }

        createColorGradient(minValue, maxValue, whiteZero, prodRenderInfo, comp);
    }

    private void createColorBarLegend(double minValue, double maxValue, String title, String comp) {
        //SystemUtils.LOG.info("createColorBarLegend " + minValue + " " + maxValue);

        String unit = "m/s";
        if (comp.equalsIgnoreCase("osw")) {
            unit = "m";
        }
        final Format legendLabelFormat = new DecimalFormat("# " + unit);

        final ColorBarLegend colorBarLegend = new ColorBarLegend();
        colorBarLegend.setColorGradient(32, 256, minValue, maxValue, HUE_RED, HUE_MAX_RED,
                Color.WHITE,
                ColorBarLegend.createDefaultColorGradientLabels(minValue, maxValue, legendLabelFormat),
                ColorBarLegend.createDefaultTitle(title),
                comp.equalsIgnoreCase("rvl"));

        /*
        colorBarLegend = ColorBarLegend.fromColorGradient(32, 256, minValue, maxValue, minHue, maxHue,
                Color.WHITE,
                ColorBarLegend.createDefaultColorGradientLabels(minValue, maxValue, legendLabelFormat),
                ColorBarLegend.createDefaultTitle(title),
                whiteZero);
                */
        colorBarLegend.setOpacity(0.8);
        colorBarLegend.setScreenLocation(new Point(900, 320));
        //addRenderable(colorBarLegend);

        theColorBarLegendHash.put(comp, colorBarLegend);
    }


    private void setComponentVisible(String comp, WorldWindowGLCanvas wwd) {
        //SystemUtils.LOG.info("setComponentVisible " + comp);
        //SystemUtils.LOG.info("theColorBarLegendHash " + theColorBarLegendHash);
        for (String currComp : theColorBarLegendHash.keySet()) {
            if (theColorBarLegendHash.get(currComp) != null) {
                removeRenderable(theColorBarLegendHash.get(currComp));
                if (currComp.equals(comp)) {
                    addRenderable(theColorBarLegendHash.get(currComp));
                }

                //ProductRenderablesInfo productRenderablesInfo = theProductRenderablesInfoHash.get(theColorBarLegendProduct);
                for (ProductRenderablesInfo productRenderablesInfo : theProductRenderablesInfoHash.values()) {
                    //SystemUtils.LOG.info("::: productRenderablesInfo " + productRenderablesInfo);
                    if (productRenderablesInfo != null) {
                        ArrayList<Renderable> renderableList = productRenderablesInfo.theRenderableListHash.get(currComp);
                        for (Renderable renderable : renderableList) {
                            removeRenderable(renderable);
                            if (currComp.equals(comp)) {

                                addRenderable(renderable);
                            }
                        }
                    }
                }
            }
        }

        wwd.redrawNow();
    }

    private void addWindSpeedArrows(double[] latValues,
                                    double[] lonValues,
                                    double[] incAngleValues,
                                    double[] windSpeedValues,
                                    double[] windDirValues,
                                    int width,
                                    int height,
                                    ArrayList<Renderable> renderableList) {
        double pixelWidth = Math.abs(lonValues[0] - lonValues[lonValues.length - 1]) / width;
        double pixelHeight = Math.abs(latValues[0] - latValues[latValues.length - 1]) / height;

        //SystemUtils.LOG.info("pixelWidth " + pixelWidth + " pixelHeight " + pixelHeight);

        //System.out.println("pixelWidth " + pixelWidth + " pixelHeight " + pixelHeight);

        // take the smaller dimension
        double arrowLength_deg = pixelWidth;
        if (pixelHeight < pixelWidth) {
            arrowLength_deg = pixelHeight;
        }

        arrowLength_deg = arrowLength_deg * theOWIArrowCellSize;
        // let the arrow head be approximately one third of the whole length
        double arrowHeadLength = Angle.fromDegrees(arrowLength_deg).radians * GLOBE_RADIUS / 3;

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.BLACK);
        dpAttrs.setOutlineWidth(2d);


        //int numCellRows = (int) Math.ceil((height / cellSize));
        //int numCellCols = (int) Math.ceil((width / cellSize));

        int numCellRows = height / theOWIArrowCellSize;
        int numCellCols = width / theOWIArrowCellSize;
        //SystemUtils.LOG.info(":: numCells: " + numCellRows + " " + numCellCols);

        // we need to add 1 because if height is not divisible by cellSize then (height / cellSize) is equal
        // to (height-1) / cellSize so the last element is [numCellRows]
        // (this same argument applies to width)
        // Still, we'll keep numCellRows and numCellCols as limits when we iterate
        // through it and disregard this possible last element (which is the remainder, in the corners of the whole area)
        ArrowInfo[][][] arrowGrid = new ArrowInfo[theOWIArrowNumLevels][numCellRows + 1][numCellCols + 1];

        for (int row = 0; row < height; row = row + theOWIArrowCellSize) {
            for (int col = 0; col < width; col = col + theOWIArrowCellSize) {
                //int i = row*width + col;
                int globalInd = row * width + col;
                float avgLat = 0;
                float avgLon = 0;
                double avgIncAngle = 0;
                double avgWindSpeed = 0;
                double avgWindDir = 0;
                int finalCellRow = row + theOWIArrowCellSize;
                int finalCellCol = col + theOWIArrowCellSize;

                if (finalCellRow > height) {
                    finalCellRow = height;
                }
                if (finalCellCol > width) {
                    finalCellCol = width;
                }
                for (int currCellRow = row; currCellRow < finalCellRow; currCellRow++) {
                    for (int currCellCol = col; currCellCol < finalCellCol; currCellCol++) {
                        int i = currCellRow * width + currCellCol;
                        avgLat += latValues[i];
                        avgLon += lonValues[i];
                        avgIncAngle += incAngleValues[i];
                        avgWindSpeed += windSpeedValues[i];
                        avgWindDir += windDirValues[i];
                    }
                }

                avgLat = avgLat / ((finalCellRow - row) * (finalCellCol - col));
                avgLon = avgLon / ((finalCellRow - row) * (finalCellCol - col));
                avgIncAngle = avgIncAngle / ((finalCellRow - row) * (finalCellCol - col));
                avgWindSpeed = avgWindSpeed / ((finalCellRow - row) * (finalCellCol - col));
                avgWindDir = avgWindDir / ((finalCellRow - row) * (finalCellCol - col));

                           /*
                           avgLat = latValues[globalInd];
                           avgLon = lonValues[globalInd];
                           avgWindDir = windDirValues[globalInd];
                            */
                            /*
                           System.out.println("avgLat " + avgLat);
                           System.out.println("avgLon " + avgLon);
                           System.out.println("avgWindDir " + avgWindDir);
                            */
                //System.out.println("avgIncAngle " + avgIncAngle);
                //for (int i = 0; i < latValues.length; i=i+50) {
                //System.out.println(lonValues[i] + "::==::" + latValues[i] + "::==::" + incAngleValues[i] + "::==::" + windSpeedValues[i] + "::==::" + windDirValues[i] + "::==::");
                final Position startPos = new Position(Angle.fromDegreesLatitude(avgLat), Angle.fromDegreesLongitude(avgLon), 10.0);
                final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(avgWindDir), Angle.fromDegrees(arrowLength_deg)), 10.0);

                //System.out.println("startPos " + startPos + " endPos " + endPos);

                final ArrayList<Position> positions = new ArrayList<>();
                positions.add(startPos);
                positions.add(endPos);

                final DirectedPath directedPath = getDirectedPath(positions, dpAttrs);

                //double arrowHeadLength = computeSegmentLength(directedPath, dc, startPos, endPos) / 4;
                directedPath.setArrowLength(arrowHeadLength);
                int currCellRow = row / theOWIArrowCellSize;
                int currCellCol = col / theOWIArrowCellSize;
                //SystemUtils.LOG.info(":: currCell: " + currCellRow + " " + currCellCol);
                arrowGrid[0][currCellRow][currCellCol] = new ArrowInfo(directedPath, avgIncAngle, avgWindSpeed, avgWindDir, arrowLength_deg);


                //if (currCellRow > 0 && currCellCol > 0) {
                for (int cellSizeResolution = 1; cellSizeResolution < theOWIArrowNumLevels; cellSizeResolution++) {
                    // treating the original cell size as 1
                    int currBigCellSize = (int) FastMath.pow(2, cellSizeResolution);
                    if ((currCellRow % currBigCellSize == currBigCellSize - 1) && (currCellCol % currBigCellSize == currBigCellSize - 1)) {
                        int bigCellRow = (currCellRow / currBigCellSize);
                        int bigCellCol = (currCellCol / currBigCellSize);

                        int smallCellStartRow = bigCellRow * 2;
                        int smallCellStartCol = bigCellCol * 2;

                        double cumAvgIncAngle = 0;
                        double cumAvgWindSpeed = 0;
                        double cumAvgWindDir = 0;
                        Position cumStartPos = new Position(Angle.fromDegreesLatitude(0.0), Angle.fromDegreesLongitude(0.0), 10.0);
                        Position cumEndPos = new Position(Angle.fromDegreesLatitude(0.0), Angle.fromDegreesLongitude(0.0), 10.0);
                        double cumStartPosLat_deg = 0;
                        double cumStartPosLon_deg = 0;
                        double bigCellArrowLength_deg = currBigCellSize * arrowLength_deg;
                        for (int currSmallCellRow = smallCellStartRow; currSmallCellRow < smallCellStartRow + 2; currSmallCellRow++) {
                            for (int currSmallCellCol = smallCellStartCol; currSmallCellCol < smallCellStartCol + 2; currSmallCellCol++) {
                                ArrowInfo currSmallArrow = arrowGrid[cellSizeResolution - 1][currSmallCellRow][currSmallCellCol];
                                // all small cell's arrow length's will be the same
                                //bigCellArrowLength_deg = 2*currSmallArrow.theArrowLength;
                                cumAvgIncAngle += currSmallArrow.theAvgIncAngle;
                                cumAvgWindSpeed += currSmallArrow.theAvgWindSpeed;
                                cumAvgWindDir += currSmallArrow.theAvgWindDir;
                                boolean firstPosNext = true;
                                for (Position pos : currSmallArrow.theDirectedPath.getPositions()) {
                                    if (firstPosNext) {
                                        cumStartPos = cumStartPos.add(pos);
                                        cumStartPosLat_deg += pos.getLatitude().getDegrees();
                                        cumStartPosLon_deg += pos.getLongitude().getDegrees();
                                        firstPosNext = false;
                                    } else {
                                        cumEndPos = cumEndPos.add(pos);
                                    }
                                }
                            }
                        }
                        cumAvgIncAngle = cumAvgIncAngle / 4;
                        cumAvgWindSpeed = cumAvgWindSpeed / 4;
                        cumAvgWindDir = cumAvgWindDir / 4;
                        cumStartPosLat_deg = cumStartPosLat_deg / 4;
                        cumStartPosLon_deg = cumStartPosLon_deg / 4;

                        arrowGrid[cellSizeResolution][bigCellRow][bigCellCol] = null;


                        Position bigCellStartPos = new Position(Angle.fromDegreesLatitude(cumStartPosLat_deg), Angle.fromDegreesLongitude(cumStartPosLon_deg), 10.0);
                        Position bigCellEndPos = new Position(LatLon.greatCircleEndPosition(bigCellStartPos, Angle.fromDegrees(cumAvgWindDir), Angle.fromDegrees(bigCellArrowLength_deg)), 10.0);

                        //System.out.println("startPos " + startPos + " endPos " + endPos);
                        ArrayList<Position> bigCellPositions = new ArrayList<>();
                        bigCellPositions.add(bigCellStartPos);
                        bigCellPositions.add(bigCellEndPos);

                        DirectedPath bigDC = getDirectedPath(bigCellPositions, dpAttrs);
                        bigDC.setArrowLength(currBigCellSize * arrowHeadLength);
                        arrowGrid[cellSizeResolution][bigCellRow][bigCellCol] = new ArrowInfo(bigDC, cumAvgIncAngle, cumAvgWindSpeed, cumAvgWindDir, bigCellArrowLength_deg);

                    }

                }
                //}

            }
        }
        for (int cellRow = 0; cellRow < numCellRows; cellRow++) {
            for (int cellCol = 0; cellCol < numCellCols; cellCol++) {

                final int finalCellRow = cellRow;
                final int finalCellCol = cellCol;

                //DirectedPath directedPath = arrowGrid[0][cellRow][cellCol].theDirectedPath;
                Renderable renderable = new Renderable() {
                    public void render(DrawContext dc) {
                        if (!theOWIArrowsDisplayed) {
                            return;
                        }

                        // this is the length of the arrow head actually
                        //double arrowHeadLength = computeSegmentLength(directedPath, dc, startPos, endPos) / 4;
                        //directedPath.setArrowLength(arrowHeadLength);

                        //double maxHeight = cellSize * 0.5e6 / 16;

                        double currAlt = dc.getView().getCurrentEyePosition().getAltitude();
                        /*
                        int selectedResolutionInd = 0;
                        for (int resolutionInd = 0; resolutionInd < theOWIArrowNumLevels; resolutionInd++) {
                            double maxResAlt = (0.5e6 / 4) * Math.pow(2,resolutionInd);
                            double minResAlt = maxResAlt / 2;
                            if (currAlt > minResAlt && currAlt < maxResAlt) {
                                selectedResolutionInd = resolutionInd;
                                break;
                            }
                        }
                        */
                        int selectedResolutionInd = (int) (Math.log(currAlt * (4 / 0.5e6)) / Math.log(2));
                        if (selectedResolutionInd < 0) {
                            selectedResolutionInd = 0;
                        } else if (selectedResolutionInd > 4) {
                            selectedResolutionInd = 4;
                        }
                        int selectedInd = (int) FastMath.pow(2, selectedResolutionInd);


                        //int selectedInd = (int) (currAlt * (4 / 0.5e6));
                        if ((finalCellRow % selectedInd == 0) && (finalCellCol % selectedInd == 0)) {
                            int bigCellRow = finalCellRow / selectedInd;
                            int bigCellCol = finalCellCol / selectedInd;

                            // this check is necessary because the possible last element which we disregarded and which is null

                            if (arrowGrid[selectedResolutionInd][bigCellRow] != null && arrowGrid[selectedResolutionInd][bigCellRow][bigCellCol] != null) {

                                ArrowInfo currArrow = arrowGrid[selectedResolutionInd][bigCellRow][bigCellCol];

                                // we won't render the arrow if the wind speed is zero
                                if (currArrow.theAvgWindSpeed > 0) {
                                    DirectedPath currDirectedPath = currArrow.theDirectedPath;
                                    currDirectedPath.render(dc);


                                    if (theObjectInfoHash.get(currDirectedPath) == null) {
                                        String info = "Wind Speed: " + currArrow.theAvgWindSpeed + "<br/>";
                                        info += "Wind Direction: " + currArrow.theAvgWindDir + "<br/>";
                                        info += "Incidence Angle: " + currArrow.theAvgIncAngle + "<br/>";
                                        theObjectInfoHash.put(currDirectedPath, info);
                                    }
                                }
                            }
                        }

                        /*
                        if (currAlt > minHeight && currAlt < maxHeight) {
                            directedPath.render(dc);
                            //System.out.println("arrowHeadLength " + arrowHeadLength);
                        }
                        */

                        //System.out.println("eyePosition " + dc.getView().getCurrentEyePosition());
                    }
                };

                addRenderable(renderable);
                if (renderableList != null) {
                    renderableList.add(renderable);
                }

            }
        }
    }

    private DirectedPath getDirectedPath(ArrayList<Position> positions, ShapeAttributes dpAttrs) {
        DirectedPath directedPath = new DirectedPath(positions);
        directedPath.setAttributes(dpAttrs);
        //directedPath.setHighlightAttributes(highlightAttrs);
        directedPath.setVisible(true);
        directedPath.setFollowTerrain(true);
        directedPath.setAltitudeMode(WorldWind.RELATIVE_TO_GROUND);
        directedPath.setPathType(AVKey.GREAT_CIRCLE);
        return directedPath;
    }

    private void addWaveLengthArrows(double[] latValues,
                                     double[] lonValues,
                                     double[] waveLengthValues,
                                     double[] waveDirValues,
                                     ArrayList<Renderable> renderableList) {
        //SystemUtils.LOG.info(":: addWaveLengthArrows ");

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);

        for (int ind = 0; ind < waveLengthValues.length; ind++) {
            //int ind = row*width + col;
            double arrowLength_deg = waveLengthValues[ind] / 4000;

            double arrowHeadLength = Angle.fromDegrees(arrowLength_deg).radians * GLOBE_RADIUS / 3;

            final Position startPos = new Position(Angle.fromDegreesLatitude(latValues[ind]), Angle.fromDegreesLongitude(lonValues[ind]), 10.0);
            final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(waveDirValues[ind]), Angle.fromDegrees(arrowLength_deg)), 10.0);

            //System.out.println("waveLengthValues[i] " + waveLengthValues[i]);

            final ArrayList<Position> positions = new ArrayList<>();
            positions.add(startPos);
            positions.add(endPos);

            DirectedPath directedPath = getDirectedPath(positions, dpAttrs);
            directedPath.setArrowLength(arrowHeadLength);

            Renderable renderable = new Renderable() {
                public void render(DrawContext dc) {
                    directedPath.render(dc);
                }
            };

            addRenderable(renderable);
            if (renderableList != null) {
                renderableList.add(renderable);
            }
        }
    }

    private void createWVColorSurfaceWithGradient(Product product,
                                                  double[] latValues,
                                                  double[] lonValues,
                                                  double[] values,
                                                  ArrayList<Renderable> renderableList,
                                                  String comp) {
        //SystemUtils.LOG.info(":: createWVColorSurfaceWithGradient ");

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);
        // we cannot make it a scalar object because it has to be final and then we won't be able to assign to it
        // we'll store it as a first object of a final array
        //final ArrayList<Object> ctgSurfaceList = new ArrayList<Object>();

        for (int ind = 0; ind < values.length; ind++) {
            final int finalInd = ind;
            final ArrayList<Position> polygonPositions = new ArrayList<>();
            double vignette_half_side_deg = (180 / Math.PI) * 10000 / GLOBE_RADIUS;

            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] + vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] + vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] + vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] + vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));

            Path p = createPath(polygonPositions, WHITE_MATERIAL, RED_MATERIAL);

            addRenderable(p);
            if (renderableList != null) {
                renderableList.add(p);
            }

            String info = "";
            if (comp.equalsIgnoreCase("osw")) {
                info = "Wave Length: " + values[ind] + "<br/>";
            } else if (comp.equalsIgnoreCase("owi")) {
                info = "Wind Speed: " + values[ind] + "<br/>";
            } else if (comp.equalsIgnoreCase("rvl")) {
                info = "Radial Velocity: " + values[ind] + "<br/>";
            }
            String finalInfo = info;

            //AnalyticSurface analyticSurface = new AnalyticSurface();
            AnalyticSurface analyticSurface = new AnalyticSurface() {
                public void render(DrawContext dc) {
                    super.render(dc);
                    if (clampToGroundSurface != null) {
                        theObjectInfoHash.put(clampToGroundSurface, finalInfo);
                        theSurfaceProductHash.put(clampToGroundSurface, product);
                        theSurfaceSequenceHash.put(clampToGroundSurface, finalInd);
                    }
                }
            };

            analyticSurface.setSector(Sector.fromDegrees(latValues[ind] - vignette_half_side_deg, latValues[ind] + vignette_half_side_deg, lonValues[ind] - vignette_half_side_deg, lonValues[ind] + vignette_half_side_deg));
            analyticSurface.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            // one by one square doesn't seem to work, so we'll just use the next smallest
            // possible square and repeat the same value 4 times
            analyticSurface.setDimensions(2, 2);

            final ArrayList<AnalyticSurface.GridPointAttributes> attributesList = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                attributesList.add(createColorGradientAttributes(values[ind], 0, 10, HUE_RED, HUE_MAX_RED, false));
            }

            analyticSurface.setValues(attributesList);

            AnalyticSurfaceAttributes attr = new AnalyticSurfaceAttributes();
            attr.setDrawShadow(false);
            attr.setInteriorOpacity(1.0);
            //attr.setOutlineWidth(3);
            attr.setDrawOutline(false);
            analyticSurface.setSurfaceAttributes(attr);
            analyticSurface.setClientLayer(this);

            addRenderable(analyticSurface);
            if (renderableList != null) {
                renderableList.add(analyticSurface);
            }
        }
    }

    private double computeSegmentLength(Path path, DrawContext dc, Position posA, Position posB) {
        final LatLon llA = new LatLon(posA.getLatitude(), posA.getLongitude());
        final LatLon llB = new LatLon(posB.getLatitude(), posB.getLongitude());

        Angle ang;
        String pathType = path.getPathType();
        if (Objects.equals(pathType, AVKey.LINEAR)) {
            ang = LatLon.linearDistance(llA, llB);
        } else if (Objects.equals(pathType, AVKey.RHUMB_LINE) || Objects.equals(pathType, AVKey.LOXODROME)) {
            ang = LatLon.rhumbDistance(llA, llB);
        } else { // Great circle
            ang = LatLon.greatCircleDistance(llA, llB);
        }

        if (path.getAltitudeMode() == WorldWind.CLAMP_TO_GROUND) {
            return ang.radians * (dc.getGlobe().getRadius());
        }

        final double height = 0.5 * (posA.getElevation() + posB.getElevation());
        return ang.radians * (dc.getGlobe().getRadius() + height * dc.getVerticalExaggeration());
    }

    private void createColorSurface(GeoPos geoPos1, GeoPos geoPos2, double[] latValues, double[] lonValues, double[] vals,
                                    int width, int height, ArrayList<Renderable> renderableList,
                                    ProductRenderablesInfo prodRenderInfo, String comp) {
        SystemUtils.LOG.info("createColorSurface " + latValues.length + " " + lonValues.length + " " + vals.length + " " + width + " " + height);

        // analytic surface has to be overidden in order to allow for non-rectangular surfaces
        // the approach is render the surface point by point and not use the sector as the boundary
        AnalyticSurface analyticSurface = new AnalyticSurface() {

            protected void doUpdate(DrawContext dc) {
                this.referencePos = new Position(this.sector.getCentroid(), this.altitude);
                this.referencePoint = dc.getGlobe().computePointFromPosition(this.referencePos);

                if (this.surfaceRenderInfo == null ||
                        this.surfaceRenderInfo.getGridWidth() != this.width ||
                        this.surfaceRenderInfo.getGridHeight() != this.height) {
                    this.surfaceRenderInfo = new RenderInfo(this.width, this.height) {
                        public void drawInterior(DrawContext dc) {
                            if (dc == null) {
                                cartesianVertexBuffer.rewind();
                                geographicVertexBuffer.rewind();
                                colorBuffer.rewind();
                                shadowColorBuffer.rewind();
                                return;
                            }
                            super.drawInterior(dc);
                        }
                    };
                }

                this.updateSurfacePoints(dc, this.surfaceRenderInfo);
                this.updateSurfaceNormals(this.surfaceRenderInfo);
            }

            protected void updateSurfacePoints(DrawContext dc, RenderInfo outRenderInfo) {
                Iterator<? extends GridPointAttributes> iter = this.values.iterator();

                for (int row = 0; row < this.height; row++) {
                    for (int col = 0; col < this.width; col++) {
                        int i = row * (this.width) + col;
                        GridPointAttributes attr = iter.hasNext() ? iter.next() : null;

                        this.updateNextSurfacePoint(dc, Angle.fromDegrees(latValues[i]),
                                Angle.fromDegrees(lonValues[i]), attr, outRenderInfo);
                    }
                }

                outRenderInfo.drawInterior(null);
            }
        };
        analyticSurface.setSector(Sector.fromDegrees(geoPos2.getLat(), geoPos1.getLat(), geoPos1.getLon(), geoPos2.getLon()));
        analyticSurface.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
        analyticSurface.setDimensions(width, height);

        AnalyticSurfaceAttributes attr = new AnalyticSurfaceAttributes();
        attr.setDrawShadow(false);
        attr.setInteriorOpacity(1.0);
        //attr.setOutlineWidth(3);
        attr.setDrawOutline(false);
        analyticSurface.setSurfaceAttributes(attr);

        analyticSurface.setClientLayer(this);
        //addRenderable(analyticSurface);

        //BufferWrapper analyticSurfaceValueBuffer = randomGridValues(width, height, minValue, maxValue);
        BufferWrapper analyticSurfaceValueBuffer = (new BufferFactory.DoubleBufferFactory()).newBuffer(vals.length);
        analyticSurfaceValueBuffer.putDouble(0, vals, 0, vals.length);

        //BufferWrapper analyticSurfaceValueBuffer = (new BufferFactory.DoubleBufferFactory()).newBuffer(latValues.length);
        //analyticSurfaceValueBuffer.putDouble(0, latValues, 0, latValues.length);

        //smoothValues(width, height, values, 0.5d);
        //scaleValues(values, values.length, minValue, maxValue);

        //mixValuesOverTime(2000L, firstBuffer, analyticSurfaceValueBuffer, minValue, maxValue, minHue, maxHue, analyticSurface);

        prodRenderInfo.setAnalyticSurfaceAndBuffer(analyticSurface, analyticSurfaceValueBuffer, comp);
        if (renderableList != null) {
            renderableList.add(analyticSurface);
        }
    }

    private void createColorGradient(double minValue, double maxValue, boolean whiteZero,
                                     ProductRenderablesInfo prodRenderInfo, String comp) {
        //SystemUtils.LOG.info("createColorGradient " + minValue + " " + maxValue + " " + comp);
        ArrayList<AnalyticSurface> analyticSurfaces = null;
        ArrayList<BufferWrapper> analyticSurfaceValueBuffers = null;

        if (comp.equalsIgnoreCase("owi")) {
            analyticSurfaces = prodRenderInfo.owiAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.owiAnalyticSurfaceValueBuffers;
        } else if (comp.equalsIgnoreCase("osw")) {
            analyticSurfaces = prodRenderInfo.oswAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.oswAnalyticSurfaceValueBuffers;
        } else if (comp.equalsIgnoreCase("rvl")) {
            analyticSurfaces = prodRenderInfo.rvlAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.rvlAnalyticSurfaceValueBuffers;
        }

        if (analyticSurfaces != null) {

            for (int currSurfInd = 0; currSurfInd < analyticSurfaces.size(); currSurfInd++) {

                AnalyticSurface analyticSurface = analyticSurfaces.get(currSurfInd);
                BufferWrapper analyticSurfaceValueBuffer = analyticSurfaceValueBuffers.get(currSurfInd);
                final ArrayList<AnalyticSurface.GridPointAttributes> attributesList = new ArrayList<>();
                for (int i = 0; i < analyticSurfaceValueBuffer.length(); i++) {
                    double d = analyticSurfaceValueBuffer.getDouble(i);
                    attributesList.add(
                            createColorGradientAttributes(d, minValue, maxValue, HUE_RED, HUE_MAX_RED, whiteZero));
                }

                analyticSurface.setValues(attributesList);
            }
        }
    }

    // ADDED:
    // this method is copied from gov.nasa.worldwindx.examples.analytics.AnalyticSurface
    private static AnalyticSurface.GridPointAttributes createColorGradientAttributes(final double value,
                                                                                     double minValue, double maxValue,
                                                                                     double minHue, double maxHue,
                                                                                     boolean whiteZero) {
        final double hueFactor = WWMath.computeInterpolationFactor(value, minValue, maxValue);

        //double hue = WWMath.mixSmooth(hueFactor, minHue, maxHue);
        final double hue = WWMath.mix(hueFactor, minHue, maxHue);
        double sat = 1.0;
        if (whiteZero) {
            sat = Math.abs(WWMath.mixSmooth(hueFactor, -1, 1));
        }
        final Color color = Color.getHSBColor((float) hue, (float) sat, 1f);
        final double opacity = WWMath.computeInterpolationFactor(value, minValue, minValue + (maxValue - minValue) * 0.1);
        final Color rgbaColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * opacity));

        return AnalyticSurface.createGridPointAttributes(value, rgbaColor);
    }

    @Override
    public void removeProduct(final Product product) {

        final ProductRenderablesInfo productRenderablesInfo = theProductRenderablesInfoHash.get(product);

        if (productRenderablesInfo != null) {

            for (ArrayList<Renderable> renderableList : productRenderablesInfo.theRenderableListHash.values()) {
                //SystemUtils.LOG.info(":: renderableList " + renderableList);
                for (Renderable renderable : renderableList) {
                    //SystemUtils.LOG.info(":: renderable " + renderable);
                    removeRenderable(renderable);
                    if (renderable instanceof DirectedPath) {
                        theObjectInfoHash.remove(renderable);
                        theSurfaceProductHash.remove(renderable);
                        theSurfaceSequenceHash.remove(renderable);
                    }
                }
                renderableList.clear();
            }
            theProductRenderablesInfoHash.remove(product);
        }

        if (theProductRenderablesInfoHash.size() == 0) {
            theControlLevel2Panel.setVisible(false);
            for (ColorBarLegend colorBarLegend : theColorBarLegendHash.values()) {
                removeRenderable(colorBarLegend);
            }
        }
    }

    private void recreateColorBarAndGradient(double minValue, double maxValue, String comp, WorldWindowGLCanvas wwd, boolean redraw) {
        //SystemUtils.LOG.info("recreateColorBarAndGradient " + minValue + " " + maxValue + " " + comp + " " + theColorBarLegendHash.get(comp));

        String title = "";
        if (comp.equalsIgnoreCase("owi")) {
            title = "OWI Wind Speed";
        } else if (comp.equalsIgnoreCase("osw")) {
            title = "OSW Wave Height.";
        } else if (comp.equalsIgnoreCase("rvl")) {
            title = "RVL Rad. Vel.";
        }

        if (redraw) {
            removeRenderable(theColorBarLegendHash.get(comp));
        }
        createColorBarLegend(minValue, maxValue, title, comp);

        if (redraw) {
            addRenderable(theColorBarLegendHash.get(comp));
        }
        for (ProductRenderablesInfo productRenderablesInfo : theProductRenderablesInfoHash.values()) {
            //createColorGradient(minValue, maxValue, false, theProductRenderablesInfoHash.get(theColorBarLegendProduct), comp);
            createColorGradient(minValue, maxValue, false, productRenderablesInfo, comp);
        }

        if (redraw) {
            wwd.redrawNow();
        }
    }

    @Override
    public JPanel getControlPanel(final WorldWindowGLCanvas wwd) {
        theControlLevel2Panel = new JPanel(new GridLayout(7, 1, 5, 5));
        theControlLevel2Panel.setVisible(false);
        final JRadioButton owiBtn = new JRadioButton("OWI");
        owiBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                theSelectedComp = "owi";
                setComponentVisible("owi", wwd);
                theArrowsCB.setEnabled(true);
            }
        });

        final JRadioButton oswBtn = new JRadioButton("OSW");
        oswBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                theSelectedComp = "osw";
                setComponentVisible("osw", wwd);
                theArrowsCB.setEnabled(false);

                //SystemUtils.LOG.info("theSurfaceProductHash " + theSurfaceProductHash);
                //SystemUtils.LOG.info("theSurfaceSequenceHash " + theSurfaceSequenceHash);
            }
        });

        final JRadioButton rvlBtn = new JRadioButton("RVL");
        rvlBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                theSelectedComp = "rvl";
                //System.out.println("rvl:");
                //setComponentVisible("owi", false, getWwd());
                //setComponentVisible("osw", false, getWwd());
                setComponentVisible("rvl", wwd);
                theArrowsCB.setEnabled(false);
            }
        });

        final ButtonGroup group = new ButtonGroup();
        group.add(owiBtn);
        group.add(oswBtn);
        group.add(rvlBtn);
        owiBtn.setSelected(true);

        theSelectedComp = "owi";

        final JPanel componentTypePanel = new JPanel(new GridLayout(1, 4, 5, 5));
        componentTypePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        componentTypePanel.add(new JLabel("Component:"));
        componentTypePanel.add(owiBtn);
        componentTypePanel.add(oswBtn);
        componentTypePanel.add(rvlBtn);
        theControlLevel2Panel.add(componentTypePanel);

        final JPanel arrowDisplayPanel = new JPanel(new GridLayout(1, 2, 5, 5));

        theArrowsCB = new JCheckBox(new AbstractAction() {
            public void actionPerformed(ActionEvent actionEvent) {
                // Simply enable or disable the layer based on its toggle button.
                theOWIArrowsDisplayed = ((JCheckBox) actionEvent.getSource()).isSelected();

                wwd.redrawNow();
            }
        });

        arrowDisplayPanel.add(new JLabel("Display Wind Vectors:"));
        arrowDisplayPanel.add(theArrowsCB);
        theControlLevel2Panel.add(arrowDisplayPanel);

        /*
        final JPanel subsectionPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        JComboBox sectionDropDown = new JComboBox();
        sectionDropDown.addItem("001");
        sectionDropDown.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SystemUtils.LOG.info("drop down changed");
            }
        });

        subsectionPanel.add(new JLabel("Subsection:"));
        subsectionPanel.add(sectionDropDown);

        theControlLevel2Panel.add(subsectionPanel);
        */

        final JPanel maxPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        maxPanel.add(new JLabel("Max OWI Wind Speed:"));

        final JSpinner maxSP = new JSpinner(new SpinnerNumberModel(10, 0, 10, 1));
        maxSP.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                int newValue = (Integer) ((JSpinner) e.getSource()).getValue();

                theOWILimitChanged = true;
            }
        });
        maxPanel.add(maxSP);
        theControlLevel2Panel.add(maxPanel);

        final JPanel minPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        minPanel.add(new JLabel("Min OWI Wind Speed:"));

        final JSpinner minSP = new JSpinner(new SpinnerNumberModel(0, 0, 10, 1));
        minSP.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                theOWILimitChanged = true;
            }
        });
        minPanel.add(minSP);
        theControlLevel2Panel.add(minPanel);

        final JPanel maxRVLPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        maxRVLPanel.add(new JLabel("Max RVL Rad Vel.:"));

        final JSpinner maxRVLSP = new JSpinner(new SpinnerNumberModel(6, 0, 10, 1));
        maxRVLSP.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                int newValue = (Integer) ((JSpinner) e.getSource()).getValue();
                theRVLLimitChanged = true;
            }
        });
        maxRVLPanel.add(maxRVLSP);
        theControlLevel2Panel.add(maxRVLPanel);

        final JButton updateButton = new JButton("Update");
        updateButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {

                if (theOWILimitChanged) {

                    //double minValue = ((Integer) minSP.getValue()) * 1.0e4;
                    //double maxValue = ((Integer) maxSP.getValue()) * 1.0e4;
                    double minValue = ((Integer) minSP.getValue());
                    double maxValue = ((Integer) maxSP.getValue());
                    recreateColorBarAndGradient(minValue, maxValue, "owi", wwd, theSelectedComp.equalsIgnoreCase("owi"));
                }

                if (theRVLLimitChanged) {
                    //SystemUtils.LOG.info("theRVLLimitChanged");

                    //double minValue = ((Integer) minSP.getValue()) * 1.0e4;
                    //double maxValue = ((Integer) maxSP.getValue()) * 1.0e4;

                    double maxValue = ((Integer) maxRVLSP.getValue());
                    double minValue = -1 * maxValue;

                    recreateColorBarAndGradient(minValue, maxValue, "rvl", wwd, theSelectedComp.equalsIgnoreCase("rvl"));
                }

                theOWILimitChanged = false;
                theRVLLimitChanged = false;
            }
        });
        theControlLevel2Panel.add(updateButton);

        createColorBarLegend(0, 10, "OWI Wind Speed", "owi");
        createColorBarLegend(0, 10, "OSW Wave Height.", "osw");
        createColorBarLegend(-6, 5, "RVL Rad. Vel.", "rvl");
        //addRenderable(theColorBarLegendHash.get("owi"));

        return theControlLevel2Panel;
    }
}