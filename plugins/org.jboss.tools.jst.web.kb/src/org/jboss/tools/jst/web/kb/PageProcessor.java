/******************************************************************************* 
 * Copyright (c) 2009-2013 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.tools.jst.web.kb;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.wst.sse.ui.StructuredTextEditor;
import org.jboss.tools.common.el.core.model.ELInstance;
import org.jboss.tools.common.el.core.model.ELModel;
import org.jboss.tools.common.el.core.model.ELUtil;
import org.jboss.tools.common.el.core.parser.ELParser;
import org.jboss.tools.common.el.core.parser.ELParserUtil;
import org.jboss.tools.common.el.core.resolver.ELContext;
import org.jboss.tools.common.el.core.resolver.ELResolver;
import org.jboss.tools.common.text.TextProposal;
import org.jboss.tools.common.util.EclipseUIUtil;
import org.jboss.tools.jst.web.kb.internal.XmlContextImpl;
import org.jboss.tools.jst.web.kb.internal.taglib.CustomTagLibAttribute;
import org.jboss.tools.jst.web.kb.taglib.CustomTagLibManager;
import org.jboss.tools.jst.web.kb.taglib.IAttribute;
import org.jboss.tools.jst.web.kb.taglib.IComponent;
import org.jboss.tools.jst.web.kb.taglib.IContextComponent;
import org.jboss.tools.jst.web.kb.taglib.ICustomTagLibComponent;
import org.jboss.tools.jst.web.kb.taglib.ICustomTagLibrary;
import org.jboss.tools.jst.web.kb.taglib.IFacesConfigTagLibrary;
import org.jboss.tools.jst.web.kb.taglib.ITagLibRecognizer;
import org.jboss.tools.jst.web.kb.taglib.ITagLibrary;
import org.jboss.tools.livereload.core.internal.angularjs.ContentAssistServlet;
import org.jboss.tools.livereload.core.internal.angularjs.ContentAssistWebSocket;
import org.jboss.tools.livereload.core.internal.server.jetty.JettyServerRunner;
import org.jboss.tools.livereload.core.internal.server.jetty.LiveReloadScriptFileServlet;
import org.jboss.tools.livereload.core.internal.server.jetty.LiveReloadScriptInjectionFilter;
import org.jboss.tools.livereload.core.internal.server.jetty.LiveReloadServer;
import org.jboss.tools.livereload.core.internal.server.jetty.LiveReloadWebSocketServlet;
import org.jboss.tools.livereload.core.internal.server.jetty.WorkspaceFileServlet;

/**
 * @author Alexey Kazakov
 */
public class PageProcessor {

	private static final PageProcessor INSTANCE = new PageProcessor();
	private ICustomTagLibrary[] customTagLibs;
	private CustomTagLibAttribute[] componentExtensions;

	/**
	 * @return instance of PageProcessor
	 */
	public static PageProcessor getInstance() {
		return INSTANCE;
	}

	private PageProcessor() {
		customTagLibs = CustomTagLibManager.getInstance().getLibraries();
		componentExtensions = CustomTagLibManager.getInstance().getComponentExtensions();
	}

	/**
	 * 
	 * @param query
	 * @param context
	 * @return
	 */
	public TextProposal[] getProposals(KbQuery query, ELContext context) {
		return getProposals(query, context, false);
	}

	private List<TextProposal> excludeExtendedComponents(List<TextProposal> proposals) {
		Map<String, Set<TextProposal>> runtimeComponentMap = new HashMap<String, Set<TextProposal>>();
		Map<String, TextProposal> customComponentMap = new HashMap<String, TextProposal>();
		Set<TextProposal> customNotExtendedComponents = new HashSet<TextProposal>();
		for (TextProposal proposal : proposals) {
			Object source = proposal.getSource();
			if(source instanceof IComponent) {
				IComponent component = (IComponent)source;
				String name = component.getTagLib().getURI() + ":" + component.getName(); //$NON-NLS-1$
				if(component instanceof ICustomTagLibComponent) {
					if(component.isExtended()) {
						customComponentMap.put(name, proposal);
					} else {
						customNotExtendedComponents.add(proposal);
					}
				} else {
					Set<TextProposal> textProposals = runtimeComponentMap.get(name);
					if(textProposals==null) {
						textProposals = new HashSet<TextProposal>();
					}
					textProposals.add(proposal);
					runtimeComponentMap.put(name, textProposals);
				}
			}
		}
		if(!customComponentMap.isEmpty()) {
			proposals.clear();
			for (String name : runtimeComponentMap.keySet()) {
				TextProposal customProposal = customComponentMap.get(name);
				if(customProposal!=null) {
					proposals.add(customProposal);
				} else {
					proposals.addAll(runtimeComponentMap.get(name));
				}
			}
			if(!customNotExtendedComponents.isEmpty()) {
				proposals.addAll(customNotExtendedComponents);
			}
		}
		return proposals;
	}

	Browser browser;
	Set<ContentAssistWebSocket> webSockets;
//    static boolean set;

	private List<TextProposal> getJSProposals(KbQuery query) throws Exception {
		List<TextProposal> proposals = new ArrayList<TextProposal>();
		String qValue = query.getValue();
		int startEl = qValue.indexOf("{{");
		if(startEl>-1) {

			// SWT Browser
			if(browser==null) {
				//	JSPMultiPageEditor editor = (JSPMultiPageEditor)EclipseUIUtil.getActiveEditor();
				ITextEditor editor = EclipseUIUtil.getActiveEditor();
				try {
					Method m = editor.getClass().getMethod("getSourceEditor", null);
					StructuredTextEditor edt = (StructuredTextEditor)m.invoke(editor, null);
					Composite composite = (Composite)edt.getTextViewer().getControl();

					Composite browserPanel = new Composite(composite, SWT.NONE);
					browserPanel.setVisible(false);
					browserPanel.setSize(0, 0);
					browser = new Browser(browserPanel, SWT.NONE | SWT.WEBKIT);
					browser.setUrl("http://localhost:35729/browser.cli/web/todoExample/index.html");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
//			Browser browser = null;
//			IViewPart part = WebKbPlugin.getDefault().getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(CABrowserView.ID);
//			if(part!=null) {
//				browser = ((CABrowserView)part).getBrowser();
//			}
//			if(browser!=null) {
//				if(!set) {
//					browser.setUrl("http://localhost:35729/browser.cli/web/todoExample/index.html");
//				}
//				set = true;
//				browser.refresh();
//			}

			// Livereload
			String newValue = qValue.substring(startEl + 2);
//			IServer server = WSTUtils.findLiveReloadServer();
//			if(server!=null) {
//				LiveReloadServerBehaviour b = (LiveReloadServerBehaviour)WSTUtils.findServerBehaviour(server);
//				LiveReloadServer lrServer = b.getLiveReloadServer();
		        final StringBuffer command = new StringBuffer("ORG_JBOSS_TOOLS_JST.getProposals('");
		        String[] tags = query.getParentTags();
		        for (String tag : tags) {
					command.append(tag).append('>');
				}
		        command.deleteCharAt(command.length()-1).append("', '").append(newValue).append("')");
		System.out.println("Command: " + command.toString());
//				Iterator<ContentAssistWebSocket> webSocketsIterator = lrServer.webSockets.iterator();
				if(webSockets==null) {
					webSockets = startServer();
				}
				Iterator<ContentAssistWebSocket> webSocketsIterator = webSockets.iterator();
				if (webSocketsIterator.hasNext()) {
					final ContentAssistWebSocket webSocket = webSocketsIterator.next(); // use only the first one
					try {
						long start = System.nanoTime();
//						Run task = new Run(webSocket, command.toString());
//						Display.getDefault().syncExec(task);
		//
//						String result = task.getResult();
						String result = webSocket.evaluate(command.toString(), 100);
						long stop = System.nanoTime();
		System.out.format("%s [computed in %.3fms]%n", result, (stop - start) / 1e6);
						if(result == null) {
							result = "";
						}
						StringTokenizer st = new StringTokenizer(result, ", ", false);
						int dotIndex = newValue.indexOf('.');
						StringBuilder prefix = new StringBuilder(qValue.substring(0, startEl+2));
						if(dotIndex>-1) {
							prefix.append(qValue.substring(startEl+2, startEl+dotIndex+3));
						}
						while(st.hasMoreElements()) {
							String label = st.nextToken().trim();
							TextProposal proposal = new TextProposal();
							proposal.setLabel(label);
							proposal.setReplacementString(prefix.toString() + label + "}}");
							proposal.setImageDescriptor(WebKbPlugin.getImageDescriptor(WebKbPlugin.class, "EnumerationProposal.gif"));
							proposals.add(proposal);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				} else {
					System.out.println("No clients connected.");
				}
//			}
		}
		return proposals;
	}

	private Set<ContentAssistWebSocket> startServer() throws Exception {

		LiveReloadServer liveReloadServer = new LiveReloadServer("liveroload-ca-server", "localhost", 35729, true, true, true);
		JettyServerRunner liveReloadServerRunnable = JettyServerRunner.start(liveReloadServer);

//		Server server = new Server();
//
//		SelectChannelConnector websocketConnector = new SelectChannelConnector();
//		websocketConnector.setHost("localhost");
//		websocketConnector.setStatsOn(true);
//		websocketConnector.setPort(35729);
//		websocketConnector.setMaxIdleTime(0);
//		server.addConnector(websocketConnector);
//
//		final HandlerCollection handlers = new HandlerCollection();
//		server.setHandler(handlers);
//		final ServletContextHandler context = new ServletContextHandler(handlers, "/", ServletContextHandler.NO_SESSIONS);
//		context.setConnectorNames(new String[] { websocketConnector.getName() });
//		ServletHolder liveReloadServletHolder = new ServletHolder(new LiveReloadWebSocketServlet());
//		// Fix for BrowserSim (Safari) due to check in WebSocketFactory
//		liveReloadServletHolder.setInitParameter("minVersion", "-1");
//		context.addServlet(liveReloadServletHolder, "/livereload");
//
//		ServletHolder servletHolder = new ServletHolder(new ContentAssistServlet());
//		context.addServlet(servletHolder, "/cli");
//		Set<ContentAssistWebSocket> webSockets = new CopyOnWriteArraySet<ContentAssistWebSocket>();
//		context.getServletContext().setAttribute("org.jboss.tools.jst.ContentAssistWebSockets", webSockets);
//		
//		context.addServlet(new ServletHolder(new LiveReloadScriptFileServlet()), "/livereload.js");
//		context.addServlet(new ServletHolder(new WorkspaceFileServlet()), "/*");
//		context.addFilter(new FilterHolder(new LiveReloadScriptInjectionFilter(35729)), "/*", null);
//
//        server.start();

        return liveReloadServer.webSockets;
	}

	/**
	 * 
	 * @param query
	 * @param context
	 * @return
	 */
	public TextProposal[] getProposals(KbQuery query, ELContext context, boolean preferCustomComponentExtensions) {
		List<TextProposal> proposals = new ArrayList<TextProposal>();
try {
	proposals.addAll(getJSProposals(query));
} catch (Exception e) {
	e.printStackTrace();
}

		if (!isQueryForELProposals(query, context)) {
			if(context instanceof IPageContext) {
				IPageContext pageContext = (IPageContext)context;
				ITagLibrary[] libs =  pageContext.getLibraries();
				for (int i = 0; libs != null && i < libs.length; i++) {
					if(libs[i] instanceof IFacesConfigTagLibrary) {
						continue;
					}
					TextProposal[] libProposals = libs[i].getProposals(query, pageContext);
					for (int j = 0; libProposals != null && j < libProposals.length; j++) {
						proposals.add(libProposals[j]);
					}
				}
				if (query.getType() == KbQuery.Type.ATTRIBUTE_VALUE) {
					Map<String, IAttribute> attrbMap = new HashMap<String, IAttribute>();
					for (TextProposal proposal : proposals) {
						if(proposal.getSource()!=null && proposal.getSource() instanceof IAttribute) {
							IAttribute att = (IAttribute)proposal.getSource();
							attrbMap.put(att.getName(), att);
						}
					}
					IAttribute[] attrs = getAttributes(query, pageContext, false);
					for (int i = 0; i < attrs.length; i++) {
						attrbMap.put(attrs[i].getName(), attrs[i]);
					}
					for (int i = 0; i < componentExtensions.length; i++) {
						if(attrbMap.containsKey(componentExtensions[i].getName())) {
							TextProposal[] attProposals = componentExtensions[i].getProposals(query, pageContext);
							for (int j = 0; j < attProposals.length; j++) {
								proposals.add(attProposals[j]);
							}
						}
					}
				}
				for (int i = 0; customTagLibs != null && i < customTagLibs.length; i++) {
					if(shouldLoadLib(customTagLibs[i], context)) {
						TextProposal[] libProposals = customTagLibs[i].getProposals(query, pageContext);
						for (int j = 0; libProposals != null && j < libProposals.length; j++) {
							proposals.add(libProposals[j]);
						}
					}
				}
				if(preferCustomComponentExtensions && query.getType() == KbQuery.Type.TAG_NAME) {
					proposals = excludeExtendedComponents(proposals);
				}
			}
		} else {
			String value = query.getValue();
			String elString = value;
			ELResolver[] resolvers =  context.getElResolvers();
			for (int i = 0; resolvers != null && i < resolvers.length; i++) {
				List<TextProposal> pls = resolvers[i].getProposals(context, elString, query.getOffset());
				if(pls!=null) {
					proposals.addAll(pls);
				}
			}
		}
		return proposals.toArray(new TextProposal[proposals.size()]);
	}

	private boolean shouldLoadLib(ICustomTagLibrary lib, ELContext context) {
		ITagLibRecognizer recognizer = lib.getRecognizer();
		return recognizer==null || recognizer.shouldBeLoaded(lib, context);
	}

	private boolean isQueryForELProposals(KbQuery query, ELContext context) {
		if (query.getType() == KbQuery.Type.ATTRIBUTE_VALUE ||
				(query.getType() == KbQuery.Type.TEXT &&
						(context instanceof IFaceletPageContext ||
								context instanceof XmlContextImpl))) {

			String text = query.getValue();
			if (text == null) return false;
			
			int inValueOffset = text.length();
			if (text.length() < inValueOffset) return false;
			if (inValueOffset<0) return false;
			
			ELParser p = ELParserUtil.getJbossFactory().createParser();
			ELModel model = p.parse(text);
			
			ELInstance is = ELUtil.findInstance(model, inValueOffset);// ELInstance
			boolean isELStarted = (model != null && is != null && (model.toString().startsWith("#{") ||  //$NON-NLS-1$
					model.toString().startsWith("${"))); //$NON-NLS-1$
			if (!isELStarted) return false;
			
			boolean isELClosed = (model != null && is != null && model.toString().endsWith("}")); //$NON-NLS-1$
			return !isELClosed;
		}
		
		return false;
	}
	
 	/**
	 * Returns components
	 * @param query
	 * @param context
	 * @return components
	 */
	public IComponent[] getComponents(KbQuery query, IPageContext context) {
		return getComponents(query, context, false);
	}

	public IComponent[] getComponents(KbQuery query, IPageContext context, boolean includeComponentExtensions) {
		ArrayList<IComponent> components = new ArrayList<IComponent>();
		ITagLibrary[] libs =  context.getLibraries();
		for (int i = 0; i < libs.length; i++) {
			if(libs[i] instanceof IFacesConfigTagLibrary) {
				continue;
			}
			IComponent[] libComponents = libs[i].getComponents(query, context);
			for (int j = 0; j < libComponents.length; j++) {
				if(includeComponentExtensions || !libComponents[j].isExtended()) {
					components.add(libComponents[j]);
				}
			}
		}
		for (int i = 0; customTagLibs != null && i < customTagLibs.length; i++) {
			if(shouldLoadLib(customTagLibs[i], context)) {
				IComponent[] libComponents = customTagLibs[i].getComponents(query, context);
				for (int j = 0; j < libComponents.length; j++) {
					if(includeComponentExtensions || !libComponents[j].isExtended()) {
						components.add(libComponents[j]);
					}
				}
			}
		}
		return components.toArray(new IComponent[components.size()]);
	}

	private final static IAttribute[] EMPTY_ATTRIBUTE_ARRAY = new IAttribute[0];

	/**
	 * Returns attributes
	 * @param query
	 * @param context
	 * @return attributes
	 */
	public IAttribute[] getAttributes(KbQuery query, IPageContext context) {
		return getAttributes(query, context, true);
	}

	private IAttribute[] getAttributes(KbQuery query, IPageContext context, boolean includeComponentExtensions) {
		if(query.getType() == KbQuery.Type.ATTRIBUTE_NAME || query.getType() == KbQuery.Type.ATTRIBUTE_VALUE) {
			ArrayList<IAttribute> attributes = new ArrayList<IAttribute>();
			Map<String, IAttribute> attrbMap = new HashMap<String, IAttribute>();
			IComponent[] components  = getComponents(query, context, includeComponentExtensions);
			for (int i = 0; i < components.length; i++) {
				IComponent component = components[i];
				IAttribute[] libAttributess;
				if(component instanceof IContextComponent) {
					libAttributess = ((IContextComponent)component).getAttributes(context, query, true);
				} else {
					libAttributess = component.getAttributes(query, context);
				}

				if(libAttributess!=null) {
					for (int j = 0; j < libAttributess.length; j++) {
						attributes.add(libAttributess[j]);
						attrbMap.put(libAttributess[j].getName(), libAttributess[j]);
					}
				}
			}
			if(includeComponentExtensions) {
				for (int i = 0; i < componentExtensions.length; i++) {
					if(attrbMap.containsKey(componentExtensions[i].getName())) {
						attributes.add(componentExtensions[i]);
					}
				}
			}
			return attributes.toArray(new IAttribute[attributes.size()]);
		}
		return EMPTY_ATTRIBUTE_ARRAY;
	}

	public Map<String, IAttribute> getAttributesAsMap(KbQuery query, IPageContext context) {
		IAttribute[] as = getAttributes(query, context);
		Map<String, IAttribute> map = new HashMap<String, IAttribute>();
		for (IAttribute a: as) {
			String n = a.getName();
			if(map.containsKey(n)) {
				IAttribute o = map.get(n);
				int pa = (a.isPreferable() || a.isRequired()) ? 2 : 0;
				int po = (o.isPreferable() || o.isRequired()) ? 2 : 0;
				pa += (a instanceof CustomTagLibAttribute) ? 1 : 0;
				po += (o instanceof CustomTagLibAttribute) ? 1 : 0;
				if(pa <= po) {				
					continue;
				}
			}
			map.put(n, a);
		}
		return map;
	}
}