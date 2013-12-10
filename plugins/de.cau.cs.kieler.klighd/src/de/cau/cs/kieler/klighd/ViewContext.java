/*
 * KIELER - Kiel Integrated Environment for Layout Eclipse RichClient
 *
 * http://www.informatik.uni-kiel.de/rtsys/kieler/
 * 
 * Copyright 2011 by
 * + Christian-Albrechts-University of Kiel
 *   + Department of Computer Science
 *     + Real-Time and Embedded Systems Group
 * 
 * This code is provided under the terms of the Eclipse Public License (EPL).
 * See the file epl-v10.html for the license text.
 */
package de.cau.cs.kieler.klighd;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.statushandlers.StatusManager;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.cau.cs.kieler.core.WrappedException;
import de.cau.cs.kieler.core.kgraph.KGraphData;
import de.cau.cs.kieler.core.kgraph.KGraphElement;
import de.cau.cs.kieler.core.kgraph.KGraphPackage;
import de.cau.cs.kieler.core.kgraph.KNode;
import de.cau.cs.kieler.core.krendering.KRendering;
import de.cau.cs.kieler.core.properties.IProperty;
import de.cau.cs.kieler.core.properties.IPropertyHolder;
import de.cau.cs.kieler.core.properties.MapPropertyHolder;
import de.cau.cs.kieler.core.util.Pair;
import de.cau.cs.kieler.core.util.RunnableWithResult;
import de.cau.cs.kieler.kiml.klayoutdata.KLayoutData;
import de.cau.cs.kieler.kiml.util.KimlUtil;
import de.cau.cs.kieler.klighd.internal.ILayoutRecorder;
import de.cau.cs.kieler.klighd.internal.ISynthesis;
import de.cau.cs.kieler.klighd.internal.preferences.KlighdPreferences;
import de.cau.cs.kieler.klighd.internal.util.KlighdInternalProperties;
import de.cau.cs.kieler.klighd.util.KlighdProperties;
import de.cau.cs.kieler.klighd.util.KlighdSynthesisProperties;
import de.cau.cs.kieler.klighd.util.ModelingUtil;
import de.cau.cs.kieler.klighd.viewers.ContextViewer;

/**
 * A view context is a data record containing the required configuration information to translate a
 * model into a diagram and draw it on the screen, as well as provide user operations on it.<br>
 * <br>
 * 
 * ViewContexts contain information on
 * <ul>
 * <li>the business model (input model) that is to be shown,</li>
 * <li>the source workbench part the business model stems from,</li>
 * <li>the transformations that are involved in creating the diagram,</li>
 * <li>the update strategy that is to be applied in case of diagram updates,</li>
 * <li>the resulting view model describing the diagram,</li>
 * <li>the {@link IViewerProvider} that wraps the instantiation of the viewer being used</li>
 * </ul>
 * 
 * @author mri
 * @author chsch
 */
public final class ViewContext extends MapPropertyHolder {

    /** the serial version UID. */
    private static final long serialVersionUID = -431994394109554393L;

    /** the part the source model was selected from (if can reasonably be determined). */
    private transient IWorkbenchPart sourceWorkbenchPart = null;
    
    /** the viewer provider. */
    private transient IViewerProvider<KNode> viewerProvider = null;

    /** the update strategy. */
    private transient IUpdateStrategy<KNode> updateStrategy = null;
    
    /** the {@link ISynthesis} being applied. */
    private transient ISynthesis diagramSynthesis = null;
    
    /** the business model to be represented by means of this context. */
    private Object businessModel = null;

    /** the view model is initiated while configuring the involved {@link IUpdateStrategy} and kept
     * for the whole life-cycle of the view context, in order to enable proper incremental update. */
    private KNode viewModel = KimlUtil.createInitializedNode();
    
    /** the {@link IViewer} being in charge of showing this {@link ViewContext}. */
    private IViewer<KNode> viewer = null;
    
    /** the {@link #viewer} if it is a {@link ILayoutRecorder}, <code>null</code> otherwise. */
    private ILayoutRecorder layoutRecorder = null;
    
    /** the view-specific zoom style. */
    private ZoomStyle zoomStyle = ZoomStyle.valueOf(KlighdPlugin.getDefault().getPreferenceStore()
            .getString(KlighdPreferences.ZOOM_STYLE));
    
    /**
     * Standard constructor.
     * 
     * @param inputModel
     *            the source model to be represented by a diagram
     */
    public ViewContext(final Object inputModel) {
        super();
        this.businessModel = inputModel;
    }
    
    // ---------------------------------------------------------------------------------- //
    //  initialization part    
    
    /**
     * This method performs the initial configuration of <code>this</code> view context.<br>
     * In case some custom configurations are to be applied {@link #configure(IPropertyHolder)}
     * might be called alternatively. (Currently) only one of these methods shall be called in life
     * of a {@link ViewContext} instance, and only once at that.<br>
     * 
     * @return <code>this</code> {@link ViewContext} instance for convenience
     */
    public ViewContext configure() {
        return configure(KlighdSynthesisProperties.emptyConfig());
    }

    /**
     * This method performs the initial configuration of <code>this</code> view context, it
     * incorporates the properties given in <code>propertyHolder</code> during that.<br>
     * (Currently) only one of this method and {@link #configure()} shall be called in life of a
     * {@link ViewContext} instance, and only once at that.<br>
     * 
     * @param propertyHolder
     *            an {@link IPropertyHolder} that contributes custom configuration instructions
     * @return <code>this</code> {@link ViewContext} instance for convenience
     */
    public ViewContext configure(final IPropertyHolder propertyHolder) {
        final KlighdDataManager data = KlighdDataManager.getInstance();
        
        // get the transformations request
        final String synthesisId =
                propertyHolder.getProperty(KlighdSynthesisProperties.REQUESTED_DIAGRAM_SYNTHESIS);
        diagramSynthesis = data.getDiagramSynthesisById(synthesisId);
        
        if (diagramSynthesis == null) {
            diagramSynthesis = Iterables.getFirst(Iterables.filter(
                            data.getAvailableSyntheses(this.businessModel.getClass()),
                            synthesisFilter), null);
        }
        
        if (this.diagramSynthesis != null) {
            this.synthesisOptions = this.diagramSynthesis.getDisplayedSynthesisOptions();
            for (SynthesisOption option: this.synthesisOptions) {
                this.configureOption(option, option.getInitialValue());
            }
        } else {
            this.synthesisOptions = Collections.emptyList();
        }
        
        final String updateStrategyId =
                propertyHolder.getProperty(KlighdSynthesisProperties.REQUESTED_UPDATE_STRATEGY);
        updateStrategy = data.getUpdateStrategyById(updateStrategyId);
        
        if (updateStrategy == null) {
            updateStrategy = data.getHighestPriorityUpdateStrategy();
            // updateStrategy is supposed to be non-null since this plug-in registers the
            //  SimpleUpdateStrategy
        }
        
        // get the viewer provider request
        final String viewerProviderId =
                propertyHolder.getProperty(KlighdSynthesisProperties.REQUESTED_VIEWER_PROVIDER);
        viewerProvider = data.getViewerProviderById(viewerProviderId);
        
        if (viewerProvider == null) {
            viewerProvider = Iterables.getFirst(data.getAvailableViewerProviders(), null);
            
        }
        
        return this;
    }

    private final Predicate<ISynthesis> synthesisFilter = new Predicate<ISynthesis>() {
        public boolean apply(final ISynthesis synthesis) {
            try {
                return synthesis.supports(ViewContext.this.businessModel, ViewContext.this);
            } catch (WrappedException e) {
                StatusManager.getManager().handle(
                        new Status(IStatus.ERROR, KlighdPlugin.PLUGIN_ID, e.getMessage(),
                                e.getCause()), StatusManager.LOG);
                return false;
            }
        }
    };


    /**
     * Creates the wrapped {@link IViewer} instance that is actually in charge of drawing the diagram
     * into the provided SWT {@link Composite} widget <code>parent</code>. Returns <code>null</code>
     * if no fitting {@link IViewerProvider} has been found during configuration.
     * 
     * @param parentViewer
     *            the parent {@link ContextViewer}
     * @param parent
     *            the parent {@link Composite} widget
     * @return the created viewer or <code>null</code> on failure
     */
    public IViewer<?> createViewer(final ContextViewer parentViewer, final Composite parent) {

        if (this.viewerProvider != null) {
            // create the new viewer
            this.viewer = this.viewerProvider.createViewer(parentViewer, parent);
            
            if (this.viewer instanceof ILayoutRecorder) {
                this.layoutRecorder = (ILayoutRecorder) viewer;
            }

            // set the base model if possible
            this.viewer.setModel(this.viewModel, true);
            
            return this.viewer;
        } else {
            return null;
        }
    }


    /**
     * Executes the {@link #diagramSynthesis} attached to <code>this</code> view context and updates
     * the view model by applying the configured {@link IUpdateStrategy}. In case the former
     * input/source model has been replaced by a new one of compatible type this new one must be
     * provided, otherwise <code>model</code> may by <code>null</code>.
     * 
     * @param sourceModel
     *            the initial, updated, or replaced input model, may be <code>null</code>
     */
    public void update(final Object sourceModel) {
        this.update(sourceModel, this.updateStrategy);
    }

    /**
     * Executes the {@link #diagramSynthesis} attached to <code>this</code> view context and updates
     * the view model by applying the configured {@link IUpdateStrategy}. In case the former
     * input/source model has been replaced by a new one of compatible type this new one must be
     * provided, otherwise <code>model</code> may by <code>null</code>.
     * 
     * @param model
     *            the initial, updated, or replaced input model, may be <code>null</code>
     * @param theUpdateStrategy
     *            the updateStrategy to use during this update, must not be <code>null</code>
     */
    public void update(final Object model, final IUpdateStrategy<KNode> theUpdateStrategy) {
        final Object sourceModel = model != null ? model : this.businessModel;
        
        final KNode newViewModel;
        if (this.diagramSynthesis != null
                && diagramSynthesis.getSourceClass().isAssignableFrom(sourceModel.getClass())) {
            
            try {
                newViewModel = this.diagramSynthesis.transform(sourceModel, this);
            } catch (Exception e) {
                final String msg = "";
                StatusManager.getManager().handle(
                        new Status(IStatus.ERROR, KlighdPlugin.PLUGIN_ID, msg, e));
                return;
            }

        } else if (sourceModel instanceof KNode) {
            newViewModel = (KNode) sourceModel;
            
        } else {
            final String msg = "KLighD: Could not create a diagram of provided input model "
                    + sourceModel + ".";
            // TODO: extend error msg
            StatusManager.getManager().handle(new Status(IStatus.WARNING, KlighdPlugin.PLUGIN_ID, msg));
            return;
        }
        
        if (theUpdateStrategy != null) {
            theUpdateStrategy.update(this.viewModel, newViewModel, this);
        } else {
            this.updateStrategy.update(this.viewModel, newViewModel, this);
        }
        
        
        this.businessModel = sourceModel;
    }


    /**
     * Sets the source workbench part (part the source model has been chosen in).
     * 
     * @param theSourceWorkbenchPart
     *            the source workbench part (part the source model has been chosen in)
     */
    public void setSourceWorkbenchPart(final IWorkbenchPart theSourceWorkbenchPart) {
        this.sourceWorkbenchPart = theSourceWorkbenchPart;
    }

    /**
     * Returns the source workbench part.
     * 
     * @return the source workbench part (part the source model has been chosen in)
     */
    public IWorkbenchPart getSourceWorkbenchPart() {
        return sourceWorkbenchPart;
    }

    /**
     * Returns the source workbench part viewer (experimental).
     * 
     * @return the source workbench part viewer(viewer the source model has been chosen in)
     */
    public Viewer getSourceWorkbenchPartViewer() {
        if (this.sourceWorkbenchPart == null) {
            return null;
        }
        try {
            IWorkbenchPart part = this.sourceWorkbenchPart;
            return (Viewer) part.getClass().getMethod("getViewer").invoke(part);
        } catch (Exception e) {
            StatusManager.getManager().addLoggedStatus(
                    new Status(IStatus.ERROR, KlighdPlugin.PLUGIN_ID,
                            "KLighD: Determination of a viewer widget (beyond the KLighD viewer) "
                                    + "showing the currently depicted model failed.\n"
                                    + "This error might occured while trying to focus a depicted model "
                                    + "element in a related editor."));
        }
        return null;               
    }
    
    /**
     * Returns the current model to be represented.
     * 
     * @return the current model to be represented.
     */
    public Object getInputModel() {
        RunnableWithResult<?> modelAccess = this.getProperty(KlighdProperties.MODEL_ACCESS);
        if (modelAccess != null) {
            modelAccess.run();
            return modelAccess.getResult();
        }
        return this.businessModel;
    }

    /**
     * Returns the update strategy used in this view context.
     * 
     * @return the update strategy
     */
    public IUpdateStrategy<? extends KNode> getUpdateStrategy() {
        return updateStrategy;
    }

    /**
     * Returns the view model root, which is kept for the whole life-cycle of the view context, in
     * order to enable proper incremental update.
     * 
     * @return the view model
     */
    public KNode getViewModel() {
        return viewModel;
    }
    
    /**
     *  @return the {@link IViewer} being in charge of showing this {@link ViewContext}.
     */
    public IViewer<KNode> getViewer() {
        return viewer;
    }
    
    /**
     * @return the {@link ILayoutRecorder} being in charge of recording the layout for proper animation
     */
    public ILayoutRecorder getLayoutRecorder() {
        return layoutRecorder;
    }

    /**
     * @return the zoomStyle
     */
    public ZoomStyle getZoomStyle() {
        return zoomStyle;
    }
    
    /**
     * @return whether the zoom style is zoom to fit.
     */
    public boolean isZoomToFit() {
        return zoomStyle == ZoomStyle.ZOOM_TO_FIT;
    }
    
    /**
     * Keep in mind that zoom to focus has a higher priority, thus
     * this can only return false if {@link #isZoomToFit()} returns false.
     * 
     * @return whether the zoom style is zoom to focus.
     */
    public boolean isZoomToFocus() {
        return !isZoomToFit() && zoomStyle == ZoomStyle.ZOOM_TO_FOCUS;
    }
    
    /**
     * @param zoomStyle the zoomStyle to set
     */
    public void setZoomStyle(final ZoomStyle zoomStyle) {
        this.zoomStyle = zoomStyle;
    }


    // ---------------------------------------------------------------------------------- //
    //  Source target element handling    

    /**
     * Returns the element in the input model that is represented by the given <code>element</code>
     * in the diagram.<br>
     * <b>Note:</b> This method does not check whether <code>element</code> is currently contained
     * in the view model (accessible via {@link #getViewModel()}).
     * 
     * @param element
     *            the diagram element whose source element in the input (source, semantic, or
     *            business) model is requested
     * @return the element in the input model or <code>null</code> if no source element could be
     *         identified
     */
    public Object getSourceElement(final EObject element) {
        final Object model;
        if (KGraphPackage.eINSTANCE.getKGraphData().isInstance(element)) {
            model = ((KGraphData) element).getProperty(KlighdInternalProperties.MODEL_ELEMEMT);
        } else if (KGraphPackage.eINSTANCE.getKGraphElement().isInstance(element)) {
            KLayoutData layoutData = ((KGraphElement) element).getData(KLayoutData.class);
            if (layoutData != null) {
                model = layoutData.getProperty(KlighdInternalProperties.MODEL_ELEMEMT);
            } else {
                model = null;
            }
        } else {
            model = null;
        }
        
         return model;
    }


    /**
     * Returns the elements in the view model that represent the given <code>element</code> in the
     * diagram.<br>
     * <b>Note:</b> This method does not check whether <code>element</code> is currently contained
     * in the input model being represented (accessible via {@link #getInputModel()}).
     * 
     * @param element
     *            the object in the input (source, semantic, or business) model
     * @return a {@link Collection} of diagram elements representing the given <code>element</code>
     *         or <code>{@link Collections#emptyList()}</code> if no corresponding view model
     *         elements could be identified
     */
    public Collection<EObject> getTargetElements(final Object element) {
        
        if (element == null) {
            return Collections.emptyList();
        }
        
        final Iterator<EObject> it =
                ModelingUtil.eAllContentsOfType2(viewModel, KGraphElement.class, KRendering.class);
        
        final Collection<EObject> targetCollection =
                Lists.newArrayList(Iterators.filter(it, new Predicate<EObject>() {

            /** {@inheritDoc} */
            public boolean apply(final EObject input) {
                
                if (KGraphPackage.eINSTANCE.getKGraphElement().isInstance(input)) {
                    return apply(((KGraphElement) input).getData(KLayoutData.class));
                    
                } else if (KGraphPackage.eINSTANCE.getKGraphData().isInstance(input)) {
                    return apply((KGraphData) input);
                    
                } else {
                    return false;
                }
            }
            
            private boolean apply(final KGraphData data) {
                return data.getProperty(KlighdInternalProperties.MODEL_ELEMEMT) == element;
            }
        }));
        
        return targetCollection;
    }


    /**
     * Returns the elements in the view model that represent the given <code>element</code> in the
     * diagram.<br>
     * Since multiple view elements can be associated with an input model element, there are most
     * likely multiple view model elements. Thus the method returns the first one, or the first one of
     * type <code>ofType</code> if that parameter is non-<code>null</code>, or <code>null</code> if
     * there isn't any corresponding element (of type <code>ofType</code>).<br>
     * <b>Note:</b> This method does not check whether <code>element</code> is currently contained
     * in the input model being represented (accessible via {@link #getInputModel()}).
     * 
     * @param <T>
     *            the type of denoted by <code>type</code>
     * @param element
     *            the element in the source model
     * @param ofType
     *            the type of the requested view model element, maybe <code>null</code>
     * @return <code>element</code>'s representation in the context's view model or
     *         <code>null</code> if <code>element</code> is <code>null</code> of none exists
     */
    public <T extends EObject> T getTargetElement(final Object element, final Class<T> ofType) {
        if (element == null) {
            return null;
        }
        
        final Collection<EObject> targetCollection = getTargetElements(element);
        
        if (targetCollection == null || targetCollection.isEmpty()) {
            return null;
        } else {
            if (ofType == null) {
                @SuppressWarnings("unchecked")
                final T res = (T) (EObject) Iterables.getFirst(targetCollection, null);
                return res; 
            } else {
                return Iterables.getFirst(Iterables.filter(targetCollection, ofType), null);
            }
        }
    }


    // ---------------------------------------------------------------------------------- //
    //  Synthesis option handling    

    private List<SynthesisOption> synthesisOptions = null;
    
    /** Memory of the configured transformation options to be evaluated by the transformation. */
    private Map<SynthesisOption, Object> synthesisOptionConfig = Maps.newHashMap();
        
    
    /**
     * Returns the set of {@link SynthesisOption TransformationOptions} declared by the
     * transformation and forward to the users in the UI in order to allow them to influence the
     * transformation result.
     * 
     * @return the set of {@link SynthesisOption TransformationOptions}
     * 
     * @author chsch
     */
    public List<SynthesisOption> getDisplayedSynthesisOptions() {
        return this.synthesisOptions;
    }
    
    /**
     * Getter.
     * 
     * @param option the option to evaluate the configuration state / the configured value.
     * @return the configured value of {@link SynthesisOption} option.
     */
    public Object getOptionValue(final SynthesisOption option) {
        
        if (option == null) {
            throw new IllegalArgumentException("KLighD transformation option handling: "
                    + "The transformation " + this.diagramSynthesis
                    + " attempted to evaluate the transformation option \"null\".");
        }        
        return this.synthesisOptionConfig.get(option);
    }

    /**
     * 
     * @param option
     *            the {@link SynthesisOption} to set
     * @param value
     *            the value of the {@link SynthesisOption}
     */
    public void configureOption(final SynthesisOption option, final Object value) {

        if (option == null || !this.synthesisOptions.contains(option)) {
            throw new IllegalArgumentException("KLighD transformation option handling: "
                    + "Attempted to configure illegal option ("
                    + (option == null ? null : option.getName())
                    + "), which is not introduced by the transformation " + this.diagramSynthesis
                    + ".");
        }

        if (value == null) {
            synthesisOptionConfig.remove(option);
        } else {
            synthesisOptionConfig.put(option, value);
        }
    }   

    // ---------------------------------------------------------------------------------- //
    //  Recommended layout option handling    
    
    /**
     * Passes the recommended layout options and related values provided by the last transformation
     * context in the chain of such contexts, i.e. the last transformation in the transformation
     * chain.
     * 
     * @return a map of options (map keys) and related values (map values)
     */
    public List<Pair<IProperty<?>, List<?>>> getDisplayedLayoutOptions() {
        if (this.diagramSynthesis != null) {
            return this.diagramSynthesis.getDisplayedLayoutOptions();
        } else {
            return Collections.emptyList();
        }
    }
}
