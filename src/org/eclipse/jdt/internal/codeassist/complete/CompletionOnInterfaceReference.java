/*******************************************************************************
 * Copyright (c) 2001 International Business Machines Corp. and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v0.5 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v05.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.jdt.internal.codeassist.complete;

public class CompletionOnInterfaceReference extends CompletionOnSingleTypeReference {
	public CompletionOnInterfaceReference(char[] source, long pos) {
		super(source, pos);
	}
	public String toStringExpression(int tab) {
		return "<CompleteOnInterface:" + new String(token) + ">"; //$NON-NLS-2$ //$NON-NLS-1$
	}
}