/*
 * Copyright (C) 2023 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.sar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.sar.gpf.filtering.SpeckleFilterOp;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for BandPassFilter Operator.
 */
public class TestBandPassFilterOp extends ProcessorTest {

    private final static File inputFile = TestData.inputStackIMS;

    @Before
    public void setUp() throws Exception {
        try {
            // If the file does not exist: the test will be ignored
            assumeTrue(inputFile + " not found", inputFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final static OperatorSpi spi = new BandPassFilterOp.Spi();

    @Test
    public void testBandPass() throws Exception {
        final float[] expected = new float[] { -29.0f, -26.0f, -6.0f, -6.0f };
        process(inputFile, SpeckleFilterOp.BOXCAR_SPECKLE_FILTER, expected);
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @param inputFile    the path to the input product
     * @throws Exception general exception
     */
    public void process(final File inputFile, final String filter, final float[] expected) throws Exception {

        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        final BandPassFilterOp op = (BandPassFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[4];
        band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs:
        assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
    }
}
