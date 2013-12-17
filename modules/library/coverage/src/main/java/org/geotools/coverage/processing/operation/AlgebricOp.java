/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2005-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.coverage.processing.operation;

import it.geosolutions.jaiext.range.Range;
import it.geosolutions.jaiext.range.RangeFactory;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.media.jai.JAI;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.ROI;
import javax.media.jai.RenderedOp;
import javax.media.jai.registry.RenderedRegistryMode;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.ViewType;
import org.geotools.coverage.processing.CoverageProcessingException;
import org.geotools.coverage.processing.OperationJAI;
import org.geotools.factory.Hints;
import org.geotools.geometry.jts.JTS;
import org.geotools.metadata.iso.citation.Citations;
import org.geotools.parameter.DefaultParameterDescriptor;
import org.geotools.parameter.ImagingParameterDescriptors;
import org.geotools.parameter.ImagingParameters;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.ProjectiveTransform;
import org.geotools.resources.i18n.ErrorKeys;
import org.geotools.resources.i18n.Errors;
import org.geotools.resources.image.ImageUtilities;
import org.jaitools.imageutils.ROIGeometry;
import org.opengis.coverage.Coverage;
import org.opengis.metadata.spatial.PixelOrientation;
import org.opengis.parameter.InvalidParameterValueException;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.util.InternationalString;
import com.vividsolutions.jts.geom.Geometry;
import it.geosolutions.jaiext.algebra.AlgebraDescriptor;
import it.geosolutions.jaiext.algebra.AlgebraCRIF;
import javax.media.jai.OperationRegistry;
import java.awt.image.renderable.RenderedImageFactory;
import javax.media.jai.registry.RIFRegistry;

public class AlgebricOp extends OperationJAI {

    /**
     * The rendered mode for JAI operation.
     */
    protected static final String RENDERED_MODE = RenderedRegistryMode.MODE_NAME;

    static {
        try {
            OperationRegistry or = JAI.getDefaultInstance().getOperationRegistry();
            or.registerDescriptor(new AlgebraDescriptor());
            RenderedImageFactory rif = new AlgebraCRIF();
            RIFRegistry.register(or, "algebric", "it.geosolutions.jaiext.roiaware", rif);
        } catch (Exception e) {
            // if already registered (in other cases error will be thrown anyway)
        }
    }
    
    public AlgebricOp() {
        super(getOperationDescriptor("algebric"), new ImagingParameterDescriptors(
                getOperationDescriptor("algebric"), REPLACED_DESCRIPTORS));
    }

    /**
     * The parameter descriptor for the input sources
     */
    public static final ParameterDescriptor<List> SOURCES_PARAMETER = new DefaultParameterDescriptor<List>(
            Citations.JAI, "sources", List.class, // Value class (mandatory)
            null, // Array of valid values
            null, // Default value
            null, // Minimal value
            null, // Maximal value
            null, // Unit of measure
            true);

    private static Set<ParameterDescriptor> REPLACED_DESCRIPTORS;

    static {
        final Set<ParameterDescriptor> replacedDescriptors = new HashSet<ParameterDescriptor>();
        replacedDescriptors.add(SOURCES_PARAMETER);
        REPLACED_DESCRIPTORS = Collections.unmodifiableSet(replacedDescriptors);
    }

    /**
     * Copies parameter values from the specified {@link ParameterValueGroup} to the {@link ParameterBlockJAI}
     * 
     * @param parameters The {@link ParameterValueGroup} to be copied.
     * @return A copy of the provided {@link ParameterValueGroup} as a JAI block.
     * 
     * @see org.geotools.coverage.processing.OperationJAI#prepareParameters(org.opengis.parameter.ParameterValueGroup)
     */
    protected ParameterBlockJAI prepareParameters(ParameterValueGroup parameters) {
        // /////////////////////////////////////////////////////////////////////
        //
        // Make a copy of the input parameters.
        //
        // ///////////////////////////////////////////////////////////////////
        final ImagingParameters copy = (ImagingParameters) descriptor.createValue();
        org.geotools.parameter.Parameters.copy(parameters, copy);
        final ParameterBlockJAI block = (ParameterBlockJAI) copy.parameters;        
        try {
            // /////////////////////////////////////////////////////////////////////
            //
            //
            // Now transcode the parameters as needed by this operation.
            //
            //
            // ///////////////////////////////////////////////////////////////////
            // XXX make it robust
            final GridCoverage2D source = ((List<GridCoverage2D>) parameters.parameter("sources")
                    .getValue()).get(PRIMARY_SOURCE_INDEX);
            final AffineTransform gridToWorldTransformCorrected = new AffineTransform(
                    (AffineTransform) ((GridGeometry2D) source.getGridGeometry())
                            .getGridToCRS2D(PixelOrientation.UPPER_LEFT));
            final MathTransform worldToGridTransform;
            try {
                worldToGridTransform = ProjectiveTransform.create(gridToWorldTransformCorrected
                        .createInverse());
            } catch (NoninvertibleTransformException e) {
                // //
                //
                // Something bad happened here, namely the transformation to go
                // from grid to world was not invertible. Let's wrap and
                // propagate the error.
                //
                // //
                final CoverageProcessingException ce = new CoverageProcessingException(e);
                throw ce;
            }
            // /////////////////////////////////////////////////////////////////////
            //
            // Transcode the geometry parameter into a roi.
            //
            // /////////////////////////////////////////////////////////////////////
            final Object o = parameters.parameter("roi").getValue();
            if (o != null && o instanceof Geometry) {
                Geometry transformedGeometry = JTS.transform((Geometry) o, worldToGridTransform);

                ROI roiGeo = new ROIGeometry(transformedGeometry);
                block.setParameter("roi", roiGeo);
            }
            // /////////////////////////////////////////////////////////////////////
            //
            // Check if the NoData Range is present.
            //
            // If present it is simply passed to the operation, else, it is checked
            // from the coverage No Data inner parameter.
            //
            // /////////////////////////////////////////////////////////////////////
            final Object noData = parameters.parameter("noData").getValue();
            final Object destinationNoData = parameters.parameter("destinationNoData").getValue();
            if (noData != null && destinationNoData != null) {
                block.setParameter("noData", noData);
                block.setParameter("destinationNoData", destinationNoData);
            } else {
                // Selection of the NO DATA associated to the coverage
                Object noDataValue = source.getProperty("GC_NODATA");
                // If the inner NO DATA parameter is present, then it is elaborated
                if (noDataValue != null && noDataValue instanceof Number) {
                    // Selection of the value
                    Number innerNoData = ((Number) noDataValue);
                    // If no NODATA Range is present, then it is created from the inner NODATA value
                    if (noData == null) {
                        Range noDataRange;
                        // Coverage data type
                        int coverageDataType = source.getRenderedImage().getSampleModel()
                                .getDataType();
                        // Creation of the Range object
                        switch (coverageDataType) {
                        case DataBuffer.TYPE_BYTE:
                            noDataRange = RangeFactory.create(innerNoData.byteValue(), true,
                                    innerNoData.byteValue(), true);
                            break;
                        case DataBuffer.TYPE_USHORT:
                            noDataRange = RangeFactory.createU(innerNoData.shortValue(), true,
                                    innerNoData.shortValue(), true);
                            break;
                        case DataBuffer.TYPE_SHORT:
                            noDataRange = RangeFactory.create(innerNoData.shortValue(), true,
                                    innerNoData.shortValue(), true);
                            break;
                        case DataBuffer.TYPE_INT:
                            noDataRange = RangeFactory.create(innerNoData.intValue(), true,
                                    innerNoData.intValue(), true);
                            break;
                        case DataBuffer.TYPE_FLOAT:
                            noDataRange = RangeFactory.create(innerNoData.floatValue(), true,
                                    innerNoData.floatValue(), true, true);
                            break;
                        case DataBuffer.TYPE_DOUBLE:
                            noDataRange = RangeFactory.create(innerNoData.doubleValue(), true,
                                    innerNoData.doubleValue(), true, true);
                            break;
                        default:
                            throw new IllegalArgumentException("Wrong coverage data type");
                        }
                        // Addition to the parameterBlock
                        block.setParameter("noData", noDataRange);
                    }
                    // If no destination NODATAe is present, then it is added from the inner NODATA value.
                    if (destinationNoData == null) {
                        block.setParameter("destinationNoData", innerNoData.doubleValue());
                    }
                }
            }
            return block;
        } catch (Exception e) {
            // //
            //
            // Something bad happened here. Let's wrap and propagate the error.
            //
            // //
            final CoverageProcessingException ce = new CoverageProcessingException(e);
            throw ce;
        }
    }

    /**
     * Applies a process operation to a grid coverage. The default implementation performs the following steps:
     * 
     * <ol>
     * <li>Converts source grid coverages to their <cite>geophysics</cite> view using
     * <code>{@linkplain GridCoverage2D#geophysics GridCoverage2D.geophysics}(true)</code>. This allow to performs all computation on geophysics
     * values instead of encoded samples. <strong>Note:</strong> this step is disabled if {@link #computeOnGeophysicsValues computeOnGeophysicsValues}
     * returns {@code false}.</li>
     * 
     * <li>Ensures that every sources {@code GridCoverage2D}s use the same coordinate reference system (at least for the two-dimensional part) with
     * the same {@link GridGeometry2D#getGridToCRS2D gridToCRS} relationship.</li>
     * 
     * <li>Invokes {@link #deriveGridCoverage}. The sources in the {@code ParameterBlock} are {@link RenderedImage} objects obtained from
     * {@link GridCoverage2D#getRenderedImage()}.</li>
     * 
     * <li>If a changes from non-geophysics to geophysics view were performed at step 1, converts the result back to the original view using
     * <code>{@linkplain GridCoverage2D#geophysics GridCoverage2D.geophysics}(false)</code>.</li>
     * </ol>
     * 
     * @param parameters List of name value pairs for the parameters required for the operation.
     * @param hints A set of rendering hints, or {@code null} if none.
     * @return The result as a grid coverage.
     * @throws CoverageProcessingException if the operation can't be applied.
     * 
     * @see #deriveGridCoverage
     */
    public Coverage doOperation(final ParameterValueGroup parameters, final Hints hints)
            throws CoverageProcessingException {
        final ParameterBlockJAI block = prepareParameters(parameters);
        /*
         * Extracts the source grid coverages now as an array. The sources will be set in the ParameterBlockJAI (as RenderedImages) later, after the
         * reprojection performed in the next block.
         */

        final List<GridCoverage2D> coverages = (List<GridCoverage2D>) parameters.parameter(
                "sources").getValue();

        final GridCoverage2D[] sources = new GridCoverage2D[coverages.size()];
        ViewType primarySourceType = extractSources(parameters, sources, coverages);
        /*
         * Ensures that all coverages use the same CRS and has the same 'gridToCRS' relationship. After the reprojection, the method still checks all
         * CRS in case the user overridden the {@link #resampleToCommonGeometry} method.
         */
        resampleToCommonGeometry(sources, null, null, hints);

        GridCoverage2D coverage = sources[PRIMARY_SOURCE_INDEX];

        final CoordinateReferenceSystem crs = coverage.getCoordinateReferenceSystem2D();
        // TODO: remove the cast when we will be allowed to compile for J2SE 1.5.
        final MathTransform2D gridToCRS = coverage.getGridGeometry().getGridToCRS2D();
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] == null) {
                continue;
            }
            final GridCoverage2D source = sources[i];
            if (!CRS.equalsIgnoreMetadata(crs, source.getCoordinateReferenceSystem2D())
                    || !CRS.equalsIgnoreMetadata(gridToCRS, source.getGridGeometry()
                            .getGridToCRS2D())) {
                throw new IllegalArgumentException(
                        Errors.format(ErrorKeys.INCOMPATIBLE_GRID_GEOMETRY));
            }
            block.setSource(source.getRenderedImage(), i);
        }
        /*
         * Applies the operation. This delegates the work to the chain of 'deriveXXX' methods.
         */
        coverage = deriveGridCoverage(sources, block, hints);
        return postProcessResult(coverage, primarySourceType);
    }

    /**
     * Applies a JAI operation to a grid coverage. This method is invoked automatically by {@link #doOperation}. The default implementation performs
     * the following steps:
     * 
     * <ul>
     * <li>Gets the {@linkplain GridSampleDimension sample dimensions} for the target images by invoking the {@link #deriveSampleDimension
     * deriveSampleDimension(...)} method.</li>
     * <li>Applied the JAI operation using {@link #createRenderedImage}.</li>
     * <li>Wraps the result in a {@link GridCoverage2D} object.</li>
     * </ul>
     * 
     * @param sources The source coverages.
     * @param parameters Parameters, rendering hints and coordinate reference system to use.
     * @return The result as a grid coverage.
     * 
     * @see #doOperation
     * @see #deriveSampleDimension
     * @see JAI#createNS
     */
    protected GridCoverage2D deriveGridCoverage(final GridCoverage2D[] sources,
            final ParameterBlockJAI parameters, final Hints previousHints) {
        GridCoverage2D primarySource = sources[PRIMARY_SOURCE_INDEX];
        /*
         * Set the rendering hints image layout. Only the following properties will be set:
         * 
         * - Color model
         */
        RenderingHints hints = ImageUtilities.getRenderingHints(primarySource.getRenderedImage());
        if (previousHints != null) {
            if (hints != null) {
                hints.add(previousHints); // May overwrite the image layout we have just set.
            } else {
                hints = previousHints;
            }
        }
        /*
         * Performs the operation using JAI and construct the new grid coverage. Uses the coordinate system from the main source coverage in order to
         * preserve the extra dimensions (if any). The first two dimensions should be equal to the coordinate system set in the 'parameters' block.
         */
        final InternationalString name = deriveName(sources, PRIMARY_SOURCE_INDEX, null);
        final CoordinateReferenceSystem crs = primarySource.getCoordinateReferenceSystem();
        final MathTransform toCRS = primarySource.getGridGeometry().getGridToCRS();
        final RenderedImage data = createRenderedImage(parameters, hints);
        final Map properties = getProperties(data, crs, name, toCRS, sources, null);
        return getFactory(previousHints).create(name, // The grid coverage name
                data, // The underlying data
                crs, // The coordinate system (may not be 2D).
                toCRS, // The grid transform (may not be 2D).
                primarySource.getSampleDimensions(), // The sample dimensions
                sources, // The source grid coverages.
                properties); // Properties
    }

    /**
     * Prepare the properties for this ZonalStats operation.
     * 
     * @see OperationJAI#getProperties(RenderedImage, CoordinateReferenceSystem, InternationalString, MathTransform, GridCoverage2D[],
     *      org.geotools.coverage.processing.OperationJAI.Parameters),
     */
    protected Map<String, ?> getProperties(RenderedImage data, CoordinateReferenceSystem crs,
            InternationalString name, MathTransform toCRS, GridCoverage2D[] sources,
            Parameters parameters) {
        // /////////////////////////////////////////////////////////////////////
        //
        // If and only if data is a RenderedOp we return the properties of the source.
        //
        // /////////////////////////////////////////////////////////////////////
        if (data instanceof RenderedOp) {

            Map<String, Object> properties = new HashMap<String, Object>();

            String[] propertyNames = data.getPropertyNames();

            for (String property : propertyNames) {
                properties.put(property, data.getProperty(property));
            }
            return properties;
        }
        return null;

    }

    /**
     * Post processing on the coverage resulting from JAI operation.
     * 
     * @param coverage {@link GridCoverage2D} resulting from the operation.
     * @param primarySourceType Tells if we have to change the "geo-view" for the provided {@link GridCoverage2D}.
     * 
     * @return the prepared {@link GridCoverage2D}.
     */
    private static GridCoverage2D postProcessResult(GridCoverage2D coverage,
            final ViewType primarySourceType) {
        if (primarySourceType != null) {
            coverage = coverage.view(primarySourceType);
        }
        return coverage;
    }

    protected ViewType extractSources(final ParameterValueGroup parameters,
            final GridCoverage2D[] sources, final List<GridCoverage2D> sourceCoverage)
            throws ParameterNotFoundException, InvalidParameterValueException {

        ViewType type = null;
        final boolean computeOnGeophysicsValues = computeOnGeophysicsValues(parameters);
        for (int i = 0; i < sources.length; i++) {
            GridCoverage2D source = sourceCoverage.get(i);
            if (computeOnGeophysicsValues) {
                final GridCoverage2D old = source;
                source = source.view(ViewType.GEOPHYSICS);
                if (i == PRIMARY_SOURCE_INDEX) {
                    type = (old == source) ? ViewType.GEOPHYSICS : ViewType.PACKED;
                }
            }
            sources[i] = source;
        }
        return type;
    }

}
