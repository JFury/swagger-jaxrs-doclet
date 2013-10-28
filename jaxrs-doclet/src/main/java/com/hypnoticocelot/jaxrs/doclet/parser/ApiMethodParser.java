package com.hypnoticocelot.jaxrs.doclet.parser;

import com.hypnoticocelot.jaxrs.doclet.DocletOptions;
import com.hypnoticocelot.jaxrs.doclet.model.ApiParameter;
import com.hypnoticocelot.jaxrs.doclet.model.ApiResponseMessage;
import com.hypnoticocelot.jaxrs.doclet.model.HttpMethod;
import com.hypnoticocelot.jaxrs.doclet.model.Method;
import com.hypnoticocelot.jaxrs.doclet.model.Model;
import com.hypnoticocelot.jaxrs.doclet.translator.Translator;
import com.sun.javadoc.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.collect.Collections2.filter;
import static com.hypnoticocelot.jaxrs.doclet.parser.AnnotationHelper.parsePath;

public class ApiMethodParser {

    public static final String ROLES_ALLOWED = "javax.annotation.security.RolesAllowed";
    public static final String PERMIT_ALL = "javax.annotation.security.PermitAll";
    public static final String ANY_ROLES = "Any";

    public static final String CONTENT_DISPOSITION = "org.glassfish.jersey.media.multipart.FormDataContentDisposition";

    private final DocletOptions options;
    private final Translator translator;
    private final String parentPath;
    private final MethodDoc methodDoc;
    private final Set<Model> models;

    public ApiMethodParser(DocletOptions options, String parentPath, MethodDoc methodDoc) {
        this.options = options;
        this.translator = options.getTranslator();
        this.parentPath = parentPath;
        this.methodDoc = methodDoc;
        this.models = new LinkedHashSet<Model>();
    }

    public Method parse() {
        HttpMethod httpMethod = HttpMethod.fromMethod(methodDoc);
        if (httpMethod == null) {
            return null;
        }

        String path = parentPath + firstNonNull(parsePath(methodDoc.annotations()), "");

        // parameters
        List<ApiParameter> parameters = new LinkedList<ApiParameter>();
        for (Parameter parameter : methodDoc.parameters()) {
            if (!shouldIncludeParameter(httpMethod, parameter)) {
                continue;
            }
            if (options.isParseModels()) {
                models.addAll(new ApiModelParser(options, translator, parameter.type()).parse());
            }

            parameters.add(new ApiParameter(
                    AnnotationHelper.paramTypeOf(parameter),
                    AnnotationHelper.paramNameOf(parameter),
                    commentForParameter(methodDoc, parameter),
                    translator.typeName(parameter.type()).value()
            ));
        }

        // response messages
        Pattern pattern = Pattern.compile("(\\d+) (.+)"); // matches "<code><space><text>"
        List<ApiResponseMessage> responseMessages = new LinkedList<ApiResponseMessage>();
        for (String tagName : options.getErrorTags()) {
            for (Tag tagValue : methodDoc.tags(tagName)) {
                Matcher matcher = pattern.matcher(tagValue.text());
                if (matcher.find()) {
                    responseMessages.add(new ApiResponseMessage(Integer.valueOf(matcher.group(1)),
                            matcher.group(2)));
                }
            }
        }

        // return type
        Type type = methodDoc.returnType();
        ParameterizedType parameterizedType = type.asParameterizedType();
        Type elementType = null;
        String returnType = translator.typeName(type).value();
        if(isCollection(returnType) && parameterizedType != null) {
            elementType = parameterizedType.typeArguments()[0];
            returnType = returnType + "[" + translator.typeName(elementType).value() + "]";
        }

        if (options.isParseModels()) {
            models.addAll(new ApiModelParser(options, translator, type).parse());
            if(elementType != null) {
                models.addAll(new ApiModelParser(options, translator, elementType).parse());
            }
        }

        // First Sentence of Javadoc method description
        Tag[] fst = methodDoc.firstSentenceTags();
        StringBuilder sentences = new StringBuilder();
        for (Tag tag : fst) {
            sentences.append(tag.text());
        }
        String firstSentences = sentences.toString();


        String rolesInfo = getRolesInfo(methodDoc.annotations());
        if(rolesInfo == null) {
            rolesInfo = getRolesInfo(methodDoc.containingClass().annotations());
        }
        if(rolesInfo == null) {
            rolesInfo = ANY_ROLES;
        }

        StringBuilder additionalInfo = new StringBuilder("</br> ROLES: " + rolesInfo);

        String[] jsonView = AnnotationHelper.getJsonViews(methodDoc.annotations());
        if(jsonView != null) {
            additionalInfo.append("</br> ").append("VIEWS: ");
            for(int i=0; i<jsonView.length; i++){
                additionalInfo.append(i>0?",":"").append(jsonView[i]);
            }
        }

        return new Method(
                httpMethod,
                methodDoc.name(),
                path,
                parameters,
                responseMessages,
                firstSentences + " Auth is required: " + (ANY_ROLES.equals(rolesInfo)?"No":"Yes"),
                methodDoc.commentText().replace(firstSentences, "") + additionalInfo.toString(),
                returnType
        );
    }

    private String getRolesInfo(AnnotationDesc[] annotationDescs) {
        String rolesInfo=null;
        AnnotationValue annotationVal;

        if(AnnotationHelper.isAnnotationPresented(annotationDescs, PERMIT_ALL)) {
            rolesInfo = ANY_ROLES;
        } else if ((annotationVal = AnnotationHelper.getAnnotationValue(annotationDescs, ROLES_ALLOWED)) != null) {
            rolesInfo = annotationVal.toString();
        }

        return rolesInfo;
    }

    private boolean isCollection(String s) {
        return "List".equals(s) || "Set".equals(s);
    }

    public Set<Model> models() {
        return models;
    }

    private boolean shouldIncludeParameter(HttpMethod httpMethod, Parameter parameter) {
        // Content disposition is a meta-parameter, i.e. just information about a parameter,
        // So there will be another parameter with the same name, but with the real type
        if(parameter.type().qualifiedTypeName().equals(CONTENT_DISPOSITION)) {
            return false;
        }

        List<AnnotationDesc> allAnnotations = Arrays.asList(parameter.annotations());
        Collection<AnnotationDesc> excluded = filter(allAnnotations, new AnnotationHelper.ExcludedAnnotations(options));
        if (!excluded.isEmpty()) {
            return false;
        }

        Collection<AnnotationDesc> jaxRsAnnotations = filter(allAnnotations, new AnnotationHelper.JaxRsAnnotations());
        if (!jaxRsAnnotations.isEmpty()) {
            return true;
        }

        return (allAnnotations.isEmpty() || httpMethod == HttpMethod.POST);
    }

    private String commentForParameter(MethodDoc method, Parameter parameter) {
        for (ParamTag tag : method.paramTags()) {
            if (tag.parameterName().equals(parameter.name())) {
                return tag.parameterComment();
            }
        }
        return "";
    }

}
