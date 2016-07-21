/**
 * <copyright>
 * </copyright>
 *
 * $Id: ATypeValidator.java,v 1.1 2010/02/04 09:24:53 sefftinge Exp $
 */
package org.eclipse.xtext.generator.grammarAccess.ametamodel.asubpackage.validation;


/**
 * A sample validator interface for {@link org.eclipse.xtext.generator.grammarAccess.ametamodel.asubpackage.AType}.
 * This doesn't really do anything, and it's not a real EMF artifact.
 * It was generated by the org.eclipse.emf.examples.generator.validator plug-in to illustrate how EMF's code generator can be extended.
 * This can be disabled with -vmargs -Dorg.eclipse.emf.examples.generator.validator=false.
 */
public interface ATypeValidator {
	boolean validate();

	boolean validateName(String value);
}