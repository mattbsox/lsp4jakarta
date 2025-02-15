/******************************************************************************* 
 * Copyright (c) 2022 IBM Corporation and others. 
 * 
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v. 2.0 which is available at 
 * http://www.eclipse.org/legal/epl-2.0. 
 * 
 * SPDX-License-Identifier: EPL-2.0 
 * 
 * Contributors: 
 *     Giancarlo Pernudi Segura - initial API and implementation
 *     Lidia Ataupillco Ramos
 *     Aviral Saxena
 *******************************************************************************/

package org.eclipse.lsp4jakarta.jdt.core.websocket;

import static org.eclipse.lsp4jakarta.jdt.core.TypeHierarchyUtils.doesITypeHaveSuperType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4jakarta.jdt.core.AbstractDiagnosticsCollector;
import org.eclipse.lsp4jakarta.jdt.core.JDTUtils;
import org.eclipse.lsp4jakarta.jdt.core.JakartaCorePlugin;

public class WebSocketDiagnosticsCollector extends AbstractDiagnosticsCollector {
    public WebSocketDiagnosticsCollector() {
        super();
    }

    @Override
    protected String getDiagnosticSource() {
        return WebSocketConstants.DIAGNOSTIC_SOURCE;
    }

    @Override
    public void collectDiagnostics(ICompilationUnit unit, List<Diagnostic> diagnostics) {
        if (unit == null) {
            return;
        }

        IType[] alltypes;
        HashMap<String, Boolean> checkWSEnd = null;
        try {
            alltypes = unit.getAllTypes();
            for (IType type : alltypes) {
                checkWSEnd = isWSEndpoint(type);
                // checks if the class uses annotation to create a WebSocket endpoint
                if (checkWSEnd.get(WebSocketConstants.IS_ANNOTATION)) {
                    // WebSocket Invalid Parameters Diagnostic
                    invalidParamsCheck(type, unit, diagnostics);
                    
                    /* @PathParam Value Mismatch Warning */
                    List<String> endpointPathVars = findAndProcessEndpointURI(type);
                    /*
                     * WebSocket endpoint annotations must be attached to a class, and thus is
                     * guaranteed to be processed before any of the member method annotations
                     */
                    if (endpointPathVars != null) {
                        // PathParam URI Mismatch Warning Diagnostic
                        uriMismatchWarningCheck(type, endpointPathVars, diagnostics, unit);
                    }

                    // OnMessage validation for WebSocket message formats
                    onMessageWSMessageFormats(type, diagnostics, unit);

                    // ServerEndpoint annotation diagnostics
                    serverEndpointErrorCheck(type, diagnostics, unit);
                }
            }
        } catch (JavaModelException e) {
            JakartaCorePlugin.logException(WebSocketConstants.DIAGNOSTIC_ERR_MSG, e);
        }
    }

    private void invalidParamsCheck(IType type, ICompilationUnit unit, List<Diagnostic> diagnostics)
            throws JavaModelException {
        IMethod[] allMethods = type.getMethods();
        for (IMethod method : allMethods) {
            IAnnotation[] allAnnotations = method.getAnnotations();
            Set<String> specialParamTypes = null, rawSpecialParamTypes = null;

            for (IAnnotation annotation : allAnnotations) {
                String annotationName = annotation.getElementName();
                String diagnosticCode = null;

                if (isMatchedJavaElement(type, annotationName, WebSocketConstants.ON_OPEN)) {
                    specialParamTypes = WebSocketConstants.ON_OPEN_PARAM_OPT_TYPES;
                    rawSpecialParamTypes = WebSocketConstants.RAW_ON_OPEN_PARAM_OPT_TYPES;
                    diagnosticCode = WebSocketConstants.DIAGNOSTIC_CODE_ON_OPEN_INVALID_PARAMS;
                } else if (isMatchedJavaElement(type, annotationName, WebSocketConstants.ON_CLOSE)) {
                    specialParamTypes = WebSocketConstants.ON_CLOSE_PARAM_OPT_TYPES;
                    rawSpecialParamTypes = WebSocketConstants.RAW_ON_CLOSE_PARAM_OPT_TYPES;
                    diagnosticCode = WebSocketConstants.DIAGNOSTIC_CODE_ON_CLOSE_INVALID_PARAMS;
                }
                if (diagnosticCode != null) {
                    ILocalVariable[] allParams = method.getParameters();
                    for (ILocalVariable param : allParams) {
                        String signature = param.getTypeSignature();
                        String formatSignature = signature.replace("/", ".");
                        String resolvedTypeName = JavaModelUtil.getResolvedTypeName(formatSignature, type);
                        boolean isPrimitive = JavaModelUtil.isPrimitive(formatSignature);
                        boolean isSpecialType;
                        boolean isPrimWrapped;

                        if (resolvedTypeName != null) {
                            isSpecialType = specialParamTypes.contains(resolvedTypeName);
                            isPrimWrapped = isWrapper(resolvedTypeName);
                        } else {
                            String simpleParamType = Signature.getSignatureSimpleName(signature);
                            isSpecialType = rawSpecialParamTypes.contains(simpleParamType);
                            isPrimWrapped = isWrapper(simpleParamType);
                        }

                        // check parameters valid types
                        if (!(isSpecialType || isPrimWrapped || isPrimitive)) {
                            diagnostics.add(createDiagnostic(param, unit,
                                    createParamTypeDiagMsg(specialParamTypes, annotationName), diagnosticCode, null,
                                    DiagnosticSeverity.Error));
                            continue;
                        }

                        if (!isSpecialType) {
                            // check that if parameter is not a specialType, it has a @PathParam annotation
                            IAnnotation[] param_annotations = param.getAnnotations();
                            boolean hasPathParamAnnot = Arrays.asList(param_annotations).stream().anyMatch(annot -> {
                                try {
                                    return isMatchedJavaElement(type, annot.getElementName(),
                                            WebSocketConstants.PATH_PARAM_ANNOTATION);
                                } catch (JavaModelException e) {
                                    JakartaCorePlugin.logException("Failed to get matched annotation", e);
                                    return false;
                                }
                            });
                            if (!hasPathParamAnnot) {
                                diagnostics.add(createDiagnostic(param, unit,
                                        WebSocketConstants.DIAGNOSTIC_PATH_PARAMS_ANNOT_MISSING,
                                        WebSocketConstants.DIAGNOSTIC_CODE_PATH_PARAMS_ANNOT, null,
                                        DiagnosticSeverity.Error));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates a warning diagnostic if a PathParam annotation does not match any
     * variable parameters of the WebSocket EndPoint URI associated with the class
     * in which the method is contained
     * 
     * @param type representing the class list of diagnostics for this class
     *             compilation unit with which the type is associated
     */
    private void uriMismatchWarningCheck(IType type, List<String> endpointPathVars, List<Diagnostic> diagnostics,
            ICompilationUnit unit)
            throws JavaModelException {
        IMethod[] typeMethods = type.getMethods();
        for (IMethod method : typeMethods) {
            ILocalVariable[] methodParams = method.getParameters();
            for (ILocalVariable param : methodParams) {
                IAnnotation[] paramAnnotations = param.getAnnotations();
                for (IAnnotation annotation : paramAnnotations) {
                    if (isMatchedJavaElement(type, annotation.getElementName(),
                            WebSocketConstants.PATHPARAM_ANNOTATION)) {
                        IMemberValuePair[] valuePairs = annotation.getMemberValuePairs();
                        for (IMemberValuePair pair : valuePairs) {
                            if (pair.getMemberName().equals(WebSocketConstants.ANNOTATION_VALUE)
                                    && pair.getValueKind() == IMemberValuePair.K_STRING) {
                                String pathValue = (String) pair.getValue();
                                if (!endpointPathVars.contains(pathValue)) {
                                    diagnostics.add(createDiagnostic(annotation, unit,
                                            WebSocketConstants.PATHPARAM_VALUE_WARN_MSG,
                                            WebSocketConstants.PATHPARAM_DIAGNOSTIC_CODE, null,
                                            DiagnosticSeverity.Warning));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates an error diagnostic if there exists more than one method annotated
     * with @OnMessage for a given message format.
     * 
     * @param type
     * @param diagnostics
     * @param unit
     * @throws JavaModel
     */
    private void onMessageWSMessageFormats(IType type, List<Diagnostic> diagnostics, ICompilationUnit unit)
            throws JavaModelException {
        IMethod[] typeMethods = type.getMethods();
        IAnnotation onMessageTextUsed = null;
        IAnnotation onMessageBinaryUsed = null;
        IAnnotation onMessagePongUsed = null;
        for (IMethod method : typeMethods) {
            IAnnotation[] allAnnotations = method.getAnnotations();
            for (IAnnotation annotation : allAnnotations) {
                if (isMatchedJavaElement(type, annotation.getElementName(), WebSocketConstants.ON_MESSAGE)) {
                    ILocalVariable[] allParams = method.getParameters();
                    for (ILocalVariable param : allParams) {
                        if (!isParamPath(type, param)) {
                            String signature = param.getTypeSignature();
                            String formatSignature = signature.replace("/", ".");
                            String resolvedTypeName = JavaModelUtil.getResolvedTypeName(formatSignature, type);
                            String typeName = null;
                            if (resolvedTypeName == null) {
                                typeName = Signature.getSignatureSimpleName(signature);
                            }
                            if ((resolvedTypeName != null
                                    && WebSocketConstants.LONG_MESSAGE_CLASSES.contains(resolvedTypeName))
                                    || WebSocketConstants.SHORT_MESSAGE_CLASSES.contains(typeName)) {
                                WebSocketConstants.MESSAGE_FORMAT messageFormat = resolvedTypeName != null
                                        ? getMessageFormat(resolvedTypeName, true)
                                        : getMessageFormat(typeName, false);
                                switch (messageFormat) {
                                case TEXT:
                                    if (onMessageTextUsed != null) {
                                        diagnostics.add(createDiagnostic(annotation, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                        diagnostics.add(createDiagnostic(onMessageTextUsed, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                    }
                                    onMessageTextUsed = annotation;
                                    break;
                                case BINARY:
                                    if (onMessageBinaryUsed != null) {
                                        diagnostics.add(createDiagnostic(annotation, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                        diagnostics.add(createDiagnostic(onMessageBinaryUsed, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                    }
                                    onMessageBinaryUsed = annotation;
                                    break;
                                case PONG:
                                    if (onMessagePongUsed != null) {
                                        diagnostics.add(createDiagnostic(annotation, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                        diagnostics.add(createDiagnostic(onMessagePongUsed, unit,
                                                WebSocketConstants.DIAGNOSTIC_ON_MESSAGE_DUPLICATE_METHOD,
                                                WebSocketConstants.DIAGNOSTIC_CODE_ON_MESSAGE_DUPLICATE_METHOD, null,
                                                DiagnosticSeverity.Error));
                                    }
                                    onMessagePongUsed = annotation;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Create an error diagnostic if a ServerEndpoint annotation's URI contains relative
     * paths, missing a leading slash, or does not follow a valid level-1 template URI.
     */
    private void serverEndpointErrorCheck(IType type, List<Diagnostic> diagnostics, ICompilationUnit unit)
            throws JavaModelException {
        IAnnotation[] annotations = type.getAnnotations();
        for (IAnnotation annotation : annotations) {
            if (isMatchedJavaElement(type, annotation.getElementName(),
                    WebSocketConstants.SERVER_ENDPOINT_ANNOTATION)) {
                for (IMemberValuePair annotationMemberValuePair : annotation.getMemberValuePairs()) {
                    if (annotationMemberValuePair.getMemberName().equals(WebSocketConstants.ANNOTATION_VALUE)) {
                        String path = annotationMemberValuePair.getValue().toString();
                        if (!JDTUtils.hasLeadingSlash(path)) {
                            diagnostics.add(createDiagnostic(annotation, unit,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT_NO_SLASH,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT, null, DiagnosticSeverity.Error));
                        }
                        if (hasRelativePathURIs(path)) {
                            diagnostics.add(createDiagnostic(annotation, unit,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT_RELATIVE,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT, null, DiagnosticSeverity.Error));
                        } else if (!JDTUtils.isValidLevel1URI(path)) {
                            diagnostics.add(createDiagnostic(annotation, unit,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT_NOT_LEVEL1,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT, null, DiagnosticSeverity.Error));
                        }
                        if (hasDuplicateURIVariables(path)) {
                            diagnostics.add(createDiagnostic(annotation, unit,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT_DUPLICATE_VAR,
                                    WebSocketConstants.DIAGNOSTIC_SERVER_ENDPOINT, null, DiagnosticSeverity.Error));
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds a WebSocket EndPoint annotation and extracts all variable parameters in
     * the EndPoint URI
     * 
     * @param type representing the class
     * @return List of variable parameters in the EndPoint URI if one exists, null
     *         otherwise
     */
    private List<String> findAndProcessEndpointURI(IType type) throws JavaModelException {
        String endpointURI = null;
        IAnnotation[] typeAnnotations = type.getAnnotations();
        String[] targetAnnotations = {WebSocketConstants.SERVER_ENDPOINT_ANNOTATION, WebSocketConstants.CLIENT_ENDPOINT_ANNOTATION};
        for (IAnnotation annotation : typeAnnotations) {
            String matchedAnnotation = getMatchedJavaElementName(type, annotation.getElementName(), targetAnnotations);
            if (matchedAnnotation != null) {
                IMemberValuePair[] valuePairs = annotation.getMemberValuePairs();
                for (IMemberValuePair pair : valuePairs) {
                    if (pair.getMemberName().equals(WebSocketConstants.ANNOTATION_VALUE)
                            && pair.getValueKind() == IMemberValuePair.K_STRING) {
                        endpointURI = (String) pair.getValue();
                    }
                }
            }
        }
        if (endpointURI == null) {
            return null;
        }
        List<String> endpointPathVars = new ArrayList<String>();
        String[] endpointParts = endpointURI.split(WebSocketConstants.URI_SEPARATOR);
        for (String part : endpointParts) {
            if (part.startsWith(WebSocketConstants.CURLY_BRACE_START)
                    && part.endsWith(WebSocketConstants.CURLY_BRACE_END)) {
                endpointPathVars.add(part.substring(1, part.length() - 1));
            }
        }
        return endpointPathVars;
    }

    /**
     * Check if valueClass is a wrapper object for a primitive value Based on
     * https://github.com/eclipse/lsp4mp/blob/9789a1a996811fade43029605c014c7825e8f1da/microprofile.jdt/org.eclipse.lsp4mp.jdt.core/src/main/java/org/eclipse/lsp4mp/jdt/core/utils/JDTTypeUtils.java#L294-L298
     * 
     * @param valueClass the resolved type of valueClass in string or the simple
     *                   type of valueClass
     * @return if valueClass is a wrapper object
     */
    private boolean isWrapper(String valueClass) {
        return WebSocketConstants.WRAPPER_OBJS.contains(valueClass)
                || WebSocketConstants.RAW_WRAPPER_OBJS.contains(valueClass);
    }

    /**
     * Checks if type is a WebSocket endpoint by meeting one of the 2 conditions
     * listed on
     * https://jakarta.ee/specifications/websocket/2.0/websocket-spec-2.0.html#applications
     * are met: class is annotated or class implements Endpoint class
     * 
     * @param type the type representing the class
     * @return the conditions for a class to be a WebSocket endpoint
     * @throws JavaModelException
     */
    private HashMap<String, Boolean> isWSEndpoint(IType type) throws JavaModelException {
        HashMap<String, Boolean> wsEndpoint = new HashMap<>();

        // check trivial case
        if (!type.isClass()) {
            wsEndpoint.put(WebSocketConstants.IS_ANNOTATION, false);
            wsEndpoint.put(WebSocketConstants.IS_SUPERCLASS, false);
            return wsEndpoint;
        }

        // Check that class follows
        // https://jakarta.ee/specifications/websocket/2.0/websocket-spec-2.0.html#applications
        List<String> endpointAnnotations = getMatchedJavaElementNames(type,
                Stream.of(type.getAnnotations()).map(annotation -> annotation.getElementName()).toArray(String[]::new),
                WebSocketConstants.WS_ANNOTATION_CLASS);

        boolean useSuperclass = false;
        try {
            useSuperclass = doesITypeHaveSuperType(type, WebSocketConstants.ENDPOINT_SUPERCLASS) >= 0;
        } catch (CoreException e) {
            JakartaCorePlugin.logException(WebSocketConstants.DIAGNOSTIC_ERR_MSG, e);
        }

        wsEndpoint.put(WebSocketConstants.IS_ANNOTATION, (endpointAnnotations.size() > 0));
        wsEndpoint.put(WebSocketConstants.IS_SUPERCLASS, useSuperclass);

        return wsEndpoint;
    }

    private boolean isParamPath(IType type, ILocalVariable param) throws JavaModelException {
        IAnnotation[] allVariableAnnotations = param.getAnnotations();
        for (IAnnotation variableAnnotation : allVariableAnnotations) {
            if (isMatchedJavaElement(type, variableAnnotation.getElementName(),
                    WebSocketConstants.PATH_PARAM_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    private WebSocketConstants.MESSAGE_FORMAT getMessageFormat(String typeName, boolean longName) {
        if (longName) {
            switch (typeName) {
            case WebSocketConstants.STRING_CLASS_LONG:
                return WebSocketConstants.MESSAGE_FORMAT.TEXT;
            case WebSocketConstants.READER_CLASS_LONG:
                return WebSocketConstants.MESSAGE_FORMAT.TEXT;
            case WebSocketConstants.BYTEBUFFER_CLASS_LONG:
                return WebSocketConstants.MESSAGE_FORMAT.BINARY;
            case WebSocketConstants.INPUTSTREAM_CLASS_LONG:
                return WebSocketConstants.MESSAGE_FORMAT.BINARY;
            case WebSocketConstants.PONGMESSAGE_CLASS_LONG:
                return WebSocketConstants.MESSAGE_FORMAT.PONG;
            default:
                throw new IllegalArgumentException("Invalid message format type");
            }
        }
        switch (typeName) {
        case WebSocketConstants.STRING_CLASS_SHORT:
            return WebSocketConstants.MESSAGE_FORMAT.TEXT;
        case WebSocketConstants.READER_CLASS_SHORT:
            return WebSocketConstants.MESSAGE_FORMAT.TEXT;
        case WebSocketConstants.BYTEBUFFER_CLASS_SHORT:
            return WebSocketConstants.MESSAGE_FORMAT.BINARY;
        case WebSocketConstants.INPUTSTREAM_CLASS_SHORT:
            return WebSocketConstants.MESSAGE_FORMAT.BINARY;
        case WebSocketConstants.PONGMESSAGE_CLASS_SHORT:
            return WebSocketConstants.MESSAGE_FORMAT.PONG;
        default:
            throw new IllegalArgumentException("Invalid message format type");
        }
    }

    private String createParamTypeDiagMsg(Set<String> methodParamOptTypes, String methodAnnotTarget) {
        String paramMessage = String.join("\n- ", methodParamOptTypes);
        return String.format(WebSocketConstants.PARAM_TYPE_DIAG_MSG, "@" + methodAnnotTarget, paramMessage);
    }

    /**
     * Check if a URI string contains any sequence with //, /./, or /../
     *
     * @param uriString ServerEndpoint URI
     * @return if a URI has a relative path
     */
    private boolean hasRelativePathURIs(String uriString) {
        return uriString.matches(WebSocketConstants.REGEX_RELATIVE_PATHS);
    }

    /**
     * Check if a URI string has a duplicate variable
     * 
     * @param uriString ServerEndpoint URI
     * @return if a URI has duplicate variables
     */
    private boolean hasDuplicateURIVariables(String uriString) {
        HashSet<String> variables = new HashSet<String>();
        for (String segment : uriString.split(WebSocketConstants.URI_SEPARATOR)) {
            if (segment.matches(WebSocketConstants.REGEX_URI_VARIABLE)) {
                String variable = segment.substring(1, segment.length() - 1);
                if (variables.contains(variable)) {
                    return true;
                } else {
                    variables.add(variable);
                }
            }
        }
        return false;
    }
}
