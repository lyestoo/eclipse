/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.eval;

import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.CompoundAssignment;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemFieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReasons;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;

/**
 * A single name reference inside a code snippet can denote a field of a remote
 * receiver object (that is, the receiver of the context in the stack frame).
 */
public class CodeSnippetSingleNameReference extends SingleNameReference implements EvaluationConstants, InvocationSite, ProblemReasons {

	EvaluationContext evaluationContext;
	FieldBinding delegateThis;
	
public CodeSnippetSingleNameReference(char[] source, long pos, EvaluationContext evaluationContext) {
	super(source, pos);
	this.evaluationContext = evaluationContext;
}
public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo, boolean valueRequired) {

	switch (bits & RestrictiveFlagMASK) {
		case FIELD : // reading a field
			// check if reading a final blank field
			FieldBinding fieldBinding;
			if ((fieldBinding = (FieldBinding) binding).isBlankFinal() 
					&& currentScope.allowBlankFinalFieldAssignment(fieldBinding)) {
				if (!flowInfo.isDefinitelyAssigned(fieldBinding)) {
					currentScope.problemReporter().uninitializedBlankFinalField(fieldBinding, this);
				}
			}
			break;
		case LOCAL : // reading a local variable
			LocalVariableBinding localBinding;
			if (!flowInfo.isDefinitelyAssigned(localBinding = (LocalVariableBinding) binding)) {
				currentScope.problemReporter().uninitializedLocalVariable(localBinding, this);
			}
			if (flowInfo.isReachable()) {
				localBinding.useFlag = LocalVariableBinding.USED;
			} else if (localBinding.useFlag == LocalVariableBinding.UNUSED) {
				localBinding.useFlag = LocalVariableBinding.FAKE_USED;
			}
	}
	return flowInfo;
}
/**
 * Check and/or redirect the field access to the delegate receiver if any
 */
public TypeBinding checkFieldAccess(BlockScope scope) {

	if (delegateThis == null) {
		return super.checkFieldAccess(scope);
	}
	FieldBinding fieldBinding = (FieldBinding) binding;
	bits &= ~RestrictiveFlagMASK; // clear bits
	bits |= FIELD;
	if (!fieldBinding.isStatic()) {
		// must check for the static status....
		if (this.evaluationContext.isStatic) {
			scope.problemReporter().staticFieldAccessToNonStaticVariable(
				this,
				fieldBinding);
			constant = NotAConstant;
			return null;
		}
	}
	constant = FieldReference.getConstantFor(fieldBinding, this, true, scope);

	if (isFieldUseDeprecated(fieldBinding, scope, (this.bits & IsStrictlyAssignedMASK) !=0)) {
		scope.problemReporter().deprecatedField(fieldBinding, this);
	}
	return fieldBinding.type;

}
public void generateAssignment(BlockScope currentScope, CodeStream codeStream, Assignment assignment, boolean valueRequired) {

	// optimizing assignment like: i = i + 1 or i = 1 + i
	if (assignment.expression.isCompactableOperation()) {
		BinaryExpression operation = (BinaryExpression) assignment.expression;
		SingleNameReference variableReference;
		if ((operation.left instanceof SingleNameReference) && ((variableReference = (SingleNameReference) operation.left).binding == binding)) {
			// i = i + value, then use the variable on the right hand side, since it has the correct implicit conversion
			variableReference.generateCompoundAssignment(currentScope, codeStream, syntheticAccessors == null ? null : syntheticAccessors[WRITE], operation.right, (operation.bits & OperatorMASK) >> OperatorSHIFT, operation.left.implicitConversion /*should be equivalent to no conversion*/, valueRequired);
			return;
		}
		int operator = (operation.bits & OperatorMASK) >> OperatorSHIFT;
		if ((operation.right instanceof SingleNameReference)
			&& ((operator == PLUS) || (operator == MULTIPLY)) // only commutative operations
			&& ((variableReference = (SingleNameReference) operation.right).binding == binding)
			&& (operation.left.constant != NotAConstant) // exclude non constant expressions, since could have side-effect
			&& ((operation.left.implicitConversion >> 4) != T_String) // exclude string concatenation which would occur backwards
			&& ((operation.right.implicitConversion >> 4) != T_String)) { // exclude string concatenation which would occur backwards
			// i = value + i, then use the variable on the right hand side, since it has the correct implicit conversion
			variableReference.generateCompoundAssignment(currentScope, codeStream, syntheticAccessors == null ? null : syntheticAccessors[WRITE], operation.left, operator, operation.right.implicitConversion /*should be equivalent to no conversion*/, valueRequired);
			return;
		}
	}
	switch (bits & RestrictiveFlagMASK) {
		case FIELD : // assigning to a field
			FieldBinding fieldBinding = (FieldBinding) this.codegenBinding;
			if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
				if (!fieldBinding.isStatic()) { // need a receiver?
					if ((bits & DepthMASK) != 0) {
						ReferenceBinding targetType = currentScope.enclosingSourceType().enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT);
						Object[] emulationPath = currentScope.getEmulationPath(targetType, true /*only exact match*/, false/*consider enclosing arg*/);
						codeStream.generateOuterAccess(emulationPath, this, targetType, currentScope);
					} else {
						this.generateReceiver(codeStream);
					}
				}
				assignment.expression.generateCode(currentScope, codeStream, true);
				fieldStore(codeStream, fieldBinding, null, valueRequired);
				if (valueRequired) {
					codeStream.generateImplicitConversion(assignment.implicitConversion);
				}
			} else {
				((CodeSnippetCodeStream) codeStream).generateEmulationForField(fieldBinding);
				if (!fieldBinding.isStatic()) { // need a receiver?
					if ((bits & DepthMASK) != 0) {
						// internal error, per construction we should have found it
						// not yet supported
						currentScope.problemReporter().needImplementation();
					} else {
						this.generateReceiver(codeStream);
					}
				} else {
					codeStream.aconst_null();
				}
				assignment.expression.generateCode(currentScope, codeStream, true);
				if (valueRequired) {
					if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
						codeStream.dup2_x2();
					} else {
						codeStream.dup_x2();
					}
				}
				((CodeSnippetCodeStream) codeStream).generateEmulatedWriteAccessForField(fieldBinding);
				if (valueRequired) {
					codeStream.generateImplicitConversion(assignment.implicitConversion);
				}
			}
			return;
		case LOCAL : // assigning to a local variable
			LocalVariableBinding localBinding = (LocalVariableBinding) this.codegenBinding;
			if (localBinding.resolvedPosition != -1) {
				assignment.expression.generateCode(currentScope, codeStream, true);
			} else {
				if (assignment.expression.constant != NotAConstant) {
					// assigning an unused local to a constant value = no actual assignment is necessary
					if (valueRequired) {
						codeStream.generateConstant(assignment.expression.constant, assignment.implicitConversion);
					}
				} else {
					assignment.expression.generateCode(currentScope, codeStream, true);
					/* Even though the value may not be required, we force it to be produced, and discard it later
					on if it was actually not necessary, so as to provide the same behavior as JDK1.2beta3.	*/
					if (valueRequired) {
						codeStream.generateImplicitConversion(assignment.implicitConversion); // implicit conversion
					} else {
						if ((localBinding.type == LongBinding) || (localBinding.type == DoubleBinding)) {
							codeStream.pop2();
						} else {
							codeStream.pop();
						}
					}
				}
				return;
			}
			// normal local assignment (since cannot store in outer local which are final locations)
			codeStream.store(localBinding, valueRequired);
			if ((bits & FirstAssignmentToLocalMASK) != 0) { // for local variable debug attributes
				localBinding.recordInitializationStartPC(codeStream.position);
			}
			// implicit conversion
			if (valueRequired) {
				codeStream.generateImplicitConversion(assignment.implicitConversion);
			}
	}
}
public void generateCode(BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
	int pc = codeStream.position;
	if (constant != NotAConstant) {
		if (valueRequired) {
			codeStream.generateConstant(constant, implicitConversion);
		}
	} else {
		switch (bits & RestrictiveFlagMASK) {
			case FIELD : // reading a field
				FieldBinding fieldBinding;
				if (valueRequired) {
					if ((fieldBinding = (FieldBinding) this.codegenBinding).constant == NotAConstant) { // directly use inlined value for constant fields
						if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
							 // directly use inlined value for constant fields
							boolean isStatic;
							if (!(isStatic = fieldBinding.isStatic())) {
								if ((bits & DepthMASK) != 0) {
									ReferenceBinding targetType = currentScope.enclosingSourceType().enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT);
									Object[] emulationPath = currentScope.getEmulationPath(targetType, true /*only exact match*/, false/*consider enclosing arg*/);
									codeStream.generateOuterAccess(emulationPath, this, targetType, currentScope);
								} else {
									generateReceiver(codeStream);
								}
							}
							// managing private access							
							if (isStatic) {
								codeStream.getstatic(fieldBinding);
							} else {
								codeStream.getfield(fieldBinding);
							}
						} else {
							// managing private access
							if (!fieldBinding.isStatic()) {
								if ((bits & DepthMASK) != 0) {
									// internal error, per construction we should have found it
									// not yet supported
									currentScope.problemReporter().needImplementation();
								} else {
									generateReceiver(codeStream);
								}
							} else {
								codeStream.aconst_null();
							}
							((CodeSnippetCodeStream)codeStream).generateEmulatedReadAccessForField(fieldBinding);
						}
						codeStream.generateImplicitConversion(implicitConversion);
					} else { // directly use the inlined value
						codeStream.generateConstant(fieldBinding.constant, implicitConversion);
					}
				}
				break;
			case LOCAL : // reading a local
				LocalVariableBinding localBinding = (LocalVariableBinding) this.codegenBinding;
				if (valueRequired) {
					// outer local?
					if ((bits & DepthMASK) != 0) {
						// outer local can be reached either through a synthetic arg or a synthetic field
						VariableBinding[] path = currentScope.getEmulationPath(localBinding);
						codeStream.generateOuterAccess(path, this, localBinding, currentScope);
					} else {
						// regular local variable read
						codeStream.load(localBinding);
					}
					codeStream.generateImplicitConversion(implicitConversion);
				}
		}
	}
	codeStream.recordPositionsFrom(pc, this.sourceStart);
}
/*
 * The APIs with an extra argument is used whenever there are two references to the same variable which
 * are optimized in one access: e.g "a = a + 1" optimized into "a++".
 */
public void generateCompoundAssignment(BlockScope currentScope, CodeStream codeStream, MethodBinding writeAccessor, Expression expression, int operator, int assignmentImplicitConversion, boolean valueRequired) {
	switch (bits & RestrictiveFlagMASK) {
		case FIELD : // assigning to a field
			FieldBinding fieldBinding = (FieldBinding) this.codegenBinding;
			if (fieldBinding.isStatic()) {
				if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
					codeStream.getstatic(fieldBinding);
				} else {
					// used to store the value
					((CodeSnippetCodeStream) codeStream).generateEmulationForField(fieldBinding);
					codeStream.aconst_null();

					// used to retrieve the actual value
					codeStream.aconst_null();
					((CodeSnippetCodeStream) codeStream).generateEmulatedReadAccessForField(fieldBinding);
				}
			} else {
				if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
					if ((bits & DepthMASK) != 0) {
						ReferenceBinding targetType = currentScope.enclosingSourceType().enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT);
						Object[] emulationPath = currentScope.getEmulationPath(targetType, true /*only exact match*/, false/*consider enclosing arg*/);
						codeStream.generateOuterAccess(emulationPath, this, targetType, currentScope);
					} else {
						generateReceiver(codeStream);
					}
					codeStream.dup();
					codeStream.getfield(fieldBinding);
				} else {
					if ((bits & DepthMASK) != 0) {
						// internal error, per construction we should have found it
						// not yet supported
						currentScope.problemReporter().needImplementation();
					}
					// used to store the value
					((CodeSnippetCodeStream) codeStream).generateEmulationForField(fieldBinding);
					generateReceiver(codeStream);

					// used to retrieve the actual value
					codeStream.dup();
					((CodeSnippetCodeStream) codeStream).generateEmulatedReadAccessForField(fieldBinding);
				}
			}
			break;
		case LOCAL : // assigning to a local variable (cannot assign to outer local)
			LocalVariableBinding localBinding = (LocalVariableBinding) this.codegenBinding;
			Constant assignConstant;
			int increment;
			// using incr bytecode if possible
			switch (localBinding.type.id) {
				case T_String :
					codeStream.generateStringAppend(currentScope, this, expression);
					if (valueRequired) {
						codeStream.dup();
					}
					codeStream.store(localBinding, false);
					return;
				case T_int :
					if (((assignConstant = expression.constant) != NotAConstant) 
						&& (assignConstant.typeID() != T_float) // only for integral types
						&& (assignConstant.typeID() != T_double)		
						&& ((increment = assignConstant.intValue()) == (short) increment)) { // 16 bits value
						switch (operator) {
							case PLUS :
								codeStream.iinc(localBinding.resolvedPosition, increment);
								if (valueRequired) {
									codeStream.load(localBinding);
								}
								return;
							case MINUS :
								codeStream.iinc(localBinding.resolvedPosition, -increment);
								if (valueRequired) {
									codeStream.load(localBinding);
								}
								return;
						}
					}
				default :
					codeStream.load(localBinding);
			}
	}
	// perform the actual compound operation
	int operationTypeID;
	if ((operationTypeID = implicitConversion >> 4) == T_String || operationTypeID == T_Object) {
		codeStream.generateStringAppend(currentScope, null, expression);
	} else {
		// promote the array reference to the suitable operation type
		codeStream.generateImplicitConversion(implicitConversion);
		// generate the increment value (will by itself  be promoted to the operation value)
		if (expression == IntLiteral.One){ // prefix operation
			codeStream.generateConstant(expression.constant, implicitConversion);			
		} else {
			expression.generateCode(currentScope, codeStream, true);
		}		
		// perform the operation
		codeStream.sendOperator(operator, operationTypeID);
		// cast the value back to the array reference type
		codeStream.generateImplicitConversion(assignmentImplicitConversion);
	}
	// store the result back into the variable
	switch (bits & RestrictiveFlagMASK) {
		case FIELD : // assigning to a field
			FieldBinding fieldBinding = (FieldBinding) this.codegenBinding;
			if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
				fieldStore(codeStream, fieldBinding, writeAccessor, valueRequired);
			} else {
				// current stack is:
				// field receiver value
				if (valueRequired) {
					if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
						codeStream.dup2_x2();
					} else {
						codeStream.dup_x2();
					}
				}
				// current stack is:
				// value field receiver value				
				((CodeSnippetCodeStream) codeStream).generateEmulatedWriteAccessForField(fieldBinding);
			}
			return;
		case LOCAL : // assigning to a local variable
			LocalVariableBinding localBinding = (LocalVariableBinding) this.codegenBinding;
			if (valueRequired) {
				if ((localBinding.type == LongBinding) || (localBinding.type == DoubleBinding)) {
					codeStream.dup2();
				} else {
					codeStream.dup();
				}
			}
			codeStream.store(localBinding, false);
	}
}
public void generatePostIncrement(BlockScope currentScope, CodeStream codeStream, CompoundAssignment postIncrement, boolean valueRequired) {
	switch (bits & RestrictiveFlagMASK) {
		case FIELD : // assigning to a field
			FieldBinding fieldBinding = (FieldBinding) this.codegenBinding;
			if (fieldBinding.canBeSeenBy(getReceiverType(currentScope), this, currentScope)) {
				if (fieldBinding.isStatic()) {
					codeStream.getstatic(fieldBinding);
				} else {
					if ((bits & DepthMASK) != 0) {
						ReferenceBinding targetType = currentScope.enclosingSourceType().enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT);
						Object[] emulationPath = currentScope.getEmulationPath(targetType, true /*only exact match*/, false/*consider enclosing arg*/);
						codeStream.generateOuterAccess(emulationPath, this, targetType, currentScope);
					} else {
						generateReceiver(codeStream);
					}
					codeStream.dup();
					codeStream.getfield(fieldBinding);
				}
				if (valueRequired) {
					if (fieldBinding.isStatic()) {
						if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
							codeStream.dup2();
						} else {
							codeStream.dup();
						}
					} else { // Stack:  [owner][old field value]  ---> [old field value][owner][old field value]
						if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
							codeStream.dup2_x1();
						} else {
							codeStream.dup_x1();
						}
					}
				}
				codeStream.generateConstant(postIncrement.expression.constant, implicitConversion);
				codeStream.sendOperator(postIncrement.operator, fieldBinding.type.id);
				codeStream.generateImplicitConversion(postIncrement.assignmentImplicitConversion);
				fieldStore(codeStream, fieldBinding, null, false);
			} else {
				if (fieldBinding.isStatic()) {
					codeStream.aconst_null();
				} else {
					if ((bits & DepthMASK) != 0) {
						// internal error, per construction we should have found it
						// not yet supported
						currentScope.problemReporter().needImplementation();
					} else {
						generateReceiver(codeStream);
					}
				}
				((CodeSnippetCodeStream) codeStream).generateEmulatedReadAccessForField(fieldBinding);
				if (valueRequired) {
					if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
						codeStream.dup2();
					} else {
						codeStream.dup();
					}
				}
				((CodeSnippetCodeStream) codeStream).generateEmulationForField(fieldBinding);
				if ((fieldBinding.type == LongBinding) || (fieldBinding.type == DoubleBinding)) {
					codeStream.dup_x2();
					codeStream.pop();
					if (fieldBinding.isStatic()) {
						codeStream.aconst_null();
					} else {
						generateReceiver(codeStream);
					}
					codeStream.dup_x2();
					codeStream.pop();					
				} else {
					codeStream.dup_x1();
					codeStream.pop();
					if (fieldBinding.isStatic()) {
						codeStream.aconst_null();
					} else {
						generateReceiver(codeStream);
					}
					codeStream.dup_x1();
					codeStream.pop();					
				}
				codeStream.generateConstant(postIncrement.expression.constant, implicitConversion);
				codeStream.sendOperator(postIncrement.operator, fieldBinding.type.id);
				codeStream.generateImplicitConversion(postIncrement.assignmentImplicitConversion);
				((CodeSnippetCodeStream) codeStream).generateEmulatedWriteAccessForField(fieldBinding);
			}
			return;
		case LOCAL : // assigning to a local variable
			LocalVariableBinding localBinding = (LocalVariableBinding) this.codegenBinding;
			// using incr bytecode if possible
			if (localBinding.type == IntBinding) {
				if (valueRequired) {
					codeStream.load(localBinding);
				}
				if (postIncrement.operator == PLUS) {
					codeStream.iinc(localBinding.resolvedPosition, 1);
				} else {
					codeStream.iinc(localBinding.resolvedPosition, -1);
				}
			} else {
				codeStream.load(localBinding);
				if (valueRequired){
					if ((localBinding.type == LongBinding) || (localBinding.type == DoubleBinding)) {
						codeStream.dup2();
					} else {
						codeStream.dup();
					}
				}
				codeStream.generateConstant(postIncrement.expression.constant, implicitConversion);
				codeStream.sendOperator(postIncrement.operator, localBinding.type.id);
				codeStream.generateImplicitConversion(postIncrement.assignmentImplicitConversion);

				codeStream.store(localBinding, false);
			}
	}
}
public void generateReceiver(CodeStream codeStream) {
	codeStream.aload_0();
	if (delegateThis != null) {
		codeStream.getfield(delegateThis); // delegated field access
	}
}
/**
 * Check and/or redirect the field access to the delegate receiver if any
 */
public TypeBinding getReceiverType(BlockScope currentScope) {
	if (receiverType != null) {
		return receiverType;
	}
	Scope scope = currentScope.parent;
	while (true) {
			switch (scope.kind) {
				case Scope.CLASS_SCOPE :
					return receiverType = ((ClassScope) scope).referenceContext.binding;
				default:
					scope = scope.parent;
			}
	}
}
public void manageSyntheticReadAccessIfNecessary(BlockScope currentScope, FlowInfo flowInfo) {

	if (this.delegateThis == null) {
		super.manageSyntheticReadAccessIfNecessary(currentScope, flowInfo);
		return;
	}
	
	if (!flowInfo.isReachable()) return;
	//If inlinable field, forget the access emulation, the code gen will directly target it
	if (constant != NotAConstant)
		return;
	if ((bits & FIELD) != 0) {
		FieldBinding fieldBinding = (FieldBinding) binding;
//			if (((bits & DepthMASK) != 0)
//				&& (fieldBinding.isPrivate() // private access
//					|| (fieldBinding.isProtected() // implicit protected access
//							&& fieldBinding.declaringClass.getPackage() 
//								!= this.delegateThis.type.getPackage()))) {
//				if (syntheticAccessors == null)
//					syntheticAccessors = new MethodBinding[2];
//				syntheticAccessors[READ] = 
//					((SourceTypeBinding)currentScope.enclosingSourceType().
//						enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT)).
//							addSyntheticMethod(fieldBinding, true);
//				currentScope.problemReporter().needToEmulateFieldReadAccess(fieldBinding, this);
//				return;
//			}
		// if the binding declaring class is not visible, need special action
		// for runtime compatibility on 1.2 VMs : change the declaring class of the binding
		// NOTE: from target 1.2 on, field's declaring class is touched if any different from receiver type
		// and not from Object or implicit static field access.	
		if (fieldBinding.declaringClass != this.delegateThis.type
			&& fieldBinding.declaringClass != null
			&& fieldBinding.constant == NotAConstant
			&& ((currentScope.environment().options.targetJDK >= ClassFileConstants.JDK1_2 
					&& !fieldBinding.isStatic()
					&& fieldBinding.declaringClass.id != T_Object) // no change for Object fields (if there was any)
				|| !fieldBinding.declaringClass.canBeSeenBy(currentScope))){
			this.codegenBinding = currentScope.enclosingSourceType().getUpdatedFieldBinding(fieldBinding, (ReferenceBinding)this.delegateThis.type);
		}
	}
}
public void manageSyntheticWriteAccessIfNecessary(BlockScope currentScope, FlowInfo flowInfo) {

	if (this.delegateThis == null) {
		super.manageSyntheticWriteAccessIfNecessary(currentScope, flowInfo);
		return;
	}

	if (!flowInfo.isReachable()) return;
	if ((bits & FIELD) != 0) {
		FieldBinding fieldBinding = (FieldBinding) binding;
//		if (((bits & DepthMASK) != 0) 
//			&& (fieldBinding.isPrivate() // private access
//				|| (fieldBinding.isProtected() // implicit protected access
//						&& fieldBinding.declaringClass.getPackage() 
//							!= currentScope.enclosingSourceType().getPackage()))) {
//			if (syntheticAccessors == null)
//				syntheticAccessors = new MethodBinding[2];
//			syntheticAccessors[WRITE] = 
//				((SourceTypeBinding)currentScope.enclosingSourceType().
//					enclosingTypeAt((bits & DepthMASK) >> DepthSHIFT)).
//						addSyntheticMethod(fieldBinding, false);
//			currentScope.problemReporter().needToEmulateFieldWriteAccess(fieldBinding, this);
//			return;
//		}
		// if the binding declaring class is not visible, need special action
		// for runtime compatibility on 1.2 VMs : change the declaring class of the binding
		// NOTE: from target 1.2 on, field's declaring class is touched if any different from receiver type
		// and not from Object or implicit static field access.	
		if (fieldBinding.declaringClass != this.delegateThis.type
			&& fieldBinding.declaringClass != null
			&& fieldBinding.constant == NotAConstant
			&& ((currentScope.environment().options.targetJDK >= ClassFileConstants.JDK1_2 
					&& !fieldBinding.isStatic()
					&& fieldBinding.declaringClass.id != T_Object) // no change for Object fields (if there was any)
				|| !fieldBinding.declaringClass.canBeSeenBy(currentScope))){
			this.codegenBinding = currentScope.enclosingSourceType().getUpdatedFieldBinding(fieldBinding, (ReferenceBinding)this.delegateThis.type);
		}
	}
}
/**
 * Normal field binding did not work, try to bind to a field of the delegate receiver.
 */
public TypeBinding reportError(BlockScope scope) {

	constant = Constant.NotAConstant;
	if (binding instanceof ProblemFieldBinding && ((ProblemFieldBinding) binding).problemId() == NotFound){
		if (this.evaluationContext.declaringTypeName != null) {
			delegateThis = scope.getField(scope.enclosingSourceType(), DELEGATE_THIS, this);
			if (delegateThis != null){  // if not found then internal error, field should have been found
				// will not support innerclass emulation inside delegate
				this.codegenBinding = binding = scope.getField(delegateThis.type, this.token, this);
				if (!binding.isValidBinding()) {
					return super.reportError(scope);
				}
				return checkFieldAccess(scope);
			}
		}
	}
	if (binding instanceof ProblemBinding && ((ProblemBinding) binding).problemId() == NotFound){
		if (this.evaluationContext.declaringTypeName != null) {
			delegateThis = scope.getField(scope.enclosingSourceType(), DELEGATE_THIS, this);
			if (delegateThis != null){  // if not found then internal error, field should have been found
				// will not support innerclass emulation inside delegate
				FieldBinding fieldBinding = scope.getField(delegateThis.type, this.token, this);
				if (!fieldBinding.isValidBinding()) {
					if (((ProblemFieldBinding) fieldBinding).problemId() == NotVisible) {
						// manage the access to a private field of the enclosing type
						CodeSnippetScope localScope = new CodeSnippetScope(scope);
						this.codegenBinding = binding = localScope.getFieldForCodeSnippet(delegateThis.type, this.token, this);
						return checkFieldAccess(scope);						
					} else {
						return super.reportError(scope);
					}
				}
				this.codegenBinding = binding = fieldBinding;
				return checkFieldAccess(scope);
			}
		}
	}
	return super.reportError(scope);
}
}
