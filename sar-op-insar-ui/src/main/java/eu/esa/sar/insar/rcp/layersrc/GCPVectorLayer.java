/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.insar.rcp.layersrc;

import com.bc.ceres.binding.PropertySet;
import com.bc.ceres.glayer.Layer;
import com.bc.ceres.glayer.LayerTypeRegistry;
import com.bc.ceres.grender.Rendering;
import com.bc.ceres.grender.Viewport;
import eu.esa.microwave.dat.graphics.GraphicShape;
import eu.esa.microwave.dat.layers.ScreenPixelConverter;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the movement of GCP in a coregistered image
 */
public class GCPVectorLayer extends Layer {

    private final Product product;
    private final Band band;
    private static final float lineThickness = 1.0f;
    private final List<GCPData> gcpList = new ArrayList<>(200);

    public GCPVectorLayer(PropertySet configuration) {
        super(LayerTypeRegistry.getLayerType(GCPVectorLayerType.class.getName()), configuration);
        setName("GCP Movement");
        product = (Product) configuration.getValue("product");
        band = (Band) configuration.getValue("band");

        getData();
    }

    private void getData() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        if (absRoot != null) {
            final MetadataElement bandElem = AbstractMetadata.getBandAbsMetadata(absRoot, band.getName(), false);
            if (bandElem != null) {
                final MetadataElement warpDataElem = bandElem.getElement("WarpData");
                if (warpDataElem != null) {
                    final MetadataElement[] gcpElems = warpDataElem.getElements();
                    gcpList.clear();
                    for (MetadataElement gcpElem : gcpElems) {
                        final double refX = gcpElem.getAttributeDouble("mst_x", 0);
                        final double refY = gcpElem.getAttributeDouble("mst_y", 0);

                        final double secX = gcpElem.getAttributeDouble("slv_x", 0);
                        final double secY = gcpElem.getAttributeDouble("slv_y", 0);

                        gcpList.add(new GCPData(refX, refY, secX, secY));
                    }
                }
            }
        }
    }

    @Override
    protected void renderLayer(Rendering rendering) {
        if (gcpList.isEmpty())
            return;

        final Viewport vp = rendering.getViewport();
        final RasterDataNode raster = band;
        final ScreenPixelConverter screenPixel = new ScreenPixelConverter(vp, raster);

        if (!screenPixel.withInBounds()) {
            return;
        }

        final Graphics2D graphics = rendering.getGraphics();
        graphics.setStroke(new BasicStroke(lineThickness));

        graphics.setColor(Color.RED);
        for (GCPData gcp : gcpList) {
            GraphicShape.drawArrow(graphics, screenPixel,
                    (int) gcp.secX, (int) gcp.secY,
                    (int) gcp.refX, (int) gcp.refY, 50);
        }
    }

    private static class GCPData {
        public final double refX, refY;
        public final double secX, secY;

        public GCPData(double mX, double mY, double sX, double sY) {
            this.refX = mX;
            this.refY = mY;
            this.secX = sX;
            this.secY = sY;
        }
    }
}
