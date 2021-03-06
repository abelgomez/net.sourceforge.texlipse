/*
 * $Id$
 *
 * Copyright (c) 2004-2005 by the TeXlapse Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package net.sourceforge.texlipse.editor;

import java.util.ArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.JFaceTextUtil;
import org.eclipse.jface.text.MarginPainter;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.projection.ProjectionSupport;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.texteditor.TextOperationAction;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

import net.sourceforge.texlipse.TexlipsePlugin;
import net.sourceforge.texlipse.model.TexDocumentModel;
import net.sourceforge.texlipse.outline.TexOutlinePage;
import net.sourceforge.texlipse.properties.TexlipseProperties;
import net.sourceforge.texlipse.treeview.views.TexOutlineTreeView;
import net.sourceforge.texlipse.viewer.ViewerAttributeRegistry;
import net.sourceforge.texlipse.viewer.ViewerManager;


/**
 * The Latex editor.
 * 
 * @author Oskar Ojala
 * @author Taavi Hupponen
 * @author Boris von Loesch
 */
public class TexEditor extends TextEditor {

    public final static String TEX_PARTITIONING= "__tex_partitioning";

    /** The editor's bracket matcher */
    private TexPairMatcher fBracketMatcher = new TexPairMatcher("()[]{}");
    
    private TexOutlinePage outlinePage;
    private TexOutlineTreeView fullOutline;
    private TexDocumentModel documentModel;
    private TexCodeFolder folder;
    private ProjectionSupport fProjectionSupport;
    private BracketInserter fBracketInserter;
    private TexlipseAnnotationUpdater fAnnotationUpdater;
    private boolean ctrlPressed = false;
    
    private Slider slider;
    
	private KeyListener sendDdeKeyListener = new KeyListener() {
		
		public void keyReleased(KeyEvent e) {
			ctrlPressed = false;
			return;
		}
		
		public void keyPressed(KeyEvent e) {
			if (e.keyCode == SWT.CTRL) {
				ctrlPressed = true;
				return;
			}
				
			if (!isDirty() && 
					(e.keyCode == SWT.ARROW_UP ||
					 e.keyCode == SWT.ARROW_DOWN ||
					 e.keyCode == SWT.ARROW_LEFT ||
					 e.keyCode == SWT.ARROW_RIGHT ||
					 e.keyCode == SWT.PAGE_DOWN ||
					 e.keyCode == SWT.PAGE_UP ||
					 e.keyCode == SWT.HOME ||
					 e.keyCode == SWT.END)) {
				if (TexlipsePlugin.getDefault().getPreferenceStore().getBoolean(TexlipseProperties.BUILDER_KEEP_SYNCHRONIZED)) {
		            ViewerAttributeRegistry var = new ViewerAttributeRegistry();
		            ViewerManager.sendDDERefreshAction(var);
				}
			}				
		}
	};

	private MouseListener sendDdeMouseListener = new MouseListener() {
		
		public void mouseUp(MouseEvent e) {
			
		}
		
		public void mouseDown(MouseEvent e) {
			if (ctrlPressed || !isDirty()) {
				if (TexlipsePlugin.getDefault().getPreferenceStore().getBoolean(TexlipseProperties.BUILDER_KEEP_SYNCHRONIZED)) {
		            ViewerAttributeRegistry var = new ViewerAttributeRegistry();
		            ViewerManager.sendDDERefreshAction(var);
				}
			}				
		}
		
		public void mouseDoubleClick(MouseEvent e) {
		}
	};

    
    /**
     * Constructs a new editor.
     */
    public TexEditor() {
        super();
        //setRangeIndicator(new DefaultRangeIndicator());
        folder = new TexCodeFolder(this);
    }

	private Listener resizeListener = new Listener() {
		
		private MarginPainter marginPainter;
		
		public void handleEvent(Event event) {
			
			int lineLength = TexlipsePlugin.getDefault().getPreferenceStore().getInt(TexlipseProperties.WORDWRAP_LENGTH);

			if (marginPainter == null) {
				marginPainter = new MarginPainter(getSourceViewer());
			}
			marginPainter.setMarginRulerColor(getSharedColors().getColor(PreferenceConverter.getColor(getPreferenceStore(), AbstractDecoratedTextEditorPreferenceConstants.EDITOR_PRINT_MARGIN_COLOR)));
			marginPainter.setMarginRulerColumn(lineLength);
			
			if (getSourceViewer() != null && getSourceViewer().getTextWidget() != null) {
				StyledText textWidget = getSourceViewer().getTextWidget();

				if (slider != null) {
					slider.setMinimum(0);
					slider.setMaximum(textWidget.getSize().x / JFaceTextUtil.getAverageCharWidth(textWidget));
					slider.setSelection(lineLength);
					slider.setIncrement(1);
					slider.setPageIncrement(1);
					slider.setThumb(1);
	    			slider.setToolTipText(String.valueOf(lineLength));
				}
				

				if (textWidget.getWordWrap()) {
					textWidget.setRightMargin(textWidget.getSize().x - lineLength * JFaceTextUtil.getAverageCharWidth(textWidget) - textWidget.getVerticalBar().getSize().x);
					if (getSourceViewer() instanceof ITextViewerExtension2) {
						((ITextViewerExtension2) getSourceViewer()).addPainter(marginPainter);
					}
				} else {
					textWidget.setRightMargin(0);
					if (getSourceViewer() instanceof ITextViewerExtension2) {
						((ITextViewerExtension2) getSourceViewer()).removePainter(marginPainter);
					}
				}
			}
		}
	};
    
	ITextListener indentTextListener = new ITextListener() {
		
		public void textChanged(TextEvent event) {
			if (getSourceViewer() != null && getSourceViewer().getTextWidget() != null) {
				StyledText textWidget = getSourceViewer().getTextWidget();
				if (event.getLength() == textWidget.getCharCount()) {
					doWrapIndent();
				} else {
					int lineNumber = textWidget.getLineAtOffset(event.getOffset());
					doWrapIndentLine(lineNumber);
				}
			}
		}
	};

	private void doWrapIndentLine(int lineNumber) {
		if (getSourceViewer() != null && getSourceViewer().getTextWidget() != null) {
			StyledText textWidget = getSourceViewer().getTextWidget();
			String line = textWidget.getLine(lineNumber);
			int chars = 0;
			loop : for (byte c : line.getBytes()) {
				switch (c) {
					case ' ':
						chars++;
						break;
					case '\t':
						chars += getSourceViewerConfiguration().getTabWidth(getSourceViewer());
						break;
					default:
						break loop;
				}
			}
			textWidget.setLineWrapIndent(lineNumber, 1, chars * JFaceTextUtil.getAverageCharWidth(textWidget));
		}
	}

	private void doWrapIndent() {
		if (getSourceViewer() != null && getSourceViewer().getTextWidget() != null) {
			StyledText textWidget = getSourceViewer().getTextWidget();
			for (int i = 0; i < textWidget.getLineCount(); i++) {
				doWrapIndentLine(i);
			}
		}
	}

	
    /** 
     * Create the part control.
     * 
     * @see org.eclipse.ui.IWorkbenchPart#createPartControl(org.eclipse.swt.widgets.Composite)
     */
    public void createPartControl(Composite parent) {
    	Composite top = new Composite(parent, SWT.NONE);
    	top.setLayoutData(GridDataFactory.fillDefaults().create());
		top.setLayout(GridLayoutFactory.fillDefaults().spacing(0, 0).create());
		
    	slider = new Slider(top, SWT.NONE);
    	slider.setLayoutData(GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).grab(true, false).create());
    	slider.addSelectionListener(new SelectionAdapter() {
    		@Override
    		public void widgetSelected(SelectionEvent e) {
    			TexlipsePlugin.getDefault().getPreferenceStore().setValue(TexlipseProperties.WORDWRAP_LENGTH, slider.getSelection());
    			resizeListener.handleEvent(new Event());
    		}
		});
    	
    	Composite editorComposite = new Composite(top, SWT.NONE);
    	editorComposite.setLayoutData(GridDataFactory.fillDefaults().align(SWT.FILL, SWT.FILL).grab(true, true).create());
    	editorComposite.setLayout(new FillLayout());
    	
        super.createPartControl(editorComposite);
        
        // enable projection support (for code folder)
        ProjectionViewer projectionViewer = (ProjectionViewer) getSourceViewer();
        fProjectionSupport = new ProjectionSupport(projectionViewer,
                getAnnotationAccess(), getSharedColors());
        fProjectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.error");
        fProjectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.warning");
        fProjectionSupport.install();
    
        if (TexlipsePlugin.getDefault().getPreferenceStore().getBoolean(TexlipseProperties.CODE_FOLDING)) {
        	projectionViewer.doOperation(ProjectionViewer.TOGGLE);
        }
        
        fAnnotationUpdater = new TexlipseAnnotationUpdater(this);
        
        ((IPostSelectionProvider) getSelectionProvider()).addPostSelectionChangedListener(
                new ISelectionChangedListener(){
                    public void selectionChanged(SelectionChangedEvent event) {
                        //Delete all StatuslineErrors after selection changes
                        documentModel.removeStatusLineErrorMessage();
                    }
                });

        // register documentModel as documentListener
        // in initializeEditor this would cause NPE
        this.getDocumentProvider().getDocument(getEditorInput()).addDocumentListener(this.documentModel);
        this.documentModel.initializeModel();
        this.documentModel.updateNow();

        ISourceViewer sourceViewer = getSourceViewer();
        if (sourceViewer instanceof ITextViewerExtension) {
            if (fBracketInserter == null)
                fBracketInserter = new BracketInserter(getSourceViewer(), this);
            ((ITextViewerExtension) sourceViewer).prependVerifyKeyListener(fBracketInserter);
        }
        
        
        final StyledText textWidget = sourceViewer.getTextWidget();
        textWidget.addKeyListener(sendDdeKeyListener);
        textWidget.addMouseListener(sendDdeMouseListener);
        textWidget.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				textWidget.removeKeyListener(sendDdeKeyListener);
				textWidget.removeMouseListener(sendDdeMouseListener);
				textWidget.removeDisposeListener(this);
			}
		});

		if (TexlipsePlugin.getDefault().getPreferenceStore().getBoolean(TexlipseProperties.WORDWRAP_DEFAULT)) {
			String wrapStyle = TexlipsePlugin.getPreference(TexlipseProperties.WORDWRAP_TYPE);
			if (wrapStyle.equals(TexlipseProperties.WORDWRAP_TYPE_SOFT)) {
				TexAutoIndentStrategy.setHardWrap(false);
				textWidget.setWordWrap(true);
				textWidget.addListener(SWT.Resize, resizeListener);
			} else if (wrapStyle.equals(TexlipseProperties.WORDWRAP_TYPE_HARD)) {
				textWidget.removeListener(SWT.Resize, resizeListener);
				TexAutoIndentStrategy.setHardWrap(true);
				textWidget.setWordWrap(false);
			}
		}
		textWidget.addListener(SWT.Activate, resizeListener);
		getViewer().addTextListener(indentTextListener);
    }

    /** 
     * Initialize TexDocumentModel and enable latex support in projects
     * other than Latex Project.
     * 
     * @see org.eclipse.ui.texteditor.AbstractDecoratedTextEditor#initializeEditor()
     */
    protected void initializeEditor() {
        super.initializeEditor();
        setEditorContextMenuId("net.sourceforge.texlipse.texEditorScope");
        this.documentModel = new TexDocumentModel(this);
        setSourceViewerConfiguration(new TexSourceViewerConfiguration(this));
        // register a document provider to get latex support even in non-latex projects
        if (getDocumentProvider() == null) {
            setDocumentProvider(new TexDocumentProvider());
        }
    }
    
    /** 
     * Create, configure and return the SourceViewer.
     * 
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#createSourceViewer(org.eclipse.swt.widgets.Composite, org.eclipse.jface.text.source.IVerticalRuler, int)
     */
    protected ISourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles) {
        ProjectionViewer viewer = new ProjectionViewer(parent, ruler, getOverviewRuler(), true, styles); 
        getSourceViewerDecorationSupport(viewer);
        return viewer;
    }

    /** 
     * @see org.eclipse.ui.texteditor.AbstractDecoratedTextEditor#configureSourceViewerDecorationSupport(org.eclipse.ui.texteditor.SourceViewerDecorationSupport)
     */
    protected void configureSourceViewerDecorationSupport(SourceViewerDecorationSupport support) {
        // copy the necessary values from plugin preferences instead of overwriting editor preferences
        getPreferenceStore().setValue(TexlipseProperties.MATCHING_BRACKETS, TexlipsePlugin.getPreference(TexlipseProperties.MATCHING_BRACKETS));
        getPreferenceStore().setValue(TexlipseProperties.MATCHING_BRACKETS_COLOR, TexlipsePlugin.getPreference(TexlipseProperties.MATCHING_BRACKETS_COLOR));
        
        support.setCharacterPairMatcher(fBracketMatcher);
        support.setMatchingCharacterPainterPreferenceKeys(TexlipseProperties.MATCHING_BRACKETS, TexlipseProperties.MATCHING_BRACKETS_COLOR);

        super.configureSourceViewerDecorationSupport(support);
    }
    
    /** 
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#createActions()
     */
    protected void createActions() {
        super.createActions();
        
        IAction a = new TextOperationAction(TexlipsePlugin.getDefault().getResourceBundle(), "ContentAssistProposal.", this, ISourceViewer.CONTENTASSIST_PROPOSALS);
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
        setAction("ContentAssistProposal", a);
    }
    
    /**
     * @return The source viewer of this editor
     */
    public ISourceViewer getViewer(){
    	return getSourceViewer();
    }
    
    /**
     * Used by platform to get the OutlinePage and ProjectionSupport 
     * adapter. 
     *  
     * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
     */
    public Object getAdapter(Class required) {
        if (IContentOutlinePage.class.equals(required)) {
            if (this.outlinePage == null) {
                this.outlinePage = new TexOutlinePage(this);
                this.documentModel.updateOutline();
            }
            return outlinePage;
        } else if (fProjectionSupport != null) {
            Object adapter = fProjectionSupport.getAdapter(getSourceViewer(), required);
            if (adapter != null)
                return adapter;
        }
        return super.getAdapter(required);
    }
    
    /**
     * @return The outline page associated with this editor
     */
    public TexOutlinePage getOutlinePage() {
        return this.outlinePage;
    }
    
    /**
     * @return Returns the documentModel.
     */
    public TexDocumentModel getDocumentModel() {
        return documentModel;
    }

    /**
     * @return the preference store of this editor
     */
    public IPreferenceStore getPreferences() {
        return getPreferenceStore();
    }

    /** 
     * Triggers parsing. If there is a way to determine whether the
     * platform is currently being shut down, triggering of parsing in 
     * such a case could be skipped.
     * 
     * @see org.eclipse.ui.ISaveablePart#doSave(org.eclipse.core.runtime.IProgressMonitor)
     */
    public void doSave(IProgressMonitor monitor) {
        super.doSave(monitor);
        this.documentModel.updateNow();
    }
    
    /**
     * Updates the code folding of this editor.
     * 
     * @param rootNodes The document tree that correspond to folds
     * @param monitor A progress monitor for the job doing the update
     */
    public void updateCodeFolder(ArrayList rootNodes, IProgressMonitor monitor) {
        this.folder.update(rootNodes);        
    }

    /**
     * Triggers the model to be updated as soon as possible.
     * 
     * Used by drag'n'drop and copy paste actions of the outline.
     */
    public void updateModelNow() {
    	this.documentModel.updateNow();
    }
    
    /**
     * Used by outline to determine whether drag'n'drop operations
     * are permitted.
     * 
     * @return true if current model is dirty
     */
    public boolean isModelDirty() {
        return this.documentModel.isDirty();
    }

    /**
     * @see org.eclipse.ui.IWorkbenchPart#dispose()
     */
    public void dispose() {
        super.dispose();
    }
    
//  B----------------------------------- mmaus
    
    /**
     * 
     * @return the fullOutline view.
     */
    public TexOutlineTreeView getFullOutline() {
        return fullOutline;
    }
    
    /**
     * register the full outline.
     * @param view the view.
     */
    public void registerFullOutline(TexOutlineTreeView view) {
        boolean projectChange = false;
        if (view == null || view.getEditor() == null) {
            projectChange = true;
        }
        else if (view.getEditor().getEditorInput() instanceof IFileEditorInput) {
            IFileEditorInput oldInput = (IFileEditorInput) view.getEditor().getEditorInput();
            IProject newProject = getProject();
            // Check whether the project changes
            if (!oldInput.getFile().getProject().equals(newProject))
                projectChange = true;
        } else
            projectChange = true;

        this.fullOutline = view;
        this.fullOutline.setEditor(this);
        if (projectChange) {
            //If the project changes we have to update the fulloutline
            this.fullOutline.projectChanged();
            this.documentModel.updateNow();
        }
    }
    
    /**
     * unregister the full outline if the view is closed.
     * @param view the view.
     */
    public void unregisterFullOutline(TexOutlineTreeView view) {
        this.fullOutline = null;
        
    }
    
    public IDocument getTexDocument(){
        return this.getDocumentProvider().getDocument(getEditorInput());
    } 
    
//  E----------------------------------- mmaus
    
    /**
     * @return The project that belongs to the current file
     * or null if it does not belong to any project
     */
    public IProject getProject() {
        IResource res = (IResource) getEditorInput().getAdapter(IResource.class);
        if (res == null) return null;
        else return res.getProject();
    }
    
    /**
     * Initializes the key binding scopes of this editor.
     */
    protected void initializeKeyBindingScopes() {
        setKeyBindingScopes(new String[] { "org.eclipse.ui.textEditorScope", "net.sourceforge.texlipse.texEditorScope" });  //$NON-NLS-1$
    }
}

