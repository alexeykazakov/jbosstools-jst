/******************************************************************************* 
 * Copyright (c) 2013 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.tools.jst.web.kb;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * @author Alexey Kazakov
 */
public class CABrowserView extends ViewPart {

	private Browser browser;
	public static final String ID = "org.jboss.tools.jst.web.kb.views.CABrowser";

	/* (non-Javadoc)
	 * @see org.eclipse.ui.part.WorkbenchPart#createPartControl(org.eclipse.swt.widgets.Composite)
	 */
	@Override
	public void createPartControl(Composite parent) {
		Composite browserPanel = createBrowserPanel(parent);
//		browserPanel.setVisible(false);
//		browserPanel.setSize(1, 1);
		browser = new Browser(browserPanel, SWT.NONE | SWT.WEBKIT);
		browser.setLayoutData(new GridData(GridData.FILL_BOTH));
		browser.pack();
	}

	private Composite createBrowserPanel(Composite parent) {
		Composite browserPanel = new Composite(parent, SWT.NONE);
		GridData g = new GridData();
		g.horizontalAlignment = SWT.FILL;
		g.verticalAlignment = SWT.FILL;
		g.grabExcessHorizontalSpace = true;
		g.grabExcessVerticalSpace = true;
		browserPanel.setLayoutData(g);
		GridLayout l = new GridLayout();
		l.verticalSpacing = 0;
		l.marginWidth = 0;
		l.marginHeight = 0;
		browserPanel.setLayout(l);
		return browserPanel;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.part.WorkbenchPart#setFocus()
	 */
	@Override
	public void setFocus() {
	}

	public Browser getBrowser() {
		return browser;
	}
}