/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.ui.views.markers.internal;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.mapping.ResourceMapping;
import org.eclipse.core.resources.mapping.ResourceMappingContext;
import org.eclipse.core.resources.mapping.ResourceTraversal;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.help.HelpSystem;
import org.eclipse.help.IContext;
import org.eclipse.help.IContextProvider;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DragSourceListener;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.HelpEvent;
import org.eclipse.swt.events.HelpListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.XMLMemento;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ContributionItemFactory;
import org.eclipse.ui.actions.SelectionProviderAction;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.internal.ide.IDEWorkbenchMessages;
import org.eclipse.ui.internal.ide.IDEWorkbenchPlugin;
import org.eclipse.ui.part.IShowInSource;
import org.eclipse.ui.part.MarkerTransfer;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.preferences.ViewPreferencesAction;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.progress.WorkbenchJob;
import org.eclipse.ui.views.tasklist.ITaskListResourceAdapter;

/**
 * MarkerView is the abstract super class of the marker based views.
 * 
 */
public abstract class MarkerView extends TableView {

	private static final String TAG_SELECTION = "selection"; //$NON-NLS-1$

	private static final String TAG_MARKER = "marker"; //$NON-NLS-1$

	private static final String TAG_RESOURCE = "resource"; //$NON-NLS-1$

	private static final String TAG_ID = "id"; //$NON-NLS-1$

	private static final String TAG_FILTERS_SECTION = "filters"; //$NON-NLS-1$

	private static final String TAG_FILTER_ENTRY = "filter"; //$NON-NLS-1$

	private static final String MENU_FILTERS_GROUP = "group.filter";//$NON-NLS-1$

	private static final String MENU_SHOW_IN_GROUP = "group.showIn";//$NON-NLS-1$

	// Section from a 3.1 or earlier workbench
	private static final String OLD_FILTER_SECTION = "filter"; //$NON-NLS-1$

	private class UpdateJob extends WorkbenchJob {

		private Collection pendingMarkerUpdates = Collections
				.synchronizedSet(new HashSet());

		boolean refreshAll = false;

		UpdateJob() {
			super(MarkerMessages.MarkerView_queueing_updates);
		}

		/**
		 * Refresh the changed list.
		 * 
		 * @param changed
		 */
		void refresh(MarkerList changed) {
			if (refreshAll)
				return;
			pendingMarkerUpdates.addAll(changed.asList());
		}

		/**
		 * Refresh the whole view
		 */
		void refreshAll() {
			refreshAll = true;
			pendingMarkerUpdates.clear();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
		 */
		public IStatus runInUIThread(IProgressMonitor monitor) {

			if (refreshAll) {
				getViewer().refresh(true);
				refreshAll = false;
				return Status.OK_STATUS;
			}

			if (!pendingMarkerUpdates.isEmpty()) {
				Object[] markers = pendingMarkerUpdates.toArray();
				for (int i = 0; i < markers.length; i++) {
					getViewer().refresh(markers[i], true);
				}
				pendingMarkerUpdates.clear();
			}

			pendingMarkerUpdates.clear();
			return Status.OK_STATUS;
		}

		
	}

	private UpdateJob updateJob = new UpdateJob();

	// A private field for keeping track of the number of markers
	// before the busy testing started
	private int preBusyMarkers = 0;

	protected Object[] focusElements;

	private Clipboard clipboard;

	IResourceChangeListener resourceListener = new IResourceChangeListener() {
		public void resourceChanged(IResourceChangeEvent event) {

			String[] markerTypes = getMarkerTypes();
			Collection changedMarkers = new ArrayList();
			Collection addedMarkers = new ArrayList();
			Collection removedMarkers = new ArrayList();

			for (int idx = 0; idx < markerTypes.length; idx++) {
				IMarkerDelta[] markerDeltas = event.findMarkerDeltas(
						markerTypes[idx], true);
				for (int i = 0; i < markerDeltas.length; i++) {
					IMarkerDelta delta = markerDeltas[i];
					int kind = delta.getKind();

					if (kind == IResourceDelta.CHANGED) {
						changedMarkers.add(delta.getMarker());
					}
					if (kind == IResourceDelta.ADDED) {
						addedMarkers.add(delta.getMarker());
					}
					if (kind == IResourceDelta.REMOVED) {
						removedMarkers.add(delta.getMarker());
					}
				}

			}

			if (!changedMarkers.isEmpty()) {
				MarkerList changed = getCurrentMarkers().findMarkers(
						changedMarkers);
				if (changed.getItemCount() > 0) {
					changed.refresh();
					updateJob.refresh(changed);
					getProgressService().schedule(updateJob);
				}
			}

			if (addRefreshRequired(addedMarkers) || removeRefreshRequired(removedMarkers)) {
				updateJob.refreshAll();
				getProgressService().schedule(updateJob);
			}

		}

		/**
		 * Return whether or not any of the removedMarkers are being 
		 * shown.
		 * @param removedMarkers
		 * @return <code>boolean</code>
		 */
		private boolean removeRefreshRequired(Collection removedMarkers) {
			if(removedMarkers.isEmpty())
				return false;
			
			MarkerList currentList = getCurrentMarkers();
			Iterator removes = removedMarkers.iterator();
			while(removes.hasNext()){
				if(currentList.getMarker((IMarker) removes.next())!= null)
					return true;
			}
			
			return false;
		}

		/**
		 * Preprocess to see if an update is required.
		 * 
		 * @param addedMarkers
		 * @return boolean
		 */
		private boolean addRefreshRequired(Collection addedMarkers) {
			if (addedMarkers.isEmpty())
				return false;
			MarkerFilter[] filters = getEnabledFilters();
			for (int i = 0; i < filters.length; i++) {
				Iterator added = addedMarkers.iterator();
				while (added.hasNext()) {
					try {
						if (filters[i].select(MarkerList
								.createMarker((IMarker) added.next())))
							return true;
					} catch (CoreException e) {
						IDEWorkbenchPlugin.getDefault().getLog().log(
								e.getStatus());
					}
				}
			}
			return false;
		}

	};

	private class ContextProvider implements IContextProvider {
		public int getContextChangeMask() {
			return SELECTION;
		}

		public IContext getContext(Object target) {
			String contextId = null;
			// See if there is a context registered for the current selection
			ConcreteMarker marker = (ConcreteMarker) ((IStructuredSelection) getViewer()
					.getSelection()).getFirstElement();
			if (marker != null) {
				contextId = IDE.getMarkerHelpRegistry().getHelp(
						marker.getMarker());
			}

			if (contextId == null) {
				contextId = getStaticContextId();
			}
			return HelpSystem.getContext(contextId);
		}

		public String getSearchExpression(Object target) {
			return null;
		}
	}

	private ContextProvider contextProvider = new ContextProvider();

	protected ActionCopyMarker copyAction;

	protected ActionPasteMarker pasteAction;

	protected SelectionProviderAction revealAction;

	protected SelectionProviderAction openAction;

	protected SelectionProviderAction deleteAction;

	protected SelectionProviderAction selectAllAction;

	protected SelectionProviderAction propertiesAction;

	private ISelectionListener focusListener = new ISelectionListener() {
		public void selectionChanged(IWorkbenchPart part, ISelection selection) {
			MarkerView.this.focusSelectionChanged(part, selection);
		}
	};

	private int totalMarkers = 0;

	WorkbenchJob countUpdateJob;

	private MarkerFilter[] markerFilters = new MarkerFilter[0];

	// A cache of the enabled filters
	private MarkerFilter[] enabledFilters = null;

	private MenuManager filtersMenu;

	private MenuManager showInMenu;

	/**
	 * Get the current markers for the receiver.
	 * 
	 * @return MarkerList
	 */
	public MarkerList getCurrentMarkers() {
		return ((MarkerAdapter) getViewerInput()).getCurrentMarkers();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IViewPart#init(org.eclipse.ui.IViewSite,
	 *      org.eclipse.ui.IMemento)
	 */
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		IWorkbenchSiteProgressService progressService = getProgressService();
		if (progressService != null) {
			getProgressService().showBusyForFamily(
					ResourcesPlugin.FAMILY_MANUAL_BUILD);
			getProgressService().showBusyForFamily(
					ResourcesPlugin.FAMILY_AUTO_BUILD);
		}
		loadFiltersPreferences();

	}

	/**
	 * Load the filters preference.
	 */
	private void loadFiltersPreferences() {

		String preference = IDEWorkbenchPlugin.getDefault()
				.getPreferenceStore().getString(getFiltersPreferenceName());

		if (preference.equals(IPreferenceStore.STRING_DEFAULT_DEFAULT)) {
			createDefaultFilter();
			return;
		}

		StringReader reader = new StringReader(preference);
		try {
			restoreFilters(XMLMemento.createReadRoot(reader));
		} catch (WorkbenchException e) {
			IDEWorkbenchPlugin.getDefault().getLog().log(e.getStatus());
		}

	}

	/**
	 * Update for filter changes. Save the preference and clear the enabled
	 * cache.
	 */
	void updateForFilterChanges() {

		XMLMemento memento = XMLMemento.createWriteRoot(TAG_FILTERS_SECTION);

		MarkerFilter[] filters = getUserFilters();
		for (int i = 0; i < filters.length; i++) {
			IMemento child = memento.createChild(TAG_FILTER_ENTRY, filters[i]
					.getName());
			filters[i].saveFilterSettings(child);
		}

		StringWriter writer = new StringWriter();
		try {
			memento.save(writer);
		} catch (IOException e) {
			IDEWorkbenchPlugin.getDefault().getLog().log(Util.errorStatus(e));
		}

		IDEWorkbenchPlugin.getDefault().getPreferenceStore().putValue(
				getFiltersPreferenceName(), writer.toString());
		IDEWorkbenchPlugin.getDefault().savePluginPreferences();

		clearEnabledFilters();
	}

	/**
	 * Get the name of the filters preference for instances of the receiver.
	 * 
	 * @return String
	 */
	abstract String getFiltersPreferenceName();

	/**
	 * Restore the filters from the mimento.
	 * 
	 * @param memento
	 */
	private void restoreFilters(IMemento memento) {

		IMemento[] sections = null;
		if (memento != null)
			sections = memento.getChildren(TAG_FILTER_ENTRY);

		if (sections == null) {
			// Check if we have an old filter setting around
			IDialogSettings mainSettings = getDialogSettings();
			IDialogSettings filtersSection = mainSettings
					.getSection(OLD_FILTER_SECTION);
			if (filtersSection != null) {
				MarkerFilter markerFilter = createFilter(MarkerMessages.MarkerFilter_defaultFilterName);
				markerFilter.restoreFilterSettings(filtersSection);
				setFilters(new MarkerFilter[] { markerFilter });
			}

		} else {
			MarkerFilter[] newFilters = new MarkerFilter[sections.length];

			for (int i = 0; i < sections.length; i++) {
				newFilters[i] = createFilter(sections[i].getID());
				newFilters[i].restoreState(sections[i]);
			}
			setFilters(newFilters);
		}

		if (markerFilters.length == 0) {// Make sure there is at least a default
			createDefaultFilter();
		}

	}

	/**
	 * Create a default filter for the receiver.
	 * 
	 */
	private void createDefaultFilter() {
		MarkerFilter filter = createFilter(MarkerMessages.MarkerFilter_defaultFilterName);
		filter.resetState();
		setFilters(new MarkerFilter[] { filter });
	}

	/**
	 * Create a filter called name.
	 * 
	 * @param name
	 * @return MarkerFilter
	 */
	protected abstract MarkerFilter createFilter(String name);

	/**
	 * Return the memento tag for the receiver.
	 * 
	 * @return String
	 */
	protected abstract String getSectionTag();

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#createPartControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createPartControl(Composite parent) {
		clipboard = new Clipboard(parent.getDisplay());

		super.createPartControl(parent);

		initDragAndDrop();

		getSite().getPage().addSelectionListener(focusListener);
		focusSelectionChanged(getSite().getPage().getActivePart(), getSite()
				.getPage().getSelection());
		ResourcesPlugin.getWorkspace().addResourceChangeListener(
				resourceListener);
		getViewer().refresh();

		// Set help on the view itself
		getViewer().getControl().addHelpListener(new HelpListener() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.HelpListener#helpRequested(org.eclipse.swt.events.HelpEvent)
			 */
			public void helpRequested(HelpEvent e) {
				IContext context = contextProvider.getContext(getViewer()
						.getControl());
				PlatformUI.getWorkbench().getHelpSystem().displayHelp(context);
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
	 */
	public Object getAdapter(Class adaptable) {
		if (adaptable.equals(IContextProvider.class))
			return contextProvider;
		if (adaptable.equals(IShowInSource.class)) {
			return new IShowInSource() {
				public ShowInContext getShowInContext() {
					ISelection selection = getViewer().getSelection();
					if (!(selection instanceof IStructuredSelection))
						return null;
					IStructuredSelection structured = (IStructuredSelection) selection;
					Iterator markerIterator = structured.iterator();
					List newSelection = new ArrayList();
					while (markerIterator.hasNext()) {
						ConcreteMarker element = (ConcreteMarker) markerIterator
								.next();
						newSelection.add(element.getResource());
					}
					return new ShowInContext(getViewer().getInput(),
							new StructuredSelection(newSelection));
				}

			};
		}
		return super.getAdapter(adaptable);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.markers.internal.TableView#viewerSelectionChanged(org.eclipse.jface.viewers.IStructuredSelection)
	 */
	protected void viewerSelectionChanged(IStructuredSelection selection) {

		updateStatusMessage(selection);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#dispose()
	 */
	public void dispose() {
		super.dispose();

		ResourcesPlugin.getWorkspace().removeResourceChangeListener(
				resourceListener);
		getSite().getPage().removeSelectionListener(focusListener);

		// dispose of selection provider actions (may not have been created yet
		// if
		// createPartControls was never called)
		if (openAction != null) {
			openAction.dispose();
			copyAction.dispose();
			selectAllAction.dispose();
			deleteAction.dispose();
			revealAction.dispose();
			propertiesAction.dispose();
			clipboard.dispose();

		}
		if (showInMenu != null)
			showInMenu.dispose();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#createActions()
	 */
	protected void createActions() {
		revealAction = new ActionRevealMarker(this, getViewer());
		openAction = new ActionOpenMarker(this, getViewer());
		copyAction = new ActionCopyMarker(this, getViewer());
		copyAction.setClipboard(clipboard);
		copyAction.setProperties(getSortingFields());
		pasteAction = new ActionPasteMarker(this, getViewer());
		pasteAction.setClipboard(clipboard);
		pasteAction.setPastableTypes(getMarkerTypes());
		deleteAction = new ActionRemoveMarker(this, getViewer());
		selectAllAction = new ActionSelectAll(this);

		propertiesAction = new ActionMarkerProperties(this, getViewer());

		super.createActions();

		setFilterAction(new FiltersAction(this));

		setPreferencesAction(new ViewPreferencesAction() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.ui.preferences.ViewPreferencesAction#openViewPreferencesDialog()
			 */
			public void openViewPreferencesDialog() {
				openPreferencesDialog(getMarkerEnablementPreferenceName(),
						getMarkerLimitPreferenceName());

			}

		});
	}

	/**
	 * Open a dialog to set the preferences.
	 * 
	 * @param markerEnablementPreferenceName
	 * @param markerLimitPreferenceName
	 */
	private void openPreferencesDialog(String markerEnablementPreferenceName,
			String markerLimitPreferenceName) {

		Dialog dialog = new MarkerViewPreferenceDialog(getSite()
				.getWorkbenchWindow().getShell(),
				markerEnablementPreferenceName, markerLimitPreferenceName,
				MarkerMessages.MarkerPreferences_DialogTitle);
		if (dialog.open() == Window.OK)
			getViewer().refresh();

	}

	/**
	 * Get the name of the marker enablement preference.
	 * 
	 * @return String
	 */
	abstract String getMarkerLimitPreferenceName();

	abstract String[] getMarkerTypes();

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#initToolBar(org.eclipse.jface.action.IToolBarManager)
	 */
	protected void initToolBar(IToolBarManager tbm) {
		tbm.add(deleteAction);
		tbm.add(getFilterAction());
		tbm.update(false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#registerGlobalActions(org.eclipse.ui.IActionBars)
	 */
	protected void registerGlobalActions(IActionBars actionBars) {
		actionBars.setGlobalActionHandler(ActionFactory.COPY.getId(),
				copyAction);
		actionBars.setGlobalActionHandler(ActionFactory.PASTE.getId(),
				pasteAction);
		actionBars.setGlobalActionHandler(ActionFactory.DELETE.getId(),
				deleteAction);
		actionBars.setGlobalActionHandler(ActionFactory.SELECT_ALL.getId(),
				selectAllAction);
		actionBars.setGlobalActionHandler(ActionFactory.PROPERTIES.getId(),
				propertiesAction);
	}

	protected void initDragAndDrop() {
		int operations = DND.DROP_COPY;
		Transfer[] transferTypes = new Transfer[] {
				MarkerTransfer.getInstance(), TextTransfer.getInstance() };
		DragSourceListener listener = new DragSourceAdapter() {
			public void dragSetData(DragSourceEvent event) {
				performDragSetData(event);
			}

			public void dragFinished(DragSourceEvent event) {
			}
		};

		getViewer().addDragSupport(operations, transferTypes, listener);
	}

	/**
	 * The user is attempting to drag marker data. Add the appropriate data to
	 * the event depending on the transfer type.
	 */
	private void performDragSetData(DragSourceEvent event) {
		if (MarkerTransfer.getInstance().isSupportedType(event.dataType)) {

			event.data = getSelectedMarkers();
			return;
		}
		if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
			List selection = ((IStructuredSelection) getViewer().getSelection())
					.toList();
			try {
				IMarker[] markers = new IMarker[selection.size()];
				selection.toArray(markers);
				if (markers != null) {
					event.data = copyAction.createMarkerReport(markers);
				}
			} catch (ArrayStoreException e) {
			}
		}
	}

	/**
	 * Get the array of selected markers.
	 * 
	 * @return IMarker[]
	 */
	private IMarker[] getSelectedMarkers() {
		Object[] selection = ((IStructuredSelection) getViewer().getSelection())
				.toArray();
		ArrayList markers = new ArrayList();
		for (int i = 0; i < selection.length; i++) {
			if (selection[i] instanceof ConcreteMarker)
				markers.add(((ConcreteMarker) selection[i]).getMarker());
		}
		return (IMarker[]) markers.toArray(new IMarker[markers.size()]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#fillContextMenu(org.eclipse.jface.action.IMenuManager)
	 */
	protected void fillContextMenu(IMenuManager manager) {
		if (manager == null)
			return;
		manager.add(openAction);
		createShowInMenu(manager);
		manager.add(new Separator());
		manager.add(copyAction);
		pasteAction.updateEnablement();
		manager.add(pasteAction);
		manager.add(deleteAction);
		manager.add(selectAllAction);
		fillContextMenuAdditions(manager);
		manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
		manager.add(new Separator());
		manager.add(propertiesAction);
	}

	/**
	 * Fill the context menu for the receiver.
	 * 
	 * @param manager
	 */
	abstract void fillContextMenuAdditions(IMenuManager manager);

	/**
	 * Get the filters for the receiver.
	 * 
	 * @return MarkerFilter[]
	 */
	protected final MarkerFilter[] getUserFilters() {
		return markerFilters;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#handleKeyPressed(org.eclipse.swt.events.KeyEvent)
	 */
	protected void handleKeyPressed(KeyEvent event) {
		if (event.character == SWT.DEL && event.stateMask == 0
				&& deleteAction.isEnabled()) {
			deleteAction.run();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#handleOpenEvent(org.eclipse.jface.viewers.OpenEvent)
	 */
	protected void handleOpenEvent(OpenEvent event) {
		if(openAction.isEnabled())
			openAction.run();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#saveSelection(org.eclipse.ui.IMemento)
	 */
	protected void saveSelection(IMemento memento) {
		IStructuredSelection selection = (IStructuredSelection) getViewer()
				.getSelection();
		IMemento selectionMem = memento.createChild(TAG_SELECTION);
		for (Iterator iterator = selection.iterator(); iterator.hasNext();) {
			Object next = iterator.next();
			if (!(next instanceof ConcreteMarker))
				continue;
			ConcreteMarker marker = (ConcreteMarker) next;
			IMemento elementMem = selectionMem.createChild(TAG_MARKER);
			elementMem.putString(TAG_RESOURCE, marker.getMarker().getResource()
					.getFullPath().toString());
			elementMem.putString(TAG_ID, String.valueOf(marker.getMarker()
					.getId()));
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.internal.tableview.TableView#restoreSelection(org.eclipse.ui.IMemento)
	 */
	protected IStructuredSelection restoreSelection(IMemento memento) {
		if (memento == null) {
			return new StructuredSelection();
		}
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IMemento selectionMemento = memento.getChild(TAG_SELECTION);
		if (selectionMemento == null) {
			return new StructuredSelection();
		}
		ArrayList selectionList = new ArrayList();
		IMemento[] markerMems = selectionMemento.getChildren(TAG_MARKER);
		for (int i = 0; i < markerMems.length; i++) {
			try {
				long id = new Long(markerMems[i].getString(TAG_ID)).longValue();
				IResource resource = root.findMember(markerMems[i]
						.getString(TAG_RESOURCE));
				if (resource != null) {
					IMarker marker = resource.findMarker(id);
					if (marker != null)
						selectionList
								.add(getCurrentMarkers().getMarker(marker));
				}
			} catch (CoreException e) {
			}
		}
		return new StructuredSelection(selectionList);
	}

	protected abstract String[] getRootTypes();

	/**
	 * @param part
	 * @param selection
	 */
	protected void focusSelectionChanged(IWorkbenchPart part,
			ISelection selection) {

		List selectedElements = new ArrayList();
		if (part instanceof IEditorPart) {
			IEditorPart editor = (IEditorPart) part;
			IFile file = ResourceUtil.getFile(editor.getEditorInput());
			if (file == null) {
				IEditorInput editorInput = editor.getEditorInput();
				if (editorInput != null) {
					Object mapping = editorInput
							.getAdapter(ResourceMapping.class);
					if (mapping != null)
						selectedElements.add(mapping);
				}
			} else {
				selectedElements.add(file);
			}
		} else {
			if (selection instanceof IStructuredSelection) {
				for (Iterator iterator = ((IStructuredSelection) selection)
						.iterator(); iterator.hasNext();) {
					Object object = iterator.next();
					if (object instanceof IAdaptable) {
						ITaskListResourceAdapter taskListResourceAdapter;
						Object adapter = ((IAdaptable) object)
								.getAdapter(ITaskListResourceAdapter.class);
						if (adapter != null
								&& adapter instanceof ITaskListResourceAdapter) {
							taskListResourceAdapter = (ITaskListResourceAdapter) adapter;
						} else {
							taskListResourceAdapter = DefaultMarkerResourceAdapter
									.getDefault();
						}

						IResource resource = taskListResourceAdapter
								.getAffectedResource((IAdaptable) object);
						if (resource == null) {
							Object mapping = ((IAdaptable) object)
									.getAdapter(ResourceMapping.class);
							if (mapping != null)
								selectedElements.add(mapping);
						} else
							selectedElements.add(resource);
					}
				}
			}
		}
		updateFocusMarkers(selectedElements.toArray());
	}

	/**
	 * Update the focus resources of the filters.
	 * 
	 * @param elements
	 */
	protected final void updateFilterSelection(Object[] elements) {

		Collection resourceCollection = new ArrayList();
		for (int i = 0; i < elements.length; i++) {
			if (elements[i] instanceof IResource)
				resourceCollection.add(elements[i]);
			else
				addResources(resourceCollection,
						((ResourceMapping) elements[i]));
		}

		IResource[] resources = new IResource[resourceCollection.size()];
		resourceCollection.toArray(resources);

		for (int i = 0; i < markerFilters.length; i++) {
			markerFilters[i].setFocusResource(resources);
		}
	}

	/**
	 * Add the resources for the mapping to resources.
	 * 
	 * @param resources
	 * @param mapping
	 */
	private void addResources(Collection resources, ResourceMapping mapping) {
		try {
			ResourceTraversal[] traversals = mapping.getTraversals(
					ResourceMappingContext.LOCAL_CONTEXT,
					new NullProgressMonitor());
			for (int i = 0; i < traversals.length; i++) {
				ResourceTraversal traversal = traversals[i];
				IResource[] result = traversal.getResources();
				for (int j = 0; j < result.length; j++) {
					resources.add(result[j]);
				}
			}
		} catch (CoreException e) {
			IDEWorkbenchPlugin.getDefault().getLog().log(e.getStatus());
			return;
		}

	}

	protected abstract String getStaticContextId();

	/**
	 * Update the focus markers for the supplied elements.
	 * 
	 * @param elements
	 */
	void updateFocusMarkers(Object[] elements) {
		boolean updateNeeded = updateNeeded(focusElements, elements);
		if (updateNeeded) {
			focusElements = elements;
			updateFilterSelection(elements);
			getViewer().refresh();
		}
	}

	private boolean updateNeeded(Object[] oldElements, Object[] newElements) {
		// determine if an update if refiltering is required
		MarkerFilter[] filters = getEnabledFilters();
		boolean updateNeeded = false;

		for (int i = 0; i < filters.length; i++) {
			MarkerFilter filter = filters[i];
			if (!filter.isEnabled())
				continue;

			int onResource = filter.getOnResource();
			if (onResource == MarkerFilter.ON_ANY
					|| onResource == MarkerFilter.ON_WORKING_SET) {
				continue;
			}
			if (newElements == null || newElements.length < 1) {
				continue;
			}
			if (oldElements == null || oldElements.length < 1) {
				return true;
			}
			if (Arrays.equals(oldElements, newElements)) {
				continue;
			}
			if (onResource == MarkerFilter.ON_ANY_IN_SAME_CONTAINER) {
				Collection oldProjects = MarkerFilter
						.getProjectsAsCollection(oldElements);
				Collection newProjects = MarkerFilter
						.getProjectsAsCollection(newElements);

				if (oldProjects.size() == newProjects.size()) {
					if (newProjects.containsAll(oldProjects))
						continue;
				}

				return true;
			}
			updateNeeded = true;// We are updating as there is nothing to stop
			// us
		}

		return updateNeeded;
	}

	void updateTitle() {
		String status = Util.EMPTY_STRING;
		int filteredCount = getCurrentMarkers().getItemCount();
		int totalCount = getTotalMarkers();
		if (filteredCount == totalCount) {
			status = NLS.bind(MarkerMessages.filter_itemsMessage, new Integer(
					totalCount));
		} else {
			status = NLS.bind(MarkerMessages.filter_matchedMessage,
					new Integer(filteredCount), new Integer(totalCount));
		}
		setContentDescription(status);
	}

	/**
	 * Updates the message displayed in the status line. This method is invoked
	 * in the following cases:
	 * <ul>
	 * <li>when this view is first created</li>
	 * <li>when new elements are added</li>
	 * <li>when something is deleted</li>
	 * <li>when the filters change</li>
	 * </ul>
	 * <p>
	 * By default, this method calls
	 * <code>updateStatusMessage(IStructuredSelection)</code> with the current
	 * selection or <code>null</code>. Classes wishing to override this
	 * functionality, should just override the method
	 * <code>updateStatusMessage(IStructuredSelection)</code>.
	 * </p>
	 */
	protected void updateStatusMessage() {
		ISelection selection = getViewer().getSelection();

		if (selection instanceof IStructuredSelection)
			updateStatusMessage((IStructuredSelection) selection);
		else
			updateStatusMessage(null);
	}

	/**
	 * Updates that message displayed in the status line. If the selection
	 * parameter is <code>null</code> or its size is 0, the status area is
	 * blanked out. If only 1 marker is selected, the status area is updated
	 * with the contents of the message attribute of this marker. In other cases
	 * (more than one marker is selected) the status area indicates how many
	 * items have been selected.
	 * <p>
	 * This method may be overwritten.
	 * </p>
	 * <p>
	 * This method is called whenever a selection changes in this view.
	 * </p>
	 * 
	 * @param selection
	 *            a valid selection or <code>null</code>
	 */
	protected void updateStatusMessage(IStructuredSelection selection) {
		String message = ""; //$NON-NLS-1$

		if (selection == null || selection.size() == 0) {
			// Show stats on all items in the view
			message = updateSummaryVisible();
		} else if (selection.size() == 1) {
			// Use the Message attribute of the marker
			Object first = selection.getFirstElement();
			if (first instanceof ConcreteMarker) {
				message = ((ConcreteMarker) first).getDescription();
			}
		} else if (selection.size() > 1) {
			// Show stats on only those items in the selection
			message = updateSummarySelected(selection);
		}
		getViewSite().getActionBars().getStatusLineManager()
				.setMessage(message);
	}

	/**
	 * @param selection
	 * @return the summary status message
	 */
	protected String updateSummarySelected(IStructuredSelection selection) {
		// Show how many items selected
		return MessageFormat.format(
				MarkerMessages.marker_statusSummarySelected,
				new Object[] { new Integer(selection.size()) });
	}

	/**
	 * @return the update summary
	 */
	protected String updateSummaryVisible() {
		return ""; //$NON-NLS-1$
	}

	/**
	 * Open a dialog on the filters
	 * 
	 */
	public final void openFiltersDialog() {

		DialogMarkerFilter dialog = createFiltersDialog();

		if (dialog.open() == Window.OK) {

			MarkerFilter[] result = dialog.getFilters();
			if (result == null)
				return;
			if (result.length == 0)
				setFilters(new MarkerFilter[] { createFilter(MarkerMessages.MarkerFilter_defaultFilterName) });
			else
				setFilters(result);

			updateForFilterChanges();
			refreshFilterMenu();
			getViewer().refresh();
		}
	}

	/**
	 * Set the filters to newFilters.
	 * 
	 * @param newFilters
	 */
	void setFilters(MarkerFilter[] newFilters) {
		markerFilters = newFilters;
	}

	/**
	 * Clear the cache of enabled filters.
	 * 
	 */
	void clearEnabledFilters() {
		enabledFilters = null;
	}

	/**
	 * Refresh the contents of the filter sub menu.
	 */
	private void refreshFilterMenu() {
		if (filtersMenu == null)
			return;
		filtersMenu.removeAll();
		MarkerFilter[] filters = getAllFilters();
		for (int i = 0; i < filters.length; i++) {
			filtersMenu.add(new FilterEnablementAction(filters[i], this));
		}

	}

	/**
	 * Open a filter dialog on the receiver.
	 */
	protected abstract DialogMarkerFilter createFiltersDialog();

	/**
	 * Given a selection of IMarker, reveals the corresponding elements in the
	 * viewer
	 * 
	 * @param structuredSelection
	 * @param reveal
	 */
	public void setSelection(IStructuredSelection structuredSelection,
			boolean reveal) {
		TreeViewer viewer = getViewer();

		List newSelection = new ArrayList(structuredSelection.size());

		for (Iterator i = structuredSelection.iterator(); i.hasNext();) {
			Object next = i.next();
			if (next instanceof IMarker) {
				ConcreteMarker marker = getCurrentMarkers().getMarker(
						(IMarker) next);
				if (marker != null) {
					newSelection.add(marker);
				}
			}
		}

		if (viewer != null)
			viewer.setSelection(new StructuredSelection(newSelection), reveal);
	}

	protected MarkerList getVisibleMarkers() {
		return getCurrentMarkers();
	}

	/**
	 * Returns the total number of markers. Should not be called while the
	 * marker list is still updating.
	 * 
	 * @return the total number of markers in the workspace (including
	 *         everything that doesn't pass the filters)
	 */
	int getTotalMarkers() {
		// The number of visible markers should never exceed the total number of
		// markers in
		// the workspace. If this assertation fails, it probably indicates some
		// sort of concurrency problem
		// (most likely, getTotalMarkers was called while we were still
		// computing the marker lists)
		// Assert.isTrue(totalMarkers >= currentMarkers.getItemCount());

		return totalMarkers;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.markers.internal.TableView#sorterChanged()
	 */
	protected void sorterChanged() {
		getViewer().refresh();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.part.WorkbenchPart#showBusy(boolean)
	 */
	public void showBusy(boolean busy) {
		super.showBusy(busy);

		if (busy) {
			preBusyMarkers = totalMarkers;
		} else {// Only bold if there has been a change in count
			if (totalMarkers != preBusyMarkers)
				getProgressService().warnOfContentChange();
		}

	}

	/**
	 * Create the UIJob used in the receiver for updates.
	 * 
	 */
	private void createUIJob() {
		countUpdateJob = new WorkbenchJob(MarkerMessages.MarkerView_refreshProgress) {

			public IStatus runInUIThread(IProgressMonitor monitor) {
				// Ensure that the view hasn't been disposed
				Tree tree = getTree();

				if (tree != null && !tree.isDisposed()) {
					updateStatusMessage();
					updateTitle();
					if(isHierarchalMode())
						getViewer().expandAll();
				}
				return Status.OK_STATUS;
			}
		};
		countUpdateJob.setPriority(Job.INTERACTIVE);
		countUpdateJob.setSystem(true);
	}

	/**
	 * Get the filters that are currently enabled.
	 * 
	 * @return MarkerFilter[]
	 */
	MarkerFilter[] getEnabledFilters() {

		if (enabledFilters == null) {
			Collection filters = findEnabledFilters();

			enabledFilters = new MarkerFilter[filters.size()];
			filters.toArray(enabledFilters);
		}
		return enabledFilters;

	}

	/**
	 * Find the filters enabled in the view.
	 * 
	 * @return Collection of MarkerFilter
	 */
	protected Collection findEnabledFilters() {
		MarkerFilter[] allFilters = getAllFilters();
		ArrayList filters = new ArrayList(0);
		for (int i = 0; i < allFilters.length; i++) {
			if (allFilters[i].isEnabled())
				filters.add(allFilters[i]);
		}
		return filters;
	}

	/**
	 * Get all of the filters applied to the receiver.
	 * 
	 * @return MarkerFilter[]
	 */
	MarkerFilter[] getAllFilters() {
		return getUserFilters();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.markers.internal.TableView#addDropDownContributions(org.eclipse.jface.action.IMenuManager)
	 */
	void addDropDownContributions(IMenuManager menu) {
		super.addDropDownContributions(menu);
		menu.add(new Separator(MENU_FILTERS_GROUP));
		// Don't add in the filters until they are set
		filtersMenu = new MenuManager(MarkerMessages.filtersSubMenu_title);
		refreshFilterMenu();
		menu.appendToGroup(MENU_FILTERS_GROUP, filtersMenu);
	}

	/**
	 * Create the show in menu if there is a single selection.
	 * 
	 * @param menu
	 */
	void createShowInMenu(IMenuManager menu) {
		ISelection selection = getViewer().getSelection();
		if (!(selection instanceof IStructuredSelection))
			return;

		IStructuredSelection structured = (IStructuredSelection) selection;
		if(!Util.isSingleConcreteSelection(structured))
			return;


		menu.add(new Separator(MENU_SHOW_IN_GROUP));
		// Don't add in the filters until they are set
		showInMenu = new MenuManager(IDEWorkbenchMessages.Workbench_showIn);
		showInMenu.add(ContributionItemFactory.VIEWS_SHOW_IN
				.create(getViewSite().getWorkbenchWindow()));

		menu.appendToGroup(MENU_SHOW_IN_GROUP, showInMenu);

	}

	/**
	 * Refresh the marker counts
	 * 
	 * @param monitor
	 */
	void refreshMarkerCounts(IProgressMonitor monitor) {
		monitor.subTask(MarkerMessages.MarkerView_refreshing_counts);
		try {
			totalMarkers = MarkerList.compute(getMarkerTypes()).length;
		} catch (CoreException e) {
			IDEWorkbenchPlugin.getDefault().getLog().log(e.getStatus());
			return;
		}

	}

	/**
	 * Schedule an update of the summary counts.
	 */
	public void scheduleCountUpdate() {
		if (countUpdateJob == null)
			createUIJob();

		countUpdateJob.schedule();
		
		if(getSite().getShell().getDisplay().getThread() == Thread.currentThread())
			return; //Do not block the UI
		
		try {
			countUpdateJob.join();
		} catch (InterruptedException e) {
			return;
		}
	}

	/**
	 * Returns the marker limit or -1 if unlimited
	 * 
	 * @return int
	 */
	int getMarkerLimit() {

		// If limits are enabled return it. Otherwise return -1
		if (IDEWorkbenchPlugin.getDefault().getPreferenceStore().getBoolean(
				getMarkerEnablementPreferenceName())) {
			return IDEWorkbenchPlugin.getDefault().getPreferenceStore().getInt(
					getMarkerLimitPreferenceName());

		}
		return -1;

	}

	/**
	 * Get the name of the marker limit preference.
	 * 
	 * @return String
	 */
	abstract String getMarkerEnablementPreferenceName();

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.views.markers.internal.TableView#createViewerInput()
	 */
	Object createViewerInput() {
		return new MarkerAdapter(this);
	}

	/**
	 * Return whether or not we are showing the hierarchal or flat mode
	 * 
	 * @return <code>true</code> if hierarchal mode is being shown
	 */
	public boolean isHierarchalMode() {
		return false;
	}

	/**
	 * Return the TableSorter
	 * 
	 * @return TableSorter
	 */
	public TableSorter getTableSorter() {
		return (TableSorter) getViewer().getSorter();
	}

	/**
	 * Add a listener for the end of the update.
	 * @param listener
	 */
	public void addUpdateFinishListener(IJobChangeListener listener) {
		updateJob.addJobChangeListener(listener);

	}

	/**
	 * Remove a listener for the end of the update.
	 * @param listener
	 */
	public void removeUpdateFinishListener(IJobChangeListener listener) {
		updateJob.removeJobChangeListener(listener);

	}
}
