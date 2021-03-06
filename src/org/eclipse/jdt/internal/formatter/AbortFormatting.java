/*******************************************************************************
 * Copyright (c) 2002, 2003 International Business Machines Corp. and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.jdt.internal.formatter;

/**
 * Unchecked exception wrapping invalid input checked exception which may occur
 * when scanning original formatted source. 
 * 
 * @since 2.1
 */
public class AbortFormatting extends RuntimeException {

	Throwable nestedException;
	
	public AbortFormatting(String message) {
		super(message);
	}
	public AbortFormatting(Throwable nestedException) {
		super(nestedException.getMessage());
		this.nestedException = nestedException;
	}
}
